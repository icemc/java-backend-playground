import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const SEED_QUERIES = ['the', 'man', 'love', 'war', 'star', 'night', 'life', 'day', 'girl', 'king'];

// Runs once before the load test: gathers a pool of real title ids from the running system's own
// search endpoint, rather than shipping a dataset-snapshot-specific list of hardcoded ids that
// would go stale the moment the seeded database is reloaded.
export function setup() {
  const ids = new Set();
  for (const term of SEED_QUERIES) {
    const res = http.get(`${BASE_URL}/api/v1/titles/search?title=${encodeURIComponent(term)}&size=100`);
    if (res.status === 200) {
      const body = res.json();
      for (const item of body.content) {
        ids.add(item.id);
      }
    }
  }
  const pool = Array.from(ids);
  if (pool.length === 0) {
    throw new Error('setup() found no title ids to sample from /api/v1/titles/search - is the database seeded?');
  }
  return { pool };
}

export const options = {
  stages: [
    { duration: '30s', target: 50 },
    { duration: '1m', target: 100 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<300'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function (data) {
  const id = data.pool[Math.floor(Math.random() * data.pool.length)];
  const res = http.get(`${BASE_URL}/api/v1/titles/${id}`);
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
  sleep(1);
}
