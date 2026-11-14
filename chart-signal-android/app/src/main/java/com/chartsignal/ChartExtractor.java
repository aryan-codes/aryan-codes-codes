package com.chartsignal;

import java.util.ArrayList;

/**
 * Turns the pixels of a chart area into a price curve, without OCR.
 *
 * Line-tracking method (used when the chart has a coloured line or coloured candles):
 *   1. The background is the most common colour in the box.
 *   2. The line colour is the most common strongly coloured (saturated) ink. If a second, clearly
 *      different saturated colour is nearly as common, both are used (green/red candles).
 *      Grey or white labels, text, buttons and other colours are ignored.
 *   3. Rows / columns where line-coloured pixels span most of the box (the current-price line,
 *      time markers) are ignored.
 *   4. In each column the line-coloured pixels form vertical runs. Starting at the latest point
 *      (rightmost), the line is followed leftwards from run to touching or nearby run, so anything
 *      not connected to the line (a price tag, an icon) is skipped. A thick block at the old end
 *      (e.g. the current-price tag) is trimmed.
 *
 * If no coloured line can be followed across at least 30% of the box, it falls back to the older
 * method: the median height of all ink in each column.
 *
 * The result is averaged down to at most maxPoints samples. Only relative movement matters, so the
 * price is in "fraction of box height" units (0 = bottom, 1 = top).
 */
public final class ChartExtractor {

    public static final class Curve {
        public double[] p;         // oldest first
        public int n;
        public double coverage;    // fraction of the box width that holds the curve
        public double firstCol, lastCol; // fraction of box width where the curve starts and ends
        public boolean tracked;    // true = line tracking, false = median fallback
    }

    static final int INK_DIST = 90;      // L1 distance from background to count as ink
    static final int SAT_MIN = 60;       // max-min channel spread to count as "coloured"
    static final int COLOR_DIST = 140;   // L1 distance to the line colour to count as line

    public static Curve extract(int[] argb, int w, int h, int left, int top, int right, int bottom, int maxPoints) {
        left = clamp(left, 0, w - 1); right = clamp(right, left + 1, w);
        top = clamp(top, 0, h - 1); bottom = clamp(bottom, top + 1, h);
        int bw = right - left, bh = bottom - top;
        if (bw < 40 || bh < 30) return null;

        // 1. background
        int[] hist = new int[4096];
        for (int y = top; y < bottom; y += 2) {
            int o = y * w;
            for (int x = left; x < right; x += 2) hist[q(argb[o + x])]++;
        }
        int bgq = argmax(hist, -1);
        int bg = unq(bgq);

        // 2. line colour(s)
        int[] sat = new int[4096];
        for (int y = top; y < bottom; y++) {
            int o = y * w;
            for (int x = left; x < right; x++) {
                int c = argb[o + x];
                if (dist(c, bg) > INK_DIST && spread(c) >= SAT_MIN) sat[q(c)]++;
            }
        }
        int c1q = argmax(sat, -1);
        Curve tracked = null;
        if (sat[c1q] >= Math.max(30, bw * bh / 2000)) {
            int c1 = meanColor(argb, w, left, top, right, bottom, unq(c1q), bg);
            // second colour: most common saturated colour clearly different from the first
            int c2q = -1;
            for (int i = 0; i < 4096; i++) {
                if (sat[i] == 0 || dist(unq(i), c1) < 200) continue;
                if (c2q < 0 || sat[i] > sat[c2q]) c2q = i;
            }
            int c2 = Integer.MIN_VALUE;
            if (c2q >= 0 && sat[c2q] >= sat[c1q] * 0.3) c2 = meanColor(argb, w, left, top, right, bottom, unq(c2q), bg);
            // a coloured line is there: follow it, and if it can't be followed, report no curve
            // rather than falling back to a method that picks up labels and buttons
            return track(argb, w, left, top, bw, bh, bg, c1, c2, maxPoints);
        }
        return median(argb, w, left, top, bw, bh, bg, maxPoints);
    }

    // ---------------------------------------------------------------- line tracking

    private static Curve track(int[] argb, int w, int left, int top, int bw, int bh, int bg, int c1, int c2, int maxPoints) {
        boolean[] m = new boolean[bw * bh];
        int[] rowCount = new int[bh], colCount = new int[bw];
        for (int y = 0; y < bh; y++) {
            int o = (y + top) * w + left;
            for (int x = 0; x < bw; x++) {
                int c = argb[o + x];
                if (dist(c, bg) <= INK_DIST / 2) continue;
                boolean on = dist(c, c1) < COLOR_DIST || (c2 != Integer.MIN_VALUE && dist(c, c2) < COLOR_DIST);
                if (on) { m[y * bw + x] = true; rowCount[y]++; colCount[x]++; }
            }
        }
        boolean[] badRow = new boolean[bh], badCol = new boolean[bw];
        for (int y = 0; y < bh; y++) badRow[y] = rowCount[y] > bw * 0.4;
        for (int x = 0; x < bw; x++) badCol[x] = colCount[x] > bh * 0.5;
        // a thin full-width line also hides the curve where they cross; let 1-2 px rows through
        // by treating them as transparent rather than as gaps
        ArrayList<int[]>[] runs = runsPerColumn(m, bw, bh, badRow, badCol);

        // Start from the right and take the first (= rightmost) connected stroke that spans at least
        // 30% of the box. Short strokes on the way (a button, an icon, a marker) are skipped.
        double[] bestY = null, bestThick = null;
        int bestFirst = -1, bestLast = -1, bestCols = 0;
        java.util.HashSet<int[]> used = new java.util.HashSet<>();
        search:
        for (int xs = bw - 1; xs >= (int) (bw * 0.3); xs--) {
            for (int[] start : runs[xs]) {
                if (used.contains(start)) continue;
                double[] ys = new double[bw];
                double[] th = new double[bw];
                java.util.Arrays.fill(ys, Double.NaN);
                int cols = follow(runs, xs, start, bw, bh, ys, th, used);
                int f = xs;
                for (int x = 0; x < bw; x++) if (!Double.isNaN(ys[x])) { f = x; break; }
                if (xs - f >= bw * 0.3) {
                    bestY = ys; bestThick = th; bestLast = xs; bestFirst = f; bestCols = cols;
                    break search;
                }
            }
        }
        if (bestY == null) return null;

        // trim a thick block at the old (left) end: e.g. the current-price tag
        double[] t = new double[bestCols];
        int k = 0;
        for (int x = bestFirst; x <= bestLast; x++) if (!Double.isNaN(bestY[x])) t[k++] = bestThick[x];
        java.util.Arrays.sort(t, 0, k);
        // typical thickness from the flat parts (vertical strokes of a staircase line are long)
        double medThick = t[k / 4];
        // a block (button, price tag) is thick across many columns; a vertical stroke of the line is
        // thick only across the line's own width
        double thickLimit = 4 * medThick + 4;
        int minBlockCols = (int) Math.max(12, 3 * medThick);
        int f = bestFirst, lead = 0;
        for (int x = bestFirst; x < bestLast && (Double.isNaN(bestY[x]) || bestThick[x] > thickLimit); x++) lead++;
        if (lead >= minBlockCols) f = bestFirst + lead;
        bestFirst = f;
        // ...and at the new (right) end: a button or block the line ran into
        int e = bestLast;
        int blockCols = 0;
        for (int x = bestLast; x > bestFirst && (Double.isNaN(bestY[x]) || bestThick[x] > thickLimit); x--) blockCols++;
        if (blockCols >= minBlockCols) {
            e = bestLast - blockCols;
            while (e > bestFirst && Double.isNaN(bestY[e])) e--;
        }
        bestLast = e;
        if (bestLast - bestFirst < bw * 0.3) return null;
        if (blockCols >= minBlockCols) {
            // the block may have hidden the line's last strokes: extend rightwards through runs that
            // touch the line (a button above the line doesn't touch it)
            int[] cur = null;
            double cy = bestY[bestLast], ch = bestThick[bestLast];
            for (int[] r : runs[bestLast]) if (Math.abs((r[0] + r[1]) / 2.0 - cy) < 1 && r[1] - r[0] + 1 == (int) ch) cur = r;
            for (int x = bestLast + 1; cur != null && x < bw; x++) {
                int[] next = null;
                for (int[] r : runs[x]) {
                    if (r[1] >= cur[0] - 2 && r[0] <= cur[1] + 2) {
                        if (next == null || Math.abs((r[0] + r[1]) - (cur[0] + cur[1])) < Math.abs((next[0] + next[1]) - (cur[0] + cur[1]))) next = r;
                    }
                }
                if (next == null) break;
                cur = next;
                bestY[x] = (cur[0] + cur[1]) / 2.0;
                bestThick[x] = cur[1] - cur[0] + 1;
                bestLast = x;
            }
        }
        // the newest column often holds the vertical stroke of the latest price change; the price
        // is at the end of that stroke away from the previous level, not in its middle
        if (bestThick[bestLast] > 2 * medThick + 2) {
            int pv = bestLast - 1;
            while (pv > bestFirst && Double.isNaN(bestY[pv])) pv--;
            double half = bestThick[bestLast] / 2.0, inset = medThick / 2.0;
            double yUp = bestY[bestLast] - half + inset, yDn = bestY[bestLast] + half - inset;
            bestY[bestLast] = Math.abs(yUp - bestY[pv]) > Math.abs(yDn - bestY[pv]) ? yUp : yDn;
        }

        // interpolate gaps
        double[] col = new double[bw];
        int prev = -1;
        for (int x = bestFirst; x <= bestLast; x++) {
            if (Double.isNaN(bestY[x])) continue;
            col[x] = (bh - bestY[x]) / bh;
            if (prev >= 0 && x - prev > 1) {
                for (int g = prev + 1; g < x; g++) {
                    double fr = (double) (g - prev) / (x - prev);
                    col[g] = col[prev] * (1 - fr) + col[x] * fr;
                }
            }
            prev = x;
        }
        Curve c = resample(col, bestFirst, bestLast, bw, maxPoints);
        c.tracked = true;
        return c;
    }

    /** Follow the line leftwards from column xs. Fills ys (run centre, px) and thickness. */
    private static int follow(ArrayList<int[]>[] runs, int xs, int[] start, int bw, int bh, double[] ys, double[] th,
                              java.util.Set<int[]> used) {
        // the line is drawn as one continuous stroke, so neighbouring columns' runs touch; allow only
        // small breaks (anti-aliasing, compression, a crossing marker), growing with skipped columns
        int maxJump = Math.max(4, (int) (bh * 0.03));
        int maxGap = Math.max(4, bw / 50);
        int[] cur = start;
        used.add(start);
        ys[xs] = (cur[0] + cur[1]) / 2.0;
        th[xs] = cur[1] - cur[0] + 1;
        int cols = 1, lastX = xs;
        for (int x = xs - 1; x >= 0; x--) {
            int[] pick = null;
            int bestD = Integer.MAX_VALUE;
            double bestC = Double.MAX_VALUE;
            double cc = (cur[0] + cur[1]) / 2.0;
            for (int[] r : runs[x]) {
                int d;
                if (r[1] >= cur[0] - 3 && r[0] <= cur[1] + 3) d = 0;           // touching
                else d = Math.min(Math.abs(r[0] - cur[1]), Math.abs(cur[0] - r[1]));
                double ctr = Math.abs((r[0] + r[1]) / 2.0 - cc);
                if (d < bestD || (d == bestD && ctr < bestC)) { bestD = d; bestC = ctr; pick = r; }
            }
            int allowed = maxJump + 2 * (lastX - x - 1);
            if (pick != null && bestD <= Math.min(allowed, bh / 8)) {
                cur = pick;
                used.add(pick);
                ys[x] = (cur[0] + cur[1]) / 2.0;
                th[x] = cur[1] - cur[0] + 1;
                cols++;
                lastX = x;
            } else if (lastX - x > maxGap) {
                break;
            }
        }
        return cols;
    }

    /**
     * Vertical runs of line pixels in each column. Rows that are mostly line-coloured (the thin
     * current-price line) are ignored on their own, but the latest price usually sits exactly on that
     * row, so a run that includes those rows is kept when it is at least as thick as the line itself.
     */
    @SuppressWarnings("unchecked")
    private static ArrayList<int[]>[] runsPerColumn(boolean[] m, int bw, int bh, boolean[] badRow, boolean[] badCol) {
        // typical line thickness, from runs that don't touch removed rows
        ArrayList<Integer> lens = new ArrayList<>();
        for (int x = 0; x < bw; x += 3) {
            if (badCol[x]) continue;
            int s = -1;
            boolean touchesBad = false;
            for (int y = 0; y <= bh; y++) {
                boolean on = y < bh && m[y * bw + x];
                if (on) { if (s < 0) { s = y; touchesBad = false; } if (badRow[y]) touchesBad = true; }
                else if (s >= 0) { if (!touchesBad) lens.add(y - s); s = -1; }
            }
        }
        java.util.Collections.sort(lens);
        int thick = lens.isEmpty() ? 3 : lens.get(lens.size() / 2);
        int minOnBad = Math.max(3, (int) Math.round(thick * 0.6));

        ArrayList<int[]>[] runs = new ArrayList[bw];
        for (int x = 0; x < bw; x++) {
            runs[x] = new ArrayList<>();
            if (badCol[x]) continue;
            int s = -1, lastOn = -10;
            boolean hasGood = false;
            for (int y = 0; y <= bh; y++) {
                boolean on = y < bh && m[y * bw + x];
                if (on) {
                    if (s >= 0 && y - lastOn > 3) {
                        addRun(runs[x], s, lastOn, hasGood, minOnBad);
                        s = -1;
                    }
                    if (s < 0) { s = y; hasGood = false; }
                    if (!badRow[y]) hasGood = true;
                    lastOn = y;
                }
            }
            if (s >= 0) addRun(runs[x], s, lastOn, hasGood, minOnBad);
        }
        return runs;
    }

    private static void addRun(ArrayList<int[]> list, int s, int e, boolean hasGood, int minOnBad) {
        // a run made only of current-price-line rows is kept only if it is as thick as the line
        if (!hasGood && e - s + 1 < minOnBad) return;
        list.add(new int[]{s, e});
    }

    // ---------------------------------------------------------------- median fallback

    private static Curve median(int[] argb, int w, int left, int top, int bw, int bh, int bg, int maxPoints) {
        boolean[] ink = new boolean[bw * bh];
        int[] rowCount = new int[bh], colCount = new int[bw];
        for (int y = 0; y < bh; y++) {
            int o = (y + top) * w + left;
            for (int x = 0; x < bw; x++) {
                if (dist(argb[o + x], bg) > INK_DIST) { ink[y * bw + x] = true; rowCount[y]++; colCount[x]++; }
            }
        }
        boolean[] badRow = new boolean[bh], badCol = new boolean[bw];
        for (int y = 0; y < bh; y++) badRow[y] = rowCount[y] > bw * 0.4;
        for (int x = 0; x < bw; x++) badCol[x] = colCount[x] > bh * 0.5;

        double[] col = new double[bw];
        int[] ys = new int[bh];
        int first = -1, last = -1;
        for (int x = 0; x < bw; x++) {
            col[x] = Double.NaN;
            if (badCol[x]) continue;
            int k = 0;
            for (int y = 0; y < bh; y++) if (!badRow[y] && ink[y * bw + x]) ys[k++] = y;
            if (k < 1) continue;
            java.util.Arrays.sort(ys, 0, k);
            col[x] = (double) (bh - ys[k / 2]) / bh;
            if (first < 0) first = x;
            last = x;
        }
        if (first < 0) return null;
        // keep the longest stretch of columns with ink (gaps up to 12 px)
        int bestA = -1, bestB = -1, runA = -1, lastInk = -1;
        for (int x = first; x <= last + 13; x++) {
            boolean has = x <= last && !Double.isNaN(col[x]);
            if (has) {
                if (runA < 0 || x - lastInk > 12) runA = x;
                lastInk = x;
                if (lastInk - runA > bestB - bestA) { bestA = runA; bestB = lastInk; }
            }
        }
        first = bestA; last = bestB;
        if (first < 0 || last - first < 40) return null;
        int prev = first;
        for (int x = first + 1; x <= last; x++) {
            if (Double.isNaN(col[x])) continue;
            if (x - prev > 1) {
                for (int g = prev + 1; g < x; g++) {
                    double t = (double) (g - prev) / (x - prev);
                    col[g] = col[prev] * (1 - t) + col[x] * t;
                }
            }
            prev = x;
        }
        Curve c = resample(col, first, last, bw, maxPoints);
        c.tracked = false;
        return c;
    }

    // ---------------------------------------------------------------- helpers

    private static Curve resample(double[] col, int first, int last, int bw, int maxPoints) {
        int len = last - first + 1;
        int n = Math.min(maxPoints, len);
        Curve c = new Curve();
        c.p = new double[n];
        c.n = n;
        for (int i = 0; i < n; i++) {
            int a = first + (int) ((long) i * len / n);
            int b = first + (int) ((long) (i + 1) * len / n);
            double s = 0;
            for (int x = a; x < Math.max(b, a + 1); x++) s += col[x];
            c.p[i] = s / Math.max(1, b - a);
        }
        c.coverage = (double) len / bw;
        c.firstCol = (double) first / bw;
        c.lastCol = (double) last / bw;
        return c;
    }

    /** Average colour of pixels near a seed colour (sharper than the 4-bit histogram bin). */
    private static int meanColor(int[] argb, int w, int left, int top, int right, int bottom, int seed, int bg) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = top; y < bottom; y += 2) {
            int o = y * w;
            for (int x = left; x < right; x += 2) {
                int c = argb[o + x];
                if (dist(c, seed) < 60 && dist(c, bg) > INK_DIST) {
                    r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
            }
        }
        if (n == 0) return seed;
        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    private static int argmax(int[] a, int skip) {
        int best = 0;
        for (int i = 1; i < a.length; i++) if (i != skip && a[i] > a[best]) best = i;
        return best;
    }

    private static int dist(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
            + Math.abs((a & 255) - (b & 255));
    }

    private static int spread(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    private static int q(int c) {
        return (((c >> 20) & 15) << 8) | (((c >> 12) & 15) << 4) | ((c >> 4) & 15);
    }

    private static int unq(int qc) {
        // bin centre: 4-bit value * 16 + 8 (max 248, so channels never spill into each other)
        return (((qc >> 8) & 15) * 16 + 8) << 16 | (((qc >> 4) & 15) * 16 + 8) << 8 | ((qc & 15) * 16 + 8);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
