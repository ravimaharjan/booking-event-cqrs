import React, { useCallback, useEffect, useState } from 'react';
import * as api from '../api.js';
import { Link } from '../router.jsx';
import { useSession } from '../session.jsx';
import { when } from '../format.js';
import Pager from '../Pager.jsx';

// Bookings are listed from the server, newest first, one page at a time. The optional event filter narrows it.
export default function BookingsPage({ initialId, eventFilter }) {
  const { remember, readToken, busy, run } = useSession();
  const [lookup, setLookup] = useState(initialId);
  const [found, setFound] = useState(null);
  const [eventId, setEventId] = useState(eventFilter);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [rows, setRows] = useState(null);

  const list = useCallback(
    async (p = page, sz = size) => {
      const r = await run(() => api.listBookings({ eventId: eventId.trim(), page: p, size: sz }, readToken));
      if (r?.status === 200) {
        setRows(r.body);
        setPage(p);
      }
    },
    [run, eventId, page, size, readToken],
  );

  useEffect(() => {
    list(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const find = useCallback(
    async (id) => {
      const r = await run(() => api.getBooking(id, readToken));
      setFound(r);
      if (r?.status === 200) remember('bookings', id);
    },
    [run, readToken, remember],
  );

  useEffect(() => {
    if (initialId) find(initialId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const b = found?.status === 200 ? found.body : null;

  return (
    <>
      <form className="card" onSubmit={(e) => { e.preventDefault(); find(lookup.trim()); }}>
        <h2>Find a booking</h2>
        <div className="row">
          <input value={lookup} onChange={(e) => setLookup(e.target.value)} placeholder="booking id" />
          <button disabled={!lookup.trim() || busy}>View</button>
        </div>
        {found && !b && <p className="hint">Not found (HTTP {found.status}).</p>}
        {b && (
          <table className="items">
            <tbody>
              <tr><th>booking</th><td><code>{b.bookingId}</code></td></tr>
              <tr><th>event</th><td><Link to={`/events/${b.eventId}`}><code>{b.eventId}</code></Link></td></tr>
              <tr><th>tier</th><td><code>{b.tierId}</code></td></tr>
              <tr><th>quantity</th><td>{b.quantity}</td></tr>
              <tr><th>customer</th><td>{b.customerEmail}</td></tr>
              <tr><th>created</th><td>{when(b.createdAt)}</td></tr>
            </tbody>
          </table>
        )}
      </form>

      <div className="card">
        <h2>Bookings</h2>
        <div className="row">
          <input value={eventId} onChange={(e) => setEventId(e.target.value)} placeholder="filter by event id (optional)" />
          <button disabled={busy} onClick={() => list(0)}>List</button>
        </div>
        {rows === null ? (
          <p className="hint">Loading…</p>
        ) : rows.length === 0 ? (
          <p className="hint">{page > 0 ? 'No more bookings.' : <>No bookings yet. Book tickets from <Link to="/">Events &amp; booking</Link>.</>}</p>
        ) : (
          <table className="items">
            <thead>
              <tr><th>booking</th><th>event</th><th>qty</th><th>customer</th><th>created</th></tr>
            </thead>
            <tbody>
              {rows.map((x) => (
                <tr key={x.bookingId} className="clickable" onClick={() => { setLookup(x.bookingId); find(x.bookingId); }}>
                  <td><code title={x.bookingId}>{x.bookingId.slice(0, 8)}</code></td>
                  <td><Link to={`/events/${x.eventId}`} onClick={(e) => e.stopPropagation()}><code>{x.eventId.slice(0, 8)}</code></Link></td>
                  <td>{x.quantity}</td>
                  <td>{x.customerEmail}</td>
                  <td>{when(x.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {rows !== null && (
          <Pager page={page} size={size} setSize={setSize} count={rows.length} disabled={busy} onPage={list} />
        )}
      </div>
    </>
  );
}
