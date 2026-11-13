import http from 'k6/http';
import { check } from 'k6';

const base = __ENV.BOOKING_URL;
if (!base || !/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) {
  throw new Error('Set BOOKING_URL to the loopback address printed by FixtureMain');
}

export const options = {
  scenarios: {
    bookings: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.BOOKINGS_PER_SECOND || 20),
      timeUnit: '1s',
      duration: __ENV.LOAD_DURATION || '20s',
      preAllocatedVUs: 10,
      maxVUs: 20,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<250'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate==1'],
    dropped_iterations: ['count==0'],
  },
};

export default function () {
  const name = `Load attendee ${__VU}-${__ITER}`;
  const created = http.post(`${base}/api/bookings`, JSON.stringify({ name }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'POST /api/bookings' },
  });
  const valid = check(created, {
    'booking created with expected data': (response) =>
      response.status === 201 && response.json('name') === name &&
      response.headers.Location === `/api/bookings/${response.json('id')}`,
  });
  if (!valid) return;

  const id = created.json('id');
  const fetched = http.get(`${base}/api/bookings/${id}`, {
    tags: { name: 'GET /api/bookings/:id' },
  });
  check(fetched, {
    'booking read back with expected data': (response) =>
      response.status === 200 && response.json('id') === id && response.json('name') === name,
  });
}
