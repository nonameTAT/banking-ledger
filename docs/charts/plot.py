#!/usr/bin/env python3
"""Regenerate the capacity charts in docs/charts/.

    python3 docs/charts/plot.py

No third-party packages: the SVG is written directly, so the figures can be
rebuilt from a bare checkout. Every number below is transcribed from the tables
in docs/capacity-report.md, which stay the readable-value twin of these charts.

Each chart is emitted twice, for the light and the dark surface, and the report
picks between them with a <picture> element. The dark file is not a flip of the
light one: both are stepped from the same blue ramp against their own surface.
"""

import os

# --- data, from docs/capacity-report.md ----------------------------------
# Write mix. Two independent passes per concurrency level, kept apart on
# purpose: throughput on this host varies by up to 15% between identical runs,
# so it is a band, and a chart that averaged the passes away would hide the one
# caveat the report insists on.
VUS = [5, 10, 25, 50, 100]
THROUGHPUT = [(747, 486), (692, 745), (745, 810), (805, 889), (732, 762)]
MEDIAN = [(6.0, 7.1), (7.1, 7.7), (12.2, 12.0), (44.6, 40.0), (111.0, 107.5)]
P95 = [(12.0, 25.1), (50.2, 41.8), (124.7, 108.3), (154.2, 139.5), (232.3, 223.1)]
P99 = [(15.5, 33.0), (65.5, 55.1), (147.1, 127.7), (180.5, 160.9), (296.5, 295.4)]

# Reads at 50 VUs, varying only how much history the account has.
HISTORY = [(0, 6291), (3045, 2077), (8409, 985), (35065, 653)]

# The same shape at 50 VUs with the writes removed.
RW_THROUGHPUT = [("Write mix", 805, 889), ("Reads only", 6201, 6291)]
RW_P95 = [("Write mix", 139.5, 154.2), ("Reads only", 13.3, 13.3)]

# --- theme ----------------------------------------------------------------
# Chrome and ink from the reference palette. The percentile ramp is ordinal
# (median < p95 < p99 is an order, not an identity), so it is one hue stepped
# light-to-dark rather than three unrelated colours; both sets were checked
# with the palette validator's --ordinal mode.
THEMES = {
    "light": {
        "surface": "#fcfcfb", "primary": "#0b0b0b", "secondary": "#52514e",
        "muted": "#898781", "grid": "#e1e0d9", "axis": "#c3c2b7",
        "series": "#2a78d6",
        "ramp": ["#86b6ef", "#2a78d6", "#104281"],
    },
    "dark": {
        "surface": "#1a1a19", "primary": "#ffffff", "secondary": "#c3c2b7",
        "muted": "#898781", "grid": "#2c2c2a", "axis": "#383835",
        "series": "#3987e5",
        "ramp": ["#cde2fb", "#86b6ef", "#2a78d6"],
    },
}

FONT = "system-ui, -apple-system, 'Segoe UI', sans-serif"
OUT = os.path.dirname(os.path.abspath(__file__))


def esc(s):
    """Escape for both text nodes and single-quoted attribute values."""
    return (str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("'", "&#39;").replace('"', "&quot;"))


def text(x, y, s, fill, size=12, anchor="start", weight="400", tabular=False):
    extra = " font-variant-numeric='tabular-nums'" if tabular else ""
    return (f"<text x='{x:.1f}' y='{y:.1f}' fill='{fill}' font-size='{size}' "
            f"font-family=\"{FONT}\" font-weight='{weight}' "
            f"text-anchor='{anchor}'{extra}>{esc(s)}</text>")


def vbar(x, y_top, y_base, w, fill, r=4):
    """Column: rounded data-end, square at the baseline."""
    r = min(r, w / 2, abs(y_base - y_top))
    return (f"<path d='M{x:.1f},{y_base:.1f} L{x:.1f},{y_top + r:.1f} "
            f"Q{x:.1f},{y_top:.1f} {x + r:.1f},{y_top:.1f} "
            f"L{x + w - r:.1f},{y_top:.1f} Q{x + w:.1f},{y_top:.1f} "
            f"{x + w:.1f},{y_top + r:.1f} L{x + w:.1f},{y_base:.1f} Z' fill='{fill}'/>")


def hbar(x0, x1, y, h, fill, r=4):
    """Bar: rounded data-end, square at the baseline."""
    r = min(r, h / 2, abs(x1 - x0))
    return (f"<path d='M{x0:.1f},{y:.1f} L{x1 - r:.1f},{y:.1f} "
            f"Q{x1:.1f},{y:.1f} {x1:.1f},{y + r:.1f} "
            f"L{x1:.1f},{y + h - r:.1f} Q{x1:.1f},{y + h:.1f} {x1 - r:.1f},{y + h:.1f} "
            f"L{x0:.1f},{y + h:.1f} Z' fill='{fill}'/>")


def dot(x, y, fill, surface, r=4):
    """Marker with the 2px surface ring, so overlaps stay legible."""
    return (f"<circle cx='{x:.1f}' cy='{y:.1f}' r='{r}' fill='{fill}' "
            f"stroke='{surface}' stroke-width='2'/>")


def gridline(x0, x1, y, color):
    return (f"<line x1='{x0:.1f}' y1='{y:.1f}' x2='{x1:.1f}' y2='{y:.1f}' "
            f"stroke='{color}' stroke-width='1'/>")


def polyline(pts, color, width=2, opacity=1.0):
    d = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
    return (f"<polyline points='{d}' fill='none' stroke='{color}' "
            f"stroke-width='{width}' stroke-linejoin='round' "
            f"stroke-linecap='round' opacity='{opacity}'/>")


def open_svg(w, h, t, title, desc):
    return (f"<svg xmlns='http://www.w3.org/2000/svg' width='{w}' height='{h}' "
            f"viewBox='0 0 {w} {h}' role='img' aria-label='{esc(title)}'>"
            f"<title>{esc(title)}</title><desc>{esc(desc)}</desc>"
            f"<rect width='{w}' height='{h}' fill='{t['surface']}'/>")


def write(name, mode, body):
    path = os.path.join(OUT, f"{name}-{mode}.svg")
    with open(path, "w", encoding="utf-8") as f:
        f.write(body + "</svg>\n")
    print("wrote", os.path.relpath(path, os.path.dirname(OUT)))


# --- chart A: the saturation signature ------------------------------------
def chart_saturation(mode):
    t = THEMES[mode]
    W, H = 760, 520
    L, R = 62, 96
    xs = [L + i * (W - L - R) / (len(VUS) - 1) for i in range(len(VUS))]

    title = "The write path is saturated at 5 concurrent clients"
    s = open_svg(W, H, t, title,
                 "Two panels sharing a concurrency axis. Upper: throughput per "
                 "second, flat between roughly 700 and 800 from 5 to 100 "
                 "concurrent clients. Lower: median, p95 and p99 latency, each "
                 "rising in proportion to the clients added.")
    s += text(L, 30, title, t["primary"], 16, weight="600")
    s += text(L, 50, "Throughput stays flat while latency rises in proportion to the clients added: "
              "work in equals work", t["secondary"], 12)
    s += text(L, 66, "out, and everything extra is spent waiting. Both passes are plotted; neither is "
              "averaged away.", t["secondary"], 12)

    # -- panel 1: throughput, drawn as a band because it is a band ----------
    top, bot = 106, 242
    ymax = 1000
    def py(v):
        return bot - (v / ymax) * (bot - top)

    s += text(L, top - 14, "Throughput (requests per second)", t["primary"], 13, weight="600")
    for tick in range(0, ymax + 1, 250):
        s += gridline(L, W - R, py(tick), t["grid"])
        s += text(L - 10, py(tick) + 4, f"{tick:,}", t["muted"], 11, anchor="end", tabular=True)

    hi = [(xs[i], py(max(THROUGHPUT[i]))) for i in range(len(VUS))]
    lo = [(xs[i], py(min(THROUGHPUT[i]))) for i in range(len(VUS))]
    band = " ".join(f"{x:.1f},{y:.1f}" for x, y in hi + lo[::-1])
    s += f"<polygon points='{band}' fill='{t['series']}' opacity='0.10'/>"
    s += polyline(hi, t["series"], 2, 0.55)
    s += polyline(lo, t["series"], 2, 0.55)
    for i, x in enumerate(xs):
        for v in THROUGHPUT[i]:
            s += dot(x, py(v), t["series"], t["surface"])

    # The 5-VU pair is the report's own example of the noise floor: two
    # identical runs, 747 and 486. Labelled so the dip reads as the spread it
    # is rather than as a trend the rest of the chart then contradicts.
    s += text(xs[0], py(747) - 13, "747", t["primary"], 11, anchor="middle",
              weight="600", tabular=True)
    s += text(xs[0], py(486) + 20, "486", t["primary"], 11, anchor="middle",
              weight="600", tabular=True)
    s += text(W - R + 12, py(700) - 6, "each dot is", t["secondary"], 11)
    s += text(W - R + 12, py(700) + 9, "one pass", t["secondary"], 11)
    s += gridline(L, W - R, bot, t["axis"])

    # -- panel 2: latency, an ordinal ramp ---------------------------------
    top2, bot2 = 312, 462
    lmax = 320
    def ly(v):
        return bot2 - (v / lmax) * (bot2 - top2)

    s += text(L, top2 - 14, "Latency (milliseconds)", t["primary"], 13, weight="600")
    for tick in range(0, lmax + 1, 80):
        s += gridline(L, W - R, ly(tick), t["grid"])
        s += text(L - 10, ly(tick) + 4, tick, t["muted"], 11, anchor="end", tabular=True)

    # The ramp is stored light-to-dark in both themes, which is the order the
    # palette validator checks. Which end carries the emphasis flips with the
    # surface: on white the darkest step is the most prominent, on black the
    # lightest is. p99 is the line that matters, so it takes the prominent end
    # either way and median takes the recessive one.
    steps = t["ramp"] if mode == "light" else list(reversed(t["ramp"]))
    series = [("median", MEDIAN, steps[0]), ("p95", P95, steps[1]),
              ("p99", P99, steps[2])]
    for label, data, color in series:
        mids = [(xs[i], ly(sum(data[i]) / 2)) for i in range(len(VUS))]
        for i, x in enumerate(xs):          # spread of the two passes
            a, b = ly(max(data[i])), ly(min(data[i]))
            if abs(a - b) > 1:
                s += (f"<line x1='{x:.1f}' y1='{a:.1f}' x2='{x:.1f}' y2='{b:.1f}' "
                      f"stroke='{color}' stroke-width='2' stroke-linecap='round' "
                      f"opacity='0.45'/>")
        s += polyline(mids, color, 2)
        for x, y in mids:
            s += dot(x, y, color, t["surface"])
        s += text(mids[-1][0] + 12, mids[-1][1] + 4, label, t["secondary"], 12, weight="600")

    s += gridline(L, W - R, bot2, t["axis"])
    for i, x in enumerate(xs):
        s += text(x, bot2 + 20, VUS[i], t["muted"], 11, anchor="middle", tabular=True)
    s += text((L + W - R) / 2, H - 12, "Concurrent clients (virtual users)",
              t["secondary"], 12, anchor="middle")
    return s


# --- chart B: reads decay as the account accumulates history ---------------
def chart_history(mode):
    t = THEMES[mode]
    W, H = 760, 392
    L, R = 62, 40
    top, bot = 134, 318
    ymax = 7000

    def py(v):
        return bot - (v / ymax) * (bot - top)

    title = "Read throughput falls in proportion to the account's history"
    s = open_svg(W, H, t, title,
                 "Four columns at 50 concurrent clients: 6,291 requests per "
                 "second against an empty account, falling to 653 against an "
                 "account holding 35,065 ledger entries.")
    s += text(L, 30, title, t["primary"], 16, weight="600")
    s += text(L, 50, "Same endpoints, same concurrency, empty ledger each time - only the account's entry "
              "count varies.", t["secondary"], 12)
    s += text(L, 66, "A page of 20 entries costs a count of every row behind it, so a tenfold longer "
              "history costs about", t["secondary"], 12)
    s += text(L, 82, "a tenfold drop. Entries are append-only, so for an active account this only ever "
              "gets worse.", t["secondary"], 12)
    s += text(L, 112, "Requests per second, 50 concurrent clients", t["primary"], 13, weight="600")

    for tick in range(0, ymax + 1, 1750):
        s += gridline(L, W - R, py(tick), t["grid"])
        s += text(L - 10, py(tick) + 4, f"{tick:,}", t["muted"], 11, anchor="end", tabular=True)

    slot = (W - L - R) / len(HISTORY)
    bw = 24
    for i, (entries, rps) in enumerate(HISTORY):
        cx = L + slot * (i + 0.5)
        s += vbar(cx - bw / 2, py(rps), bot, bw, t["series"])
        s += text(cx, py(rps) - 12, f"{rps:,}", t["primary"], 12, anchor="middle",
                  weight="600", tabular=True)
        s += text(cx, bot + 20, f"{entries:,}", t["secondary"], 12, anchor="middle", tabular=True)

    s += gridline(L, W - R, bot, t["axis"])
    s += text((L + W - R) / 2, H - 12, "Ledger entries on the account being read",
              t["secondary"], 12, anchor="middle")
    return s


# --- chart C: where the ceiling comes from ---------------------------------
def chart_read_vs_write(mode):
    t = THEMES[mode]
    W, H = 760, 300
    title = "The limit is the write path, not the machine"
    s = open_svg(W, H, t, title,
                 "At 50 concurrent clients the read-only shape sustains about "
                 "eight times the throughput of the write mix, at roughly a "
                 "tenth of the p95 latency.")
    s += text(40, 30, title, t["primary"], 16, weight="600")
    s += text(40, 50, "The same shape at 50 concurrent clients with the writes removed. Reads are not "
              "bounded by CPU, the", t["secondary"], 12)
    s += text(40, 66, "connection pool or the network - every deposit and withdrawal takes a row lock on "
              "the one system cash", t["secondary"], 12)
    s += text(40, 82, "account, which serialises cash movement service-wide. Each bar spans the two "
              "passes.", t["secondary"], 12)

    panels = [
        (40, 270, "Throughput (requests per second)", RW_THROUGHPUT, 7000, 1750, 0),
        (430, 250, "p95 latency (milliseconds)", RW_P95, 200, 50, 1),
    ]
    for px, pw, heading, data, vmax, step, dp in panels:
        fmt = ("{:,.%df}" % dp).format
        s += text(px, 116, heading, t["primary"], 13, weight="600")
        top, bh, gap = 144, 28, 34

        def bx(v):
            return px + (v / vmax) * pw

        for tick in range(0, vmax + 1, step):
            gx = bx(tick)
            s += (f"<line x1='{gx:.1f}' y1='{top - 8:.1f}' x2='{gx:.1f}' "
                  f"y2='{top + 2 * bh + gap + 8:.1f}' stroke='{t['grid']}' stroke-width='1'/>")
            s += text(gx, top + 2 * bh + gap + 26, f"{tick:,}", t["muted"], 11,
                      anchor="middle", tabular=True)

        for i, (label, lo, hi) in enumerate(data):
            y = top + i * (bh + gap)
            s += hbar(px, bx(hi), y, bh, t["series"])
            if hi != lo:   # the pass-to-pass span, cut back out of the bar
                s += (f"<rect x='{bx(lo):.1f}' y='{y:.1f}' width='{bx(hi) - bx(lo):.1f}' "
                      f"height='{bh}' fill='{t['surface']}' opacity='0.45'/>")
            s += text(px, y - 8, label, t["secondary"], 12)
            span = (fmt(lo) + "-" + fmt(hi)) if hi != lo else fmt(hi)
            s += text(bx(hi) + 10, y + bh / 2 + 4, span, t["primary"], 12,
                      weight="600", tabular=True)

        s += (f"<line x1='{px:.1f}' y1='{top - 8:.1f}' x2='{px:.1f}' "
              f"y2='{top + 2 * bh + gap + 8:.1f}' stroke='{t['axis']}' stroke-width='1'/>")
    return s


if __name__ == "__main__":
    for mode in ("light", "dark"):
        write("capacity-saturation", mode, chart_saturation(mode))
        write("read-throughput-vs-history", mode, chart_history(mode))
        write("read-vs-write", mode, chart_read_vs_write(mode))
