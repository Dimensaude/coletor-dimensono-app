import android.os.Handler;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.function.Consumer;

import br.dimensono.bandsono.huawei.util.GB;

/**
 * Simulated Huawei Band 10 with HiChain authentication ({@link HarnessMain.FakeBand} in HiChain mode)
 * behind a line protocol on stdin/stdout, so a client written in another language (the Swift client on
 * a macOS runner) can be tested against the same band the Java client is tested against.
 * <pre>
 * stdin   W &lt;hex&gt;   one BLE write from the phone, cut anywhere (frames, sliced or not, are reassembled)
 *         D         phone disconnected: partial frames and the HiChain session are dropped, trust store kept
 *         Q         quit (end of input works too); empty lines and lines starting with # are ignored
 * stdout  N &lt;hex&gt;   one BLE notification (each frame cut into --notify-chunk bytes)
 *         L &lt;text&gt;  log: band config, each request seen, each HiChain step, the PIN, "authenticated"...
 *         E &lt;text&gt;  band-side protocol error: bad CRC, token mismatch, unexpected command, parity mismatch...
 * </pre>
 * Replies are written synchronously, before the next input line is read. Everything else the code
 * under test logs goes to stderr. Band-side randomness (nonces, PIN, authToken, IVs) comes from --seed.
 */
public class BandServer {
    static final String USAGE = "usage: java -cp <dir> BandServer [--auth-version N (2)] [--encrypt-method 0|1 (0)]"
            + " [--mtu N (244)] [--slice N (244)] [--bond-state N (0)] [--notify-chunk N (20)] [--seed N (1)]"
            + " [--no-strict] [--verbose]";

    static class Options {
        int authVersion = 2, encryptMethod = 0, mtu = 0xF4, slice = 0xF4, bondState = 0, notifyChunk = 20;
        long seed = 1;
        boolean parity = true;   // --no-strict turns the byte-parity check off
        boolean verbose = false; // protocol code debug logs on stderr

        static Options parse(String[] args) {
            Options o = new Options();
            for (int i = 0; i < args.length; i++) {
                String a = args[i], v = null;
                int eq = a.indexOf('=');
                if (eq > 0) {
                    v = a.substring(eq + 1);
                    a = a.substring(0, eq);
                }
                if (a.equals("--no-strict")) { o.parity = false; continue; }
                if (a.equals("--verbose")) { o.verbose = true; continue; }
                if (v == null) {
                    if (i + 1 >= args.length) throw new IllegalArgumentException(a + " needs a value");
                    v = args[++i];
                }
                long n;
                try {
                    n = Long.decode(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(a + ": not a number: " + v);
                }
                switch (a) {
                    case "--auth-version": o.authVersion = (int) range(a, n, 0, 255); break;
                    case "--encrypt-method": o.encryptMethod = (int) range(a, n, 0, 1); break;
                    case "--mtu": o.mtu = (int) range(a, n, 20, 0x7FFF); break;
                    case "--slice": o.slice = (int) range(a, n, 16, 0x7FFF); break;
                    case "--bond-state": o.bondState = (int) range(a, n, 0, 255); break;
                    case "--notify-chunk": o.notifyChunk = (int) range(a, n, 1, 0xFFFF); break;
                    case "--seed": o.seed = n; break;
                    default: throw new IllegalArgumentException("unknown option " + a);
                }
            }
            return o;
        }

        static long range(String name, long n, long min, long max) {
            if (n < min || n > max) throw new IllegalArgumentException(name + " must be " + min + ".." + max + ", got " + n);
            return n;
        }
    }

    final Options opt;
    final HarnessMain.FakeBand band;
    private final Consumer<String> out;

    BandServer(Options opt, Consumer<String> out) {
        this.opt = opt;
        this.out = out;
        int base = (int) (System.currentTimeMillis() / 1000) - 3 * 86400;
        base -= base % 86400; // same synthetic history as the harness
        band = new HarnessMain.FakeBand(base, "00:00:00:00:00:00").useHiChain(opt.authVersion, opt.encryptMethod);
        band.rnd = new Random(opt.seed);
        band.mtu = opt.mtu;
        band.slice = opt.slice;
        band.bondState = opt.bondState;
        band.knownOnly = true;
        band.parity = opt.parity;
        band.sink = this::notifyFrame;
        band.logSink = m -> out.accept("L " + m);
        band.errSink = m -> out.accept("E " + m);
        out.accept(String.format("L band ready: HUAWEI Band 10, HiChain (deviceSupportType 4), authVersion %d, encryptMethod %d,"
                        + " mtu %d, slice %d, bondState %d, notify chunk %d, seed %d, parity %s",
                opt.authVersion, opt.encryptMethod, opt.mtu, opt.slice, opt.bondState, opt.notifyChunk, opt.seed,
                opt.parity ? "on" : "off"));
    }

    void notifyFrame(byte[] frame) {
        for (int o = 0; o < frame.length; o += opt.notifyChunk)
            out.accept("N " + GB.hexdump(frame, o, Math.min(opt.notifyChunk, frame.length - o)));
    }

    /** Handles one input line; false when the session should end (Q). */
    boolean handleLine(String line) {
        String l = line.trim();
        if (l.isEmpty() || l.startsWith("#")) return true;
        String cmd = l.split("\\s+", 2)[0];
        String arg = l.substring(cmd.length()).trim();
        try {
            switch (cmd.toUpperCase(java.util.Locale.ROOT)) {
                case "W": {
                    String hex = arg.replaceAll("\\s+", "");
                    if (hex.isEmpty() || hex.length() % 2 != 0 || !hex.matches("[0-9A-Fa-f]+")) {
                        out.accept("E bad input line (want W <hex>): " + l);
                        return true;
                    }
                    band.write(GB.hexStringToByteArray(hex));
                    return true;
                }
                case "D":
                    band.resetConnection();
                    out.accept("L phone disconnected: partial frames and HiChain session dropped, "
                            + band.trust.size() + " trusted client(s) kept");
                    return true;
                case "Q":
                    out.accept("L bye");
                    return false;
                default:
                    out.accept("E bad input line (want W <hex>, D or Q): " + l);
                    return true;
            }
        } catch (RuntimeException e) {
            out.accept("E internal error on '" + l + "': " + e);
            return true;
        }
    }

    /** Runs what the band scheduled on the virtual clock (the RR interval stream) right away. */
    static void runBandTimers() {
        while (!Handler.idle()) Handler.runUntil(Handler.now + 1000);
    }

    public static void main(String[] args) throws IOException {
        PrintStream proto = new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8");
        System.setOut(System.err); // stdout carries only N/L/E lines; the protocol code's logs go to stderr
        Options opt;
        try {
            opt = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("BandServer: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        android.util.Log.quiet = !opt.verbose;
        BandServer server = new BandServer(opt, l -> {
            proto.print(l);
            proto.print('\n'); // \n on every platform
            proto.flush();
        });
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (!server.handleLine(line)) break;
            runBandTimers();
        }
        proto.flush();
    }
}
