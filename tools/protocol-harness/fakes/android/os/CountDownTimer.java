package android.os;
/** Virtual-time CountDownTimer for the harness. */
public abstract class CountDownTimer {
    private final long total, interval;
    private final Handler h = new Handler();
    private long left;
    private boolean cancelled;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (cancelled) return;
            left -= interval;
            if (left <= 0) { onFinish(); return; }
            onTick(left);
            h.postDelayed(this, interval);
        }
    };
    public CountDownTimer(long millisInFuture, long countDownInterval) { total = millisInFuture; interval = countDownInterval; }
    public abstract void onTick(long millisUntilFinished);
    public abstract void onFinish();
    public synchronized final CountDownTimer start() { cancelled = false; left = total; onTick(left); h.postDelayed(tick, interval); return this; }
    public synchronized final void cancel() { cancelled = true; h.removeCallbacks(tick); }
}
