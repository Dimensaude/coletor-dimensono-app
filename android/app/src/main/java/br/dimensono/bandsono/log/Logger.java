package br.dimensono.bandsono.log;

import android.util.Log;

/**
 * Tiny logger with an slf4j-like API. Writes to logcat and to an in-memory ring buffer
 * that the app can show/share from the settings screen (connection log).
 */
public class Logger {
    private final String tag;

    Logger(String tag) {
        this.tag = tag.length() > 23 ? tag.substring(0, 23) : tag;
    }

    private static String format(String msg, Object[] args) {
        if (msg == null) msg = "null";
        if (args == null || args.length == 0) return msg;
        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        int i = 0;
        while (i < msg.length()) {
            if (i + 1 < msg.length() && msg.charAt(i) == '{' && msg.charAt(i + 1) == '}' && argIdx < args.length) {
                Object a = args[argIdx++];
                sb.append(a instanceof Throwable ? a.toString() : String.valueOf(a));
                i += 2;
            } else {
                sb.append(msg.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    private static Throwable throwableOf(Object[] args) {
        if (args != null && args.length > 0 && args[args.length - 1] instanceof Throwable)
            return (Throwable) args[args.length - 1];
        return null;
    }

    private void log(int level, String msg, Object[] args) {
        String text = format(msg, args);
        Throwable t = throwableOf(args);
        if (t != null && (msg == null || !msg.contains("{}"))) {
            text = text + " :: " + t;
        }
        Log.println(level, tag, t != null ? text + "\n" + Log.getStackTraceString(t) : text);
        LogBuffer.add(level, tag, text);
    }

    public void trace(String msg, Object... args) { log(Log.VERBOSE, msg, args); }
    public void debug(String msg, Object... args) { log(Log.DEBUG, msg, args); }
    public void info(String msg, Object... args) { log(Log.INFO, msg, args); }
    public void warn(String msg, Object... args) { log(Log.WARN, msg, args); }
    public void error(String msg, Object... args) { log(Log.ERROR, msg, args); }

    public boolean isDebugEnabled() { return true; }
}
