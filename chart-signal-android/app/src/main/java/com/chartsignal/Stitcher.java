package com.chartsignal;

import java.util.ArrayList;

/**
 * Joins the curves read from consecutive screenshots into one continuous price history.
 *
 * Each read gives a curve in "fraction of box height" units whose last point is "now". The trading
 * app rescales its price axis as the chart scrolls, so consecutive curves differ by an affine map
 * (y_hist = a * y_new + b). For every new read we:
 *   1. place its points in time (point i is (n-1-i) * dt seconds before now),
 *   2. try small corrections to the time scale and offset (the user's "seconds across the box"
 *      setting and the screen refresh are never exact),
 *   3. fit a and b by least squares on the overlap with the history,
 *   4. keep the best fit if it explains the overlap well, map the new points with it and append
 *      the ones newer than the history. Otherwise a new segment starts.
 *
 * Values are only comparable within one segment. The best time-scale correction also tells us the
 * chart's real span, which is reported as a calibration hint.
 */
public final class Stitcher {

    public static final class Sample {
        public final long t;      // ms
        public final double v;
        public final int seg;
        Sample(long t, double v, int seg) { this.t = t; this.v = v; this.seg = seg; }
    }

    private final ArrayList<Sample> hist = new ArrayList<>();
    private int seg = 0;
    private final long keepMs;
    private final double[] scaleTrail = new double[30];
    private int scaleCount = 0;
    private int misses = 0;
    /** Consecutive reads that may fail to match before a new segment is started. */
    static final int MAX_MISSES = 4;
    public double lastR2 = Double.NaN;      // fit quality of the last join (for the log)
    public double lastRms = Double.NaN;     // overlap error, fraction of box height
    public boolean lastJoined = false;

    public Stitcher(long keepMs) { this.keepMs = keepMs; }

    public int segment() { return seg; }

    public int size() { return hist.size(); }

    /** Time-scale corrections tried around the learned one: 0.4x to 2.5x in ~7% steps, so even a
     *  badly wrong "seconds across the box" setting is found and corrected. */
    static final double[] SCALES = new double[29];
    static {
        for (int i = 0; i < SCALES.length; i++) SCALES[i] = Math.pow(2.5 / 0.4, (double) i / (SCALES.length - 1)) * 0.4;
    }
    /** Only the newest part of the overlap is fitted: the time error from a wrong span grows with age. */
    static final double OVERLAP_FRACTION = 0.35;

    /**
     * @param p   curve, oldest first, last point = now
     * @param n   points used
     * @param dt  seconds per point, from the user's setting
     * @param now timestamp of the screenshot, ms
     */
    public void update(double[] p, int n, double dt, long now) {
        lastJoined = false;
        lastR2 = Double.NaN;
        lastRms = Double.NaN;
        if (n < 10) return;
        if (hist.isEmpty() || now - hist.get(hist.size() - 1).t > 10000) {
            startSegment(p, n, dt, now);
            return;
        }

        double bestErr = Double.MAX_VALUE, bestA = 0, bestB = 0, bestScale = 1, bestR2 = 0;
        long bestLag = 0;
        long lagStep = Math.max(1, Math.round(dt * 1000));
        double base = learnedScale();
        for (double sc0 : SCALES) {
            double sc = sc0 * base;
            if (sc < 0.25 || sc > 4.0) continue;
            double sdt = dt * sc;
            // no time-offset search: the newest point is always "now", and an offset would let a wrong
            // span be patched read by read instead of being measured
            for (int li = 0; li <= 0; li++) {
                long lag = li * lagStep;
                double[] fit = fitOverlap(p, n, sdt, now + lag);
                if (fit == null) continue;
                // fit = {a, b, rms error in box heights, r2, count}
                if (fit[0] <= 0) continue;            // axis never flips
                if (fit[2] < bestErr) {
                    bestErr = fit[2]; bestA = fit[0]; bestB = fit[1]; bestR2 = fit[3];
                    bestScale = sc; bestLag = lag;
                }
            }
        }
        lastR2 = bestR2;
        lastRms = bestErr;
        // accept when the overlap (minus its worst 20%) matches within 3% of the box height
        if (bestErr == Double.MAX_VALUE || bestErr > 0.03) {
            // one bad read (a redraw, a tooltip, a mis-read) shouldn't break the history: skip it,
            // and only start over if several reads in a row don't match
            if (++misses >= MAX_MISSES) { misses = 0; startSegment(p, n, dt, now); }
            return;
        }
        misses = 0;
        recordScale(bestScale);
        long lastT = hist.get(hist.size() - 1).t;
        double sdt = dt * bestScale;
        long tNow = now + bestLag;
        for (int i = 0; i < n; i++) {
            long t = tNow - Math.round((n - 1 - i) * sdt * 1000);
            if (t <= lastT) continue;
            hist.add(new Sample(t, bestA * p[i] + bestB, seg));
        }
        lastJoined = true;
        prune(now);
    }

    private void startSegment(double[] p, int n, double dt, long now) {
        seg++;
        dt *= learnedScale();
        // the new segment owns its whole time range: drop the old segment's overlapping tail
        long firstT = now - Math.round((n - 1) * dt * 1000);
        int keep = hist.size();
        while (keep > 0 && hist.get(keep - 1).t >= firstT) keep--;
        if (keep < hist.size()) hist.subList(keep, hist.size()).clear();
        for (int i = 0; i < n; i++) {
            long t = now - Math.round((n - 1 - i) * dt * 1000);
            hist.add(new Sample(t, p[i], seg));
        }
        prune(now);
    }

    /**
     * Fit hist ≈ a * new + b on overlapping times. Robust: after a least-squares fit, the worst 20% of
     * points are dropped and the fit is redone. On a stepped chart a sub-pixel shift of a step
     * gives a few large errors right at the step, which shouldn't sink an otherwise good match.
     * Returns {a, b, rms error in box heights, r2, count} or null.
     */
    private double[] fitOverlap(double[] p, int n, double dt, long now) {
        long t0 = now - Math.round((n - 1) * dt * 1000 * OVERLAP_FRACTION);
        int cap = 0;
        for (int k = hist.size() - 1; k >= 0 && hist.get(k).t >= t0; k--) cap++;
        if (cap < 15) return null;
        double[] xs = new double[cap], ys = new double[cap];
        int cnt = 0;
        for (int k = hist.size() - 1; k >= 0; k--) {
            Sample s = hist.get(k);
            if (s.seg != seg || s.t < t0) break;
            if (s.t > now) continue;
            double pos = (n - 1) - (now - s.t) / (dt * 1000);
            int i = (int) Math.floor(pos);
            if (i < 0 || i >= n - 1) continue;
            double f = pos - i;
            xs[cnt] = p[i] * (1 - f) + p[i + 1] * f;
            ys[cnt] = s.v;
            cnt++;
        }
        if (cnt < 15) return null;
        double[] fit = ls(xs, ys, null, cnt);
        if (fit == null) return null;
        double[] res = new double[cnt];
        for (int i = 0; i < cnt; i++) res[i] = Math.abs(ys[i] - (fit[0] * xs[i] + fit[1]));
        double[] sorted = java.util.Arrays.copyOf(res, cnt);
        java.util.Arrays.sort(sorted);
        double cut = sorted[(int) (cnt * 0.8)];
        boolean[] keep = new boolean[cnt];
        for (int i = 0; i < cnt; i++) keep[i] = res[i] <= cut;
        double[] fit2 = ls(xs, ys, keep, cnt);
        return fit2 == null ? fit : fit2;
    }

    /** Least squares y = a x + b over the kept points; {a, b, rms in x units, r2, count}. */
    private static double[] ls(double[] xs, double[] ys, boolean[] keep, int cnt) {
        double sx = 0, sy = 0, sxx = 0, sxy = 0, syy = 0;
        int m = 0;
        for (int i = 0; i < cnt; i++) {
            if (keep != null && !keep[i]) continue;
            double x = xs[i], y = ys[i];
            sx += x; sy += y; sxx += x * x; sxy += x * y; syy += y * y;
            m++;
        }
        if (m < 12) return null;
        double mx = sx / m, my = sy / m;
        double vx = sxx / m - mx * mx, vy = syy / m - my * my, cxy = sxy / m - mx * my;
        if (vx <= 1e-12 || vy <= 1e-12) return null;
        double a = cxy / vx, b = my - a * mx;
        double res = Math.max(0, vy - a * cxy);
        double r2 = 1 - res / vy;
        double rms = a > 0 ? Math.sqrt(res) / a : Double.MAX_VALUE;
        return new double[]{a, b, rms, r2, m};
    }

    private void recordScale(double s) {
        scaleTrail[scaleCount % scaleTrail.length] = s;
        scaleCount++;
    }

    /** Scale correction used as the starting point: the median of recent joins, else 1. */
    private double learnedScale() {
        double h = scaleHint();
        return Double.isNaN(h) ? 1.0 : h;
    }

    /** Median time-scale correction over recent joins (1.0 = the setting looks right). */
    public double scaleHint() {
        int k = Math.min(scaleCount, scaleTrail.length);
        if (k < 10) return Double.NaN;
        double[] c = java.util.Arrays.copyOf(scaleTrail, k);
        java.util.Arrays.sort(c);
        return c[k / 2];
    }

    private void prune(long now) {
        int drop = 0;
        while (drop < hist.size() && now - hist.get(drop).t > keepMs) drop++;
        if (drop > 0) hist.subList(0, drop).clear();
    }

    /** Value at time t (linear interpolation) in the given segment, or NaN if not covered. */
    public double valueAt(long t, int segment) {
        int lo = 0, hi = hist.size() - 1;
        if (hi < 0 || t < hist.get(0).t || t > hist.get(hi).t) return Double.NaN;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (hist.get(mid).t <= t) lo = mid; else hi = mid;
        }
        Sample a = hist.get(lo), b = hist.get(hi);
        if (a.seg != segment || b.seg != segment) return Double.NaN;
        if (b.t == a.t) return a.v;
        double f = (double) (t - a.t) / (b.t - a.t);
        return a.v * (1 - f) + b.v * f;
    }

    /** Latest time covered. */
    public long lastTime() { return hist.isEmpty() ? Long.MIN_VALUE : hist.get(hist.size() - 1).t; }

    /** Has the given segment ended (a newer segment exists)? */
    public boolean segmentEnded(int segment) { return seg != segment; }
}
