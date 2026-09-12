import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

import {
    TREND_STATS,
    steadyStateThresholds,
    summarise,
    windows,
} from './phases.js';

// Money movement is the expensive path: it takes row locks, writes a
// transaction and two entries, and an audit row. Reads are included because a
// real load is not all writes, but the mix is deliberately write-heavy so the
// number this produces is the one that matters.
//
// The run climbs to the target concurrency, holds there, and winds down. Only
// the hold window is a measurement; see phases.js for why, and read the
// STEADY STATE block of the summary rather than the whole-run one.
const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN;
const ACCOUNTS = (__ENV.ACCOUNT_IDS || '').split(',').filter(Boolean);

const VUS = Number(__ENV.VUS || 20);
const WINDOW = windows({
    rampUp: __ENV.RAMP_UP || '20s',
    hold: __ENV.HOLD || '40s',
    rampDown: __ENV.RAMP_DOWN || '10s',
});

const businessErrors = new Counter('business_errors');
const conflicts = new Rate('conflict_rate');

export const options = {
    scenarios: {
        ramp: {
            executor: 'ramping-vus',
            startVUs: 1,
            stages: [
                { duration: WINDOW.rampUp, target: VUS },
                { duration: WINDOW.hold, target: VUS },
                { duration: WINDOW.rampDown, target: 0 },
            ],
        },
    },
    summaryTrendStats: TREND_STATS,
    // A ledger that answers quickly but wrongly is worthless, so correctness is
    // a threshold alongside latency rather than something checked afterwards.
    // Latency is judged on the hold window; correctness on the whole run.
    thresholds: steadyStateThresholds({ p95Milliseconds: 1000 }),
};

function request() {
    return {
        headers: {
            Authorization: `Bearer ${TOKEN}`,
            'Content-Type': 'application/json',
        },
        // Tagged at the moment of the request, which is what lets the summary
        // separate the hold window from the ramp on either side of it.
        tags: { phase: WINDOW.phase() },
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
        request());

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
            request());

        // 400 is legitimate here: a transfer can outrun its account's balance.
        if (!check(transfer, {
            'transfer resolved': (r) => r.status === 201 || r.status === 400,
        })) {
            businessErrors.add(1);
        }
    }

    // Read path, including the paginated ledger query.
    const read = http.get(`${BASE}/api/accounts/${account}`, request());
    check(read, { 'account read': (r) => r.status === 200 });

    const entries = http.get(
        `${BASE}/api/accounts/${account}/entries?size=20`,
        request());
    check(entries, { 'entries read': (r) => r.status === 200 });
}

export function handleSummary(data) {
    return summarise(data, {
        label: 'ledger write mix',
        vus: VUS,
        window: WINDOW,
    });
}
