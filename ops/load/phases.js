// Keeps the measurement window separate from the rest of the run.
//
// A ramping-vus run has three parts: it climbs to the target concurrency, holds
// there, then winds back down. Only the middle part is served at the
// concurrency the run was set up to measure. k6's summary aggregates all three,
// so a figure taken from it is an average over every concurrency between one and
// the target, and quoting that as "throughput at N concurrent clients" is
// wrong in a direction that flatters the service: the climb contributes fast,
// uncontended requests that the target concurrency never sees.
//
// Every request is tagged with the part of the run that issued it, and the
// summary this module prints reports the hold window on its own, with the
// whole-run figures kept alongside and labelled as what they are.
import exec from 'k6/execution';

/** k6 accepts durations as strings; the phase boundaries need numbers. */
export function toMilliseconds(duration) {
    if (typeof duration === 'number') {
        return duration;
    }

    const units = { h: 3600000, m: 60000, s: 1000, ms: 1 };
    const parts = String(duration).match(/(\d+(?:\.\d+)?)(ms|[hms])/g);

    if (!parts) {
        throw new Error(`cannot read duration: ${duration}`);
    }

    return parts.reduce((total, part) => {
        const [, value, unit] = part.match(/(\d+(?:\.\d+)?)(ms|[hms])/);

        return total + Number(value) * units[unit];
    }, 0);
}

/**
 * Describes the three windows of a run, so the phase of a request and the
 * length of the measurement window are both derived from one place.
 */
export function windows({ rampUp, hold, rampDown }) {
    const rampUpMs = toMilliseconds(rampUp);
    const holdMs = toMilliseconds(hold);
    const rampDownMs = toMilliseconds(rampDown);

    return {
        rampUp,
        hold,
        rampDown,
        rampUpMs,
        holdMs,
        rampDownMs,

        /**
         * Which window the request being issued right now belongs to. Tagged at
         * the moment of the request rather than per iteration, so an iteration
         * that starts inside the hold window and finishes outside it does not
         * drag late requests into the measurement.
         */
        phase() {
            const elapsed = exec.instance.currentTestRunDuration;

            if (elapsed < rampUpMs) {
                return 'ramp';
            }

            if (elapsed < rampUpMs + holdMs) {
                return 'hold';
            }

            return 'down';
        },
    };
}

/**
 * Thresholds scoped to the hold window, which is also what makes the hold-window
 * sub-metrics exist in the summary: k6 only keeps a sub-metric it was asked
 * about. Correctness is checked over the whole run, because a request answered
 * wrongly during the ramp is still wrong.
 */
export function steadyStateThresholds({ p95Milliseconds = 1000 } = {}) {
    return {
        'http_req_failed{phase:hold}': ['rate<0.01'],
        'http_req_duration{phase:hold}': [`p(95)<${p95Milliseconds}`],
        'http_reqs{phase:hold}': ['count>0'],

        http_req_failed: ['rate<0.01'],
        business_errors: ['count<1'],
    };
}

/** p99 is not in k6's default set, and the capacity report quotes it. */
export const TREND_STATS =
    ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'];

/**
 * Replaces k6's summary with one that cannot be misread as a capacity figure.
 *
 * <p>Throughput is computed against the length of the window it was measured
 * over, which is the whole point: k6 divides a sub-metric's count by the total
 * run duration, so its own rate for the hold window is diluted by the ramp and
 * the ramp-down and reads low.
 */
export function summarise(data, { label, vus, window }) {
    const lines = [];
    const total = data.state.testRunDurationMs;

    lines.push('');
    lines.push(`${label} — ${vus} VUs`);
    lines.push('='.repeat(72));
    lines.push(
        `windows: ramp ${window.rampUp} -> hold ${window.hold}`
        + ` -> ramp-down ${window.rampDown}`
        + `   (run ${(total / 1000).toFixed(1)}s)`);
    lines.push('');

    lines.push(
        `STEADY STATE — the hold window only, at ${vus} concurrent clients.`);
    lines.push('This is the figure to quote.');
    lines.push(block(data, '{phase:hold}', window.holdMs));
    lines.push('');

    lines.push(
        'WHOLE RUN — ramp, hold and ramp-down together. Concurrency climbs from');
    lines.push(
        `1 to ${vus} and back, so this is not the throughput at ${vus} clients.`);
    lines.push(block(data, '', total));
    lines.push('');

    lines.push(thresholdReport(data));
    lines.push('');

    return { stdout: lines.join('\n') };
}

function block(data, suffix, windowMs) {
    const requests = data.metrics[`http_reqs${suffix}`];
    const duration = data.metrics[`http_req_duration${suffix}`];
    const failed = data.metrics[`http_req_failed${suffix}`];

    if (!requests || !duration) {
        return '  (no requests in this window)';
    }

    const count = requests.values.count;
    const seconds = windowMs / 1000;

    // A k6 Rate counts the values that were true as "passes", and
    // http_req_failed adds true for a request that failed, so the failures are
    // the passes. Naming that trips up everyone reading it once.
    const observed = failed ? failed.values.passes + failed.values.fails : 0;
    const failures = failed ? failed.values.passes : 0;
    const failureRate = failed ? failed.values.rate : 0;

    return [
        `  requests    ${count} in ${seconds.toFixed(1)}s`
        + `  =  ${(count / seconds).toFixed(1)} req/s`,
        `  latency     median ${ms(duration, 'med')}`
        + `   p95 ${ms(duration, 'p(95)')}`
        + `   p99 ${ms(duration, 'p(99)')}`
        + `   max ${ms(duration, 'max')}`,
        `  failed      ${failures} / ${observed}`
        + `  (${(failureRate * 100).toFixed(2)}%)`,
    ].join('\n');
}

function ms(trend, statistic) {
    const value = trend.values[statistic];

    return value === undefined ? 'n/a' : `${value.toFixed(1)}ms`;
}

function thresholdReport(data) {
    const failed = [];

    for (const [name, metric] of Object.entries(data.metrics)) {
        for (const [threshold, outcome] of Object.entries(metric.thresholds || {})) {
            if (!outcome.ok) {
                failed.push(`  FAILED  ${name}: ${threshold}`);
            }
        }
    }

    if (failed.length === 0) {
        return 'thresholds: all passed';
    }

    return ['thresholds:', ...failed].join('\n');
}
