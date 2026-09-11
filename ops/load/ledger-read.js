// Read-only baseline, for comparison against the write mix.
//
// Deposits and withdrawals all post against the single system cash account and
// take a row lock on it, so they serialise on one row no matter how many
// clients there are. Reads take no such lock. Running the same shape without
// the writes shows how much of the ceiling is that lock rather than CPU,
// connections, or the network.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN;
const ACCOUNTS = (__ENV.ACCOUNT_IDS || '').split(',').filter(Boolean);

export const options = {
    scenarios: {
        ramp: {
            executor: 'ramping-vus',
            startVUs: 1,
            stages: [
                { duration: '10s', target: Number(__ENV.VUS || 50) },
                { duration: '30s', target: Number(__ENV.VUS || 50) },
                { duration: '5s', target: 0 },
            ],
        },
    },
};

export default function () {
    const account = ACCOUNTS[Math.floor(Math.random() * ACCOUNTS.length)];
    const headers = { headers: { Authorization: `Bearer ${TOKEN}` } };

    check(http.get(`${BASE}/api/accounts/${account}`, headers),
        { 'account read': (r) => r.status === 200 });
    check(http.get(`${BASE}/api/accounts/${account}/entries?size=20`, headers),
        { 'entries read': (r) => r.status === 200 });
}
