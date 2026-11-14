import React, { useCallback, useEffect, useState } from 'react';
import * as api from '../api.js';
import Burst from '../Burst.jsx';
import { Link } from '../router.jsx';
import { useSession } from '../session.jsx';
import { money, when } from '../format.js';

export default function EventPage({ eventId }) {
  const { run, busy, readToken, token, useToken, setLast } = useSession();
  const [result, setResult] = useState(null);

  const fetchEvent = useCallback(async () => {
    const r = await run(() => api.getEvent(eventId, readToken));
    if (r) setResult(r);
  }, [run, eventId, readToken]);

  useEffect(() => {
    fetchEvent();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [eventId]);

  const event = result?.status === 200 ? result.body : null;

  return (
    <>
      <p><Link to="/">← Events &amp; booking</Link></p>
      <div className="card">
        <h2>Event</h2>
        <p className="hint"><code>{eventId}</code></p>
        {result === null && <p className="hint">Loading…</p>}
        {result && !event && <p className="hint">Not found (HTTP {result.status}).</p>}
        {event && (
          <>
            <h1 className="event-name">{event.name}</h1>
            <p>{event.city} · {when(event.startsAt)}</p>
            <table className="items">
              <thead>
                <tr><th>tier</th><th>price</th><th>available</th><th>total</th></tr>
              </thead>
              <tbody>
                {event.tiers.map((t) => (
                  <tr key={t.tierId}>
                    <td>{t.name}</td>
                    <td>{money(t.priceCents)}</td>
                    <td>{t.available}</td>
                    <td>{t.total}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
        <div className="row" style={{ marginTop: 10 }}>
          <button className="ghost" disabled={busy} onClick={fetchEvent}>Refresh</button>
          {event && <Link to={`/?book=${eventId}`} className="btn">Book tickets</Link>}
          <Link to={`/bookings?event=${eventId}`} className="btn ghost">Bookings</Link>
        </div>
      </div>
      <Burst
        eventId={eventId}
        token={token?.value}
        useToken={useToken}
        onDone={(r) => r && setLast(r)}
      />
    </>
  );
}
