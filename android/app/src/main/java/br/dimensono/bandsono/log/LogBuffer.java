package br.dimensono.bandsono.log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Keeps the last lines of the protocol log so the user can copy it from the app. */
public class LogBuffer {
    private static final int MAX_LINES = 3000;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final char[] LEVELS = {'?', '?', 'V', 'D', 'I', 'W', 'E', 'A'};

    public static synchronized void add(int level, String tag, String msg) {
        char l = level >= 0 && level < LEVELS.length ? LEVELS[level] : '?';
        lines.addLast(fmt.format(new Date()) + " " + l + "/" + tag + ": " + msg);
        while (lines.size() > MAX_LINES) lines.removeFirst();
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() {
        lines.clear();
    }
}
