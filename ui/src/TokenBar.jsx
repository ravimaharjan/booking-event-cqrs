import React, { useEffect, useState } from 'react';
import { useSession } from './session.jsx';

export default function TokenBar() {
  const { token, useToken, setUseToken } = useSession();
  const [, tick] = useState(0);
  useEffect(() => {
    const t = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);
  const age = token ? Math.round((Date.now() - token.issuedAt) / 1000) : null;
  return (
    <section className="card token">
      <div>
        <b>X-Read-After token</b>{' '}
        {token ? (
          <>
            <code>{token.value}</code> <span className="hint">issued {age}s ago</span>
          </>
        ) : (
          <span className="hint">none yet. Create an event or book to get one.</span>
        )}
      </div>
      <label className="check">
        <input type="checkbox" checked={useToken} onChange={(e) => setUseToken(e.target.checked)} />
        Send the token on reads
        <span className="hint"> (off = anonymous read: replica or cache. On and recent = primary, cache bypassed)</span>
      </label>
    </section>
  );
}
