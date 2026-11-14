package com.chartsignal;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demo-only auto-trader. Pure logic: the screen reading (OCR, colours) and the tapping are passed in,
 * so the safety rules can be tested off the phone.
 *
 * Rules:
 *  - Before every trade, the DEMO badge must read "DEMO" and be highlighted, and the REAL badge must
 *    read "REAL" and be clearly less highlighted. If that can't be confirmed, auto-trading stops for
 *    the session.
 *  - One trade at a time. The balance is read just before the tap and again after expiry; the change
 *    is the broker's own result: about +stake*payout = won, -stake = lost, 0 = refund.
 *  - Stops after maxTrades trades or once the broker-graded net loss reaches stopLoss.
 */
public final class AutoTrader {

    /** A rectangle on screen in fractions of the screen size, centred on a tapped point. */
    public static final class Region {
        public final float cx, cy, w, h;
        public Region(float cx, float cy, float w, float h) { this.cx = cx; this.cy = cy; this.w = w; this.h = h; }
    }

    public interface Screen {
        /** Text recognised inside the region, or null if recognition failed. */
        String readText(Region r);
        /** Fraction of pixels in the region that are strongly coloured (a highlighted badge). */
        double highlighted(Region r);
    }

    public interface Tapper {
        /** Tap at a point given in screen fractions. False if the tap service isn't running. */
        boolean tap(float x, float y);
    }

    // configuration
    public Region buy, sell, demo, real, balance;
    public int maxTrades = 100;
    public double stopLoss = 1000;
    public double stake = 50;
    public long expiryMs = 5000;
    public long settleDelayMs = 2500;

    // state
    public boolean enabled;
    public boolean stopped;
    public String stopReason = "";
    public int trades, won, lost, refunds, unclear;
    public double net;
    private long lastSettledAt = 0;
    private int balanceFails = 0;

    public static final class Pending {
        public long openedAt, settleAt;
        public int dir;
        public double before;
    }
    public Pending pending;

    /** What happened on this step, for the log and the overlay; null if nothing. */
    public static final class Event {
        public String type;       // "open", "settle", "stop", "skip"
        public String detail = "";
        public int dir;
        public double before = Double.NaN, after = Double.NaN, delta = Double.NaN;
        public String outcome;
    }

    public boolean configured() {
        return buy != null && sell != null && demo != null && real != null && balance != null;
    }

    public Event step(long now, String signal, Screen s, Tapper tapper) {
        // settle the open trade from the broker's balance
        if (pending != null) {
            if (now < pending.settleAt) return null;
            Double after = parseBalance(s.readText(balance));
            if (after == null) {
                if (now > pending.settleAt + 8000) {
                    unclear++;
                    Event e = settleEvent(Double.NaN, "unclear");
                    e.detail = "couldn't read the balance after expiry";
                    pending = null;
                    lastSettledAt = now;
                    return e;
                }
                return null;
            }
            double delta = after - pending.before;
            String outcome = classify(delta);
            switch (outcome) {
                case "won": won++; net += delta; break;
                case "lost": lost++; net += delta; break;
                case "refund": refunds++; break;
                default: unclear++; break;
            }
            Event e = settleEvent(after, outcome);
            pending = null;
            lastSettledAt = now;
            if (net <= -stopLoss) stop("stop-loss reached (" + money(net) + ")");
            else if (trades >= maxTrades) stop("trade limit reached (" + trades + ")");
            return e;
        }

        if (!enabled || stopped || !configured()) return null;
        int dir = "CALL".equals(signal) ? 1 : "PUT".equals(signal) ? -1 : 0;
        if (dir == 0) return null;
        if (now - lastSettledAt < 1000) return null;

        // safety: confirm DEMO right before trading
        String why = demoProblem(s);
        if (why != null) {
            stop("not confirmed on DEMO: " + why);
            Event e = new Event();
            e.type = "stop";
            e.detail = stopReason;
            return e;
        }

        Double before = parseBalance(s.readText(balance));
        if (before == null) {
            if (++balanceFails >= 3) {
                stop("can't read the balance");
                Event e = new Event(); e.type = "stop"; e.detail = stopReason; return e;
            }
            Event e = new Event(); e.type = "skip"; e.detail = "balance unreadable"; return e;
        }
        balanceFails = 0;

        Region target = dir > 0 ? buy : sell;
        if (!tapper.tap(target.cx, target.cy)) {
            stop("tap service is off (turn it on in Accessibility settings)");
            Event e = new Event(); e.type = "stop"; e.detail = stopReason; return e;
        }
        pending = new Pending();
        pending.openedAt = now;
        pending.settleAt = now + expiryMs + settleDelayMs;
        pending.dir = dir;
        pending.before = before;
        trades++;
        Event e = new Event();
        e.type = "open";
        e.dir = dir;
        e.before = before;
        return e;
    }

    /** Null if DEMO is confirmed, else the reason. */
    String demoProblem(Screen s) {
        String dt = s.readText(demo), rt = s.readText(real);
        if (dt == null || rt == null) return "badge text unreadable";
        if (!norm(dt).contains("DEMO")) return "DEMO badge reads \"" + dt.trim() + "\"";
        if (!norm(rt).contains("REAL")) return "REAL badge reads \"" + rt.trim() + "\"";
        double dh = s.highlighted(demo), rh = s.highlighted(real);
        if (dh < 0.25) return "DEMO badge isn't highlighted";
        if (rh > dh * 0.5) return "REAL badge looks highlighted";
        return null;
    }

    String classify(double delta) {
        if (Math.abs(delta) <= 0.5) return "refund";
        if (delta < 0 && Math.abs(delta + stake) <= Math.max(1, stake * 0.02)) return "lost";
        if (delta > 0 && delta <= stake * 1.0 + 0.5) return "won";
        return "unclear";   // a different amount: a manual trade, a misread, a deposit…
    }

    private Event settleEvent(double after, String outcome) {
        Event e = new Event();
        e.type = "settle";
        e.dir = pending.dir;
        e.before = pending.before;
        e.after = after;
        e.delta = after - pending.before;
        e.outcome = outcome;
        return e;
    }

    public void stop(String reason) {
        stopped = true;
        stopReason = reason;
    }

    private static final Pattern MONEY = Pattern.compile("([0-9][0-9,]*\\.[0-9]{2})");

    /** First amount like 9,577.50 in the text. Commas are thousands separators. */
    public static Double parseBalance(String text) {
        if (text == null) return null;
        Matcher m = MONEY.matcher(text.replace(" ", ""));
        if (!m.find()) return null;
        try { return Double.parseDouble(m.group(1).replace(",", "")); }
        catch (NumberFormatException e) { return null; }
    }

    private static String norm(String s) {
        return s.toUpperCase(Locale.US).replace('0', 'O').replaceAll("[^A-Z]", "");
    }

    static String money(double v) {
        return (v < 0 ? "-$" : "+$") + String.format(Locale.US, "%.2f", Math.abs(v));
    }

    public String summary() {
        int dec = won + lost;
        String s = "AUTO (demo): " + trades + " trades, " + won + "W " + lost + "L";
        if (dec > 0) s += " (" + Math.round(100.0 * won / dec) + "%)";
        s += ", net " + money(net);
        if (stopped) s += " · stopped: " + stopReason;
        else if (!enabled) s += " · off";
        return s;
    }
}
