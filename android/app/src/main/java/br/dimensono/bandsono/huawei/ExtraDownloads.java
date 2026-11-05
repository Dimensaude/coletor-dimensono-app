package br.dimensono.bandsono.huawei;

import android.os.Handler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import br.dimensono.bandsono.huawei.packets.Workout;
import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

/**
 * The downloads that come AFTER the workouts in a sync, each only when the band announced the capability:
 * the ECG analysis file (bit 106), the arrhythmia PPG sequence file and result class (bit 113), and the GPS / PDR files of
 * workouts. Collection only: the bytes are handed over raw and nothing is read or shown.
 * <p>
 * Order: ECG, arrhythmia (PPG, then the 500007 result), GPS/PDR of at most {@link #MAX_TRAJECTORIES_PER_SYNC} workouts, so
 * a band that never sends the GPS cannot starve the others. Every file has its own timeout and all of them together a
 * budget that fits inside the sync's watchdog; a silent band, an error or an empty answer never stops the sync.
 * Callbacks and timers of an earlier sync are ignored (the sync generation).
 */
final class ExtraDownloads {
    private static final Logger LOG = LoggerFactory.getLogger(ExtraDownloads.class);

    /** One extra file: the band's own file timeout is not trusted, a silent band must not eat the session. */
    static final int FILE_TIMEOUT_MS = 60 * 1000;
    /** All extras together. */
    static final int BUDGET_MS = 3 * 60 * 1000;
    static final int MIN_BUDGET_MS = 10 * 1000;
    static final int WATCHDOG_MARGIN_MS = 20 * 1000;
    static final int MAX_TRAJECTORIES_PER_SYNC = 3;
    /** The band's answer when there is no such file (no GPS / PDR / data for the window). */
    static final int ERROR_NO_SUCH_FILE = 144001;

    /** What the provider offers to the extras. */
    interface Host {
        int generation();

        boolean running();

        HuaweiState state();

        HuaweiSupportProvider.Listener listener();

        HuaweiFileDownloadManager downloads();

        void rawFile(String kind, int startSec, int endSec, byte[] data);

        void safely(String what, Runnable r);

        /** Dictionary sync of the arrhythmia result class (P2P 500007); {@code done} runs once, answered or not. */
        void syncArrhythmiaResult(Runnable done);

        long syncStartWallMs();
    }

    enum Status { OK, EMPTY, DEVICE_ERROR, FAILED, TIMEOUT, SKIPPED }

    /** How one extra file ended. */
    static final class Result {
        final Status status;
        final int errorCode;
        final byte[] data;

        Result(Status status, int errorCode, byte[] data) {
            this.status = status;
            this.errorCode = errorCode;
            this.data = data;
        }

        /** The band says there is nothing to get: an empty file or error 144001. Not a failure. */
        boolean noFile() {
            return status == Status.EMPTY || (status == Status.DEVICE_ERROR && errorCode == ERROR_NO_SUCH_FILE);
        }

        /** The question was answered (with data or with "nothing"). */
        boolean answered() {
            return status == Status.OK || noFile();
        }
    }

    private interface Done {
        void done(Result r);
    }

    private final Host host;
    private final Handler handler;
    private final int watchdogMs;

    private int gen;
    private List<PendingTrajectory> pending = null;
    private boolean over = false;
    private Runnable budgetTimer = null;
    private Runnable currentAbort = null;

    ExtraDownloads(Host host, Handler handler, int watchdogMs) {
        this.host = host;
        this.handler = handler;
        this.watchdogMs = watchdogMs;
    }

    /** A new sync starts: forget the previous one's state and timers. */
    void reset() {
        cancel();
        pending = null;
        over = false;
    }

    /** The sync ended: no timer of the extras may fire any more. */
    void cancel() {
        if (budgetTimer != null) handler.removeCallbacks(budgetTimer);
        budgetTimer = null;
        currentAbort = null;
    }

    private boolean alive() {
        return host.running() && host.generation() == gen;
    }

    // ------------------------------------------------------------------ pending GPS / PDR list

    private List<PendingTrajectory> pendingList() {
        if (pending == null) {
            pending = new ArrayList<>();
            try {
                List<PendingTrajectory> stored = host.listener().getPendingTrajectories();
                if (stored != null) pending.addAll(stored);
            } catch (Exception e) {
                LOG.error("Could not read the pending GPS list: " + e.getClass().getSimpleName());
            }
        }
        return pending;
    }

    private void savePending(boolean prune) {
        List<PendingTrajectory> list = pendingList();
        if (prune) {
            for (PendingTrajectory p : new ArrayList<>(list)) {
                if (p.finished()) {
                    if (!(p.gpsDone && p.pdrDone))
                        LOG.warn("Giving up on the GPS/PDR of workout {} after {} tries", p.number, p.attempts);
                    list.remove(p);
                }
            }
            while (list.size() > PendingTrajectory.MAX_ENTRIES) list.remove(0);
        }
        final List<PendingTrajectory> copy = new ArrayList<>(list);
        host.safely("pending GPS list", () -> host.listener().savePendingTrajectories(copy));
    }

    /** A stored workout may have a GPS/PDR file: remember it until both were collected (the workout cursor moves on now). */
    void noteTrajectory(Workout.WorkoutCount.Response.WorkoutNumbers numbers, Workout.WorkoutTotals.Response t) {
        if (!host.state().getSupportsGpsNewSync() || t.startTime <= 0) return;
        if (numbers.trajectoriesKnown && numbers.trajectoriesData == 0) return; // the band says: no GPS/PDR files
        for (PendingTrajectory p : pendingList())
            if (p.sameWorkout(numbers.workoutNumber, t.startTime)) return;
        pendingList().add(new PendingTrajectory(numbers.workoutNumber, t.startTime, t.endTime));
        savePending(false);
    }

    // ------------------------------------------------------------------ one file, bounded

    /**
     * One extra file with its own timeout and the budget of all extras. Calls {@code cb} exactly once (not at all when the
     * sync it belongs to is over).
     */
    private void extraFile(final String what, final Function<HuaweiFileDownloadManager.FileDownloadCallback,
            HuaweiFileDownloadManager.FileRequest> maker, final Done cb) {
        if (!alive() || over) {
            cb.done(new Result(Status.SKIPPED, 0, null));
            return;
        }
        final int myGen = gen;
        final boolean[] fin = {false};
        final Runnable[] tmo = new Runnable[1];
        final java.util.function.Consumer<Result> finish = r -> {
            if (fin[0]) return;
            fin[0] = true;
            handler.removeCallbacks(tmo[0]);
            currentAbort = null;
            if (host.generation() != myGen || !host.running()) return; // an earlier sync's file: ignored
            cb.done(r);
        };
        tmo[0] = () -> {
            if (host.generation() != myGen || !host.running()) return; // the timer of an earlier sync: must not touch this one
            LOG.warn("Extra file {} timed out", what);
            host.downloads().cancelAll();
            finish.accept(new Result(Status.TIMEOUT, 0, null));
        };
        currentAbort = () -> {
            if (host.generation() != myGen || !host.running()) return;
            host.downloads().cancelAll();
            finish.accept(new Result(Status.SKIPPED, 0, null));
        };
        handler.postDelayed(tmo[0], FILE_TIMEOUT_MS);
        try {
            host.downloads().addToQueue(maker.apply(new HuaweiFileDownloadManager.FileDownloadCallback() {
                @Override
                public void downloadComplete(HuaweiFileDownloadManager.FileRequest fileRequest, File localRawFile) {
                    byte[] d = fileRequest.getData();
                    LOG.info("Extra file {}: {} bytes", what, d == null ? 0 : d.length);
                    finish.accept(new Result(d == null || d.length == 0 ? Status.EMPTY : Status.OK, 0, d));
                }

                @Override
                public void downloadException(HuaweiFileDownloadManager.HuaweiFileDownloadException e) {
                    int code = e instanceof HuaweiFileDownloadManager.HuaweiFileDownloadDeviceErrorException
                            ? ((HuaweiFileDownloadManager.HuaweiFileDownloadDeviceErrorException) e).code : 0;
                    LOG.info("Extra file {} not downloaded: {}", what, e.getMessage());
                    finish.accept(new Result(code != 0 ? Status.DEVICE_ERROR : Status.FAILED, code, null));
                }
            }), true);
        } catch (RuntimeException e) {
            LOG.error("Could not request extra file " + what + ": " + e.getClass().getSimpleName());
            finish.accept(new Result(Status.FAILED, 0, null));
        }
    }

    // ------------------------------------------------------------------ the whole phase

    /** Runs the extras (each skipped when not announced), then {@code next}. */
    void run(final Runnable next) {
        gen = host.generation();
        if (!alive()) return;
        final HuaweiState state = host.state();
        final boolean gps = state.getSupportsGpsNewSync() && hasUnfinished();
        final boolean ecg = state.supportsEcgAnalysis();
        final boolean arr = state.supportsArrhythmia();
        if (!gps && !ecg && !arr) {
            next.run();
            return;
        }
        long elapsed = System.currentTimeMillis() - host.syncStartWallMs();
        long budget = Math.min(BUDGET_MS, watchdogMs - elapsed - WATCHDOG_MARGIN_MS);
        if (budget < MIN_BUDGET_MS) {
            LOG.info("No time left for the extra downloads in this sync");
            next.run();
            return;
        }
        over = false;
        final Runnable finish = () -> {
            cancel();
            next.run();
        };
        budgetTimer = () -> {
            if (!alive()) return;
            LOG.warn("Budget of the extra downloads used up");
            over = true;
            Runnable abort = currentAbort;
            if (abort != null) abort.run();
        };
        handler.postDelayed(budgetTimer, budget);
        windowStep("ecg_analysis", ecg, null, () -> windowStep("arrhythmia_ppg", arr, HuaweiDictTypes.ARRHYTHMIA_SEQUENCE_DICT,
                () -> resultStep(arr, () -> trajectories(finish))));
    }

    private boolean hasUnfinished() {
        for (PendingTrajectory p : pendingList()) if (!p.finished()) return true;
        return false;
    }

    private void resultStep(boolean wanted, Runnable next) {
        if (!wanted || !alive() || over) {
            next.run();
            return;
        }
        host.syncArrhythmiaResult(next);
    }

    /**
     * Raw file of a time window, from the end of the last one to now (ECG analysis; the arrhythmia PPG is a sequence dictionary).
     * Only the raw bytes are kept. The window moves on when it was read, also when the band says there is nothing (empty or
     * 144001); on any other failure it stays, so the next sync asks again.
     */
    private void windowStep(final String kind, boolean wanted, final Integer sequenceDict, final Runnable next) {
        if (!wanted || !alive() || over) {
            next.run();
            return;
        }
        final int end = (int) (System.currentTimeMillis() / 1000);
        int last = 0;
        try {
            last = host.listener().getLastExtraEnd(kind);
        } catch (Exception e) {
            LOG.error("Could not read the " + kind + " cursor");
        }
        final int start = Math.min(end, Math.max(last, end - 30 * 24 * 3600));
        extraFile(kind, cb -> sequenceDict == null
                ? HuaweiFileDownloadManager.FileRequest.ecgAnalysisFileRequest(start, end, cb)
                : HuaweiFileDownloadManager.FileRequest.sequenceDataFileRequest(start, end, sequenceDict, cb), r -> {
            if (r.status == Status.OK) host.rawFile(kind, start, end, r.data);
            if (r.answered()) host.safely(kind + " cursor", () -> host.listener().onExtraWindowDone(kind, end));
            next.run();
        });
    }

    // ------------------------------------------------------------------ GPS / PDR

    private void trajectories(final Runnable next) {
        final List<PendingTrajectory> todo = new ArrayList<>();
        if (host.state().getSupportsGpsNewSync()) {
            for (PendingTrajectory p : pendingList()) {
                if (!p.finished() && todo.size() < MAX_TRAJECTORIES_PER_SYNC) todo.add(p);
            }
        }
        trajectoryStep(todo, 0, next);
    }

    private void trajectoryStep(final List<PendingTrajectory> todo, final int i, final Runnable next) {
        if (!alive() || over || i >= todo.size()) {
            if (alive() && !todo.isEmpty()) savePending(true);
            next.run();
            return;
        }
        final PendingTrajectory p = todo.get(i);
        final Runnable nextOne = () -> trajectoryStep(todo, i + 1, next);
        if (p.gpsDone) {
            pdrStep(p, nextOne);
            return;
        }
        extraFile("gps", cb -> HuaweiFileDownloadManager.FileRequest.workoutGpsFileRequest(true, p.number, null, cb), r -> {
            if (r.status == Status.OK) host.rawFile("workout_gps", p.startTs, p.endTs, r.data);
            if (r.answered()) p.gpsDone = true; // a band without GPS answers 144001 or an empty file: not an error
            else if (r.status != Status.SKIPPED) p.attempts++;
            savePending(false);
            // the PDR does not depend on the GPS (a band may have only a PDR track), but a band that did not answer at all is left alone
            if (r.answered()) pdrStep(p, nextOne);
            else nextOne.run();
        });
    }

    /** If the PDR fails, the GPS already handed over is kept. */
    private void pdrStep(final PendingTrajectory p, final Runnable nextOne) {
        if (p.pdrDone) {
            nextOne.run();
            return;
        }
        extraFile("pdr", cb -> HuaweiFileDownloadManager.FileRequest.workoutPdrFileRequest(p.number, null, cb), r -> {
            if (r.status == Status.OK) host.rawFile("workout_pdr", p.startTs, p.endTs, r.data);
            if (r.answered()) p.pdrDone = true;
            else if (r.status != Status.SKIPPED) p.attempts++;
            savePending(false);
            nextOne.run();
        });
    }
}
