// Read-only baseline, for comparison against the write mix.
//
// Deposits and withdrawals all post against the single system cash account and
// take a row lock on it, so they serialise on one row no matter how many
// clients there are. Reads take no such lock. Running the same shape without
// the writes shows how much of the ceiling is that lock rather than CPU,
// connections, or the network.
//
// The comparison is only sound if both sides are measured over the same kind of
// window, so this reports the hold window exactly as the write mix does.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

import {
    TREND_STATS,
    steadyStateThresholds,
    summarise,
    windows,
} from './phases.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN;
const ACCOUNTS = (__ENV.ACCOUNT_IDS || '').split(',').filter(Boolean);

// The write mix fails a run on a rejected posting; the equivalent here is a
// read that did not answer, so the same threshold applies to both scripts.
const businessErrors = new Counter('business_errors');

const VUS = Number(__ENV.VUS || 50);
const WINDOW = windows({
    rampUp: __ENV.RAMP_UP || '10s',
    hold: __ENV.HOLD || '30s',
    rampDown: __ENV.RAMP_DOWN || '5s',
});

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
    thresholds: steadyStateThresholds({ p95Milliseconds: 1000 }),
};

function request() {
    return {
        headers: { Authorization: `Bearer ${TOKEN}` },
        tags: { phase: WINDOW.phase() },
    };
}

export default function () {
    const account = ACCOUNTS[Math.floor(Math.random() * ACCOUNTS.length)];

    const read = check(http.get(`${BASE}/api/accounts/${account}`, request()),
        { 'account read': (r) => r.status === 200 });

    const entries = check(
        http.get(`${BASE}/api/accounts/${account}/entries?size=20`, request()),
        { 'entries read': (r) => r.status === 200 });

    if (!read || !entries) {
        businessErrors.add(1);
    }
}

export function handleSummary(data) {
    return summarise(data, {
        label: 'ledger reads only',
        vus: VUS,
        window: WINDOW,
    });
}
