import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// The standard 28 IMDb genre values. Verify these match the real seeded GENRE enum exactly
// before a serious run - a mismatched spelling/hyphenation silently returns an empty result
// rather than an error (the query filters `genres::text[] @> ARRAY[:genre]::text[]`, LLD §4.3):
//   SELECT unnest(enum_range(NULL::genre)) ORDER BY 1;
const GENRES = [
  'Action', 'Adult', 'Adventure', 'Animation', 'Biography', 'Comedy', 'Crime',
  'Documentary', 'Drama', 'Family', 'Fantasy', 'Film-Noir', 'Game-Show',
  'History', 'Horror', 'Music', 'Musical', 'Mystery', 'News', 'Reality-TV',
  'Romance', 'Sci-Fi', 'Short', 'Sport', 'Talk-Show', 'Thriller', 'War', 'Western',
];

export const options = {
  stages: [
    { duration: '30s', target: 50 },
    { duration: '1m', target: 100 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const genre = GENRES[Math.floor(Math.random() * GENRES.length)];
  const res = http.get(`${BASE_URL}/api/v1/genres/${encodeURIComponent(genre)}/top-rated?limit=25`);
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
  sleep(1);
}
