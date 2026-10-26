// Random transfers between funded accounts at a fixed arrival rate.
//
// constant-arrival-rate keeps sending at RATE per second even when responses slow down. A
// closed loop ("each virtual user sends, waits, sends") quietly sends less when the server is
// slow, which hides exactly the latency you are trying to measure.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 100);
const ACCOUNTS = Number(__ENV.ACCOUNTS || 50);
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    transfers: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  thresholds: {
    'http_req_duration{name:transfer}': ['p(99)<250'],
    'checks': ['rate>0.99'],
  },
};

export function setup() {
  const treasury = http.get(`${BASE}/accounts/treasury/USD`).json('id');
  const ids = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const id = http.post(`${BASE}/accounts`, JSON.stringify({ currency: 'USD' }), { headers: JSON_HEADERS }).json('id');
    http.post(`${BASE}/transfers`,
      JSON.stringify({ fromAccountId: treasury, toAccountId: id, amountMinor: 1000000, currency: 'USD' }),
      { headers: { ...JSON_HEADERS, 'Idempotency-Key': `fund-${id}` } });
    ids.push(id);
  }
  return { ids };
}

export default function (data) {
  const from = data.ids[Math.floor(Math.random() * data.ids.length)];
  let to = data.ids[Math.floor(Math.random() * data.ids.length)];
  if (to === from) {
    to = data.ids[(data.ids.indexOf(from) + 1) % data.ids.length];
  }
  const res = http.post(`${BASE}/transfers`,
    JSON.stringify({ fromAccountId: from, toAccountId: to, amountMinor: 1 + Math.floor(Math.random() * 500), currency: 'USD' }),
    { headers: { ...JSON_HEADERS, 'Idempotency-Key': `${__VU}-${__ITER}-${Date.now()}` }, tags: { name: 'transfer' } });

  // 201 posted, 422 insufficient funds and 409 optimistic conflict are all correct answers.
  check(res, { 'correct answer': (r) => [201, 409, 422].includes(r.status) });
}
