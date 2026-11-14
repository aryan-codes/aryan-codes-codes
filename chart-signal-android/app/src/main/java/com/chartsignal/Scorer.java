package com.chartsignal;

/**
 * Scores CALL/PUT signals against the stitched price history once their expiry has passed.
 * One trade at a time: a new signal is only taken when the previous one has expired, the same way
 * a trader would take one trade per expiry, so repeated reads of the same signal aren't counted
 * over and over.
 */
public final class Scorer {

    public static final class Trade {
        public long openT, expiryT;
        public int dir;          // +1 CALL, -1 PUT
        public int seg;
        public double openV;
        public String outcome;   // null while open; "won", "lost", "tie", "unscored"
    }

    public int won, lost, tie, unscored;
    private Trade open;
    /** Last trade that was settled, for logging; cleared by the caller. */
    public Trade settled;

    public boolean hasOpen() { return open != null; }

    /** Offer a signal from the latest read. Returns the trade if it was taken. */
    public Trade offer(String signal, Stitcher st, long now, long expiryMs) {
        if (open != null) return null;
        int dir = "CALL".equals(signal) ? 1 : "PUT".equals(signal) ? -1 : 0;
        if (dir == 0) return null;
        double v = st.valueAt(st.lastTime(), st.segment());
        if (Double.isNaN(v)) return null;
        Trade t = new Trade();
        t.openT = st.lastTime();
        t.expiryT = t.openT + expiryMs;
        t.dir = dir;
        t.seg = st.segment();
        t.openV = v;
        open = t;
        return t;
    }

    /** Settle the open trade if its expiry is covered by the history (or can no longer be). */
    public void settle(Stitcher st) {
        settled = null;
        if (open == null) return;
        if (st.segmentEnded(open.seg)) {
            open.outcome = "unscored";
            unscored++;
        } else if (st.lastTime() >= open.expiryT) {
            double v = st.valueAt(open.expiryT, open.seg);
            if (Double.isNaN(v)) { open.outcome = "unscored"; unscored++; }
            else {
                double move = v - open.openV;
                if (Math.abs(move) < 1e-6) { open.outcome = "tie"; tie++; }
                else if (Math.signum(move) == open.dir) { open.outcome = "won"; won++; }
                else { open.outcome = "lost"; lost++; }
            }
        } else {
            return;
        }
        settled = open;
        open = null;
    }

    public String summary() {
        int dec = won + lost;
        if (dec == 0 && unscored == 0) return "no scored signals yet";
        String s = "session: " + won + " won / " + dec;
        if (dec > 0) s += " (" + Math.round(100.0 * won / dec) + "%)";
        if (tie > 0) s += ", " + tie + " tie";
        if (unscored > 0) s += ", " + unscored + " unscored";
        return s;
    }
}
