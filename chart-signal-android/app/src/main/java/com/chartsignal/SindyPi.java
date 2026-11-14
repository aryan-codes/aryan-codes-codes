package com.chartsignal;

/**
 * SINDy-PI (sparse identification of nonlinear dynamics, parallel implicit form) on a
 * one-dimensional price curve.
 *
 * State is s = (x, v): x is the z-scored, lightly smoothed price and v = dx/dt (per sample).
 * The unknown is the acceleration a = dv/dt. The library is
 *   Theta = [ 1, x, v, x^2, xv, v^2, x^3, x^2 v, x v^2, v^3 | a, a x, a v ]
 * For each implicit candidate on the left (a, a x, a v) we regress it on every other column with
 * sequentially thresholded ridge regression (STRidge), which gives a rational model
 *   a * D(x, v) = g(x, v)   ->   a = g / D.
 * The candidate and sparsity threshold with the lowest error on a held-out tail are kept,
 * then the model is integrated forward: v += a, x += v.
 */
public final class SindyPi {

    static final int NLIB = 10;
    static final String[] LHS_NAMES = {"a", "a·x", "a·v"};

    static void lib(double x, double v, double[] out) {
        out[0] = 1; out[1] = x; out[2] = v;
        out[3] = x * x; out[4] = x * v; out[5] = v * v;
        out[6] = x * x * x; out[7] = x * x * v; out[8] = x * v * v; out[9] = v * v * v;
    }

    public static final class Model {
        final double[] g = new double[NLIB];
        double c0, cx, cv;      // D = c0 + cx x + cv v
        double aClip;
        double mean, std;       // z-score of the price prefix
        double valErr;
        String lhs;
        int active;
        private final double[] buf = new double[NLIB];

        double accel(double x, double v) {
            double d = c0 + cx * x + cv * v;
            if (Math.abs(d) < 1e-3) d = d < 0 ? -1e-3 : 1e-3;
            lib(x, v, buf);
            double num = 0;
            for (int k = 0; k < NLIB; k++) num += g[k] * buf[k];
            double a = num / d;
            if (Double.isNaN(a)) return 0;
            return Math.max(-aClip, Math.min(aClip, a));
        }

        /** Forecast the change in price (original units) over h samples, filling path if given. */
        public double forecast(double lastX, double lastV, int h, double[] path) {
            double x = lastX, v = lastV;
            double vClip = 6 * aClip + Math.abs(lastV) * 2 + 1e-6;
            for (int i = 0; i < h; i++) {
                v += accel(x, v);
                v = Math.max(-vClip, Math.min(vClip, v));
                x += v;
                if (path != null) path[i] = x * std + mean;
            }
            return (x - lastX) * std;
        }

        public String describe() {
            return "SINDy-PI lhs=" + lhs + " terms=" + active;
        }
    }

    /** Fit on prefix p[0..n-1]. Returns null when there is too little usable data. */
    public static Model fit(double[] p, int n) {
        if (n < 40) return null;
        double mean = 0;
        for (int i = 0; i < n; i++) mean += p[i];
        mean /= n;
        double var = 0;
        for (int i = 0; i < n; i++) var += (p[i] - mean) * (p[i] - mean);
        double std = Math.sqrt(var / n);
        if (std < 1e-9) return null;

        double[] s = smooth(p, n, mean, std);
        int rows = n - 2;
        double[][] theta = new double[rows][NLIB + 3];
        double[] aCol = new double[rows];
        double[] tmp = new double[NLIB];
        for (int t = 1; t < n - 1; t++) {
            double x = s[t];
            double v = (s[t + 1] - s[t - 1]) / 2;
            double a = s[t + 1] - 2 * s[t] + s[t - 1];
            lib(x, v, tmp);
            double[] r = theta[t - 1];
            System.arraycopy(tmp, 0, r, 0, NLIB);
            r[NLIB] = a; r[NLIB + 1] = a * x; r[NLIB + 2] = a * v;
            aCol[t - 1] = a;
        }
        double aStd = 0;
        for (double a : aCol) aStd += a * a;
        aStd = Math.sqrt(aStd / rows) + 1e-9;

        int split = (int) (rows * 0.8);
        double[] tols = {0.0, 0.02, 0.05, 0.1, 0.2, 0.4};
        Model best = null;

        for (int j = 0; j < 3; j++) {
            int lhsCol = NLIB + j;
            int[] cols = new int[NLIB + 2];
            int c = 0;
            for (int k = 0; k < NLIB + 3; k++) if (k != lhsCol) cols[c++] = k;
            for (double tol : tols) {
                double[] xi = stridge(theta, split, cols, lhsCol, tol);
                if (xi == null) continue;
                Model m = new Model();
                // a * (e_j - xi_implicit) . [1, x, v] = xi_lib . lib
                double[] cD = new double[3];
                cD[j] = 1;
                int act = 0;
                for (int q = 0; q < cols.length; q++) {
                    int k = cols[q];
                    if (xi[q] != 0) act++;
                    if (k < NLIB) m.g[k] = xi[q];
                    else cD[k - NLIB] -= xi[q];
                }
                m.c0 = cD[0]; m.cx = cD[1]; m.cv = cD[2];
                m.aClip = 4 * aStd;
                m.mean = mean; m.std = std;
                m.lhs = LHS_NAMES[j];
                m.active = act;
                // validation: one-step acceleration error on the held-out tail
                double err = 0;
                int cnt = 0;
                for (int r = split; r < rows; r++) {
                    double x = theta[r][1], v = theta[r][2];
                    double e = m.accel(x, v) - aCol[r];
                    err += e * e;
                    cnt++;
                }
                m.valErr = cnt == 0 ? Double.MAX_VALUE : err / cnt / (aStd * aStd);
                // mild preference for sparser models
                m.valErr *= 1 + 0.01 * act;
                if (best == null || m.valErr < best.valErr) best = m;
            }
        }
        return best;
    }

    /** Last smoothed state (x, v) in z-score units, for forecasting from the end of the prefix. */
    public static double[] lastState(double[] p, int n, Model m) {
        double[] s = smooth(p, n, m.mean, m.std);
        return new double[]{s[n - 1], s[n - 1] - s[n - 2]};
    }

    static double[] smooth(double[] p, int n, double mean, double std) {
        double[] s = new double[n];
        int w = 2;
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - w), hi = Math.min(n - 1, i + w);
            double sum = 0;
            for (int k = lo; k <= hi; k++) sum += p[k];
            s[i] = (sum / (hi - lo + 1) - mean) / std;
        }
        return s;
    }

    /** Sequentially thresholded ridge regression with column scaling. */
    static double[] stridge(double[][] theta, int rows, int[] cols, int y, double tol) {
        int m = cols.length;
        double[] scale = new double[m];
        for (int q = 0; q < m; q++) {
            double ss = 0;
            for (int r = 0; r < rows; r++) ss += theta[r][cols[q]] * theta[r][cols[q]];
            scale[q] = Math.sqrt(ss / rows) + 1e-12;
        }
        boolean[] on = new boolean[m];
        java.util.Arrays.fill(on, true);
        double[] w = null;
        double lambda = 1e-4 * rows;
        for (int it = 0; it < 10; it++) {
            int[] idx = new int[m];
            int k = 0;
            for (int q = 0; q < m; q++) if (on[q]) idx[k++] = q;
            if (k == 0) return new double[m];
            double[][] A = new double[k][k + 1];
            for (int r = 0; r < rows; r++) {
                double[] row = theta[r];
                double yr = row[y];
                for (int a = 0; a < k; a++) {
                    double va = row[cols[idx[a]]] / scale[idx[a]];
                    A[a][k] += va * yr;
                    for (int b = a; b < k; b++) A[a][b] += va * row[cols[idx[b]]] / scale[idx[b]];
                }
            }
            for (int a = 0; a < k; a++) {
                for (int b = 0; b < a; b++) A[a][b] = A[b][a];
                A[a][a] += lambda;
            }
            double[] sol = solve(A, k);
            if (sol == null) return null;
            w = new double[m];
            boolean changed = false;
            for (int a = 0; a < k; a++) {
                double ws = sol[a];
                if (Math.abs(ws) < tol) { on[idx[a]] = false; changed = true; }
                else w[idx[a]] = ws;
            }
            if (!changed) break;
        }
        if (w == null) return null;
        for (int q = 0; q < m; q++) w[q] /= scale[q];
        return w;
    }

    static double[] solve(double[][] A, int k) {
        for (int c = 0; c < k; c++) {
            int piv = c;
            for (int r = c + 1; r < k; r++) if (Math.abs(A[r][c]) > Math.abs(A[piv][c])) piv = r;
            if (Math.abs(A[piv][c]) < 1e-12) return null;
            double[] t = A[c]; A[c] = A[piv]; A[piv] = t;
            for (int r = 0; r < k; r++) {
                if (r == c) continue;
                double f = A[r][c] / A[c][c];
                if (f == 0) continue;
                for (int q = c; q <= k; q++) A[r][q] -= f * A[c][q];
            }
        }
        double[] x = new double[k];
        for (int c = 0; c < k; c++) x[c] = A[c][k] / A[c][c];
        return x;
    }
}
