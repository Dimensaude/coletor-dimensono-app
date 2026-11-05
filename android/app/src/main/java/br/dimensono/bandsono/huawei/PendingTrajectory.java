package br.dimensono.bandsono.huawei;

import java.util.ArrayList;
import java.util.List;

/**
 * A workout whose GPS / PDR files were not completely collected yet. The workout cursor moves on as soon as the
 * workout is stored, so without this list a failed download would never be tried again. The owner (app or gateway)
 * keeps the list durable between syncs, as the text of {@link #serialize}.
 */
public final class PendingTrajectory {
    /** Tries (one per sync) after which a workout is given up on. */
    public static final int MAX_ATTEMPTS = 5;
    /** The list is kept short: the oldest entries go first. */
    public static final int MAX_ENTRIES = 50;

    public final short number;
    public final int startTs, endTs;
    public int attempts;
    public boolean gpsDone, pdrDone;

    public PendingTrajectory(short number, int startTs, int endTs) {
        this.number = number;
        this.startTs = startTs;
        this.endTs = endTs;
    }

    public boolean sameWorkout(short n, int start) {
        return number == n && startTs == start;
    }

    public boolean finished() {
        return (gpsDone && pdrDone) || attempts >= MAX_ATTEMPTS;
    }

    /** {@code number,start,end,attempts,gps,pdr;...} (numbers only, no health data). */
    public static String serialize(List<PendingTrajectory> list) {
        StringBuilder b = new StringBuilder();
        for (PendingTrajectory p : list) {
            if (b.length() > 0) b.append(';');
            b.append(p.number).append(',').append(p.startTs).append(',').append(p.endTs).append(',').append(p.attempts)
                    .append(',').append(p.gpsDone ? 1 : 0).append(',').append(p.pdrDone ? 1 : 0);
        }
        return b.toString();
    }

    /** Bad pieces are skipped: an unreadable list must never stop a sync. */
    public static List<PendingTrajectory> parse(String s) {
        List<PendingTrajectory> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String item : s.split(";")) {
            String[] f = item.split(",");
            if (f.length != 6) continue;
            try {
                PendingTrajectory p = new PendingTrajectory(Short.parseShort(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]));
                p.attempts = Integer.parseInt(f[3]);
                p.gpsDone = "1".equals(f[4]);
                p.pdrDone = "1".equals(f[5]);
                out.add(p);
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return out;
    }
}
