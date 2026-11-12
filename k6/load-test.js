// Load profile: ~95% anonymous reads (replica), a trickle of bookings, and a read-your-writes check.
// Run: k6 run k6/load-test.js   (BASE_URL, READ_RPS, WRITE_RPS can be overridden with -e)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const READ_RPS = Number(__ENV.READ_RPS || 500);
const WRITE_RPS = Number(__ENV.WRITE_RPS || 5);
const CITIES = ['Berlin', 'Paris', 'London', 'Madrid', 'Rome'];
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const staleAfterWrite = new Counter('stale_reads_after_write');

export const options = {
  scenarios: {
    browse: {
      executor: 'constant-arrival-rate', exec: 'browse',
      rate: READ_RPS, timeUnit: '1s', duration: '1m',
      preAllocatedVUs: 50, maxVUs: 500,
    },
    book: {
      executor: 'constant-arrival-rate', exec: 'book',
      rate: WRITE_RPS, timeUnit: '1s', duration: '1m',
      preAllocatedVUs: 10, maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{scenario:browse}': ['p(95)<200'],
    stale_reads_after_write: ['count==0'],
  },
};

export function setup() {
  const events = [];
  for (const city of CITIES) {
    for (let i = 0; i < 4; i++) {
      const res = http.post(`${BASE}/api/commands/events`, JSON.stringify({
        name: `${city} event ${i}`, city, startsAt: '2027-01-01T20:00:00Z',
        tiers: [{ name: 'General', priceCents: 5000, quantity: 1000000 }],
      }), { headers: JSON_HEADERS });
      const token = res.headers['X-Read-After'];
      const id = res.json('id');
      const detail = http.get(`${BASE}/api/queries/events/${id}`, { headers: { 'X-Read-After': token } });
      events.push({ id, tierId: detail.json('tiers.0.tierId') });
    }
  }
  // Let the replica catch up so anonymous reads of the seeded events don't 404.
  sleep(Number(__ENV.SEED_WAIT_SECONDS || 6));
  return { events };
}

// Anonymous visitors: no token, so everything is served by replicas.
export function browse(data) {
  if (Math.random() < 0.6) {
    const city = CITIES[Math.floor(Math.random() * CITIES.length)];
    check(http.get(`${BASE}/api/queries/events?city=${city}`), { 'list 200': (r) => r.status === 200 });
  } else {
    const e = data.events[Math.floor(Math.random() * data.events.length)];
    check(http.get(`${BASE}/api/queries/events/${e.id}`), { 'detail 200': (r) => r.status === 200 });
  }
}

// A booker writes, then immediately reads their booking with the token. Must never be stale.
export function book(data) {
  const e = data.events[Math.floor(Math.random() * data.events.length)];
  const res = http.post(`${BASE}/api/commands/events/${e.id}/bookings`, JSON.stringify({
    tierId: e.tierId, quantity: 1, customerEmail: `user${__VU}@example.com`,
  }), { headers: JSON_HEADERS });
  if (!check(res, { 'booked 201': (r) => r.status === 201 })) return;

  const read = http.get(`${BASE}/api/queries/bookings/${res.json('id')}`,
    { headers: { 'X-Read-After': res.headers['X-Read-After'] } });
  if (read.status !== 200) staleAfterWrite.add(1);
}
