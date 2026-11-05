package br.dimensono.bandsono;

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide status of the band connection, observed by the Flutter UI and the notification. */
public final class BandStatus {
    public interface Observer {
        void onStatus(Map<String, Object> status);
    }

    private static final Map<String, Object> status = new HashMap<>();
    private static final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    static {
        status.put("state", "DISCONNECTED");
        status.put("detail", "");
        status.put("battery", -1);
        status.put("syncing", false);
        status.put("calibrating", false);
    }

    private BandStatus() {
    }

    public static synchronized Map<String, Object> snapshot() {
        return new HashMap<>(status);
    }

    public static void update(String key, Object value) {
        Map<String, Object> copy;
        synchronized (BandStatus.class) {
            status.put(key, value);
            copy = new HashMap<>(status);
        }
        dispatch(copy);
    }

    public static void update(Map<String, Object> values) {
        Map<String, Object> copy;
        synchronized (BandStatus.class) {
            status.putAll(values);
            copy = new HashMap<>(status);
        }
        dispatch(copy);
    }

    private static void dispatch(final Map<String, Object> copy) {
        main.post(() -> {
            for (Observer o : observers) o.onStatus(copy);
        });
    }

    public static void addObserver(Observer o) {
        observers.add(o);
        final Map<String, Object> copy = snapshot();
        main.post(() -> o.onStatus(copy));
    }

    public static void removeObserver(Observer o) {
        observers.remove(o);
    }
}
