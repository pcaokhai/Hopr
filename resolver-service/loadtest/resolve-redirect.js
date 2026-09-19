// k6 load test for the resolver's redirect path (GET /{shortKey}).
//
// Run via scripts/load-test-resolver.sh, which seeds short links through shortener-service
// and passes them in as SHORT_KEYS. Direct invocation:
//   SHORT_KEYS=abc123,def456 RESOLVER_URL=http://localhost:8083 k6 run resolve-redirect.js
//
// Load level: 20 VUs sustained for 30s (after a 5s ramp-up), ~20 concurrent resolvers.
// That is a deliberately small profile for a learning project's single-instance Compose
// stack, not a production capacity test — see docs/contracts/README.md's sibling section
// in the PR description for the reasoning and the measured numbers.
import http from 'k6/http';
import { check } from 'k6';

const RESOLVER_URL = __ENV.RESOLVER_URL || 'http://localhost:8083';
const SHORT_KEYS = (__ENV.SHORT_KEYS || '').split(',').filter(Boolean);

if (SHORT_KEYS.length === 0) {
  throw new Error('SHORT_KEYS env var must contain at least one short key to resolve');
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  scenarios: {
    resolve: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5s', target: 20 },
        { duration: '30s', target: 20 },
        { duration: '5s', target: 0 },
      ],
    },
  },
};

export default function () {
  const shortKey = SHORT_KEYS[Math.floor(Math.random() * SHORT_KEYS.length)];
  // redirects: 0 so k6 measures the resolver's own response, not a followed hop to the
  // (unreachable, example.com-style) long URL it redirects to.
  const res = http.get(`${RESOLVER_URL}/${shortKey}`, { redirects: 0 });
  check(res, { 'is a 307 redirect': (r) => r.status === 307 });
}
