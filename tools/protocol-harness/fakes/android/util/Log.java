package android.util;
public final class Log {
    public static final int VERBOSE=2, DEBUG=3, INFO=4, WARN=5, ERROR=6;
    public static boolean quiet = false;
    public static int println(int p, String tag, String msg) { if (!quiet || p >= WARN) System.out.println("[" + android.os.Handler.now + "ms] " + tag + ": " + msg); return 0; }
    public static String getStackTraceString(Throwable t) { java.io.StringWriter sw = new java.io.StringWriter(); t.printStackTrace(new java.io.PrintWriter(sw)); return sw.toString(); }
}
