import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@imdb.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'change-me-please';
const USER_COUNT = Number(__ENV.LOAD_TEST_USER_COUNT || 15);

// Runs every endpoint group simultaneously in one k6 invocation, unlike search.js/title-detail.js/
// top-rated.js/six-degrees.js (LLD §8), which are deliberately run one at a time so each run's
// metrics are attributable to a single endpoint. This script exists for the opposite reason: to
// surface *interaction* effects a single-endpoint run can't - admin writes evicting caches while
// reads are in flight, JWT verification overhead under combined load, connection-pool contention
// across very different query shapes at once. Both testing styles stay valid; this is additive, not
// a replacement.
//
// Every VU-created row is tagged and self-cleaning where the API allows it:
//   - Admin-created titles/people are named "K6 Load Test <timestamp>-<random>" and soft-deleted by
//     the same iteration that created them, so the run doesn't leave the admin id sequence and the
//     titles/people tables growing unbounded across repeated runs.
//   - Load-test user accounts are registered as k6-loadtest-user-<n>-<runId>@example.com so they're
//     trivially greppable and never collide with a real or previous run's accounts.
// Nothing here issues a hard delete (the API has none, by design - soft-delete only, LLD §3.4), so
// re-running this script repeatedly against the same dev database is safe but not zero-footprint:
// soft-deleted admin rows and the load-test user accounts/reviews/lists/watchlists persist. That's
// intentional - the point is realistic write traffic, not a spotless database afterward.

const runId = `${Date.now()}-${Math.floor(Math.random() * 100000)}`;

// SMOKE_TEST=1 shrinks every scenario to a few seconds at 1-2 VUs - for verifying the whole script
// (setup(), every request shape, every scenario function) actually runs end to end before committing
// to the full multi-minute, tens-of-VUs run below. Not a separate script: same code path, same
// endpoint coverage, just a much smaller dial.
const SMOKE_TEST = __ENV.SMOKE_TEST === '1';
function stages(smoke, real) {
  return SMOKE_TEST ? smoke : real;
}

// open() must run in k6's init context (top-level module scope) - see six-degrees.js for why this
// can't be called lazily inside setup().
let rawPeopleCsv = null;
try {
  rawPeopleCsv = open('./data/sampled-people.csv');
} catch (e) {
  rawPeopleCsv = null;
}

function parsePeopleCsv(csv) {
  if (!csv) return [];
  return csv
    .split('\n')
    .slice(1)
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith('#'))
    .map((line) => {
      const [id, name] = line.split(',');
      return { id, name };
    });
}

const SEARCH_TERMS = ['love', 'war', 'man', 'night', 'king', 'world', 'star', 'dark', 'life', 'city'];
const GENRES = ['Action', 'Comedy', 'Drama', 'Horror', 'Romance', 'Thriller', 'Sci-Fi', 'Adventure'];

function jsonHeaders(token) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  return { headers };
}

// Runs once before any scenario's VUs start iterating. Assembles everything every scenario needs:
// a pool of real title/person ids to browse (same discovery approach as title-detail.js/
// six-degrees.js), a pool of freshly-registered regular-user sessions for the userJourney scenario,
// and one admin session (the bootstrap admin, IMDB_BOOTSTRAP_ADMIN_EMAIL/_PASSWORD in
// docker-compose.yaml) for the adminWrites scenario.
export function setup() {
  const titleIds = new Set();
  for (const term of SEARCH_TERMS) {
    const res = http.get(`${BASE_URL}/api/v1/titles/search?title=${encodeURIComponent(term)}&size=50`);
    if (res.status === 200) {
      for (const item of res.json().content) titleIds.add(item.id);
    }
  }
  const titlePool = Array.from(titleIds);
  if (titlePool.length === 0) {
    throw new Error('setup() found no title ids - is the database seeded?');
  }

  const peoplePool = parsePeopleCsv(rawPeopleCsv);
  if (peoplePool.length < 2) {
    throw new Error(
      'data/sampled-people.csv has fewer than 2 entries - see data/generate-sampled-people.sql. ' +
      'six-degrees.js falls back to live discovery when this is empty; this script requires the ' +
      'CSV since it also needs stable ids for the userJourney scenario, not just any two people.'
    );
  }

  const userSessions = [];
  for (let i = 0; i < USER_COUNT; i++) {
    const email = `k6-loadtest-user-${i}-${runId}@example.com`;
    const res = http.post(
      `${BASE_URL}/api/v1/auth/register`,
      JSON.stringify({ email, password: 'k6-load-test-password', displayName: `K6 Load Test User ${i}` }),
      jsonHeaders()
    );
    if (res.status !== 201) {
      throw new Error(`setup() failed to register load-test user ${email}: ${res.status} ${res.body}`);
    }
    userSessions.push({ token: res.json().accessToken });
  }

  const adminLogin = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
    jsonHeaders()
  );
  if (adminLogin.status !== 200) {
    throw new Error(
      `setup() failed to log in as the bootstrap admin (${ADMIN_EMAIL}): ${adminLogin.status} ${adminLogin.body} - ` +
      'is IMDB_BOOTSTRAP_ADMIN_EMAIL/_PASSWORD set the same way in docker-compose.yaml and this script\'s ' +
      'ADMIN_EMAIL/ADMIN_PASSWORD env vars?'
    );
  }
  const adminToken = adminLogin.json().accessToken;

  return { titlePool, peoplePool, userSessions, adminToken };
}

const browsingErrors = new Rate('browsing_errors');
const userJourneyErrors = new Rate('user_journey_errors');
const adminWriteErrors = new Rate('admin_write_errors');

export const options = {
  scenarios: {
    // Anonymous read traffic: the same four endpoints search.js/title-detail.js/top-rated.js/
    // six-degrees.js each exercise in isolation, merged into one randomized mix - the biggest slice
    // of VUs, matching real traffic being read-heavy.
    browsing: {
      executor: 'ramping-vus',
      exec: 'browsing',
      startVUs: 0,
      stages: stages(
        [{ duration: '5s', target: 2 }],
        [
          { duration: '30s', target: 40 },
          { duration: '1m', target: 80 },
          { duration: '30s', target: 0 },
        ]
      ),
    },
    // Authenticated regular users: browse a title, then work it into their watchlist, a review, and
    // a personal list - the full user-generated-content surface (Phases 6-8 of the CRUD expansion).
    userJourney: {
      executor: 'ramping-vus',
      exec: 'userJourney',
      startVUs: 0,
      stages: stages(
        [{ duration: '5s', target: 2 }],
        [
          { duration: '30s', target: 10 },
          { duration: '1m', target: 20 },
          { duration: '30s', target: 0 },
        ]
      ),
    },
    // Admin writes: deliberately the smallest slice of VUs, matching how infrequent admin
    // operations are relative to reads/user-content in real usage - but each iteration still
    // touches every admin-write endpoint over titles, people, crew, and cast/crew credits.
    adminWrites: {
      executor: 'ramping-vus',
      exec: 'adminWrites',
      startVUs: 0,
      stages: stages(
        [{ duration: '5s', target: 1 }],
        [
          { duration: '30s', target: 2 },
          { duration: '1m', target: 5 },
          { duration: '30s', target: 0 },
        ]
      ),
    },
  },
  thresholds: {
    // Duration budgets per scenario, loosest for browsing since it includes six-degrees calls
    // (LLD §8 already documents that endpoint's cost depends on graph shape, not a bounded lookup).
    'http_req_duration{scenario:browsing}': ['p(95)<3000'],
    'http_req_duration{scenario:userJourney}': ['p(95)<1000'],
    'http_req_duration{scenario:adminWrites}': ['p(95)<1000'],
    // Custom error rates, not the built-in http_req_failed - a mixed-status-code workload like
    // userJourney's reviews (a repeat review on the same title is a correct 409, not a failure) and
    // browsing's six-degrees calls (an expected 504 on a hard pair) would otherwise be misclassified
    // as failures by k6's default "any 4xx/5xx is a failure" rule. Each scenario's own function
    // below records failure only for a genuinely unexpected status code.
    browsing_errors: ['rate<0.05'],
    user_journey_errors: ['rate<0.02'],
    admin_write_errors: ['rate<0.01'],
  },
};

function randomOf(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export function browsing(data) {
  group('search', () => {
    const res = http.get(`${BASE_URL}/api/v1/titles/search?title=${encodeURIComponent(randomOf(SEARCH_TERMS))}&size=20`);
    browsingErrors.add(!check(res, { 'search: status is 200': (r) => r.status === 200 }));
  });

  group('title detail', () => {
    const res = http.get(`${BASE_URL}/api/v1/titles/${randomOf(data.titlePool)}`);
    browsingErrors.add(!check(res, { 'title detail: status is 200': (r) => r.status === 200 }));
  });

  group('top rated', () => {
    const res = http.get(`${BASE_URL}/api/v1/genres/${encodeURIComponent(randomOf(GENRES))}/top-rated?limit=25`);
    browsingErrors.add(!check(res, { 'top rated: status is 200': (r) => r.status === 200 }));
  });

  group('six degrees', () => {
    const a = randomOf(data.peoplePool);
    let b = randomOf(data.peoplePool);
    while (b.id === a.id && data.peoplePool.length > 1) b = randomOf(data.peoplePool);
    const res = http.get(`${BASE_URL}/api/v1/people/six-degrees?personA=${a.id}&personB=${b.id}&maxDegree=7`);
    // 504 is a real, accepted outcome for a genuinely hard pair (ApiExceptionHandler.handleQueryTimeout),
    // not a bug - matching six-degrees.js's own acceptance of 200/404 as both valid.
    browsingErrors.add(!check(res, { 'six degrees: status is 200, 404, or 504': (r) => [200, 404, 504].includes(r.status) }));
  });

  sleep(1);
}

export function userJourney(data) {
  const session = randomOf(data.userSessions);
  const auth = jsonHeaders(session.token);
  const titleId = randomOf(data.titlePool);

  group('browse then watchlist', () => {
    const detail = http.get(`${BASE_URL}/api/v1/titles/${titleId}`);
    userJourneyErrors.add(!check(detail, { 'title detail: status is 200': (r) => r.status === 200 }));

    const add = http.post(`${BASE_URL}/api/v1/watchlist/items`, JSON.stringify({ titleId }), auth);
    userJourneyErrors.add(!check(add, { 'add to watchlist: status is 201': (r) => r.status === 201 }));

    const mine = http.get(`${BASE_URL}/api/v1/watchlist`, auth);
    userJourneyErrors.add(!check(mine, { 'get own watchlist: status is 200': (r) => r.status === 200 }));

    const visibility = http.put(
      `${BASE_URL}/api/v1/watchlist/visibility`,
      JSON.stringify({ visibility: Math.random() < 0.5 ? 'PUBLIC' : 'PRIVATE' }),
      auth
    );
    userJourneyErrors.add(!check(visibility, { 'update watchlist visibility: status is 200': (r) => r.status === 200 }));

    const remove = http.del(`${BASE_URL}/api/v1/watchlist/items/${titleId}`, null, auth);
    userJourneyErrors.add(!check(remove, { 'remove from watchlist: status is 204': (r) => r.status === 204 }));
  });

  group('review', () => {
    const existing = http.get(`${BASE_URL}/api/v1/titles/${titleId}/reviews/me`, auth);
    userJourneyErrors.add(!check(existing, { 'get my review: status is 200 or 404': (r) => [200, 404].includes(r.status) }));

    if (existing.status === 404) {
      const create = http.post(
        `${BASE_URL}/api/v1/titles/${titleId}/reviews`,
        JSON.stringify({ rating: 1 + Math.floor(Math.random() * 10), body: 'Load-tested review', version: 0 }),
        auth
      );
      // 409 happens if a concurrent iteration for this same session/title raced us between the GET
      // above and this POST - a real, correct outcome under concurrency, not a bug.
      userJourneyErrors.add(!check(create, { 'create review: status is 201 or 409': (r) => [201, 409].includes(r.status) }));
    } else {
      const current = existing.json();
      const update = http.put(
        `${BASE_URL}/api/v1/titles/${titleId}/reviews/me`,
        JSON.stringify({ rating: 1 + Math.floor(Math.random() * 10), body: 'Updated by load test', version: current.version }),
        auth
      );
      userJourneyErrors.add(!check(update, { 'update review: status is 200 or 409': (r) => [200, 409].includes(r.status) }));
    }
  });

  group('list', () => {
    const create = http.post(
      `${BASE_URL}/api/v1/lists`,
      JSON.stringify({ name: `K6 Load Test List (${session.token.slice(-8)})`, visibility: 'PUBLIC' }),
      auth
    );
    userJourneyErrors.add(!check(create, { 'create list: status is 201': (r) => r.status === 201 }));
    if (create.status !== 201) return;
    const listId = create.json().id;

    const addItem = http.post(`${BASE_URL}/api/v1/lists/${listId}/items`, JSON.stringify({ titleId }), auth);
    userJourneyErrors.add(!check(addItem, { 'add list item: status is 201': (r) => r.status === 201 }));

    const view = http.get(`${BASE_URL}/api/v1/lists/${listId}`, auth);
    userJourneyErrors.add(!check(view, { 'get list: status is 200': (r) => r.status === 200 }));

    const removeItem = http.del(`${BASE_URL}/api/v1/lists/${listId}/items/${titleId}`, null, auth);
    userJourneyErrors.add(!check(removeItem, { 'remove list item: status is 204': (r) => r.status === 204 }));
  });

  sleep(1);
}

export function adminWrites(data) {
  const auth = jsonHeaders(data.adminToken);
  const tag = `K6 Load Test ${Date.now()}-${__VU}-${__ITER}`;

  group('title lifecycle', () => {
    const createTitle = http.post(
      `${BASE_URL}/api/v1/titles`,
      JSON.stringify({ primaryTitle: tag, originalTitle: tag, titleType: 'movie', startYear: 2024, genres: ['Drama'] }),
      auth
    );
    adminWriteErrors.add(!check(createTitle, { 'create title: status is 201': (r) => r.status === 201 }));
    if (createTitle.status !== 201) return;
    const title = createTitle.json();

    const createPerson = http.post(
      `${BASE_URL}/api/v1/people`,
      JSON.stringify({ primaryName: tag, birthYear: 1980, deathYear: null, primaryProfession: ['actor'] }),
      auth
    );
    adminWriteErrors.add(!check(createPerson, { 'create person: status is 201': (r) => r.status === 201 }));
    if (createPerson.status !== 201) {
      http.del(`${BASE_URL}/api/v1/titles/${title.id}`, null, auth);
      return;
    }
    const person = createPerson.json();

    const crew = http.put(
      `${BASE_URL}/api/v1/titles/${title.id}/crew`,
      JSON.stringify({ directors: [person.id], writers: [] }),
      auth
    );
    adminWriteErrors.add(!check(crew, { 'upsert crew: status is 200': (r) => r.status === 200 }));

    const addPrincipal = http.post(
      `${BASE_URL}/api/v1/titles/${title.id}/principals`,
      JSON.stringify({ personId: person.id, category: 'actor', job: null, characters: ['Load Test Character'], ordering: 1 }),
      auth
    );
    adminWriteErrors.add(!check(addPrincipal, { 'add principal: status is 201': (r) => r.status === 201 }));

    const principals = http.get(`${BASE_URL}/api/v1/titles/${title.id}/principals`);
    adminWriteErrors.add(!check(principals, { 'get principals: status is 200': (r) => r.status === 200 }));

    const updatePrincipal = http.put(
      `${BASE_URL}/api/v1/titles/${title.id}/principals/1?expectedVersion=0`,
      JSON.stringify({ personId: person.id, category: 'actor', job: null, characters: ['Updated Character'], ordering: 1 }),
      auth
    );
    adminWriteErrors.add(!check(updatePrincipal, { 'update principal: status is 200': (r) => r.status === 200 }));

    const deletePrincipal = http.del(`${BASE_URL}/api/v1/titles/${title.id}/principals/1`, null, auth);
    adminWriteErrors.add(!check(deletePrincipal, { 'delete principal: status is 204': (r) => r.status === 204 }));

    const updateTitle = http.put(
      `${BASE_URL}/api/v1/titles/${title.id}`,
      JSON.stringify({ primaryTitle: tag, originalTitle: tag, titleType: 'movie', startYear: 2024, genres: ['Drama', 'Thriller'], version: title.version }),
      auth
    );
    adminWriteErrors.add(!check(updateTitle, { 'update title: status is 200': (r) => r.status === 200 }));

    const patchTitle = http.patch(
      `${BASE_URL}/api/v1/titles/${title.id}`,
      JSON.stringify({ runtimeMinutes: 120, version: 1 }),
      auth
    );
    adminWriteErrors.add(!check(patchTitle, { 'patch title: status is 200': (r) => r.status === 200 }));

    const upsertRating = http.put(
      `${BASE_URL}/api/v1/titles/${title.id}/rating`,
      JSON.stringify({ averageRating: 7.5, numVotes: 1000 }),
      auth
    );
    adminWriteErrors.add(!check(upsertRating, { 'upsert rating: status is 200': (r) => r.status === 200 }));

    const deleteRating = http.del(`${BASE_URL}/api/v1/titles/${title.id}/rating`, null, auth);
    adminWriteErrors.add(!check(deleteRating, { 'delete rating: status is 204': (r) => r.status === 204 }));

    const updatePerson = http.put(
      `${BASE_URL}/api/v1/people/${person.id}`,
      JSON.stringify({ primaryName: tag, birthYear: 1980, deathYear: null, primaryProfession: ['actor'], version: person.version }),
      auth
    );
    adminWriteErrors.add(!check(updatePerson, { 'update person: status is 200': (r) => r.status === 200 }));

    const patchPerson = http.patch(
      `${BASE_URL}/api/v1/people/${person.id}`,
      JSON.stringify({ deathYear: 2020, version: 1 }),
      auth
    );
    adminWriteErrors.add(!check(patchPerson, { 'patch person: status is 200': (r) => r.status === 200 }));

    const deleteTitle = http.del(`${BASE_URL}/api/v1/titles/${title.id}`, null, auth);
    adminWriteErrors.add(!check(deleteTitle, { 'delete title: status is 204': (r) => r.status === 204 }));

    const deletePerson = http.del(`${BASE_URL}/api/v1/people/${person.id}`, null, auth);
    adminWriteErrors.add(!check(deletePerson, { 'delete person: status is 204': (r) => r.status === 204 }));
  });

  sleep(1);
}
