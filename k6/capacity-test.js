// Capacity probe: holds ONE constant request rate for DURATION and reports what the system actually achieved.
// Run one level:  k6 run -e RATE=10000 k6/capacity-test.js
// Find the ceiling: scripts/capacity-test.sh   (steps through several rates and prints a table)
//
// TARGET=hot    every request reads the same event (one hot key, the stampede case)
// TARGET=mixed  requests spread over many events and city listings (many keys)
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 5000);
const DURATION = __ENV.DURATION || '60s';
const TARGET = __ENV.TARGET || 'hot';
const CITIES = ['Berlin', 'Paris', 'London', 'Madrid', 'Rome'];
const NO_BODY = { responseType: 'none' }; // do not buffer bodies: keeps the load generator light
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.min(2000, Math.max(100, Math.ceil(RATE / 50))),
      maxVUs: Math.min(6000, Math.max(300, Math.ceil(RATE / 5))),
    },
  },
  // No abort thresholds: the point is to see how it degrades. The wrapper script applies the pass/fail rule.
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const ids = [];
  const count = TARGET === 'hot' ? 1 : 20;
  for (let i = 0; i < count; i++) {
    const city = CITIES[i % CITIES.length];
    const res = http.post(`${BASE}/api/commands/events`, JSON.stringify({
      name: `capacity ${i}`, city, startsAt: '2027-01-01T20:00:00Z',
      tiers: [{ name: 'General', priceCents: 5000, quantity: 1000000 }],
    }), { headers: JSON_HEADERS });
    ids.push(res.json('id'));
  }
  sleep(Number(__ENV.SEED_WAIT_SECONDS || 8)); // replica + second invalidation settle
  for (const id of ids) http.get(`${BASE}/api/queries/events/${id}`); // warm the cache
  return { ids };
}

export default function (data) {
  if (TARGET === 'hot') {
    check(http.get(`${BASE}/api/queries/events/${data.ids[0]}`, NO_BODY), { ok: (r) => r.status === 200 });
  } else if (Math.random() < 0.5) {
    const city = CITIES[Math.floor(Math.random() * CITIES.length)];
    check(http.get(`${BASE}/api/queries/events?city=${city}`, NO_BODY), { ok: (r) => r.status === 200 });
  } else {
    const id = data.ids[Math.floor(Math.random() * data.ids.length)];
    check(http.get(`${BASE}/api/queries/events/${id}`, NO_BODY), { ok: (r) => r.status === 200 });
  }
}
