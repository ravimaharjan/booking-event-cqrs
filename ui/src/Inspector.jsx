import React, { useState } from 'react';
import { Badge, EdgeBadge, Meta } from './Badges.jsx';
import { useSession } from './session.jsx';

// The last response and the request log, shown under every page so the cache headers are always visible.
export default function Inspector() {
  const { last, setLast, log, setLog } = useSession();
  const [page, setPage] = useState(0);
  const size = 10;
  const pages = Math.max(1, Math.ceil(log.length / size));
  const current = Math.min(page, pages - 1);
  const visible = log.slice(current * size, current * size + size);
  return (
    <>
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
          Request log <button className="ghost small" onClick={() => { setLog([]); setPage(0); }}>clear</button>
        </h2>
        <table className="log">
          <thead>
            <tr>
              <th>time</th>
              <th>request</th>
              <th>status</th>
              <th>served by</th>
              <th>cache</th>
              <th>edge</th>
              <th>token</th>
              <th>ms</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((r, i) => (
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
                <td>
                  <EdgeBadge value={r.edge} />
                </td>
                <td>{r.method === 'GET' ? (r.sentToken ? 'yes' : 'no') : ''}</td>
                <td>{r.ms.toFixed(1)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="pager">
          <button className="ghost" disabled={current === 0} onClick={() => setPage(current - 1)}>← Newer</button>
          <span>{log.length ? `${current * size + 1}–${current * size + visible.length} of ${log.length}` : 'empty'}</span>
          <button className="ghost" disabled={current >= pages - 1} onClick={() => setPage(current + 1)}>Older →</button>
        </div>
      </section>
    </>
  );
}
