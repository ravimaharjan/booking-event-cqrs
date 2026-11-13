// One place that calls the backend and records what the cache did, read from the response headers:
//   X-Served-By: primary | replica | cache      who produced the body
//   X-Cache:     hit | stale | miss | coalesced | bypass   what the cache did for this request
export const TOKEN_HEADER = 'X-Read-After';

export async function call({ method = 'GET', path, body, token }) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers[TOKEN_HEADER] = token;
  const started = performance.now();
  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const ms = performance.now() - started;
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = text;
  }
  return {
    at: new Date(),
    method,
    path,
    status: res.status,
    ms,
    servedBy: res.headers.get('X-Served-By'),
    cache: res.headers.get('X-Cache'),
    issuedToken: res.headers.get(TOKEN_HEADER),
    sentToken: Boolean(token),
    body: json,
  };
}

export const newEvent = (event) => call({ method: 'POST', path: '/api/commands/events', body: event });

export const bookTickets = (eventId, booking) =>
  call({ method: 'POST', path: `/api/commands/events/${eventId}/bookings`, body: booking });

export const getEvent = (id, token) => call({ path: `/api/queries/events/${id}`, token });

export const getBooking = (id, token) => call({ path: `/api/queries/bookings/${id}`, token });

export const listEvents = ({ city, page, size }, token) => {
  const params = new URLSearchParams({ page, size });
  if (city) params.set('city', city);
  return call({ path: `/api/queries/events?${params}`, token });
};

export async function health() {
  try {
    const res = await fetch('/actuator/health');
    return res.ok;
  } catch {
    return false;
  }
}
