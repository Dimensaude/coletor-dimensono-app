package br.dimensono.bandsono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure rules for the automatic stress calibration: one attempt every 6 h, three a day. */
public final class CalibrationPolicy {
    public static final long MIN_GAP_MS = 6 * 3600_000L;
    public static final int MAX_PER_DAY = 3;
    private static final long DAY_MS = 24 * 3600_000L;

    private CalibrationPolicy() {
    }

    /** Parses the stored comma separated attempt times, keeping only the last 24 h, oldest first. */
    public static List<Long> recent(String stored, long now) {
        List<Long> out = new ArrayList<>();
        if (stored == null || stored.isEmpty())
            return out;
        for (String part : stored.split(",")) {
            try {
                long t = Long.parseLong(part.trim());
                if (t > now - DAY_MS && t <= now + 60_000L)
                    out.add(t);
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(out);
        return out;
    }

    public static boolean allowed(List<Long> recent, long now) {
        if (recent.size() >= MAX_PER_DAY)
            return false;
        return recent.isEmpty() || now - recent.get(recent.size() - 1) >= MIN_GAP_MS;
    }

    public static String serialize(List<Long> times) {
        StringBuilder sb = new StringBuilder();
        for (long t : times)
            sb.append(sb.length() == 0 ? "" : ",").append(t);
        return sb.toString();
    }
}
