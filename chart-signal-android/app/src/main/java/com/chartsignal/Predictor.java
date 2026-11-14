package com.chartsignal;

/**
 * Runs SINDy-PI and the small neural net on a price curve, checks both with a walk-forward
 * backtest on the same visible chart, and only gives CALL or PUT when they agree and the
 * backtest clears the hit-rate bar.
 */
public final class Predictor {

    public static final class Result {
        public String signal = "NO_TRADE";   // CALL, PUT or NO_TRADE
        public String why = "";
        public double sindyDelta, nnDelta;
        public int btAgree, btHits, btSindyHits, btNnHits, btTotal;
        public String sindyInfo = "";
        public double[] forecastPath;        // SINDy path, price units, length h
        public int h;
        public int hMax;                     // longest horizon this chart can support
    }

    public static final int MIN_POINTS = 80;
    static final int ORIGINS = 20;

    /**
     * @param p       price curve, oldest first (any units; higher = higher price)
     * @param n       number of points used
     * @param h       forecast horizon in samples
     * @param minHit  minimum backtest hit rate (0..1) for agreeing calls before a signal is shown
     */
    public static Result analyze(double[] p, int n, int h, double minHit) {
        Result r = new Result();
        r.h = h;
        // the backtest needs a training prefix (net: L + h + 20 windows), then at least h of spread
        // between backtest points, then h points to score the last one
        r.hMax = (n - 1 - TinyMlp.L - 20) / 3;
        if (n < MIN_POINTS) { r.why = "chart too short (" + n + " pts)"; return r; }
        if (h < 1 || h > r.hMax) { r.why = "expiry too long for visible chart"; return r; }

        // walk-forward backtest: fit on data up to each origin, predict h steps ahead
        int minTrain = Math.max(40, TinyMlp.L + h + 20);
        int end = n - 1 - h;
        int start = Math.min(end, Math.max(minTrain, (int) (n * 0.5)));
        if (end > start) {
            for (int k = 0; k < ORIGINS; k++) {
                int o = start + (int) Math.round((double) k * (end - start) / (ORIGINS - 1));
                double truth = p[o + h] - p[o];
                if (truth == 0) continue;
                double sd = sindy(p, o + 1, h, null);
                TinyMlp nn = TinyMlp.train(p, o + 1, h, 40);
                double nd = nn == null ? 0 : nn.predict(p, o + 1, h);
                r.btTotal++;
                if (Math.signum(sd) == Math.signum(truth)) r.btSindyHits++;
                if (Math.signum(nd) == Math.signum(truth)) r.btNnHits++;
                if (sd != 0 && Math.signum(sd) == Math.signum(nd)) {
                    r.btAgree++;
                    if (Math.signum(sd) == Math.signum(truth)) r.btHits++;
                }
            }
        }

        double[] path = new double[h];
        r.sindyDelta = sindy(p, n, h, path);
        r.forecastPath = path;
        TinyMlp nn = TinyMlp.train(p, n, h, 60);
        r.nnDelta = nn == null ? 0 : nn.predict(p, n, h);
        SindyPi.Model m = SindyPi.fit(p, n);
        r.sindyInfo = m == null ? "SINDy-PI: no fit" : m.describe();

        // backtest points closer together than the horizon share most of their outcome, so a
        // crowded backtest is worth less: raise the bar in proportion
        double spread = Math.max(0, end - start);
        double need = minHit;
        if (spread < 2.0 * h) need = Math.min(0.95, minHit + 0.15 * (1 - spread / (2.0 * h)));
        minHit = need;

        int sd = (int) Math.signum(r.sindyDelta), nd = (int) Math.signum(r.nnDelta);
        double rate = r.btAgree == 0 ? 0 : (double) r.btHits / r.btAgree;
        if (sd == 0 || nd == 0) {
            r.why = "a model gave no direction";
        } else if (sd != nd) {
            r.why = "SINDy " + arrow(sd) + " vs NN " + arrow(nd) + ": disagree";
        } else if (r.btAgree < 10) {
            r.why = "backtest too thin (" + r.btAgree + " agreeing)";
        } else if (rate < minHit) {
            r.why = "backtest " + pct(rate) + " < " + pct(minHit);
        } else {
            r.signal = sd > 0 ? "CALL" : "PUT";
            r.why = "both " + arrow(sd) + ", backtest " + pct(rate) + " of " + r.btAgree;
        }
        return r;
    }

    static double sindy(double[] p, int n, int h, double[] path) {
        SindyPi.Model m = SindyPi.fit(p, n);
        if (m == null) return 0;
        double[] st = SindyPi.lastState(p, n, m);
        return m.forecast(st[0], st[1], h, path);
    }

    static String arrow(int s) { return s > 0 ? "↑" : "↓"; }

    static String pct(double r) { return Math.round(r * 100) + "%"; }
}
