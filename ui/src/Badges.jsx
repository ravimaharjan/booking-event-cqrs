import React from 'react';

const EXPLAIN = {
  cache: 'Body came straight from Redis. No database was read.',
  replica: 'Body came from the read replica (database).',
  primary: 'Body came from the primary (a fresh X-Read-After token was sent).',
  local: 'Served from this app instance\u2019s own memory (L1). No Redis call.',
  hit: 'Fresh entry served from Redis.',
  stale: 'Soft-expired entry served from Redis while ONE request refreshes it in the background.',
  miss: 'No entry. This request rebuilt it from the replica.',
  coalesced: 'No entry. This request waited for another request’s rebuild (single-flight) and read nothing.',
  bypass: 'Cache not consulted (primary read).',
};

export function Badge({ kind, value }) {
  if (!value) return null;
  return (
    <span className={`badge ${kind}-${value}`} title={EXPLAIN[value] || ''}>
      {kind === 'served' ? 'served by ' : 'cache '}
      <b>{value}</b>
    </span>
  );
}

export function EdgeBadge({ value }) {
  if (!value) return null;
  return (
    <span className={`badge edge-${value.toLowerCase()}`} title="What the caching proxy in front of the app did">
      edge <b>{value.toLowerCase()}</b>
    </span>
  );
}

export function Meta({ r }) {
  return (
    <div className="meta">
      <span className={`badge status-${r.status < 300 ? 'ok' : 'err'}`}>{r.status}</span>
      <Badge kind="served" value={r.servedBy} />
      <Badge kind="cache" value={r.cache} />
      <EdgeBadge value={r.edge} />
      <span className="badge">{r.ms.toFixed(1)} ms</span>
      {r.method === 'GET' && <span className="badge">{r.sentToken ? 'token sent' : 'no token'}</span>}
    </div>
  );
}
