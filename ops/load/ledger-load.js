import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// Money movement is the expensive path: it takes row locks, writes a
// transaction and two entries, and an audit row. Reads are included because a
// real load is not all writes, but the mix is deliberately write-heavy so the
// number this produces is the one that matters.
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN;
const ACCOUNTS = (__ENV.ACCOUNT_IDS || '').split(',').filter(Boolean);

const businessErrors = new Counter('business_errors');
const conflicts = new Rate('conflict_rate');

export const options = {
    scenarios: {
        ramp: {
            executor: 'ramping-vus',
            startVUs: 1,
            stages: [
                { duration: __ENV.RAMP_UP || '20s', target: Number(__ENV.VUS || 20) },
                { duration: __ENV.HOLD || '40s', target: Number(__ENV.VUS || 20) },
                { duration: '10s', target: 0 },
            ],
        },
    },
    // A ledger that answers quickly but wrongly is worthless, so correctness is
    // a threshold alongside latency rather than something checked afterwards.
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<1000'],
        business_errors: ['count<1'],
    },
};

function headers() {
    return {
        headers: {
            Authorization: `Bearer ${TOKEN}`,
            'Content-Type': 'application/json',
        },
    };
}

function pick() {
    return ACCOUNTS[Math.floor(Math.random() * ACCOUNTS.length)];
}

function reference(kind) {
    return `load-${kind}-${__VU}-${__ITER}-${Date.now()}`;
}

export default function () {
    const account = pick();

    // Deposit: locks the system cash account and one customer account.
    const deposit = http.post(
        `${BASE}/api/accounts/${account}/deposits`,
        JSON.stringify({
            amount: '10.00',
            currency: 'AUD',
            referenceId: reference('dep'),
        }),
        headers());

    if (!check(deposit, { 'deposit created': (r) => r.status === 201 })) {
        businessErrors.add(1);
    }
    conflicts.add(deposit.status === 409);

    // Transfer between two distinct accounts: the contended path, since both
    // rows are locked and other VUs are competing for the same small set.
    const source = pick();
    let target = pick();
    for (let i = 0; i < 5 && target === source; i++) {
        target = pick();
    }

    if (target !== source) {
        const transfer = http.post(
            `${BASE}/api/transfers`,
            JSON.stringify({
                sourceAccountId: Number(source),
                targetAccountId: Number(target),
                amount: '1.00',
                currency: 'AUD',
                referenceId: reference('tr'),
            }),
            headers());

        // 400 is legitimate here: a transfer can outrun its account's balance.
        if (!check(transfer, {
            'transfer resolved': (r) => r.status === 201 || r.status === 400,
        })) {
            businessErrors.add(1);
        }
    }

    // Read path, including the paginated ledger query.
    const read = http.get(`${BASE}/api/accounts/${account}`, headers());
    check(read, { 'account read': (r) => r.status === 200 });

    const entries = http.get(
        `${BASE}/api/accounts/${account}/entries?size=20`,
        headers());
    check(entries, { 'entries read': (r) => r.status === 200 });
}
