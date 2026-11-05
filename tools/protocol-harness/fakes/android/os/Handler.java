package android.os;
import java.util.*;
/** Deterministic virtual-time scheduler shared by all handlers. */
public class Handler {
    public static long now = 0;
    static final class Task { final long at; final long seq; final Runnable r; Task(long at, long seq, Runnable r){this.at=at;this.seq=seq;this.r=r;} }
    static final PriorityQueue<Task> queue = new PriorityQueue<>((a,b) -> a.at != b.at ? Long.compare(a.at,b.at) : Long.compare(a.seq,b.seq));
    static long seq = 0;
    public Handler() {}
    public Handler(Looper l) {}
    public boolean post(Runnable r) { queue.add(new Task(now, seq++, r)); return true; }
    public boolean postDelayed(Runnable r, long d) { queue.add(new Task(now + d, seq++, r)); return true; }
    public void removeCallbacks(Runnable r) { queue.removeIf(t -> t.r == r); }
    /** Run tasks until the queue is empty or virtual time passes `until`. */
    public static void runUntil(long until) {
        while (!queue.isEmpty() && queue.peek().at <= until) {
            Task t = queue.poll();
            if (t.at > now) now = t.at;
            t.r.run();
        }
        if (now < until) now = until;
    }
    public static boolean idle() { return queue.isEmpty(); }
}
