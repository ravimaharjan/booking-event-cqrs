import React, { useCallback, useEffect, useState } from 'react';
import * as api from '../api.js';
import { Link } from '../router.jsx';
import { useSession } from '../session.jsx';
import { money, when } from '../format.js';
import Pager from '../Pager.jsx';

const tomorrowEvening = () => {
  const d = new Date(Date.now() + 24 * 3600 * 1000);
  d.setHours(20, 0, 0, 0);
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
};

function Field({ label, children }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  );
}

function CreateEvent({ onCreated }) {
  const { run, busy, remember } = useSession();
  const [name, setName] = useState('Jazz Night');
  const [city, setCity] = useState('Berlin');
  const [startsAt, setStartsAt] = useState(tomorrowEvening());
  const [tiers, setTiers] = useState([
    { name: 'General', priceCents: 5000, quantity: 100 },
    { name: 'VIP', priceCents: 15000, quantity: 10 },
  ]);
  const setTier = (i, patch) => setTiers(tiers.map((x, j) => (j === i ? { ...x, ...patch } : x)));

  async function onSubmit(e) {
    e.preventDefault();
    const r = await run(() =>
      api.newEvent({
        name,
        city,
        startsAt: new Date(startsAt).toISOString(),
        tiers: tiers.map((t) => ({ ...t, priceCents: Number(t.priceCents), quantity: Number(t.quantity) })),
      }),
    );
    if (r?.status === 201) {
      remember('events', r.body.id);
      onCreated(r.body.id, r.issuedToken);
    }
  }

  return (
    <form className="card" onSubmit={onSubmit}>
      <h2>Create event</h2>
      <Field label="Name">
        <input value={name} onChange={(e) => setName(e.target.value)} required />
      </Field>
      <div className="row">
        <Field label="City">
          <input value={city} onChange={(e) => setCity(e.target.value)} required />
        </Field>
        <Field label="Starts at">
          <input type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} required />
        </Field>
      </div>
      {tiers.map((t, i) => (
        <div className="row tier" key={i}>
          <Field label={i === 0 ? 'Tier' : ''}>
            <input value={t.name} onChange={(e) => setTier(i, { name: e.target.value })} required />
          </Field>
          <Field label={i === 0 ? 'Price (cents)' : ''}>
            <input type="number" min="0" value={t.priceCents} onChange={(e) => setTier(i, { priceCents: e.target.value })} required />
          </Field>
          <Field label={i === 0 ? 'Quantity' : ''}>
            <input type="number" min="1" value={t.quantity} onChange={(e) => setTier(i, { quantity: e.target.value })} required />
          </Field>
          <button
            type="button"
            className="ghost"
            disabled={tiers.length === 1}
            onClick={() => setTiers(tiers.filter((_, j) => j !== i))}
            title="Remove tier"
          >
            ✕
          </button>
        </div>
      ))}
      <div className="row">
        <button type="button" className="ghost" onClick={() => setTiers([...tiers, { name: '', priceCents: 0, quantity: 1 }])}>
          + tier
        </button>
        <button disabled={busy}>Create event</button>
      </div>
    </form>
  );
}

function BookTickets({ eventId, setEventId, tiers, tierId, setTierId, loadTiers }) {
  const { run, busy, remember, token, recent } = useSession();
  const [quantity, setQuantity] = useState(1);
  const [email, setEmail] = useState('ravi@example.com');
  const [booked, setBooked] = useState(null);

  async function onSubmit(e) {
    e.preventDefault();
    const r = await run(() => api.bookTickets(eventId, { tierId, quantity: Number(quantity), customerEmail: email }));
    if (r?.status === 201) {
      remember('bookings', r.body.id);
      setBooked(r.body.id);
    }
  }

  return (
    <form className="card" onSubmit={onSubmit}>
      <h2>Book tickets</h2>
      <Field label="Event id">
        <input value={eventId} onChange={(e) => { setEventId(e.target.value); setBooked(null); }} required />
      </Field>
      {recent.events.length > 0 && (
        <div className="recent">
          {recent.events.slice(0, 5).map((id) => (
            <button type="button" key={id} className="chip" onClick={() => setEventId(id)} title={id}>
              {id.slice(0, 8)}
            </button>
          ))}
        </div>
      )}
      <div className="row">
        <Field label="Tier">
          {tiers.length ? (
            <select value={tierId} onChange={(e) => setTierId(e.target.value)}>
              {tiers.map((t) => (
                <option key={t.tierId} value={t.tierId}>
                  {t.name} · {t.available}/{t.total} left
                </option>
              ))}
            </select>
          ) : (
            <input value={tierId} onChange={(e) => setTierId(e.target.value)} placeholder="tier id" required />
          )}
        </Field>
        <Field label="Qty">
          <input type="number" min="1" value={quantity} style={{ maxWidth: 80 }} onChange={(e) => setQuantity(e.target.value)} required />
        </Field>
      </div>
      <Field label="Customer email">
        <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      </Field>
      <div className="row">
        <button
          type="button"
          className="ghost"
          disabled={!eventId || busy}
          onClick={() => loadTiers(eventId, token?.value)}
          title="Reads the event from the primary using your token, so it is always current"
        >
          Load tiers
        </button>
        <button disabled={busy || !tierId}>Book</button>
      </div>
      {booked && (
        <p className="hint">
          Booked. <Link to={`/bookings?id=${booked}`}>View booking</Link> · <Link to={`/events/${eventId}`}>View event</Link>
        </p>
      )}
    </form>
  );
}

function EventList({ refreshKey, onBook }) {
  const { run, busy, readToken } = useSession();
  const [city, setCity] = useState('Berlin');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [events, setEvents] = useState(null);

  const list = useCallback(
    async (p = page, sz = size) => {
      const r = await run(() => api.listEvents({ city, page: p, size: sz }, readToken));
      if (r?.status === 200) {
        setEvents(r.body);
        setPage(p);
      }
    },
    [run, city, page, size, readToken],
  );

  // Load on arrival, and from the first page again whenever an event is created.
  useEffect(() => {
    list(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshKey]);

  return (
    <div className="card">
      <h2>Events</h2>
      <div className="row">
        <Field label="City (empty = all)">
          <input value={city} onChange={(e) => setCity(e.target.value)} />
        </Field>
        <button disabled={busy} onClick={() => list(0)}>List</button>
      </div>
      {events === null ? (
        <p className="hint">Loading…</p>
      ) : events.length === 0 ? (
        <p className="hint">{page > 0 ? 'No more events.' : 'No events found.'}</p>
      ) : (
        <table className="items">
          <thead>
            <tr>
              <th>event</th>
              <th>starts</th>
              <th>from</th>
              <th>left</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {events.map((e) => (
              <tr key={e.eventId}>
                <td>
                  <Link to={`/events/${e.eventId}`}><b>{e.name}</b></Link>
                  <div className="hint">{e.city}</div>
                </td>
                <td>{when(e.startsAt)}</td>
                <td>{money(e.minPriceCents)}</td>
                <td>{e.availableTickets}</td>
                <td className="actions">
                  <Link to={`/events/${e.eventId}`} className="btn ghost">View</Link>
                  <button onClick={() => onBook(e.eventId)}>Book</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {events !== null && (
        <Pager page={page} size={size} setSize={setSize} count={events.length} disabled={busy} onPage={list} />
      )}
    </div>
  );
}

export default function Home({ bookEventId }) {
  const { record } = useSession();
  const [eventId, setEventId] = useState(bookEventId);
  const [tiers, setTiers] = useState([]);
  const [tierId, setTierId] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);

  // A primary read (fresh token) so the tiers of a just-created event are visible immediately.
  const loadTiers = useCallback(
    async (id, withToken) => {
      const r = record(await api.getEvent(id, withToken));
      if (r.status === 200) {
        setTiers(r.body.tiers);
        setTierId((cur) => (r.body.tiers.some((t) => t.tierId === cur) ? cur : r.body.tiers[0]?.tierId ?? ''));
      }
    },
    [record],
  );

  const { token } = useSession();
  useEffect(() => {
    setEventId(bookEventId);
    if (bookEventId) loadTiers(bookEventId, token?.value);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bookEventId]);

  const pick = (id) => {
    setEventId(id);
    loadTiers(id, token?.value);
  };

  return (
    <div className="grid">
      <div className="col">
        <h2 className="col-title">Commands (write, primary)</h2>
        <CreateEvent
          onCreated={(id, issued) => {
            pick(id);
            setRefreshKey((k) => k + 1);
            if (issued) loadTiers(id, issued);
          }}
        />
        <BookTickets
          eventId={eventId}
          setEventId={setEventId}
          tiers={tiers}
          tierId={tierId}
          setTierId={setTierId}
          loadTiers={loadTiers}
        />
      </div>
      <div className="col">
        <h2 className="col-title">Queries (read, replica / cache)</h2>
        <EventList refreshKey={refreshKey} onBook={pick} />
      </div>
    </div>
  );
}
