package com.chartsignal;

/**
 * Three models vote on the next move: SINDy-PI, the on-device neural net, and plain momentum
 * (the recent slope continues). Each model's vote is weighted by how well it called the same chart
 * at earlier points (walk-forward backtest), with recent checkpoints counting more than old ones,
 * so whichever model fits the current regime (trend vs. range) gets the most say.
 *
 * A CALL or PUT is shown only when the weighted vote is clear and the ensemble's own recency-
 * weighted backtest hit rate clears the bar.
 */
public final class Predictor {

    public static final class Result {
        public String signal = "NO_TRADE";   // CALL, PUT or NO_TRADE
        public String why = "";
        public double sindyDelta, nnDelta, momDelta;
        public double sindyAcc, nnAcc, momAcc, ensAcc;   // recency-weighted backtest hit rates
        public double sindyW, nnW, momW;                 // vote weights, sum to 1 when any > 0
        public double vote;                              // -1 .. +1
        public int btTotal, ensCalls;
        public double freshEr;                           // efficiency of the move the backtest can't see yet
        public String sindyInfo = "";
        public double[] forecastPath;                    // SINDy path, price units, length h
        public int h;
        public int hMax;                                 // longest horizon this chart supports
        // kept for the overlay's per-model line
        public int btSindyHits, btNnHits;
    }

    public static final int MIN_POINTS = 80;
    static final int ORIGINS = 20;
    static final double MIN_VOTE = 0.34;    // weighted vote must lean at least this far one way

    public static Result analyze(double[] p, int n, int h, double minHit) {
        Result r = new Result();
        r.h = h;
        // backtest needs a training prefix (net: CTX + h + 20 samples), then at least h of spread
        // between checkpoints, then h points to score the last one
        r.hMax = (n - 1 - TinyMlp.CTX - 20) / 3;
        if (n < MIN_POINTS) { r.why = "chart too short (" + n + " pts)"; return r; }
        if (h < 1 || h > r.hMax) { r.why = "expiry too long for visible chart"; return r; }

        int minTrain = Math.max(40, TinyMlp.CTX + h + 20);
        int end = n - 1 - h;
        int start = Math.min(end, Math.max(minTrain, (int) (n * 0.4)));

        // ---- walk-forward backtest ----
        int[] origin = new int[ORIGINS];
        double[][] pred = new double[ORIGINS][3];
        double[] truth = new double[ORIGINS];
        int k = 0;
        for (int i = 0; i < ORIGINS; i++) {
            int o = start + (int) Math.round((double) i * (end - start) / (ORIGINS - 1));
            if (k > 0 && o == origin[k - 1]) continue;
            double t = p[o + h] - p[o];
            if (t == 0) continue;
            origin[k] = o;
            truth[k] = t;
            pred[k][0] = sindy(p, o + 1, h, null);
            TinyMlp nn = TinyMlp.train(p, o + 1, h, 40);
            pred[k][1] = nn == null ? 0 : nn.predict(p, o + 1, h);
            pred[k][2] = momentum(p, o + 1, h);
            k++;
        }
        r.btTotal = k;
        for (int i = 0; i < k; i++) {
            if (Math.signum(pred[i][0]) == Math.signum(truth[i])) r.btSindyHits++;
            if (Math.signum(pred[i][1]) == Math.signum(truth[i])) r.btNnHits++;
        }

        // recency weight of each checkpoint: newest = 1, decaying over about a third of the span
        double span = Math.max(1, origin[Math.max(0, k - 1)] - origin[0]);
        double tau = Math.max(h, span / 3.0);
        double[] rw = new double[k];
        for (int i = 0; i < k; i++) rw[i] = Math.exp(-(origin[k - 1] - origin[i]) / tau);

        double[] acc = new double[3];
        for (int m = 0; m < 3; m++) acc[m] = weightedAcc(pred, truth, rw, m, 0, k);
        r.sindyAcc = acc[0]; r.nnAcc = acc[1]; r.momAcc = acc[2];

        // ensemble's own backtest: at each checkpoint, weights come only from earlier checkpoints
        double ensHit = 0, ensW = 0;
        for (int i = 3; i < k; i++) {
            double[] w = new double[3];
            for (int m = 0; m < 3; m++) w[m] = voteWeight(weightedAcc(pred, truth, rw, m, 0, i));
            double v = vote(pred[i], w);
            if (Double.isNaN(v) || Math.abs(v) < MIN_VOTE) continue;
            r.ensCalls++;
            ensW += rw[i];
            if (Math.signum(v) == Math.signum(truth[i])) ensHit += rw[i];
        }
        r.ensAcc = (ensHit + 1) / (ensW + 2);   // shrunk toward 50% when there are few calls

        // ---- live forecast ----
        double[] path = new double[h];
        r.sindyDelta = sindy(p, n, h, path);
        r.forecastPath = path;
        TinyMlp nn = TinyMlp.train(p, n, h, 60);
        r.nnDelta = nn == null ? 0 : nn.predict(p, n, h);
        r.momDelta = momentum(p, n, h);
        SindyPi.Model sm = SindyPi.fit(p, n);
        r.sindyInfo = sm == null ? "SINDy-PI: no fit" : sm.describe();

        double[] w = {voteWeight(acc[0]), voteWeight(acc[1]), voteWeight(acc[2])};
        double ws = w[0] + w[1] + w[2];
        if (ws > 0) { r.sindyW = w[0] / ws; r.nnW = w[1] / ws; r.momW = w[2] / ws; }
        r.vote = vote(new double[]{r.sindyDelta, r.nnDelta, r.momDelta}, w);

        // backtest points closer together than the horizon share most of their outcome: raise the bar
        double spread = Math.max(0, end - start);
        double need = minHit;
        if (spread < 2.0 * h) need = Math.min(0.95, minHit + 0.15 * (1 - spread / (2.0 * h)));

        // the last h points have no scored checkpoint yet, so a trend that started there is invisible
        // to the backtest; don't bet against a clean, fresh move
        int fm = Math.min(n - 1, Math.max(10, h));
        double net = p[n - 1] - p[n - 1 - fm], fpath = 0;
        for (int i = n - fm; i < n; i++) fpath += Math.abs(p[i] - p[i - 1]);
        r.freshEr = fpath == 0 ? 0 : net / fpath;

        if (Double.isNaN(r.vote)) {
            r.why = "no model beat a coin flip recently";
        } else if (Math.abs(r.vote) < MIN_VOTE) {
            r.why = "vote split " + Math.round(r.vote * 100) + "%";
        } else if (Math.abs(r.freshEr) > 0.35 && Math.signum(r.freshEr) != Math.signum(r.vote) && r.ensAcc < 0.85) {
            r.why = "fresh move " + arrow(r.freshEr) + " against the call";
        } else if (r.ensCalls < 8) {
            r.why = "backtest too thin (" + r.ensCalls + " calls)";
        } else if (r.ensAcc < need) {
            r.why = "backtest " + pct(r.ensAcc) + " < " + pct(need);
        } else {
            r.signal = r.vote > 0 ? "CALL" : "PUT";
            r.why = "vote " + arrow(r.vote) + " " + Math.round(Math.abs(r.vote) * 100) + "%, backtest " + pct(r.ensAcc);
        }
        return r;
    }

    /** Recency-weighted hit rate of model m over checkpoints [from, to), shrunk toward 50%. */
    static double weightedAcc(double[][] pred, double[] truth, double[] rw, int m, int from, int to) {
        double hit = 0, tot = 0;
        for (int i = from; i < to; i++) {
            if (pred[i][m] == 0) continue;
            tot += rw[i];
            if (Math.signum(pred[i][m]) == Math.signum(truth[i])) hit += rw[i];
        }
        return (hit + 1) / (tot + 2);
    }

    /** Only skill above a coin flip earns a vote. */
    static double voteWeight(double acc) { return Math.max(0, acc - 0.5); }

    /** Weighted vote in -1..+1, or NaN when no model has any weight. */
    static double vote(double[] deltas, double[] w) {
        double s = 0, ws = 0;
        for (int m = 0; m < 3; m++) { s += w[m] * Math.signum(deltas[m]); ws += w[m]; }
        return ws <= 0 ? Double.NaN : s / ws;
    }

    /** Momentum: least-squares slope of the last max(10, h) points, carried forward h steps. */
    static double momentum(double[] p, int n, int h) {
        int k = Math.min(n, Math.max(10, h));
        double mx = (k - 1) / 2.0, my = 0;
        for (int i = 0; i < k; i++) my += p[n - k + i];
        my /= k;
        double sxy = 0, sxx = 0;
        for (int i = 0; i < k; i++) { double dx = i - mx; sxy += dx * (p[n - k + i] - my); sxx += dx * dx; }
        return sxx == 0 ? 0 : sxy / sxx * h;
    }

    static double sindy(double[] p, int n, int h, double[] path) {
        SindyPi.Model m = SindyPi.fit(p, n);
        if (m == null) return 0;
        double[] st = SindyPi.lastState(p, n, m);
        return m.forecast(st[0], st[1], h, path);
    }

    static String arrow(double s) { return s > 0 ? "↑" : "↓"; }

    static String pct(double r) { return Math.round(r * 100) + "%"; }
}
