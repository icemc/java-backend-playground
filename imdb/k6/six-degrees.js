import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MAX_DEGREE = __ENV.MAX_DEGREE || 7;

// Common two-word names likely to be shared by more than one real person in a dataset this size -
// used only as a bootstrap when no curated CSV is present (see setup() below).
const SEED_QUERIES = [
  'John Smith', 'Michael Johnson', 'David Miller', 'Robert Brown', 'James Wilson',
  'Mary Johnson', 'John Williams', 'Michael Smith', 'David Jones', 'Maria Garcia',
  'James Smith', 'John Davis', 'Robert Miller', 'Michael Brown', 'John Miller',
];

function loadFromCsv() {
  let csv;
  try {
    csv = open('./data/sampled-people.csv');
  } catch (e) {
    return [];
  }
  return csv
    .split('\n')
    .slice(1) // header
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith('#'))
    .map((line) => {
      const [id, name] = line.split(',');
      return { id, name };
    });
}

// Runs once before the load test. Prefers a curated data/sampled-people.csv - see
// data/generate-sampled-people.sql for how to build a large, realistic one from the real loaded
// dataset, mixing ordinary and high-degree "hub" actors (LLD §8). Falls back to discovering real
// person ids live from the API's own disambiguation responses when no CSV has been populated yet,
// so this script still runs meaningfully against a freshly-seeded database with zero setup.
export function setup() {
  const fromCsv = loadFromCsv();
  if (fromCsv.length >= 2) {
    return { people: fromCsv };
  }

  const pool = new Map();
  for (const name of SEED_QUERIES) {
    const res = http.get(
      `${BASE_URL}/api/v1/people/six-degrees?personA=${encodeURIComponent(name)}&personB=${encodeURIComponent(name)}&maxDegree=1`
    );
    if (res.status !== 200) continue;
    const body = res.json();
    if (body.requiresDisambiguation && Array.isArray(body.candidates)) {
      for (const c of body.candidates) pool.set(c.id, c.name);
    } else if (body.personA) {
      pool.set(body.personA.id, body.personA.name);
    }
  }

  const people = Array.from(pool, ([id, name]) => ({ id, name }));
  if (people.length < 2) {
    throw new Error(
      'Could not assemble at least 2 distinct people (empty data/sampled-people.csv and no ' +
      'ambiguous-name discovery hits) - is the database seeded? See data/generate-sampled-people.sql.'
    );
  }
  return { people };
}

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '1m', target: 50 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    // Deliberately far looser than the other three endpoints (LLD §8): this is the one endpoint
    // whose cost depends on graph shape (hub actors), not a bounded index lookup - the gap between
    // this threshold and the others' is itself the finding this load test exists to produce.
    http_req_duration: ['p(95)<3000'],
    http_req_failed: ['rate<0.02'],
  },
};

// Picks two distinct people per iteration so the bidirectional CTE - and the six-degrees cache,
// keyed per unordered pair (LLD §6) - is genuinely exercised under load instead of collapsing
// into repeated hits on one warm cache entry.
export default function (data) {
  const people = data.people;
  const a = people[Math.floor(Math.random() * people.length)];
  let b = people[Math.floor(Math.random() * people.length)];
  while (b.id === a.id && people.length > 1) {
    b = people[Math.floor(Math.random() * people.length)];
  }

  const res = http.get(
    `${BASE_URL}/api/v1/people/six-degrees?personA=${encodeURIComponent(a.id)}&personB=${encodeURIComponent(b.id)}&maxDegree=${MAX_DEGREE}`
  );
  check(res, {
    'status is 200 or 404': (r) => r.status === 200 || r.status === 404,
  });
  sleep(1);
}
