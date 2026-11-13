import React, { useCallback, useEffect, useState } from 'react';
import * as api from './api.js';
import { Badge, Meta } from './Badges.jsx';
import Burst from './Burst.jsx';

const load = (key, fallback) => {
  try {
    return JSON.parse(localStorage.getItem(key)) ?? fallback;
  } catch {
    return fallback;
  }
};
const save = (key, value) => {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* storage unavailable: the page still works */
  }
};

const tomorrowEvening = () => {
  const d = new Date(Date.now() + 24 * 3600 * 1000);
  d.setHours(20, 0, 0, 0);
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
};

const pushRecent = (list, id) => [id, ...list.filter((x) => x !== id)].slice(0, 5);

function Field({ label, children }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  );
}

function Recent({ ids, onPick }) {
  if (!ids.length) return null;
  return (
    <div className="recent">
      {ids.map((id) => (
        <button key={id} className="chip" onClick={() => onPick(id)} title={id}>
          {id.slice(0, 8)}
        </button>
      ))}
    </div>
  );
}

export default function App() {
  const [apiUp, setApiUp] = useState(null);
  const [token, setToken] = useState(load('token', null)); // { value, issuedAt }
  const [useToken, setUseToken] = useState(false);
  const [, tick] = useState(0);
  const [log, setLog] = useState([]);
  const [last, setLast] = useState(null);
  const [recent, setRecent] = useState(load('recent', { events: [], bookings: [] }));
  const [busy, setBusy] = useState(false);

  // create event
  const [name, setName] = useState('Jazz Night');
  const [city, setCity] = useState('Berlin');
  const [startsAt, setStartsAt] = useState(tomorrowEvening());
  const [tiers, setTiers] = useState([
    { name: 'General', priceCents: 5000, quantity: 100 },
    { name: 'VIP', priceCents: 15000, quantity: 10 },
  ]);

  // book
  const [bookEventId, setBookEventId] = useState('');
  const [tierId, setTierId] = useState('');
  const [knownTiers, setKnownTiers] = useState([]);
  const [quantity, setQuantity] = useState(1);
  const [email, setEmail] = useState('ravi@example.com');

  // queries
  const [viewEventId, setViewEventId] = useState('');
  const [listCity, setListCity] = useState('Berlin');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [viewBookingId, setViewBookingId] = useState('');

  useEffect(() => {
    const check = () => api.health().then(setApiUp);
    check();
    const t = setInterval(() => {
      check();
      tick((n) => n + 1);
    }, 1000);
    return () => clearInterval(t);
  }, []);

  const remember = (kind, id) =>
    setRecent((r) => {
      const next = { ...r, [kind]: pushRecent(r[kind], id) };
      save('recent', next);
      return next;
    });

  const record = useCallback((r) => {
    setLast(r);
    setLog((l) => [r, ...l].slice(0, 100));
    if (r.issuedToken) {
      const t = { value: r.issuedToken, issuedAt: Date.now() };
      setToken(t);
      save('token', t);
    }
    return r;
  }, []);

  const readToken = useToken && token ? token.value : undefined;

  async function run(fn) {
    setBusy(true);
    try {
      return record(await fn());
    } catch (e) {
      alert(`Request failed: ${e.message}`);
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function loadTiers(eventId, withToken) {
    // A primary read (fresh token) so the tiers of a just-created event are visible immediately.
    const r = record(await api.getEvent(eventId, withToken));
    if (r.status === 200) {
      setKnownTiers(r.body.tiers);
      setTierId((cur) => (r.body.tiers.some((t) => t.tierId === cur) ? cur : r.body.tiers[0]?.tierId ?? ''));
    }
  }

  async function onCreate(e) {
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
      const id = r.body.id;
      remember('events', id);
      setBookEventId(id);
      setViewEventId(id);
      await loadTiers(id, r.issuedToken);
    }
  }

  async function onBook(e) {
    e.preventDefault();
    const r = await run(() =>
      api.bookTickets(bookEventId, { tierId, quantity: Number(quantity), customerEmail: email }),
    );
    if (r?.status === 201) {
      remember('bookings', r.body.id);
      setViewBookingId(r.body.id);
      setViewEventId(bookEventId);
    }
  }

  const tokenAge = token ? Math.round((Date.now() - token.issuedAt) / 1000) : null;

  return (
    <div className="app">
      <header>
        <h1>Event Booking · CQRS</h1>
        <div className="head-right">
          <span className={`dot ${apiUp ? 'up' : 'down'}`} />
          {apiUp === null ? 'checking API…' : apiUp ? 'API up' : 'API down (start it: scripts/start.sh)'}
        </div>
      </header>

      <section className="card token">
        <div>
          <b>X-Read-After token</b>{' '}
          {token ? (
            <>
              <code>{token.value}</code> <span className="hint">issued {tokenAge}s ago</span>
            </>
          ) : (
            <span className="hint">none yet. Create an event or book to get one.</span>
          )}
        </div>
        <label className="check">
          <input type="checkbox" checked={useToken} onChange={(e) => setUseToken(e.target.checked)} />
          Send the token on reads
          <span className="hint">
            {' '}
            (off = anonymous read: replica or cache. On and recent = primary, cache bypassed)
          </span>
        </label>
      </section>

      <div className="grid">
        <div className="col">
          <h2 className="col-title">Commands (write, primary)</h2>

          <form className="card" onSubmit={onCreate}>
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
                  <input
                    value={t.name}
                    onChange={(e) => setTiers(tiers.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))}
                    required
                  />
                </Field>
                <Field label={i === 0 ? 'Price (cents)' : ''}>
                  <input
                    type="number"
                    min="0"
                    value={t.priceCents}
                    onChange={(e) => setTiers(tiers.map((x, j) => (j === i ? { ...x, priceCents: e.target.value } : x)))}
                    required
                  />
                </Field>
                <Field label={i === 0 ? 'Quantity' : ''}>
                  <input
                    type="number"
                    min="1"
                    value={t.quantity}
                    onChange={(e) => setTiers(tiers.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))}
                    required
                  />
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
              <button
                type="button"
                className="ghost"
                onClick={() => setTiers([...tiers, { name: '', priceCents: 0, quantity: 1 }])}
              >
                + tier
              </button>
              <button disabled={busy}>Create event</button>
            </div>
          </form>

          <form className="card" onSubmit={onBook}>
            <h2>Book tickets</h2>
            <Field label="Event id">
              <input value={bookEventId} onChange={(e) => setBookEventId(e.target.value)} required />
            </Field>
            <Recent ids={recent.events} onPick={setBookEventId} />
            <div className="row">
              <Field label="Tier">
                {knownTiers.length ? (
                  <select value={tierId} onChange={(e) => setTierId(e.target.value)}>
                    {knownTiers.map((t) => (
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
                <input
                  type="number"
                  min="1"
                  value={quantity}
                  style={{ maxWidth: 80 }}
                  onChange={(e) => setQuantity(e.target.value)}
                  required
                />
              </Field>
            </div>
            <Field label="Customer email">
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </Field>
            <div className="row">
              <button
                type="button"
                className="ghost"
                disabled={!bookEventId || busy}
                onClick={() => loadTiers(bookEventId, token?.value)}
                title="Reads the event from the primary using your token, so it is always current"
              >
                Load tiers
              </button>
              <button disabled={busy || !tierId}>Book</button>
            </div>
          </form>
        </div>

        <div className="col">
          <h2 className="col-title">Queries (read, replica / cache)</h2>

          <div className="card">
            <h2>View event</h2>
            <div className="row">
              <input value={viewEventId} onChange={(e) => setViewEventId(e.target.value)} placeholder="event id" />
              <button
                disabled={!viewEventId || busy}
                onClick={() => run(() => api.getEvent(viewEventId, readToken))}
              >
                View
              </button>
            </div>
            <Recent ids={recent.events} onPick={setViewEventId} />
          </div>

          <div className="card">
            <h2>List events by city</h2>
            <div className="row">
              <Field label="City (empty = all)">
                <input value={listCity} onChange={(e) => setListCity(e.target.value)} />
              </Field>
              <Field label="Page">
                <input type="number" min="0" value={page} style={{ maxWidth: 70 }} onChange={(e) => setPage(e.target.value)} />
              </Field>
              <Field label="Size">
                <input type="number" min="1" max="100" value={size} style={{ maxWidth: 70 }} onChange={(e) => setSize(e.target.value)} />
              </Field>
            </div>
            <button disabled={busy} onClick={() => run(() => api.listEvents({ city: listCity, page, size }, readToken))}>
              List
            </button>
          </div>

          <div className="card">
            <h2>View booking</h2>
            <div className="row">
              <input value={viewBookingId} onChange={(e) => setViewBookingId(e.target.value)} placeholder="booking id" />
              <button
                disabled={!viewBookingId || busy}
                onClick={() => run(() => api.getBooking(viewBookingId, readToken))}
              >
                View
              </button>
            </div>
            <Recent ids={recent.bookings} onPick={setViewBookingId} />
          </div>

          <Burst
            eventId={viewEventId}
            setEventId={setViewEventId}
            token={token?.value}
            useToken={useToken}
            onDone={(r) => r && setLast(r)}
          />
        </div>
      </div>

      <section className="card">
        <h2>Last response</h2>
        {last ? (
          <>
            <div className="req">
              <code>
                {last.method} {last.path}
              </code>
            </div>
            <Meta r={last} />
            {last.issuedToken && (
              <p className="hint">
                Response carried <code>X-Read-After: {last.issuedToken}</code>
              </p>
            )}
            <pre>{JSON.stringify(last.body, null, 2)}</pre>
          </>
        ) : (
          <p className="hint">Nothing yet.</p>
        )}
      </section>

      <section className="card">
        <h2>
          Request log <button className="ghost small" onClick={() => setLog([])}>clear</button>
        </h2>
        <table className="log">
          <thead>
            <tr>
              <th>time</th>
              <th>request</th>
              <th>status</th>
              <th>served by</th>
              <th>cache</th>
              <th>token</th>
              <th>ms</th>
            </tr>
          </thead>
          <tbody>
            {log.map((r, i) => (
              <tr key={i} onClick={() => setLast(r)}>
                <td>{r.at.toLocaleTimeString()}</td>
                <td className="path">
                  {r.method} {r.path.replace('/api/', '')}
                </td>
                <td>{r.status}</td>
                <td>
                  <Badge kind="served" value={r.servedBy} />
                </td>
                <td>
                  <Badge kind="cache" value={r.cache} />
                </td>
                <td>{r.method === 'GET' ? (r.sentToken ? 'yes' : 'no') : ''}</td>
                <td>{r.ms.toFixed(1)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
