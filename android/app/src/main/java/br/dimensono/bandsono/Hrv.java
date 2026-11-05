package br.dimensono.bandsono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Time-domain HRV from an RR interval series (ms), with a simple artefact filter. */
public final class Hrv {
    private Hrv() {
    }

    /** Keeps RR in 300–2000 ms that differ less than 20 % from the previous accepted beat. */
    public static int[] clean(int[] rri) {
        List<Integer> out = new ArrayList<>();
        int prev = -1;
        for (int r : rri) {
            if (r < 300 || r > 2000)
                continue;
            if (prev > 0 && Math.abs(r - prev) > 0.2 * prev) {
                prev = r; // start a new reference after an abrupt change
                continue;
            }
            out.add(r);
            prev = r;
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    public static Map<String, Object> compute(int[] raw) {
        int[] rr = clean(raw);
        Map<String, Object> m = new HashMap<>();
        m.put("n", rr.length);
        m.put("nRaw", raw.length);
        if (rr.length < 10) {
            m.put("rmssd", 0.0);
            m.put("sdnn", 0.0);
            m.put("pnn50", 0.0);
            m.put("meanHr", 0.0);
            return m;
        }
        double mean = 0;
        for (int r : rr) mean += r;
        mean /= rr.length;
        double var = 0;
        for (int r : rr) var += (r - mean) * (r - mean);
        double sdnn = Math.sqrt(var / (rr.length - 1));
        double sq = 0;
        int nn50 = 0;
        for (int i = 1; i < rr.length; i++) {
            int d = rr[i] - rr[i - 1];
            sq += (double) d * d;
            if (Math.abs(d) > 50) nn50++;
        }
        double rmssd = Math.sqrt(sq / (rr.length - 1));
        m.put("rmssd", round1(rmssd));
        m.put("sdnn", round1(sdnn));
        m.put("pnn50", round1(100.0 * nn50 / (rr.length - 1)));
        m.put("meanHr", round1(60000.0 / mean));
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    public static String join(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%d", a[i]));
        }
        return sb.toString();
    }

    public static int[] split(String s) {
        if (s == null || s.isEmpty()) return new int[0];
        String[] p = s.split(",");
        int[] a = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                a[i] = Integer.parseInt(p[i].trim());
            } catch (NumberFormatException e) {
                a[i] = 0;
            }
        }
        return a;
    }
}
