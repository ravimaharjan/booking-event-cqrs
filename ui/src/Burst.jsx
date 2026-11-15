import React, { useState } from 'react';
import { getEvent } from './api.js';

const KINDS = ['local', 'hit', 'stale', 'miss', 'coalesced', 'bypass'];

function percentile(sorted, p) {
  return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
}

// Fires many concurrent reads of ONE event, like a hot key. Right after a booking (which invalidates the entry)
// you should see exactly one "miss" and the rest "coalesced": a single database read for the whole crowd.
export default function Burst({ eventId, setEventId, token, onDone }) {
  const [count, setCount] = useState(200);
  const [running, setRunning] = useState(false);
  const [summary, setSummary] = useState(null);

  async function fire() {
    setRunning(true);
    const started = performance.now();
    const results = await Promise.all(
      Array.from({ length: count }, () => getEvent(eventId, token).catch(() => null)),
    );
    const wall = performance.now() - started;
    const ok = results.filter(Boolean);
    const counts = Object.fromEntries(KINDS.map((k) => [k, ok.filter((r) => r.cache === k).length]));
    const latencies = ok.map((r) => r.ms).sort((a, b) => a - b);
    setSummary({
      sent: count,
      failed: count - ok.length,
      counts,
      p50: percentile(latencies, 0.5),
      p95: percentile(latencies, 0.95),
      max: latencies[latencies.length - 1],
      wall,
    });
    onDone?.(ok[0]);
    setRunning(false);
  }

  return (
    <section className="card">
      <h2>Hot key burst</h2>
      <p className="hint">
        Sends many concurrent reads of one event. Try it right after booking that event (the booking invalidates its
        cache entry) and compare <b>miss</b> with <b>coalesced</b> (and <b>local</b>, served from the app\u2019s memory): only one request should have read the database.
      </p>
      <div className="row">
        <input value={eventId} onChange={(e) => setEventId?.(e.target.value)} readOnly={!setEventId} placeholder="event id" />
        <input
          type="number"
          min="1"
          max="2000"
          value={count}
          onChange={(e) => setCount(Math.max(1, Math.min(2000, Number(e.target.value) || 1)))}
          style={{ maxWidth: 90 }}
        />
        <button disabled={!eventId || running} onClick={fire}>
          {running ? 'Running…' : `Fire ${count} requests`}
        </button>
      </div>
      {summary && (
        <table className="summary">
          <tbody>
            <tr>
              {KINDS.map((k) => (
                <th key={k}>{k}</th>
              ))}
              <th>failed</th>
            </tr>
            <tr>
              {KINDS.map((k) => (
                <td key={k} className={summary.counts[k] ? '' : 'zero'}>
                  {summary.counts[k]}
                </td>
              ))}
              <td className={summary.failed ? 'bad' : 'zero'}>{summary.failed}</td>
            </tr>
          </tbody>
        </table>
      )}
      {summary && (
        <p className="hint">
          p50 {summary.p50.toFixed(0)} ms · p95 {summary.p95.toFixed(0)} ms · max {summary.max.toFixed(0)} ms ·{' '}
          {summary.wall.toFixed(0)} ms total. Browsers cap parallel connections, so the burst is smoother than a real
          crowd; use the k6 test for a true load.
        </p>
      )}
    </section>
  );
}
