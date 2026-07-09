import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Fuzzy trigram search terms - deliberately generic word fragments likely to partial-match a
// wide spread of real primary/original titles, not exact titles.
const QUERY_TERMS = [
  'love', 'war', 'man', 'night', 'king', 'world', 'story', 'life', 'day',
  'dark', 'star', 'shadow', 'girl', 'house', 'time', 'city', 'game', 'dream',
  'last', 'new', 'red', 'black', 'blue', 'home', 'land', 'wind',
];

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

export default function () {
  const term = QUERY_TERMS[Math.floor(Math.random() * QUERY_TERMS.length)];
  const res = http.get(`${BASE_URL}/api/v1/titles/search?title=${encodeURIComponent(term)}&size=20`);
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
  sleep(1);
}
