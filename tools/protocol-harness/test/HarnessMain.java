import android.content.Context;
import android.os.Handler;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import br.dimensono.bandsono.Hrv;

import br.dimensono.bandsono.huawei.BandDevice;
import br.dimensono.bandsono.huawei.HuaweiConstants;
import br.dimensono.bandsono.huawei.HuaweiCoordinator;
import br.dimensono.bandsono.huawei.HuaweiCrypto;
import br.dimensono.bandsono.huawei.HuaweiPacket;
import br.dimensono.bandsono.huawei.HuaweiStressParser;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;
import br.dimensono.bandsono.huawei.HuaweiTLV;
import br.dimensono.bandsono.huawei.packets.DeviceConfig;
import br.dimensono.bandsono.huawei.util.CheckSums;
import br.dimensono.bandsono.huawei.util.CryptoUtils;
import br.dimensono.bandsono.huawei.util.GB;

/**
 * Simulated Huawei band talking to the real protocol code (normal authentication mode), to
 * check the request chains: link params -> auth -> bond params -> configuration -> capabilities
 * (incl. expand capabilities) -> monitoring settings -> READY -> data sync (minute data, sleep
 * messages, sleep "sequence" file over service 0x2C) -> RR interval test.
 * <p>
 * The same band can also run in HiChain mode (deviceSupportType 4, what the real Band 10 uses):
 * security negotiation -> PIN -> HiChain bind (op 1) -> HiChain auth (op 2) -> encrypted traffic.
 * {@link BandServer} exposes that band over stdin/stdout for clients in other languages.
 */
public class HarnessMain {
    static final String MAC = "AA:BB:CC:DD:EE:FF";
    static final Handler main = new Handler();
    static int failures = 0;

    static void check(boolean cond, String what) {
        System.out.println((cond ? "  PASS " : "  FAIL ") + what);
        if (!cond) failures++;
    }

    /** Expand capability bitmap with the given bit numbers set. */
    static byte[] expandCaps(int... bits) {
        byte[] b = new byte[32];
        for (int bit : bits) b[bit / 8] |= (byte) (1 << (bit % 8));
        return b;
    }

    static class StressPacket {
        int index, status;
        Integer score, time;
        byte[] features;
    }

    // ---------------------------------------------------------------- fake band
    static class FakeBand implements HuaweiSupportProvider.Transport {
        final HuaweiPacket.ParamsProvider bp = new HuaweiPacket.ParamsProvider() {
            @Override public byte[] getIv() { // HiChain: 16-byte GCM nonce, from the seeded generator
                if (!hiChain) return super.getIv();
                byte[] iv = new byte[16];
                rnd.nextBytes(iv);
                return iv;
            }
        };
        HuaweiSupportProvider phone;
        HuaweiPacket partial = null;
        final byte[] serverNonce = new byte[16];
        final List<String> seen = new ArrayList<>();
        boolean connected = true;
        final int baseTs;
        int dropAfter = -1;       // simulate silence after N requests
        boolean dictSleep = true; // band supports the sleep "sequence" file
        byte[] sequenceFile;
        int stressSwitch = -1;    // last value of the automatic stress switch
        /** Every 0x20/0x09 the phone sent: status, score, features, time (null when absent) and its position in {@code seen}. */
        final List<StressPacket> stressPackets = new ArrayList<>();
        boolean rejectStress = false; // answer 0x20/0x09 with an error code instead of success
        int rrBeats = 70;             // RR intervals streamed after the phone opens the test (0 = band sends none)
        int rrMin = 860, rrSpan = 80; // each RR is rrMin + random(rrSpan) ms
        boolean rrRealtime = false;   // false: one beat per second; true: one beat per RR interval
        boolean workouts = true;  // band offers the workout (0x17) and sleep breathing (0x2D) services
        // ---- extra downloads (GPS/PDR/ECG/arrhythmia): what the band announces and how it answers
        int[] extraCaps = {};         // expand capability bits announced besides 143/199 (106 ecg, 113 arrhythmia)
        boolean gpsNewSync = false;   // 0x01/0x31 bit 8
        int trajectoryTag = -1;       // tag 0x0e of the workout list: -1 absent, 0/1 value
        int workoutCount = 1;         // workouts offered (numbers 1..n, 2 hours apart)
        /** "gps", "pdr", "ecg", "arr" -> "ok" | "empty" | "e144001" | "error" | "silent" (no answer to the init) | "hang" (init ok, then silence). */
        final Map<String, String> fileMode = new HashMap<>();
        /** Every file init the phone sent: {key, name, start, end}, in order, and the index in {@code seen} it had. */
        final List<String[]> fileInits = new ArrayList<>();
        final List<Integer> fileInitAt = new ArrayList<>();
        byte[] curFile = null;
        String curKey = null;
        int pushDuringSync = -1;      // when >= 0, the band pushes 0x07/0x0f with this one-byte action at the first sleep count
        boolean pushed = false;
        boolean initWith7f = false;   // a normal file init answer that also carries the plain success code 0x7f = 100000
        int sleepBreath = -1;     // last value of the sleep breathing switch
        int liftWrist = -1;       // last value of "raise to wake"
        int wearLocation = -1;    // last wrist sent (1 = left)
        final String mac;

        // HiChain mode (deviceSupportType 4); off unless useHiChain() is called
        boolean hiChain = false;
        int authVersion = 2, encryptMethod = 0, bondState = 0, mtu = 0xF4, slice = 0xF4;
        boolean knownOnly = false;     // commands outside KNOWN are errors and get no answer (BandServer)
        boolean parity = false;        // compare each auth request with the bytes the Java client would send
        Random rnd = new Random(1);    // band-side randomness: nonces, PIN, authToken, IVs
        byte[] bandAuthId;             // the band's HiChain identity (peerAuthId it sends)
        /** Trust store: hex of the client's authId -> authToken from the bind. Survives disconnects. */
        final Map<String, byte[]> trust = new HashMap<>();
        HiChainSession hc;             // per connection
        byte[] pin;                    // per connection, from 0x2C
        byte[] negotiatedAuthId;       // per connection, tag 0x05 of 0x33
        boolean authenticated;         // per connection: op 2 finished, bp holds the session key
        final List<String> hiChainSteps = new ArrayList<>(); // "op/step" the band accepted
        final List<String> errors = new ArrayList<>();       // band-side protocol errors
        Consumer<byte[]> sink;         // frames to the phone (null: post them to the phone in 20-byte parts)
        Consumer<String> logSink, errSink;
        private final ByteArrayOutputStream rx = new ByteArrayOutputStream(); // unframed bytes (HiChain mode)
        private final ByteArrayOutputStream rawTlv = new ByteArrayOutputStream(); // TLV of the packet being received, as sent

        /** Server side of one HiChain operation (op 1 bind or op 2 auth); names from the band's view. */
        static class HiChainSession {
            int op, nextStep = 1;
            byte[] clientAuthId, clientRand, bandRand, psk, sessionKey, authToken;
        }

        /** Commands a knownOnly band answers: auth, the Java client's init chain, sync. */
        static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
                "01/01", "01/33", "01/2c", "01/28",                                      // authentication
                "01/07", "01/05", "01/08", "01/02", "01/03", "01/37", "01/31", "01/09", "01/1a",
                "01/30", "01/35", "01/32", "01/04", "02/05", "02/02",                    // plain acks
                "07/07", "07/0e", "07/16", "07/23", "07/17", "07/1c", "07/24", "2d/01",
                "07/0a", "07/0b", "07/0c", "07/0d", "07/03",                             // sync
                "2c/01", "2c/03", "2c/04", "2c/06", "17/07", "17/08", "17/0a", "19/01", "20/09"));

        static final Map<String, String> NAMES = new HashMap<>();
        static {
            NAMES.put("01/01", "link params");
            NAMES.put("01/33", "security negotiation");
            NAMES.put("01/2c", "PIN code");
            NAMES.put("01/28", "HiChain");
            NAMES.put("01/07", "product info");
            NAMES.put("01/05", "time");
            NAMES.put("01/08", "battery");
            NAMES.put("01/02", "supported services");
            NAMES.put("01/03", "supported commands");
        }

        static String key(int s, int c) { return String.format("%02x/%02x", s & 0xff, c & 0xff); }

        FakeBand(int baseTs) { this(baseTs, MAC); }

        FakeBand(int baseTs, String mac) {
            this.baseTs = baseTs;
            this.mac = mac;
            new Random(1).nextBytes(serverNonce);
            bp.setSliceSize(0xF4);
            bp.setMtu(0xF4);
            bp.setTransactionsCrypted(true);
            sequenceFile = buildSequenceFile(baseTs);
        }

        @Override public void write(byte[] data) {
            if (hiChain) {
                feed(data);
                return;
            }
            try {
                partial = partial == null ? new HuaweiPacket(bp).parse(data) : partial.parse(data);
            } catch (HuaweiPacket.ParseException e) {
                throw new RuntimeException("band could not parse phone packet", e);
            }
            if (!partial.complete) return;
            HuaweiPacket req = partial;
            partial = null;
            String key = String.format("%02x/%02x", req.serviceId & 0xff, req.commandId & 0xff);
            seen.add(key);
            if (dropAfter >= 0 && seen.size() > dropAfter) return;
            try {
                handle(req);
            } catch (Exception e) {
                throw new RuntimeException("band failed handling " + key, e);
            }
        }

        @Override public int getMaxWriteSize() { return 20; } // force fragmentation
        @Override public boolean isConnected() { return connected; }
        @Override public void disconnect() {
            if (!connected) return;
            connected = false;
            main.post(() -> phone.onDisconnected());
        }

        void deliver(byte[] packet) {
            if (sink != null) {
                sink.accept(packet);
                return;
            }
            for (int o = 0; o < packet.length; o += 20) {
                int len = Math.min(20, packet.length - o);
                byte[] part = new byte[len];
                System.arraycopy(packet, o, part, 0, len);
                main.postDelayed(() -> phone.onDataReceived(part), 5);
            }
        }

        void reply(int service, int command, HuaweiTLV tlv, boolean encrypted) throws Exception {
            HuaweiPacket p = new HuaweiPacket(bp);
            p.serviceId = (byte) service;
            p.commandId = (byte) command;
            p.setTlv(tlv);
            p.setEncryption(encrypted);
            p.complete = true;
            for (byte[] packet : p.serialize()) deliver(packet);
        }

        /** Unencrypted, non-TLV frame (used by 0x2C/0x05 file blocks). */
        void rawFrame(int service, int command, byte[] body) {
            ByteBuffer b = ByteBuffer.allocate(4 + 2 + body.length);
            b.put((byte) 0x5A).putShort((short) (2 + body.length + 1)).put((byte) 0x00)
                    .put((byte) service).put((byte) command).put(body);
            int crc = CheckSums.getCRC16(b.array(), 0x0000);
            ByteBuffer f = ByteBuffer.allocate(b.capacity() + 2);
            f.put(b.array()).putShort((short) crc);
            deliver(f.array());
        }

        void handle(HuaweiPacket req) throws Exception {
            HuaweiTLV t = req.getTlv();
            int s = req.serviceId & 0xff, c = req.commandId & 0xff;
            if (hiChain && handleHiChainMode(s, c, t)) return;
            if (s == 0x01) {
                switch (c) {
                    case 0x01: { // link params
                        byte[] v5 = new byte[18];
                        v5[0] = 0x00; v5[1] = 0x01; // authVersion 1
                        System.arraycopy(serverNonce, 0, v5, 2, 16);
                        reply(1, 1, new HuaweiTLV().put(0x01, (byte) 2).put(0x02, (short) 0xF4).put(0x03, (short) 0xF4)
                                .put(0x04, (short) 0).put(0x05, v5).put(0x07, (byte) 0).put(0x08, (byte) 0)
                                .put(0x09, (byte) 1).put(0x0C, (byte) 0), false);
                        return;
                    }
                    case 0x13: { // auth
                        byte[] nonce = t.getBytes(0x02);
                        byte[] clientNonce = new byte[16];
                        System.arraycopy(nonce, 2, clientNonce, 0, 16);
                        byte[] doubleNonce = ByteBuffer.allocate(32).put(serverNonce).put(clientNonce).array();
                        HuaweiCrypto crypto = new HuaweiCrypto((byte) 1, (byte) 0, (byte) 0, (byte) 0);
                        byte[] expectedChallenge = new byte[32];
                        ByteBuffer.wrap(crypto.digestChallenge(null, doubleNonce)).get(expectedChallenge);
                        check(java.util.Arrays.equals(expectedChallenge, t.getBytes(0x01)), "band accepts the phone challenge");
                        byte[] resp = new byte[32];
                        ByteBuffer.wrap(crypto.digestResponse(null, doubleNonce)).get(resp);
                        reply(1, 0x13, new HuaweiTLV().put(0x01, resp), false);
                        return;
                    }
                    case 0x0F: { // bond params: already bonded
                        reply(1, 0x0F, new HuaweiTLV().put(0x01, (byte) 1).put(0x09, 5), false);
                        String hex = new Context().getSharedPreferences("huawei_dev_" + mac.replace(":", ""), 0).getString("authkey", null);
                        bp.setSecretKey(GB.hexStringToByteArray(hex));
                        return;
                    }
                    case 0x07: // product info
                        reply(1, 7, new HuaweiTLV().put(0x03, "HW-1").put(0x07, "1.0.0.30").put(0x0A, "Band 10 "), true);
                        return;
                    case 0x05: // time
                        reply(1, 5, new HuaweiTLV().put(0x01, (int) (System.currentTimeMillis() / 1000) - 120), true);
                        return;
                    case 0x08: // battery
                        reply(1, 8, new HuaweiTLV().put(0x01, (byte) 77), true);
                        return;
                    case 0x02: { // supported services: 0x07 fitness, 0x19 RR test, 0x20 stress
                        byte[] known = DeviceConfig.SupportedServices.knownSupportedServices;
                        byte[] flags = new byte[known.length];
                        for (int i = 0; i < known.length; i++)
                            flags[i] = (byte) ((known[i] == 0x07 || known[i] == 0x19 || known[i] == 0x20
                                    || (workouts && (known[i] == 0x17 || known[i] == 0x2D))) ? 1 : 0);
                        reply(1, 2, new HuaweiTLV().put(0x02, flags), true);
                        return;
                    }
                    case 0x03: { // supported commands: everything asked
                        HuaweiTLV container = t.getObject(0x81);
                        HuaweiTLV out = new HuaweiTLV();
                        int service = -1;
                        for (HuaweiTLV.TLV x : container.get()) {
                            if (x.getTag() == 0x02) service = x.getValue()[0];
                            else if (x.getTag() == 0x03) {
                                byte[] cmds = x.getValue();
                                byte[] f = new byte[cmds.length];
                                for (int i = 0; i < cmds.length; i++) f[i] = 1;
                                out.put(0x02, (byte) service).put(0x04, f);
                            }
                        }
                        reply(1, 3, new HuaweiTLV().put(0x81, out), true);
                        return;
                    }
                    case 0x37: // expand capabilities
                    {
                        int[] bits = new int[(dictSleep ? 2 : 0) + extraCaps.length];
                        int k = 0;
                        if (dictSleep) { bits[k++] = 143; bits[k++] = 199; }
                        for (int b : extraCaps) bits[k++] = b;
                        reply(1, 0x37, new HuaweiTLV().put(0x01, expandCaps(bits)), true);
                    }
                        return;
                    case 0x09: // raise to wake
                        liftWrist = t.getByte(0x01);
                        reply(s, c, new HuaweiTLV().put(0x7F, 0x186A0), true);
                        return;
                    case 0x1a: // wear location
                        wearLocation = t.getByte(0x01);
                        reply(s, c, new HuaweiTLV().put(0x7F, 0x186A0), true);
                        return;
                    case 0x31: // setting related: TruSleep and RRI use the new (0x2C) file sync
                        reply(1, 0x31, new HuaweiTLV().put(0x02, (byte) (2 | 4 | (gpsNewSync ? 8 : 0))), true);
                        return;
                    default: // agreements etc.: plain success
                        reply(s, c, new HuaweiTLV().put(0x7F, 0x186A0), true);
                        return;
                }
            }
            if (s == 0x07) {
                switch (c) {
                    case 0x0C: // sleep count
                        if (pushDuringSync >= 0 && !pushed) {
                            pushed = true;
                            reply(7, 0x0F, new HuaweiTLV().put(0x01, (byte) pushDuringSync), true);
                        }
                        reply(7, c, new HuaweiTLV().put(0x81, new HuaweiTLV().put(0x02, (short) 2)), true);
                        return;
                    case 0x0A: // step count
                        reply(7, c, new HuaweiTLV().put(0x81, new HuaweiTLV().put(0x02, (short) 2)), true);
                        return;
                    case 0x0D: { // sleep data: one 7h segment per message
                        short idx = t.getObject(0x81).getShort(0x02);
                        int start = baseTs + idx * 86400;
                        byte[] ts = ByteBuffer.allocate(6).putInt(start).putShort((short) 420).array();
                        reply(7, 0x0D, new HuaweiTLV().put(0x81, new HuaweiTLV().put(0x02, idx)
                                .put(0x83, new HuaweiTLV().put(0x04, (byte) 0x06).put(0x05, ts))), true);
                        return;
                    }
                    case 0x0B: { // step data: 3 minutes per message (steps, HR, SpO2)
                        short idx = t.getObject(0x81).getShort(0x02);
                        HuaweiTLV container = new HuaweiTLV().put(0x02, idx).put(0x03, baseTs + idx * 3600);
                        for (int m = 0; m < 3; m++) {
                            byte[] data = new byte[]{(byte) 0xC2, 0x01, 0x00, (byte) (80 + m), (byte) 150, (byte) 96};
                            container.put(0x84, new HuaweiTLV().put(0x05, (byte) m).put(0x06, data));
                        }
                        reply(7, 0x0B, new HuaweiTLV().put(0x81, container), true);
                        return;
                    }
                    case 0x03: // totals
                        reply(7, 3, new HuaweiTLV().put(0x81, new HuaweiTLV().put(0x83, new HuaweiTLV().put(0x05, 4321))), true);
                        return;
                    case 0x17: // automatic HR (phone does not wait for an answer)
                    case 0x1c: // realtime HR
                    case 0x24: // automatic SpO2
                        return;
                    default:
                        reply(s, c, new HuaweiTLV().put(0x7F, 0x186A0), true);
                        return;
                }
            }
            if (s == 0x2c) { // new file download service
                switch (c) {
                    case 0x01: {
                        String name = t.getString(0x01);
                        byte type = t.getByte(0x02);
                        String key = type == 0x11 ? "gps" : type == 0x12 ? "pdr" : type == 0x18 ? "ecg"
                                : (type == 0x16 && t.contains(0x0c) && t.getInteger(0x0c) == 700004) ? "arr" : null;
                        fileInits.add(new String[]{key == null ? "other" : key, name, String.valueOf(t.contains(0x05) ? t.getInteger(0x05) : 0),
                                String.valueOf(t.contains(0x06) ? t.getInteger(0x06) : 0)});
                        fileInitAt.add(seen.size() - 1);
                        curKey = key;
                        int size;
                        if (key == null) {
                            curFile = sequenceFile;
                            size = type == 0x16 ? sequenceFile.length : 0;
                        } else {
                            String mode = fileMode.getOrDefault(key, "empty");
                            if (mode.equals("silent")) return;
                            if (mode.equals("e144001") || mode.equals("error")) {
                                reply(0x2c, 0x01, new HuaweiTLV().put(0x7F, mode.equals("e144001") ? 144001 : 100001), true);
                                return;
                            }
                            if (mode.equals("ok") || mode.equals("hang") || mode.equals("slow")) {
                                curFile = new byte[300];
                                for (int i = 0; i < curFile.length; i++) curFile[i] = (byte) (i * 7 + key.length() + name.hashCode()); // differs per file name, like real data
                                size = curFile.length;
                            } else {
                                curFile = new byte[0];
                                size = 0;
                            }
                        }
                        HuaweiTLV initAnswer = new HuaweiTLV().put(0x01, name).put(0x02, type).put(0x03, (byte) 7).put(0x04, size);
                        if (initWith7f) initAnswer.put(0x7F, 100000);
                        reply(0x2c, 0x01, initAnswer, true);
                        return;
                    }
                    case 0x03: // file info: 5 s timeout, 64-byte bursts, 128-byte blocks, unencrypted blocks
                        if (curKey != null && "hang".equals(fileMode.get(curKey))) return; // the band goes silent after the init
                        if (curKey != null && "slow".equals(fileMode.get(curKey))) { // answers, but only after 50 s
                            main.postDelayed(() -> {
                                try {
                                    reply(0x2c, 0x03, new HuaweiTLV().put(0x01, (byte) 7).put(0x02, (byte) 5).put(0x03, (short) 64)
                                            .put(0x04, 128).put(0x05, true), true);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }, 50000);
                            return;
                        }
                        reply(0x2c, 0x03, new HuaweiTLV().put(0x01, (byte) 7).put(0x02, (byte) 5).put(0x03, (short) 64)
                                .put(0x04, 128).put(0x05, true), true);
                        return;
                    case 0x04: { // block request -> raw 0x2C/0x05 bursts
                        int offset = t.getInteger(0x02);
                        int size = t.getInteger(0x03);
                        for (int o = offset; o < offset + size; o += 64) {
                            int len = Math.min(64, offset + size - o);
                            ByteBuffer body = ByteBuffer.allocate(6 + len);
                            byte[] src = curFile != null ? curFile : sequenceFile;
                            body.put((byte) 7).putInt(o).put((byte) 0).put(src, o, len);
                            rawFrame(0x2c, 0x05, body.array());
                        }
                        return;
                    }
                    case 0x06: // complete
                        return;
                    default:
                        return;
                }
            }
            if (s == 0x19) { // RR interval test
                if (c == 0x01) {
                    byte type = t.getByte(0x01);
                    if (type == 3) startRrStream();
                }
                return;
            }
            if (s == 0x20) { // stress switch (+ baseline when switching on)
                stressSwitch = t.getByte(0x01);
                StressPacket p = new StressPacket();
                p.index = seen.size() - 1;
                p.status = stressSwitch;
                if (t.contains(0x02)) p.score = t.getByte(0x02) & 0xff;
                if (t.contains(0x03)) p.features = t.getBytes(0x03);
                if (t.contains(0x04)) p.time = t.getInteger(0x04);
                stressPackets.add(p);
                reply(0x20, c, new HuaweiTLV().put(0x7F, rejectStress ? 0x1C139 : 0x186A0), true);
                return;
            }
            if (s == 0x2d) { // sleep breathing switch (no answer)
                sleepBreath = t.getByte(0x01);
                return;
            }
            if (s == 0x17) { // workouts: one 10-sample workout in two data blocks
                int wStart = baseTs + 86400 + 18 * 3600;
                switch (c) {
                    case 0x07: {
                        int from = t.getObject(0x81).getInteger(0x03); // like the real band: only workouts that start after the asked time
                        int shown = 0;
                        for (int i = 1; i <= workoutCount; i++) if (wStart + (i - 1) * 7200 >= from) shown++;
                        HuaweiTLV container = new HuaweiTLV().put(0x02, (short) shown);
                        for (int i = 1; i <= workoutCount; i++) {
                            if (wStart + (i - 1) * 7200 < from) continue;
                            HuaweiTLV w = new HuaweiTLV().put(0x06, (short) i).put(0x07, (short) 2).put(0x08, (short) 0);
                            if (trajectoryTag >= 0) w.put(0x0e, (byte) trajectoryTag);
                            container.put(0x85, w);
                        }
                        reply(0x17, 0x07, new HuaweiTLV().put(0x81, container), true);
                        return;
                    }
                    case 0x08: {
                        short num = t.getObject(0x81).getShort(0x02);
                        int st = wStart + (num - 1) * 7200;
                        reply(0x17, 0x08, new HuaweiTLV().put(0x81, new HuaweiTLV().put(0x02, num).put(0x03, (byte) 1)
                                .put(0x04, st).put(0x05, st + 1800).put(0x06, 250).put(0x07, 4200).put(0x08, 5100)
                                .put(0x09, 1800).put(0x0c, new byte[]{(byte) 95, (byte) 172}).put(0x12, 1790).put(0x14, (byte) 1)), true);
                        return;
                    }
                    case 0x0a: {
                        short n = t.getObject(0x81).getShort(0x03);
                        ByteBuffer h = ByteBuffer.allocate(14);
                        h.putShort((short) 1).putShort(n).putInt(wStart + n * 50).put((byte) 10).putShort((short) 5).put((byte) 3).putShort((short) 0x03);
                        ByteBuffer d = ByteBuffer.allocate(15);
                        for (int i = 0; i < 5; i++) d.put((byte) (140 + n * 5 + i)).putShort((short) (250 + i));
                        reply(0x17, 0x0a, new HuaweiTLV().put(0x81, new HuaweiTLV().put(0x02, (short) 1).put(0x03, n)
                                .put(0x04, h.array()).put(0x05, d.array())), true);
                        return;
                    }
                    default:
                        reply(s, c, new HuaweiTLV().put(0x7F, 0x186A0), true);
                        return;
                }
            }
            reply(s, c, new HuaweiTLV().put(0x7F, 0x186A0), true);
        }

        /** Sends {@code rrBeats} RR intervals (asynchronous 0x19/0x05 packets), one per second or per interval. */
        void startRrStream() {
            final Random r = new Random(7);
            long at = 0;
            for (int beat = 1; beat <= rrBeats; beat++) {
                final int rr = rrMin + r.nextInt(rrSpan);
                at += rrRealtime ? rr : 1000;
                main.postDelayed(() -> {
                    try {
                        HuaweiTLV container = new HuaweiTLV();
                        container.put(0x83, new HuaweiTLV().put(0x04, (short) rr).put(0x05, (byte) 100));
                        reply(0x19, 0x05, new HuaweiTLV().put(0x82, container), true);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, at);
            }
        }

        // ------------------------------------------------------------ HiChain mode

        // HiChain errorCode values: simulated, the real Band 10 codes are unknown
        static final int ERR_UNKNOWN_CLIENT = 0xE001, ERR_TOKEN = 0xE002, ERR_DECRYPT = 0xE003,
                ERR_STEP = 0xE004, ERR_BAD_MESSAGE = 0xE005, ERR_NO_PIN = 0xE006;

        boolean lastEncrypted; // whether the request being handled came in encrypted (tag 0x7C first)

        /** Switches this band to HiChain authentication (deviceSupportType 4: GCM with 16-byte IVs after auth). */
        FakeBand useHiChain(int authVersion, int encryptMethod) {
            hiChain = true;
            this.authVersion = authVersion;
            this.encryptMethod = encryptMethod;
            bp.setDeviceSupportType((byte) 4);
            bp.setAuthVersion((byte) authVersion);
            bp.setEncryptMethod((byte) encryptMethod);
            bandAuthId = new byte[32];
            new Random(2).nextBytes(bandAuthId); // fixed identity, the same on every connection
            return this;
        }

        void log(String msg) {
            if (logSink != null) logSink.accept(msg);
        }

        void error(String msg) {
            errors.add(msg);
            if (errSink != null) errSink.accept(msg);
        }

        /** New link params: whatever was negotiated on this link is gone (the trust store stays). */
        void resetSession() {
            hc = null;
            pin = null;
            negotiatedAuthId = null;
            authenticated = false;
            bp.setSecretKey(null);
        }

        /** Phone disconnected: also drop partially received frames. */
        void resetConnection() {
            resetSession();
            rx.reset();
            partial = null;
        }

        /** Harness: the same band accepts a new connection (fresh request log). */
        void reconnect() {
            resetConnection();
            connected = true;
            seen.clear();
            hiChainSteps.clear();
            errors.clear();
        }

        /** Reassembles Huawei frames from writes cut anywhere (several frames in one write, one frame over many). */
        void feed(byte[] data) {
            rx.write(data, 0, data.length);
            byte[] buf = rx.toByteArray();
            int pos = 0;
            while (buf.length - pos >= 3) {
                if (buf[pos] != 0x5A) {
                    int next = pos + 1;
                    while (next < buf.length && buf[next] != 0x5A) next++;
                    error("no 0x5A magic, dropped " + (next - pos) + " byte(s): " + GB.hexdump(buf, pos, next - pos));
                    pos = next;
                    partial = null;
                    continue;
                }
                int len = ((buf[pos + 1] & 0xff) << 8) | (buf[pos + 2] & 0xff);
                if (len < 3 || len > 0x7FFF) {
                    error("impossible frame length " + len + ", dropped the 0x5A byte");
                    pos++;
                    partial = null;
                    continue;
                }
                int total = 3 + len + 2; // magic + length, body, CRC
                if (buf.length - pos < total) break;
                byte[] frame = Arrays.copyOfRange(buf, pos, pos + total);
                pos += total;
                int crc = CheckSums.getCRC16(Arrays.copyOf(frame, total - 2), 0x0000) & 0xffff;
                int got = ((frame[total - 2] & 0xff) << 8) | (frame[total - 1] & 0xff);
                if (crc != got) {
                    error(String.format("bad CRC %04X (expected %04X), frame dropped: %s", got, crc, GB.hexdump(frame)));
                    partial = null;
                    continue;
                }
                onFrame(frame);
            }
            rx.reset();
            rx.write(buf, pos, buf.length - pos);
        }

        void onFrame(byte[] frame) {
            if (partial == null) rawTlv.reset();
            int from = frame[3] == 0 ? 6 : frame[3] == 1 ? 7 : 5; // unsliced / first slice (+ ids) / next slices
            if (frame.length - 2 > from) rawTlv.write(frame, from, frame.length - 2 - from);
            HuaweiPacket p;
            try {
                p = partial == null ? new HuaweiPacket(bp).parse(frame) : partial.parse(frame);
            } catch (HuaweiPacket.ParseException | RuntimeException e) {
                error("could not parse frame " + GB.hexdump(frame) + ": " + e);
                partial = null;
                return;
            }
            if (!p.complete) {
                partial = p;
                return;
            }
            partial = null;
            byte[] raw = rawTlv.toByteArray();
            lastEncrypted = raw.length > 0 && raw[0] == 0x7C; // the encrypted container starts with tag 0x7C
            String key = key(p.serviceId, p.commandId);
            seen.add(key);
            log("request " + key + (NAMES.containsKey(key) ? " (" + NAMES.get(key) + ")" : "") + (lastEncrypted ? ", encrypted" : ""));
            if (dropAfter >= 0 && seen.size() > dropAfter) return;
            if (parity) checkParity(key, p.getTlv(), raw);
            try {
                handle(p);
            } catch (Exception e) {
                error("band failed handling " + key + ": " + e);
            }
        }

        /** HiChain mode: authentication, gatekeeping and the Band 10 product info. True when the request was dealt with. */
        boolean handleHiChainMode(int s, int c, HuaweiTLV t) throws Exception {
            String key = key(s, c);
            if (knownOnly && !KNOWN.contains(key)) {
                error("unexpected command " + key + ", not answered");
                return true;
            }
            if (s == 0x01) {
                switch (c) {
                    case 0x01: linkParams(); return true;
                    case 0x33: securityNegotiation(t); return true;
                    case 0x2C: pinCode(); return true;
                    case 0x28: hiChainStep(t); return true;
                }
            }
            if (!authenticated) {
                error(key + " before HiChain authentication finished, not answered");
                return true;
            }
            if (s == 0x01 && (c == 0x0E || c == 0x0F || c == 0x13)) {
                error("normal-mode auth command " + key + " on a HiChain band, not answered");
                return true;
            }
            if (!lastEncrypted && !key.equals("2c/04")) // block requests are plain: our 0x2C/0x03 answer sets noEncrypt
                error(key + " sent in plaintext after authentication (answered anyway)");
            if (s == 0x01 && c == 0x07) { // product info as a Band 10 answers it
                reply(1, 7, new HuaweiTLV().put(0x07, "5.0.0.167(SIM)").put(0x09, "SIMB10000001")
                        .put(0x0A, "HUAWEI Band 10").put(0x11, "HUAWEI Band 10-SIM"), true);
                return true;
            }
            return false; // the normal-mode handlers
        }

        void linkParams() throws Exception {
            resetSession();
            rnd.nextBytes(serverNonce);
            bp.setSliceSize(slice);
            bp.setMtu(mtu);
            byte[] v5 = new byte[18];
            v5[1] = (byte) authVersion;
            System.arraycopy(serverNonce, 0, v5, 2, 16);
            reply(1, 1, new HuaweiTLV().put(0x01, (byte) 2).put(0x02, (short) slice).put(0x03, (short) mtu)
                    .put(0x04, (short) 0).put(0x05, v5).put(0x07, (byte) 4).put(0x08, (byte) 0)
                    .put(0x09, (byte) bondState).put(0x0C, (byte) encryptMethod), false);
        }

        void securityNegotiation(HuaweiTLV t) throws Exception {
            Byte mode = t.getByte(0x01, null);
            if (mode == null || mode != 4) {
                error("security negotiation with authMode " + mode + " (expected 4), not answered");
                return;
            }
            if (!t.contains(0x05)) {
                error("security negotiation without the client authId (tag 0x05), not answered");
                return;
            }
            negotiatedAuthId = t.getBytes(0x05);
            log("security negotiation: authMode 4, client authId " + printable(negotiatedAuthId));
            reply(1, 0x33, new HuaweiTLV().put(0x01, (byte) 4).put(0x02, (byte) 1), false); // authType 1 ^ 4 = 5: HiChain3
        }

        /** PIN as decryptPinCode expects it: key = digest secret of the auth version, GCM (12-byte IV) or CBC (16-byte IV). */
        void pinCode() throws Exception {
            pin = new byte[6];
            rnd.nextBytes(pin);
            byte[] key = authVersion == 1 || authVersion == 4 ? HuaweiCrypto.DIGEST_SECRET_v1
                    : authVersion == 2 ? HuaweiCrypto.DIGEST_SECRET_v2 : HuaweiCrypto.DIGEST_SECRET_v3;
            byte[] iv = new byte[encryptMethod == 1 ? 12 : 16];
            rnd.nextBytes(iv);
            byte[] enc = encryptMethod == 1 ? CryptoUtils.encryptAES_GCM_NoPad(pin, key, iv, null)
                    : CryptoUtils.encryptAES_CBC_Pad(pin, key, iv);
            log("PIN " + GB.hexdump(pin) + " sent " + (encryptMethod == 1 ? "AES-GCM" : "AES-CBC") + " encrypted (authVersion " + authVersion + " digest secret)");
            reply(1, 0x2C, new HuaweiTLV().put(0x01, enc).put(0x02, iv), false);
        }

        /** One HiChain ISO message (op 1 bind: steps 1-4; op 2 auth: steps 1, 2, 4), routed by its payload fields. */
        void hiChainStep(HuaweiTLV t) throws Exception {
            int op = t.getByte(0x02, (byte) 0);
            JSONObject p;
            int message;
            try {
                JSONObject value = new JSONObject(t.getString(0x01));
                p = value.getJSONObject("payload");
                message = value.optInt("message", 0);
            } catch (Exception e) {
                hiChainFail(op, 0, ERR_BAD_MESSAGE, "tag 0x01 is not a {payload, message} JSON: " + e);
                return;
            }
            int step = p.has("seed") ? 1 : p.has("encData") ? 3 : p.has("encResult") ? 4 : p.has("token") ? 2 : 0;
            log(String.format("HiChain op %d step %d (message 0x%02X)", op, step, message));
            if (op != 1 && op != 2) {
                hiChainFail(op, message, ERR_BAD_MESSAGE, "unknown operation code " + op);
                return;
            }
            if (step == 0) {
                hiChainFail(op, message, ERR_BAD_MESSAGE, "payload has none of seed / token / encData / encResult");
                return;
            }
            if (p.has("operationCode") && p.optInt("operationCode", -1) != op) {
                hiChainFail(op, message, ERR_BAD_MESSAGE, "payload operationCode " + p.opt("operationCode") + " differs from tag 0x02 (" + op + ")");
                return;
            }
            if (step == 1) {
                hc = new HiChainSession();
                hc.op = op;
            } else if (hc == null || hc.op != op || hc.nextStep != step) {
                hiChainFail(op, message, ERR_STEP, "unexpected step " + step + " of op " + op
                        + (hc == null ? " (no step 1 on this link)" : " (expected op " + hc.op + " step " + hc.nextStep + ")"));
                return;
            }
            try {
                if (step == 1) isoStart(p, message);
                else if (step == 2) isoToken(p, message);
                else if (step == 3) isoExchange(p, message);
                else isoResult(p, message);
            } catch (IllegalArgumentException e) {
                hiChainFail(op, message, ERR_BAD_MESSAGE, e.getMessage());
            }
        }

        /** Step 1: psk = HMAC(key, seed), key = SHA-256 of the PIN's uppercase hex (op 1) or the trusted authToken (op 2). */
        void isoStart(JSONObject p, int message) throws Exception {
            hc.clientAuthId = field(p, "peerAuthId");
            hc.clientRand = field(p, "isoSalt");
            byte[] seed = field(p, "seed");
            if (negotiatedAuthId != null && !Arrays.equals(negotiatedAuthId, hc.clientAuthId))
                error("HiChain peerAuthId " + printable(hc.clientAuthId) + " differs from the 0x33 authId " + printable(negotiatedAuthId));
            byte[] key;
            if (hc.op == 1) {
                if (pin == null) {
                    hiChainFail(1, message, ERR_NO_PIN, "bind (op 1) without a PIN: 0x2C was not requested on this link");
                    return;
                }
                key = CryptoUtils.digest(GB.hexdump(pin).getBytes(StandardCharsets.UTF_8));
            } else {
                key = trust.get(GB.hexdump(hc.clientAuthId));
                if (key == null) {
                    hiChainFail(2, message, ERR_UNKNOWN_CLIENT, "auth (op 2) from client " + printable(hc.clientAuthId)
                            + " that the band does not trust (formatted band, or paired with another phone)");
                    return;
                }
            }
            hc.psk = CryptoUtils.calcHmacSha256(key, seed);
            hc.bandRand = new byte[16];
            rnd.nextBytes(hc.bandRand);
            byte[] token = CryptoUtils.calcHmacSha256(hc.psk, concat(hc.bandRand, hc.clientRand, hc.clientAuthId, bandAuthId));
            hiChainReply(message, new JSONObject().put("isoSalt", GB.hexdump(hc.bandRand)).put("peerAuthId", GB.hexdump(bandAuthId))
                    .put("peerUserType", 0).put("token", GB.hexdump(token)));
            accepted(1, 2);
        }

        /** Step 2: check the client's token, answer returnCodeMac, derive the ISO session key. */
        void isoToken(JSONObject p, int message) throws Exception {
            byte[] token = field(p, "token");
            if (p.has("peerAuthId") && !Arrays.equals(field(p, "peerAuthId"), hc.clientAuthId)) {
                hiChainFail(hc.op, message, ERR_BAD_MESSAGE, "step 2 peerAuthId differs from step 1");
                return;
            }
            byte[] expected = CryptoUtils.calcHmacSha256(hc.psk, concat(hc.clientRand, hc.bandRand, bandAuthId, hc.clientAuthId));
            if (!Arrays.equals(token, expected)) {
                hiChainFail(hc.op, message, ERR_TOKEN, "client token mismatch in step 2 (wrong PIN/key, or wrong field order)");
                return;
            }
            hc.sessionKey = CryptoUtils.hkdfSha256(hc.psk, concat(hc.clientRand, hc.bandRand),
                    "hichain_iso_session_key".getBytes(StandardCharsets.UTF_8), 32);
            hiChainReply(message, new JSONObject().put("returnCodeMac", GB.hexdump(CryptoUtils.calcHmacSha256(hc.psk, new byte[4]))));
            accepted(2, hc.op == 1 ? 3 : 4);
        }

        /** Step 3 (bind only): decrypt the challenge, hand out a fresh authToken encrypted with aad = challenge. */
        void isoExchange(JSONObject p, int message) throws Exception {
            byte[] challenge;
            try {
                challenge = CryptoUtils.decryptAES_GCM_NoPad(field(p, "encData"), hc.sessionKey, field(p, "nonce"),
                        "hichain_iso_exchange".getBytes(StandardCharsets.UTF_8));
            } catch (java.security.GeneralSecurityException e) {
                hiChainFail(hc.op, message, ERR_DECRYPT, "step 3 encData does not decrypt with the session key: " + e);
                return;
            }
            hc.authToken = new byte[32];
            rnd.nextBytes(hc.authToken);
            byte[] nonce = new byte[12];
            rnd.nextBytes(nonce);
            byte[] enc = CryptoUtils.encryptAES_GCM_NoPad(hc.authToken, hc.sessionKey, nonce, challenge);
            hiChainReply(message, new JSONObject().put("nonce", GB.hexdump(nonce)).put("encAuthToken", GB.hexdump(enc)));
            accepted(3, 4);
        }

        /** Step 4: encResult must be 00000000. Bind: trust the client. Auth: derive the link key and encrypt from now on. */
        void isoResult(JSONObject p, int message) throws Exception {
            byte[] result;
            try {
                result = CryptoUtils.decryptAES_GCM_NoPad(field(p, "encResult"), hc.sessionKey, field(p, "nonce"),
                        "hichain_iso_result".getBytes(StandardCharsets.UTF_8));
            } catch (java.security.GeneralSecurityException e) {
                hiChainFail(hc.op, message, ERR_DECRYPT, "step 4 encResult does not decrypt with the session key: " + e);
                return;
            }
            if (!Arrays.equals(result, new byte[4])) {
                hiChainFail(hc.op, message, ERR_DECRYPT, "step 4 result is " + GB.hexdump(result) + ", expected 00000000");
                return;
            }
            HiChainSession done = hc;
            hc = null;
            accepted(done, 4);
            // The client takes any type != 0 (tag 0x04) as the end of the operation; tag 0x01 is a free string.
            reply(1, 0x28, new HuaweiTLV().put(0x01, "op " + done.op + " ok").put(0x04, (byte) 1), false);
            if (done.op == 1) {
                trust.put(GB.hexdump(done.clientAuthId), done.authToken);
                log("bind done, client " + printable(done.clientAuthId) + " trusted (" + trust.size() + " in the trust store)");
            } else {
                byte[] key = CryptoUtils.hkdfSha256(done.sessionKey, concat(done.clientRand, done.bandRand),
                        "hichain_return_key".getBytes(StandardCharsets.UTF_8), 32);
                bp.setSecretKey(key);
                authenticated = true;
                log("authenticated, session key " + GB.hexdump(key) + " (AES-256-GCM, 16-byte IV)");
            }
        }

        void accepted(int step, int next) {
            accepted(hc, step);
            hc.nextStep = next;
        }

        void accepted(HiChainSession s, int step) {
            hiChainSteps.add(s.op + "/" + step);
            log("HiChain op " + s.op + " step " + step + " ok");
        }

        /** JSON reply of a step: tag 0x01 = {"payload":{...},"message":0x8000|n}, tag 0x04 = 0 (JSON), sliced. */
        void hiChainReply(int message, JSONObject payload) throws Exception {
            JSONObject value = new JSONObject().put("payload", payload).put("message", 0x8000 | message);
            reply(1, 0x28, new HuaweiTLV().put(0x01, value.toString()).put(0x04, (byte) 0), false);
        }

        /** Refuses the operation: a step payload holding only a (simulated) non-zero errorCode. */
        void hiChainFail(int op, int message, int code, String why) throws Exception {
            error("HiChain op " + op + ": " + why);
            hc = null;
            hiChainReply(message, new JSONObject().put("errorCode", code));
        }

        /**
         * Byte parity: rebuilds what the Java client sends for this request, with the same variable values
         * (ids, nonces, tokens taken from the request itself), and compares the TLV bytes as received
         * (decrypted for product info). JSON key order is part of it. A mismatch is reported, not refused.
         */
        void checkParity(String key, HuaweiTLV t, byte[] got) {
            byte[] expected;
            try {
                switch (key) {
                    case "01/01":
                        expected = new DeviceConfig.LinkParams.Request(bp, HuaweiCoordinator.HuaweiDeviceType.BLE).getTlv().serialize();
                        break;
                    case "01/33": {
                        byte mode = t.getByte(0x01, (byte) 0);
                        String model = t.contains(0x07) ? t.getString(0x07) : null;
                        expected = new DeviceConfig.SecurityNegotiation.Request(bp, mode, t.getBytes(0x05, null), model).getTlv().serialize();
                        break;
                    }
                    case "01/2c":
                        expected = new DeviceConfig.PinCode.Request(bp).getTlv().serialize();
                        break;
                    case "01/28":
                        expected = javaHiChainStep(t);
                        break;
                    case "01/07":
                        if (!authenticated) return;
                        if (lastEncrypted) got = new HuaweiTLV().parse(got).decryptRaw(bp);
                        expected = new DeviceConfig.ProductInfo.Request(bp, HuaweiCoordinator.HuaweiDeviceType.BLE).getTlv().serialize();
                        break;
                    default:
                        return;
                }
            } catch (Exception e) {
                error("parity " + key + ": could not rebuild the Java client's request: " + e);
                return;
            }
            if (expected != null && !Arrays.equals(expected, got))
                error("parity " + key + ": expected " + GB.hexdump(expected) + " got " + GB.hexdump(got));
        }

        /** GetHiChainRequest's packet for this step; messageId as the Java client builds it (op 2 step 4 uses 3). */
        byte[] javaHiChainStep(HuaweiTLV t) throws Exception {
            int op = t.getByte(0x02);
            long requestId = ByteBuffer.wrap(t.getBytes(0x03)).getLong();
            JSONObject value = new JSONObject(t.getString(0x01));
            JSONObject p = value.getJSONObject("payload");
            // selfAuthId: steps 1-2 carry it as payload.peerAuthId; steps 3-4 do not, so take step 1's
            // (op 1 still serializes it as peerDeviceId/connDeviceId; op 2 steps 3-4 do not use it at all)
            byte[] selfAuthId = p.has("peerAuthId") ? GB.hexStringToByteArray(p.getString("peerAuthId"))
                    : hc != null && hc.op == op ? hc.clientAuthId
                    : value.has("peerDeviceId") ? value.getString("peerDeviceId").getBytes(StandardCharsets.UTF_8)
                    : negotiatedAuthId != null ? negotiatedAuthId : new byte[0];
            DeviceConfig.HiChain.Request r = new DeviceConfig.HiChain.Request(op, requestId, selfAuthId, HuaweiConstants.GROUP_ID);
            HuaweiPacket step;
            if (p.has("seed"))
                step = r.new StepOne(bp, 1, GB.hexStringToByteArray(p.getString("isoSalt")), GB.hexStringToByteArray(p.getString("seed")));
            else if (p.has("encData"))
                step = r.new StepThree(bp, 3, GB.hexStringToByteArray(p.getString("nonce")), GB.hexStringToByteArray(p.getString("encData")));
            else if (p.has("encResult"))
                step = r.new StepFour(bp, op == 2 ? 3 : 4, GB.hexStringToByteArray(p.getString("nonce")), GB.hexStringToByteArray(p.getString("encResult")));
            else if (p.has("token"))
                step = r.new StepTwo(bp, 2, GB.hexStringToByteArray(p.getString("token")));
            else
                return null; // no recognisable step: hiChainStep() reports it
            return step.getTlv().serialize();
        }

        /** Hex field of a HiChain payload; IllegalArgumentException when missing or not hex. */
        byte[] field(JSONObject p, String name) {
            String v = p.optString(name, null);
            if (v == null || v.isEmpty() || v.length() % 2 != 0 || !v.matches("[0-9A-Fa-f]+"))
                throw new IllegalArgumentException("payload field " + name + " missing or not hex: " + v);
            if (!v.equals(v.toUpperCase(java.util.Locale.ROOT)))
                log("note: " + name + " is lowercase hex (the Android client sends uppercase)");
            return GB.hexStringToByteArray(v);
        }

        static byte[] concat(byte[]... parts) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            for (byte[] part : parts) b.write(part, 0, part.length);
            return b.toByteArray();
        }

        static String printable(byte[] b) {
            for (byte x : b) if (x < 0x20 || x > 0x7e) return GB.hexdump(b);
            return "\"" + new String(b, StandardCharsets.US_ASCII) + "\"";
        }
    }

    /** One night: in bed 23:00, asleep 23:15, wake 07:00, with a 10-min awakening at 03:00. */
    static byte[] buildSequenceFile(int baseTs) {
        int bed = baseTs + 23 * 3600;
        int asleep = bed + 15 * 60;
        int wake = baseTs + 31 * 3600;
        HuaweiTLV fields = new HuaweiTLV();
        int[][] longs = {{700013686, asleep}, {700013298, bed}, {700013973, wake + 600}, {700013156, wake},
                {700013786, 1}, {700013245, 82}, {700013713, 15}, {700013232, 93}, {700013436, 49},
                {700013878, 41}, {700013886, 14}, {700013635, 1}};
        HuaweiTLV outer = new HuaweiTLV();
        for (int[] f : longs) {
            outer.put(0x82, new HuaweiTLV().put(0x03, f[0]).put(0x04, 1).put(0x05, f[1]));
        }
        byte[] summary = new HuaweiTLV().put(0x81, outer).serialize();
        // details: from bedTime (supportsBedTime), little-endian {duration s, status, 3 bytes}
        int[][] stages = {{15 * 60, 4}, {(int) (3.75 * 3600), 1}, {10 * 60, 4}, {4 * 3600 - 10 * 60, 3}};
        ByteBuffer det = ByteBuffer.allocate(stages.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int[] st : stages) det.putInt(st[0]).put((byte) st[1]).put(new byte[3]);
        byte[] details = det.array();
        ByteBuffer f = ByteBuffer.allocate(32 + 32 + summary.length + details.length);
        f.putInt(0).putInt(700013).putShort((short) 1).putShort((short) 1).put(new byte[20]);
        f.putInt(bed).putInt(wake).put((byte) 1).put((byte) 2).putShort((short) summary.length).putInt(details.length).put(new byte[16]);
        f.put(summary).put(details);
        return f.array();
    }

    // ---------------------------------------------------------------- phone listener
    static class Recorder implements HuaweiSupportProvider.Listener {
        final List<int[]> sleepSegs = new ArrayList<>();
        final List<HuaweiSupportProvider.SleepSummary> nights = new ArrayList<>();
        int sleep = 0, steps = 0, lastHr = -1, lastSpo = -1, syncs = 0;
        boolean lastOk = false;
        String authFail = null;
        int battery = -1;
        int[] rri = null;
        HuaweiStressParser.StressData stress = null;
        String measureError = null;
        long lastProgress = -1;
        public void onStateChanged(HuaweiSupportProvider.State s, String d) { }
        public void onDeviceInfo(BandDevice d) { battery = d.getBatteryLevel(); }
        public void onAuthenticationFailed(String r) { authFail = r; }
        public void onSleepSegment(int a, int b, int t, int src) { sleep++; sleepSegs.add(new int[]{a, b, src}); }
        public void onStepSample(int ts, int st, int cal, int dist, int spo, int hr, int rhr) { steps++; lastHr = hr; lastSpo = spo; }
        public void onSleepSummary(HuaweiSupportProvider.SleepSummary s) { nights.add(s); }
        public void onStress(int a, int b, int score, int level) { }
        public void onDictValues(String kind, List<double[]> values) { }
        public void onSyncFinished(boolean ok, String summary) { syncs++; lastOk = ok; lastSummary = summary; System.out.println("  sync summary: " + summary); }
        int unresponsive = 0, rawFiles = 0;
        long unresponsiveAt = 0;
        public void onLinkUnresponsive(String reason) { unresponsive++; unresponsiveAt = android.os.Handler.now; System.out.println("  link unresponsive: " + reason); }
        public void onRawFile(String kind, int a, int b, byte[] data) { rawFiles++; rawKinds.add(kind); }
        final java.util.List<String> rawKinds = new java.util.ArrayList<>();
        String hrMode = null, lastSummary = null;
        public void onHeartRateMode(String mode) { hrMode = mode; }
        public int getLastStepTimestamp() { return 0; }
        public int getLastSleepTimestamp() { return 0; }
        public long getLastDictTimestamp(String kind) { return 0; }
        final List<HuaweiSupportProvider.WorkoutSummary> workouts = new ArrayList<>();
        final List<HuaweiSupportProvider.WorkoutSample> workoutSamples = new ArrayList<>();
        public void onWorkout(HuaweiSupportProvider.WorkoutSummary w) { workouts.add(w); }
        final List<int[]> stages = new ArrayList<>();
        public void onSleepStage(int a, int b, int st) { stages.add(new int[]{a, b, st}); }
        public void onWorkoutSamples(int start, List<HuaweiSupportProvider.WorkoutSample> s) { workoutSamples.addAll(s); }
        public void onMeasurementProgress(long left) { lastProgress = left; }
        public void onMeasurementResult(HuaweiStressParser.StressData s, int[] r, int[] q) { stress = s; rri = r; }
        public void onMeasurementError(String reason) { measureError = reason; }
    }

    // ---------------------------------------------------------------- HiChain helpers

    static android.content.SharedPreferences devPrefs(String mac) {
        return new Context().getSharedPreferences("huawei_dev_" + mac.replace(":", ""), 0);
    }

    /** HiChain band (authVersion 2) with the byte-parity check on, as BandServer runs it. */
    static FakeBand hiChainBand(int base, String mac, int encryptMethod) {
        FakeBand b = new FakeBand(base, mac).useHiChain(2, encryptMethod);
        b.parity = true;
        return b;
    }

    /** Connects a new phone to the band and runs one virtual minute. */
    static HuaweiSupportProvider pair(FakeBand band, BandDevice dev, boolean first, Recorder rec) {
        HuaweiSupportProvider p = new HuaweiSupportProvider(new Context(), dev, first, rec);
        band.phone = p;
        main.post(() -> p.onConnected(band));
        Handler.runUntil(Handler.now + 60000);
        return p;
    }

    static String hexFrames(List<byte[]> frames) {
        StringBuilder b = new StringBuilder();
        for (byte[] f : frames) b.append(GB.hexdump(f));
        return b.toString();
    }

    /** Unencrypted, unsliced frame with a TLV body. */
    static byte[] frame(int service, int command, HuaweiTLV tlv) {
        byte[] body = tlv.serialize();
        ByteBuffer b = ByteBuffer.allocate(6 + body.length);
        b.put((byte) 0x5A).putShort((short) (3 + body.length)).put((byte) 0).put((byte) service).put((byte) command).put(body);
        return ByteBuffer.allocate(b.capacity() + 2).put(b.array()).putShort((short) CheckSums.getCRC16(b.array(), 0x0000)).array();
    }

    /**
     * Phone transport that goes through a {@link BandServer}'s text lines, as an external client would:
     * writes of one main-loop turn are glued together and cut again at random sizes (1-40 bytes).
     */
    static class LineLink implements HuaweiSupportProvider.Transport {
        final BandServer server;
        final List<String> errors = new ArrayList<>(), logs = new ArrayList<>();
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        final Random cut = new Random(5);
        HuaweiSupportProvider phone;
        boolean connected = true, flushPosted = false;
        int wLines = 0;

        LineLink(String... args) { server = new BandServer(BandServer.Options.parse(args), this::line); }

        void line(String l) {
            if (l.startsWith("N ")) {
                byte[] b = GB.hexStringToByteArray(l.substring(2));
                main.postDelayed(() -> phone.onDataReceived(b), 5);
            } else if (l.startsWith("E ")) errors.add(l.substring(2));
            else if (l.startsWith("L ")) logs.add(l.substring(2));
        }

        @Override public void write(byte[] data) {
            pending.write(data, 0, data.length);
            if (!flushPosted) {
                flushPosted = true;
                main.post(this::flush);
            }
        }

        void flush() {
            flushPosted = false;
            byte[] all = pending.toByteArray();
            pending.reset();
            for (int o = 0; o < all.length; wLines++) {
                int n = Math.min(1 + cut.nextInt(40), all.length - o);
                String hex = GB.hexdump(all, o, n);
                server.handleLine("W " + (wLines % 2 == 0 ? hex : hex.toLowerCase(java.util.Locale.ROOT)));
                o += n;
            }
        }

        @Override public int getMaxWriteSize() { return 20; }
        @Override public boolean isConnected() { return connected; }
        @Override public void disconnect() {
            if (!connected) return;
            connected = false;
            flush();
            server.handleLine("D");
            main.post(() -> phone.onDisconnected());
        }
    }


    // ---------------------------------------------------------------- extra downloads (GPS, PDR, ECG, arrhythmia) and the band's push

    /** A dictionary-sync service whose "band" answers by script (the real one needs a P2P link the harness does not simulate). */
    static class FakeDictService extends br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService {
        final Map<Integer, String> mode = new HashMap<>();   // class -> "silent" (default: answers)
        final Map<Integer, long[]> values = new HashMap<>(); // class -> {value type, value}
        final List<Integer> requested = new ArrayList<>();
        final List<Integer> requestedAt = new ArrayList<>(); // size of band.seen at the request
        final Set<Integer> served = new HashSet<>();
        final FakeBand band;

        FakeDictService(br.dimensono.bandsono.huawei.HuaweiP2PManager m, FakeBand band) {
            super(m);
            this.band = band;
        }

        @Override public void sendPing(HuaweiP2PCallback callback) {
            if (callback != null) callback.onResponse(0xca, null);
        }

        @Override public void sendCommand(byte[] data, HuaweiP2PCallback callback) {
            HuaweiTLV req = new HuaweiTLV().parse(data, 1, data.length - 1);
            int dictClass = 0;
            try {
                for (byte b : req.getBytes(0x02)) dictClass = (dictClass << 8) | (b & 0xff);
            } catch (HuaweiPacket.MissingTagException e) {
                throw new RuntimeException(e);
            }
            final int cls = dictClass;
            requested.add(cls);
            requestedAt.add(band.seen.size());
            if ("silent".equals(mode.get(cls))) return;
            final HuaweiTLV answer = new HuaweiTLV().put(0x01, 1).put(0x02, cls);
            if (served.add(cls) && values.containsKey(cls)) {
                long[] v = values.get(cls);
                HuaweiTLV item = new HuaweiTLV().put(0x05, Long.valueOf(1_000_000L)).put(0x06, Long.valueOf(1_000_100L))
                        .put(0x87, new HuaweiTLV().put(0x88, new HuaweiTLV().put(0x09, (int) v[0]).put(0x0a, dv((double) v[1]))));
                answer.put(0x83, new HuaweiTLV().put(0x84, item));
            }
            byte[] body = answer.serialize();
            byte[] msg = new byte[body.length + 1];
            msg[0] = 1;
            System.arraycopy(body, 0, msg, 1, body.length);
            main.postDelayed(() -> handleData(msg), 20);
        }
    }

    /** Listener that also keeps what the owner (app or gateway) must keep: the pending GPS/PDR list and the extra windows. */
    static class ExtraRecorder extends Recorder {
        List<br.dimensono.bandsono.huawei.PendingTrajectory> pending = new ArrayList<>();
        final Map<String, Integer> extraEnd = new HashMap<>();
        final List<String> raws = new ArrayList<>(); // kind:start:end:length
        final List<Integer> pushes = new ArrayList<>();
        final List<String> dictKinds = new ArrayList<>();
        final Map<String, Integer> windowDone = new HashMap<>(); // times onExtraWindowDone was called, per kind
        int lastWorkoutStart = 0;
        long syncStartedAt = 0, syncEndedAt = 0;

        @Override public void onRawFile(String kind, int a, int b, byte[] data) {
            super.onRawFile(kind, a, b, data);
            raws.add(kind + ":" + a + ":" + b + ":" + data.length);
        }
        @Override public void onWorkout(HuaweiSupportProvider.WorkoutSummary w) {
            super.onWorkout(w);
            lastWorkoutStart = Math.max(lastWorkoutStart, w.startTs);
        }
        @Override public int getLastWorkoutTimestamp() { return lastWorkoutStart; }
        @Override public List<br.dimensono.bandsono.huawei.PendingTrajectory> getPendingTrajectories() {
            return br.dimensono.bandsono.huawei.PendingTrajectory.parse(br.dimensono.bandsono.huawei.PendingTrajectory.serialize(pending));
        }
        @Override public void savePendingTrajectories(List<br.dimensono.bandsono.huawei.PendingTrajectory> list) {
            pending = br.dimensono.bandsono.huawei.PendingTrajectory.parse(br.dimensono.bandsono.huawei.PendingTrajectory.serialize(list));
        }
        @Override public int getLastExtraEnd(String kind) { return extraEnd.getOrDefault(kind, 0); }
        @Override public void onExtraWindowDone(String kind, int endSec) { extraEnd.put(kind, endSec); windowDone.merge(kind, 1, Integer::sum); }
        @Override public void onDictValues(String kind, List<double[]> values) { for (double[] v : values) dictKinds.add(kind + "=" + (long) v[1]); }
        @Override public void onBandRequestsSync(int action) { pushes.add(action); }
        @Override public void onSyncFinished(boolean ok, String summary) { super.onSyncFinished(ok, summary); syncEndedAt = Handler.now; }
        int rawCount(String kind) {
            int n = 0;
            for (String r : raws) if (r.startsWith(kind + ":")) n++;
            return n;
        }
    }

    static int inits(FakeBand b, String key) {
        int n = 0;
        for (String[] f : b.fileInits) if (f[0].equals(key)) n++;
        return n;
    }

    static int xi = 0;

    /** A fresh plain band + provider, connected and READY, with the given band options. */
    static FakeBand xband(int base, Consumer<FakeBand> opts) {
        FakeBand b = new FakeBand(base, String.format("11:22:33:44:56:%02X", 0x10 + xi++));
        opts.accept(b);
        return b;
    }

    static HuaweiSupportProvider xconnect(FakeBand b, ExtraRecorder rec) {
        return pair(b, new BandDevice(b.mac, "HUAWEI Band 10-X"), true, rec);
    }

    static void xsync(HuaweiSupportProvider p, ExtraRecorder rec) {
        rec.syncs = 0;
        rec.syncStartedAt = Handler.now;
        p.requestSync();
        Handler.runUntil(Handler.now + 15 * 60000);
    }

    static byte[] dv(double d) { return ByteBuffer.allocate(8).putDouble(d).array(); }

    static void extrasScenarios(int base) throws Exception {
        System.out.println("Scenario 8a: capabilities absent -> nothing extra is asked");
        FakeBand a = xband(base, b -> { b.trajectoryTag = 1; b.fileMode.put("gps", "ok"); b.fileMode.put("ecg", "ok"); b.fileMode.put("arr", "ok"); });
        ExtraRecorder ra = new ExtraRecorder();
        HuaweiSupportProvider pa = xconnect(a, ra);
        xsync(pa, ra);
        check(ra.syncs == 1 && ra.lastOk, "8a: sync OK");
        check(inits(a, "gps") + inits(a, "pdr") + inits(a, "ecg") + inits(a, "arr") == 0, "8a: no GPS/PDR/ECG/arrhythmia file asked (" + a.fileInits.size() + " file inits, all other)");
        check(ra.pending.isEmpty(), "8a: no pending list without the GPS capability");
        final long baseline = ra.syncEndedAt - ra.syncStartedAt;

        System.out.println("Scenario 8b: GPS and PDR collected, after the workouts, PDR only after the GPS");
        FakeBand b1 = xband(base, b -> { b.gpsNewSync = true; b.trajectoryTag = 1; b.fileMode.put("gps", "ok"); b.fileMode.put("pdr", "ok"); });
        ExtraRecorder r1 = new ExtraRecorder();
        HuaweiSupportProvider p1 = xconnect(b1, r1);
        xsync(p1, r1);
        int wStart = base + 86400 + 18 * 3600;
        check(r1.lastOk && r1.rawCount("workout_gps") == 1 && r1.rawCount("workout_pdr") == 1, "8b: both files handed over as raw (" + r1.raws + ")");
        check(r1.raws.contains("workout_gps:" + wStart + ":" + (wStart + 1800) + ":300"), "8b: raw GPS carries the workout start/end");
        check(b1.fileInits.size() >= 3 && b1.fileInits.get(b1.fileInits.size() - 2)[1].equals("1_gps.bin") && b1.fileInits.get(b1.fileInits.size() - 1)[1].equals("1_pdr.bin"),
                "8b: 1_gps.bin then 1_pdr.bin");
        int lastWorkoutReq = -1, firstExtra = Integer.MAX_VALUE;
        for (int i = 0; i < b1.seen.size(); i++) if (b1.seen.get(i).startsWith("17/")) lastWorkoutReq = i;
        for (int i = 0; i < b1.fileInits.size(); i++) if (!b1.fileInits.get(i)[0].equals("other")) firstExtra = Math.min(firstExtra, b1.fileInitAt.get(i));
        check(firstExtra > lastWorkoutReq, "8b: every extra request comes after the last workout request (" + lastWorkoutReq + " < " + firstExtra + ")");
        check(r1.pending.isEmpty(), "8b: nothing left pending");
        check(inits(b1, "ecg") + inits(b1, "arr") == 0, "8b: ECG/arrhythmia not asked without their bits");

        System.out.println("Scenario 8c: PDR never answers: the sync ends in time, the GPS is kept, the PDR is tried again next sync");
        FakeBand b2 = xband(base, b -> { b.gpsNewSync = true; b.trajectoryTag = 1; b.fileMode.put("gps", "ok"); b.fileMode.put("pdr", "hang"); });
        ExtraRecorder r2 = new ExtraRecorder();
        HuaweiSupportProvider p2 = xconnect(b2, r2);
        xsync(p2, r2);
        long extra2 = r2.syncEndedAt - r2.syncStartedAt - baseline;
        check(r2.syncs == 1 && r2.lastOk && !r2.lastSummary.contains("esgotado"), "8c: sync OK (not cut by the watchdog): " + r2.lastSummary);
        check(extra2 >= 55000 && extra2 <= 70000, "8c: the silent PDR cost about 60 s, not 120 s (" + extra2 + " ms)");
        check(r2.rawCount("workout_gps") == 1 && r2.rawCount("workout_pdr") == 0, "8c: GPS kept although the PDR failed");
        check(r2.pending.size() == 1 && r2.pending.get(0).gpsDone && !r2.pending.get(0).pdrDone && r2.pending.get(0).attempts == 1,
                "8c: pending keeps the workout with GPS done and PDR to retry (" + br.dimensono.bandsono.huawei.PendingTrajectory.serialize(r2.pending) + ")");
        b2.fileMode.put("pdr", "ok");
        xsync(p2, r2);
        check(r2.lastOk && r2.rawCount("workout_pdr") == 1 && r2.rawCount("workout_gps") == 1, "8c: next sync gets the PDR and does not ask the GPS again (" + r2.raws + ")");
        check(inits(b2, "gps") == 1 && inits(b2, "pdr") == 2, "8c: GPS asked once, PDR twice");
        check(r2.pending.isEmpty(), "8c: pending list empty after the PDR arrived");

        System.out.println("Scenario 8d: 144001 and empty answers mean 'no GPS', not an error; tag 0 asks nothing");
        FakeBand b3 = xband(base, b -> { b.gpsNewSync = true; b.trajectoryTag = -1; b.fileMode.put("gps", "e144001"); });
        ExtraRecorder r3 = new ExtraRecorder();
        HuaweiSupportProvider p3 = xconnect(b3, r3);
        xsync(p3, r3);
        check(r3.lastOk && inits(b3, "gps") == 1 && inits(b3, "pdr") == 1 && r3.raws.stream().noneMatch(x -> x.startsWith("workout_g") || x.startsWith("workout_p")),
                "8d: 144001 on the GPS: asked once, the PDR is asked anyway (it is independent), nothing stored");
        check(r3.pending.isEmpty(), "8d: nothing pending (it is not an error to retry)");
        FakeBand b4 = xband(base, b -> { b.gpsNewSync = true; b.trajectoryTag = 1; b.fileMode.put("gps", "empty"); });
        ExtraRecorder r4 = new ExtraRecorder();
        xsync(xconnect(b4, r4), r4);
        check(r4.lastOk && inits(b4, "gps") == 1 && inits(b4, "pdr") == 1 && r4.pending.isEmpty(), "8d: empty GPS file = no GPS; the PDR is still asked; nothing pending");
        FakeBand b5 = xband(base, b -> { b.gpsNewSync = true; b.trajectoryTag = 0; });
        ExtraRecorder r5 = new ExtraRecorder();
        xsync(xconnect(b5, r5), r5);
        check(r5.lastOk && inits(b5, "gps") == 0 && r5.pending.isEmpty(), "8d: the band says tag 0 (no trajectory files): not asked");

        System.out.println("Scenario 8e: three workouts and a band that never sends the GPS: bounded by the budget");
        FakeBand b6 = xband(base, b -> { b.gpsNewSync = true; b.trajectoryTag = 1; b.workoutCount = 3; b.fileMode.put("gps", "hang"); });
        ExtraRecorder r6 = new ExtraRecorder();
        HuaweiSupportProvider p6 = xconnect(b6, r6);
        xsync(p6, r6);
        long extra6 = r6.syncEndedAt - r6.syncStartedAt - baseline;
        check(r6.syncs == 1 && r6.lastOk && !r6.lastSummary.contains("esgotado"), "8e: sync OK: " + r6.lastSummary);
        check(extra6 >= 120000 && extra6 <= 190000, "8e: the extras took at most the 3 minute budget (" + extra6 + " ms)");
        int attempts = 0;
        for (br.dimensono.bandsono.huawei.PendingTrajectory t : r6.pending) attempts += t.attempts;
        check(r6.pending.size() == 3 && attempts >= 2 && inits(b6, "gps") <= 3, "8e: all three stay pending for later (" + br.dimensono.bandsono.huawei.PendingTrajectory.serialize(r6.pending) + ")");
        check(r6.workouts.size() == 3, "8e: the workouts themselves were all stored");

        System.out.println("Scenario 8f: tries are limited");
        FakeBand b7 = xband(base, b -> { b.gpsNewSync = true; b.trajectoryTag = 0; b.fileMode.put("gps", "error"); });
        ExtraRecorder r7 = new ExtraRecorder();
        br.dimensono.bandsono.huawei.PendingTrajectory old = new br.dimensono.bandsono.huawei.PendingTrajectory((short) 9, 1000, 2000);
        old.attempts = br.dimensono.bandsono.huawei.PendingTrajectory.MAX_ATTEMPTS - 1;
        r7.pending.add(old);
        HuaweiSupportProvider p7 = xconnect(b7, r7);
        xsync(p7, r7);
        check(r7.lastOk && inits(b7, "gps") == 1 && b7.fileInits.stream().filter(f -> f[0].equals("gps")).findFirst().get()[1].equals("9_gps.bin") && r7.pending.isEmpty(), "8f: the last try fails and the entry is dropped");
        xsync(p7, r7);
        check(inits(b7, "gps") == 1, "8f: a dropped workout is not asked again");

        System.out.println("Scenario 8g: ECG and arrhythmia PPG raw files, windows that move only when read");
        FakeBand b8 = xband(base, b -> { b.extraCaps = new int[]{106, 113}; b.fileMode.put("ecg", "ok"); b.fileMode.put("arr", "ok"); });
        ExtraRecorder r8 = new ExtraRecorder();
        HuaweiSupportProvider p8 = xconnect(b8, r8);
        xsync(p8, r8);
        check(r8.lastOk && r8.rawCount("ecg_analysis") == 1 && r8.rawCount("arrhythmia_ppg") == 1, "8g: both raw files kept (" + r8.raws + ")");
        check(inits(b8, "ecg") == 1 && b8.fileInits.stream().anyMatch(f -> f[0].equals("ecg") && f[1].equals("ecg_analysis_data.bin")), "8g: ecg_analysis_data.bin asked");
        String[] ecg1 = b8.fileInits.stream().filter(f -> f[0].equals("ecg")).findFirst().get();
        check(Integer.parseInt(ecg1[3]) - Integer.parseInt(ecg1[2]) >= 30 * 86400 - 5, "8g: first window = the last 30 days");
        int end1 = r8.extraEnd.getOrDefault("ecg_analysis", 0);
        check(end1 > 0 && r8.extraEnd.containsKey("arrhythmia_ppg"), "8g: windows recorded");
        b8.fileMode.put("ecg", "error");
        b8.fileMode.put("arr", "silent");
        xsync(p8, r8);
        check(r8.lastOk, "8g: a failing ECG and a silent arrhythmia request do not fail the sync");
        String[] ecg2 = b8.fileInits.stream().filter(f -> f[0].equals("ecg")).reduce((x, y) -> y).get();
        check(Integer.parseInt(ecg2[2]) == end1, "8g: the second window starts where the first ended");
        check(r8.extraEnd.get("ecg_analysis") == end1, "8g: a failed window does not move on");
        b8.fileMode.put("ecg", "empty");
        xsync(p8, r8);
        String[] ecg3 = b8.fileInits.stream().filter(f -> f[0].equals("ecg")).reduce((x, y) -> y).get();
        check(Integer.parseInt(ecg3[2]) == end1 && r8.extraEnd.get("ecg_analysis") >= end1 && r8.rawCount("ecg_analysis") == 1,
                "8g: an empty window moves on and stores nothing");
        FakeBand b9 = xband(base, b -> { b.extraCaps = new int[]{168}; b.fileMode.put("ecg", "ok"); b.fileMode.put("arr", "ok"); });
        ExtraRecorder r9 = new ExtraRecorder();
        xsync(xconnect(b9, r9), r9);
        check(r9.lastOk && inits(b9, "ecg") + inits(b9, "arr") == 0, "8g: the Chinese arrhythmia bit alone (168) asks nothing, it is only logged");

        System.out.println("Scenario 8h: the band's push 'please sync' (0x07/0x0f) is logged and handed over, never answered");
        FakeBand bp = xband(base, b -> { });
        ExtraRecorder rp = new ExtraRecorder();
        xconnect(bp, rp);
        int seenBefore = bp.seen.size();
        main.post(() -> {
            try {
                bp.reply(7, 0x0F, new HuaweiTLV().put(0x01, (byte) 4), true);
                bp.reply(7, 0x0F, new HuaweiTLV().put(0x01, (byte) 99), true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Handler.runUntil(Handler.now + 5000);
        check(rp.pushes.equals(Arrays.asList(4, -1)), "8h: action 4 recognised, an unknown value gives -1 (" + rp.pushes + ")");
        check(bp.seen.size() == seenBefore && rp.syncs == 0, "8h: nothing sent back, no sync started by the push");


        System.out.println("Scenario 8j: order ECG, arrhythmia, GPS; a mute GPS cannot starve the others");
        FakeBand bj = xband(base, b -> { b.gpsNewSync = true; b.trajectoryTag = 1; b.workoutCount = 3; b.extraCaps = new int[]{106, 113};
            b.fileMode.put("gps", "hang"); b.fileMode.put("ecg", "ok"); b.fileMode.put("arr", "ok"); });
        ExtraRecorder rj = new ExtraRecorder();
        xsync(xconnect(bj, rj), rj);
        check(rj.lastOk && rj.rawCount("ecg_analysis") == 1 && rj.rawCount("arrhythmia_ppg") == 1, "8j: ECG and arrhythmia PPG arrived although every GPS file hangs (" + rj.raws + ")");
        int iEcg = -1, iArr = -1, iGps = -1;
        for (int i = 0; i < bj.fileInits.size(); i++) {
            String k = bj.fileInits.get(i)[0];
            if (k.equals("ecg") && iEcg < 0) iEcg = i;
            if (k.equals("arr") && iArr < 0) iArr = i;
            if (k.equals("gps") && iGps < 0) iGps = i;
        }
        check(iEcg >= 0 && iEcg < iArr && iArr < iGps, "8j: requested in the order ECG, arrhythmia, GPS (" + iEcg + "," + iArr + "," + iGps + ")");
        check(rj.pending.size() == 3, "8j: the three workouts wait for a later sync");

        System.out.println("Scenario 8k: at most 3 workouts of GPS per sync");
        FakeBand bk = xband(base, b -> { b.gpsNewSync = true; b.trajectoryTag = 1; b.workoutCount = 5; b.fileMode.put("gps", "ok"); b.fileMode.put("pdr", "ok"); });
        ExtraRecorder rk = new ExtraRecorder();
        HuaweiSupportProvider pk = xconnect(bk, rk);
        xsync(pk, rk);
        check(rk.lastOk && inits(bk, "gps") == 3 && rk.rawCount("workout_gps") == 3 && rk.pending.size() == 2, "8k: 3 GPS asked, 2 workouts left pending (" + inits(bk, "gps") + ", " + rk.pending.size() + ")");
        xsync(pk, rk);
        check(inits(bk, "gps") == 5 && rk.rawCount("workout_gps") == 5 && rk.pending.isEmpty(), "8k: the next sync takes the other 2 (" + inits(bk, "gps") + ")");

        System.out.println("Scenario 8l: 144001 on an ECG / arrhythmia window = empty window (the marker moves on); a normal init answer with 0x7f = 100000 is fine");
        FakeBand bl = xband(base, b -> { b.extraCaps = new int[]{106, 113}; b.fileMode.put("ecg", "e144001"); b.fileMode.put("arr", "e144001");
            b.gpsNewSync = true; b.trajectoryTag = 1; b.initWith7f = true; b.fileMode.put("gps", "ok"); b.fileMode.put("pdr", "ok"); });
        ExtraRecorder rl = new ExtraRecorder();
        xsync(xconnect(bl, rl), rl);
        check(rl.lastOk && rl.windowDone.getOrDefault("ecg_analysis", 0) == 1 && rl.windowDone.getOrDefault("arrhythmia_ppg", 0) == 1
                && rl.rawCount("ecg_analysis") + rl.rawCount("arrhythmia_ppg") == 0, "8l: 144001 moves both markers on and stores nothing");
        check(rl.rawCount("workout_gps") == 1 && rl.rawCount("workout_pdr") == 1, "8l: init answers that carry 0x7f = 100000 still download (" + rl.raws + ")");

        System.out.println("Scenario 8m: the PDR can be the only track");
        FakeBand bm = xband(base, b -> { b.gpsNewSync = true; b.trajectoryTag = 1; b.fileMode.put("gps", "e144001"); b.fileMode.put("pdr", "ok"); });
        ExtraRecorder rm = new ExtraRecorder();
        xsync(xconnect(bm, rm), rm);
        check(rm.lastOk && rm.rawCount("workout_gps") == 0 && rm.rawCount("workout_pdr") == 1 && rm.pending.isEmpty(), "8m: no GPS (144001), PDR kept, nothing pending");

        System.out.println("Scenario 8n: the timer of an earlier sync does not touch the next one");
        FakeBand bn = xband(base, b -> { b.gpsNewSync = true; b.trajectoryTag = 1; b.fileMode.put("gps", "hang"); });
        ExtraRecorder rn = new ExtraRecorder();
        HuaweiSupportProvider pn = xconnect(bn, rn);
        long t0 = Handler.now;
        rn.syncStartedAt = t0;
        pn.requestSync();
        Handler.runUntil(t0 + baseline + 10000); // inside the first sync's GPS wait
        check(pn.isSyncRunning(), "8n: the first sync is waiting for the GPS");
        main.post(() -> bn.disconnect());
        Handler.runUntil(Handler.now + 2000);
        bn.reconnect();
        bn.phone = pn;
        main.post(() -> pn.onConnected(bn));
        Handler.runUntil(Handler.now + 4000);
        check(pn.getState() == HuaweiSupportProvider.State.READY, "8n: reconnected (" + pn.getState() + ")");
        bn.fileInits.clear();
        bn.fileMode.put("gps", "slow"); // the second sync's GPS takes 50 s: still running when the first sync's 60 s timer fires
        bn.fileMode.put("pdr", "empty");
        rn.syncs = 0;
        rn.syncStartedAt = Handler.now;
        pn.requestSync();
        Handler.runUntil(Handler.now + 15 * 60000);
        long extraN = rn.syncEndedAt - rn.syncStartedAt - baseline;
        check(rn.syncs == 1 && rn.lastOk && rn.rawCount("workout_gps") == 1 && rn.pending.isEmpty(),
                "8n: the second sync's slow GPS (50 s) arrived: the first sync's 60 s timer did not cancel it (" + rn.raws + ", " + extraN + " ms)");

        System.out.println("Scenario 8o: the arrhythmia result (P2P 500007) runs among the extras; a mute 500007 does not hold back the main dictionary");
        FakeBand bo = xband(base, b -> { b.extraCaps = new int[]{206, 107, 113}; });
        ExtraRecorder ro = new ExtraRecorder();
        HuaweiSupportProvider po = xconnect(bo, ro);
        FakeDictService fo = new FakeDictService(po.getHuaweiP2PManager(), bo);
        fo.values.put(500031, new long[]{500031195L, 50});
        fo.values.put(500002, new long[]{500002847L, 2});
        fo.values.put(500007, new long[]{500007088L, 7});
        fo.mode.put(500007, "silent");
        po.getHuaweiP2PManager().registerService(fo);
        xsync(po, ro);
        long extraO = ro.syncEndedAt - ro.syncStartedAt - baseline;
        check(ro.syncs == 1 && ro.lastOk, "8o: sync OK with a mute 500007");
        check(ro.dictKinds.contains("emotion=50") && ro.dictKinds.contains("apnea=2"), "8o: emotion and apnea still arrived (" + ro.dictKinds + ")");
        check(new java.util.ArrayList<>(new java.util.LinkedHashSet<>(fo.requested)).equals(Arrays.asList(500031, 500002, 500007)), "8o: classes in the handler order, 500007 last (" + fo.requested + ")");
        int lastWorkoutReq2 = -1;
        for (int i = 0; i < bo.seen.size(); i++) if (bo.seen.get(i).startsWith("17/")) lastWorkoutReq2 = i;
        check(fo.requestedAt.get(0) <= lastWorkoutReq2 && fo.requestedAt.get(fo.requestedAt.size() - 1) > lastWorkoutReq2, "8o: the main classes before the workouts, 500007 after the last workout request (" + fo.requestedAt + " > " + lastWorkoutReq2 + ")");
        check(extraO >= 55000 && extraO <= 70000, "8o: the mute 500007 cost one 60 s wait (" + extraO + " ms)");
        fo.mode.put(500007, "ok");
        fo.requested.clear();
        ro.dictKinds.clear();
        xsync(po, ro);
        check(ro.lastOk && ro.dictKinds.contains("arrhythmia_result_measure_type=7"),
                "8o: next sync: nothing stuck 'in progress', the raw result 7 is kept without any filter (" + ro.dictKinds + ")");
        check(fo.requested.get(fo.requested.size() - 1) == 500007, "8o: 500007 is still the last request");

        System.out.println("Scenario 8i: arrhythmia RESULT handler (P2P class 500007)");
        check(br.dimensono.bandsono.huawei.HuaweiDictTypes.ARRHYTHMIA_RESULT_CLASS == 500007 && br.dimensono.bandsono.huawei.HuaweiDictTypes.ARRHYTHMIA_SEQUENCE_DICT == 700004,
                "8i: 700004 is the sequence dictionary, 500007 the P2P result class");
        br.dimensono.bandsono.huawei.p2p.dictionarysync.HuaweiDictionarySyncArrhythmia h = new br.dimensono.bandsono.huawei.p2p.dictionarysync.HuaweiDictionarySyncArrhythmia();
        br.dimensono.bandsono.huawei.HuaweiState st = new br.dimensono.bandsono.huawei.HuaweiState(new Context(), "11:22:33:44:57:01");
        st.addCommandsForService(0x01, new byte[]{0x37});
        st.saveExpandCapabilities(expandCaps(143));
        check(h.getDataClass() == 500007 && !h.supports(st), "8i: not supported without bit 113");
        st.saveExpandCapabilities(expandCaps(113));
        check(h.supports(st) && st.supportsArrhythmia() && !st.supportsEcgAnalysis(), "8i: bit 113 -> arrhythmia, not ECG");
        st.saveExpandCapabilities(expandCaps(106));
        check(st.supportsEcgAnalysis() && !h.supports(st), "8i: bit 106 -> ECG only");
        st.saveExpandCapabilities(expandCaps(168));
        check(!h.supports(st) && !st.supportsArrhythmia(), "8i: bit 168 alone is not enough");
        java.util.List<br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData> dd = new ArrayList<>();
        java.util.List<br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData.DictDataValue> v1 = new ArrayList<>();
        v1.add(new br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData.DictDataValue(500007278, (byte) 10, dv(1.7e12)));
        v1.add(new br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData.DictDataValue(500007088, (byte) 10, dv(1)));
        v1.add(new br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData.DictDataValue(2200, (byte) 10, dv(3)));
        v1.add(new br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData.DictDataValue(2200, (byte) 10, dv(9)));
        v1.add(new br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData.DictDataValue(500007088, (byte) 10, dv(42)));
        dd.add(new br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData(500007, 1000L, 1001L, 1002L, v1));
        java.util.List<br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData.DictDataValue> v2 = new ArrayList<>();
        v2.add(new br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData.DictDataValue(500007088, (byte) 11, ByteBuffer.allocate(8).putLong(0).array()));
        v2.add(new br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData.DictDataValue(500007088, (byte) 10, new byte[]{1, 2}));
        v2.add(new br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData.DictDataValue(2200, (byte) 10, dv(5)));
        dd.add(new br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService.DictData(500007, 2000L, 2001L, 2002L, v2));
        Map<String, List<double[]>> parsed = h.parseByKind(dd);
        List<double[]> mt = parsed.get("arrhythmia_result_measure_type"), pp = parsed.get("arrhythmia_result_ppg");
        check(mt.size() == 3 && mt.get(0)[1] == 1 && mt.get(1)[1] == 42 && mt.get(2)[0] == 2000 && mt.get(2)[1] == 0,
                "8i: measure type kept raw whatever the value (42 included), double or long encoding; a value of the wrong size cannot be one");
        check(pp.size() == 3 && pp.get(0)[1] == 3 && pp.get(1)[1] == 9 && pp.get(2)[1] == 5 && parsed.size() == 2, "8i: ppg code kept raw with no range filter (9 included); the save time is not a value");
        check(h.isExtra() && !new br.dimensono.bandsono.huawei.p2p.dictionarysync.HuaweiDictionarySyncEmotion().isExtra(), "8i: only the arrhythmia result is an extra");
    }

    public static void main(String[] args) {
        android.util.Log.quiet = args.length == 0 || !args[0].equals("-v");
        int base = (int) (System.currentTimeMillis() / 1000) - 3 * 86400;
        base -= base % 86400; // midnight UTC, keeps the synthetic night readable

        System.out.println("Scenario 1: first connection, normal auth, configuration and sync (new sleep file)");
        FakeBand band = new FakeBand(base);
        Recorder rec = new Recorder();
        HuaweiSupportProvider phone = new HuaweiSupportProvider(new Context(), new BandDevice(MAC, "HUAWEI Band 10-ABC"), true, rec);
        band.phone = phone;
        main.post(() -> phone.onConnected(band));
        Handler.runUntil(Handler.now + 60000);
        check(rec.authFail == null, "no authentication failure (" + rec.authFail + ")");
        check(phone.getState() == HuaweiSupportProvider.State.READY, "phone reaches READY (state " + phone.getState() + ")");
        check(rec.battery == 77, "battery level read (" + rec.battery + ")");
        check(band.seen.contains("01/37"), "expand capabilities requested");
        check(band.seen.contains("07/1c") || band.seen.contains("07/17"), "continuous heart rate switched on");
        check(band.seen.contains("07/24"), "automatic SpO2 switched on");
        check(band.seen.contains("07/16"), "TruSleep switch sent");
        check(band.seen.contains("07/07"), "activity reminder sent");
        check(band.stressSwitch == -1, "automatic stress left alone until there is a baseline");
        System.out.println("  requests seen by band: " + band.seen);

        phone.requestSync();
        Handler.runUntil(Handler.now + 180000);
        check(rec.syncs == 1 && rec.lastOk, "sync finished OK");
        check(band.seen.contains("2c/01") && band.seen.contains("2c/04"), "sleep sequence file requested over 0x2C");
        check(rec.steps == 6, "6 minute samples stored (" + rec.steps + ")");
        check(rec.lastHr == 150, "heart rate decoded as unsigned byte (" + rec.lastHr + ")");
        check(rec.lastSpo == 96, "SpO2 decoded (" + rec.lastSpo + ")");
        check(rec.nights.size() == 1, "one night summary parsed (" + rec.nights.size() + ")");
        if (!rec.nights.isEmpty()) {
            HuaweiSupportProvider.SleepSummary n = rec.nights.get(0);
            check(n.score == 82 && n.latencyMin == 15 && n.efficiency == 93 && n.avgHrv == 41,
                    "summary fields: score " + n.score + ", latency " + n.latencyMin + ", eff " + n.efficiency + ", HRV " + n.avgHrv);
            check(n.fallAsleep == base + 23 * 3600 + 900, "fall asleep time decoded");
        }
        List<int[]> staged = new ArrayList<>();
        for (int[] seg : rec.sleepSegs) if (seg[2] == HuaweiSupportProvider.SOURCE_STAGES) staged.add(seg);
        check(staged.size() == 2, "sleep stages turned into 2 asleep segments around the awakening (" + staged.size() + ")");
        if (staged.size() == 2) {
            check(staged.get(0)[0] == base + 23 * 3600 + 900, "first segment starts at sleep onset");
            check(staged.get(1)[0] - staged.get(0)[1] == 600, "10-minute awakening kept as wake");
        }
        check(rec.rawKinds.contains("sleep_sequence"), "raw sleep file handed over for upload (" + rec.rawKinds + ")");
        check(rec.rawKinds.contains("step_data"), "raw minute records handed over for upload");
        check(rec.lastSummary != null && rec.lastSummary.contains("com FC"), "summary reports minutes with heart rate");
        check(rec.hrMode != null, "heart rate mode reported (" + rec.hrMode + ")");
        check(phone.getState() == HuaweiSupportProvider.State.READY, "back to READY after sync");
        check(band.liftWrist == 0, "raise-to-wake switched off on the band (" + band.liftWrist + ")");
        check(band.wearLocation == 1, "wear location sent as left wrist (" + band.wearLocation + ")");
        check(band.sleepBreath == 2, "sleep breathing monitoring switched on (" + band.sleepBreath + ")");
        check(rec.stages.size() == 4 && rec.stages.get(0)[2] == 4 && rec.stages.get(3)[2] == 3,
                "hypnogram kept as 4 stage runs (awake, light, awake, deep): " + rec.stages.size());
        check(rec.workouts.size() == 1, "one workout downloaded (" + rec.workouts.size() + ")");
        if (!rec.workouts.isEmpty()) {
            HuaweiSupportProvider.WorkoutSummary w = rec.workouts.get(0);
            check(w.startTs == base + 86400 + 18 * 3600 && w.endTs == w.startTs + 1800, "workout start/end decoded");
            check(w.calories == 250 && w.distance == 4200 && w.steps == 5100 && w.type == 1, "workout totals decoded (cal " + w.calories + ", dist " + w.distance + ")");
            check(w.minHrPeak == 95 && w.maxHrPeak == 172, "workout HR peaks as unsigned bytes (" + w.minHrPeak + "/" + w.maxHrPeak + ")");
        }
        check(rec.workoutSamples.size() == 10, "10 workout samples from 2 blocks (" + rec.workoutSamples.size() + ")");
        if (rec.workoutSamples.size() == 10) {
            check(rec.workoutSamples.get(9).heartRate == 149 && rec.workoutSamples.get(9).speed == 254, "workout sample HR/speed decoded");
            check(rec.workoutSamples.get(1).ts - rec.workoutSamples.get(0).ts == 10, "sample timestamps follow the block interval");
        }
        check(rec.rawKinds.contains("workout_totals") && rec.rawKinds.contains("workout_data"), "raw workout records handed over for upload");
        check(rec.lastSummary.contains("1 treinos"), "summary counts the workout");

        System.out.println("Scenario 1b: one-minute RR interval test, then automatic stress");
        phone.startRrTest(60000);
        Handler.runUntil(Handler.now + 75000);
        check(rec.measureError == null, "RR test finished without error (" + rec.measureError + ")");
        check(rec.rri != null && rec.rri.length >= 55, "RR intervals received (" + (rec.rri == null ? 0 : rec.rri.length) + ")");
        check(rec.stress != null && rec.stress.score > 0, "stress baseline computed (" + (rec.stress == null ? "null" : rec.stress.score) + ")");
        check(phone.hasStressBaseline(), "stress baseline stored and read back");
        check(band.stressSwitch == 1, "automatic stress switched on after the baseline (" + band.stressSwitch + ")");

        System.out.println("Scenario 1c: stress calibration end to end (70 plausible RR intervals, band accepts)");
        FakeBand bandC = new FakeBand(base, "11:22:33:44:55:C1");
        bandC.rrRealtime = true;
        bandC.rrMin = 800;
        bandC.rrSpan = 101;
        Recorder recC = new Recorder();
        HuaweiSupportProvider phoneC = new HuaweiSupportProvider(new Context(), new BandDevice("11:22:33:44:55:C1", "x"), true, recC);
        bandC.phone = phoneC;
        main.post(() -> phoneC.onConnected(bandC));
        Handler.runUntil(Handler.now + 60000);
        check(phoneC.getState() == HuaweiSupportProvider.State.READY, "1c: phone reaches READY");
        check(!phoneC.hasStressBaseline(), "1c: no stress baseline before the test");
        check(bandC.stressPackets.isEmpty(), "1c: nothing sent to 0x20/0x09 before the test");
        check(phoneC.startRrTest(60000), "1c: startRrTest(60 s) accepted");
        Handler.runUntil(Handler.now + 75000);
        check(recC.measureError == null, "1c: test finished without error (" + recC.measureError + ")");
        check(recC.lastProgress >= 0, "1c: progress callbacks received");
        check(recC.rri != null && recC.rri.length >= 60 && recC.rri.length <= 75, "1c: about 70 RR intervals received ("
                + (recC.rri == null ? 0 : recC.rri.length) + ")");
        boolean inRange = recC.rri != null;
        if (recC.rri != null) for (int v : recC.rri) if (v < 800 || v > 900) inRange = false;
        check(inRange, "1c: every RR is the 800-900 ms the band sent");
        check(bandC.seen.indexOf("19/01") >= 0 && bandC.seen.indexOf("19/01") < bandC.seen.lastIndexOf("20/09"),
                "1c: test opened (0x19/0x01) before the stress packet");
        check(recC.stress != null && recC.stress.score != 0 && recC.stress.endTime != 0,
                "1c: result has score != 0 and endTime != 0 (" + (recC.stress == null ? "null" : recC.stress.score) + ")");
        check(bandC.stressPackets.size() == 1, "1c: exactly one 0x20/0x09 sent (" + bandC.stressPackets.size() + ")");
        if (bandC.stressPackets.size() == 1 && recC.stress != null) {
            StressPacket sp = bandC.stressPackets.get(0);
            check(sp.status == 1, "1c: packet switches automatic stress on (status " + sp.status + ")");
            check(sp.score != null && sp.score == (recC.stress.score & 0xff), "1c: packet carries the baseline score (" + sp.score + ")");
            check(sp.time != null && sp.time == (int) (recC.stress.endTime / 1000), "1c: packet carries the baseline end time in seconds");
            boolean sameFeatures = sp.features != null && sp.features.length == 48;
            if (sameFeatures) {
                ByteBuffer fb = ByteBuffer.wrap(sp.features);
                for (int i = 0; i < 12; i++) if (fb.getFloat() != recC.stress.features.get(i)) sameFeatures = false;
            }
            check(sameFeatures, "1c: packet carries the 12 baseline features");
        }
        check(phoneC.hasStressBaseline(), "1c: hasStressBaseline() turns true");
        HuaweiStressParser.StressData stored = phoneC.getLastStressData();
        check(stored != null && recC.stress != null && stored.endTime == recC.stress.endTime && stored.score == recC.stress.score,
                "1c: stored baseline is the one the test computed");
        Map<String, Object> hrv = recC.rri == null ? null : Hrv.compute(recC.rri);
        check(hrv != null && ((Integer) hrv.get("n")) >= 60 && ((Double) hrv.get("rmssd")) > 0 && ((Double) hrv.get("meanHr")) > 60 && ((Double) hrv.get("meanHr")) < 80,
                "1c: Hrv.compute on the result gives plausible metrics " + hrv);
        check(bandC.connected && phoneC.getState() == HuaweiSupportProvider.State.READY, "1c: still connected and READY afterwards");

        System.out.println("Scenario 1d: band sends no RR at all -> no calibration");
        FakeBand bandD = new FakeBand(base, "11:22:33:44:55:D1");
        bandD.rrBeats = 0;
        Recorder recD = new Recorder();
        HuaweiSupportProvider phoneD = new HuaweiSupportProvider(new Context(), new BandDevice("11:22:33:44:55:D1", "x"), true, recD);
        bandD.phone = phoneD;
        main.post(() -> phoneD.onConnected(bandD));
        Handler.runUntil(Handler.now + 60000);
        check(phoneD.getState() == HuaweiSupportProvider.State.READY, "1d: phone reaches READY");
        phoneD.startRrTest(60000);
        Handler.runUntil(Handler.now + 75000);
        check(recD.measureError != null, "1d: empty test reported as an error");
        check(recD.stress == null, "1d: no result delivered");
        check(bandD.stressPackets.isEmpty(), "1d: no 0x20/0x09 sent");
        check(!phoneD.hasStressBaseline(), "1d: hasStressBaseline() stays false");
        check(bandD.connected && phoneD.getState() == HuaweiSupportProvider.State.READY, "1d: still connected and READY");

        System.out.println("Scenario 1e: too few RR intervals for a baseline -> result without calibration");
        FakeBand bandE = new FakeBand(base, "11:22:33:44:55:E1");
        bandE.rrBeats = 5;
        Recorder recE = new Recorder();
        HuaweiSupportProvider phoneE = new HuaweiSupportProvider(new Context(), new BandDevice("11:22:33:44:55:E1", "x"), true, recE);
        bandE.phone = phoneE;
        main.post(() -> phoneE.onConnected(bandE));
        Handler.runUntil(Handler.now + 60000);
        phoneE.startRrTest(60000);
        Handler.runUntil(Handler.now + 75000);
        check(recE.rri != null && recE.rri.length == 5, "1e: the 5 RR intervals are still handed to the owner ("
                + (recE.rri == null ? "null" : recE.rri.length) + ")");
        check(recE.stress == null, "1e: no stress baseline computed from 5 beats");
        check(bandE.stressPackets.isEmpty(), "1e: no 0x20/0x09 sent");
        check(!phoneE.hasStressBaseline(), "1e: hasStressBaseline() stays false");

        System.out.println("Scenario 1f: an old unusable baseline (score 0, no end time) does not count");
        HuaweiStressParser.StressData old = new HuaweiStressParser.StressData();
        old.score = 0;
        old.endTime = 0;
        for (int i = 0; i < 12; i++) old.features.add(1f);
        phoneE.storeLastStressData(old);
        check(!phoneE.hasStressBaseline() && phoneE.getLastStressData() == null, "1f: baseline with score 0 / endTime 0 is ignored");

        System.out.println("Scenario 1g: band refuses the stress packet");
        FakeBand bandG = new FakeBand(base, "11:22:33:44:55:G1");
        bandG.rejectStress = true;
        bandG.rrRealtime = true;
        bandG.rrMin = 800;
        bandG.rrSpan = 101;
        Recorder recG = new Recorder();
        HuaweiSupportProvider phoneG = new HuaweiSupportProvider(new Context(), new BandDevice("11:22:33:44:55:G1", "x"), true, recG);
        bandG.phone = phoneG;
        main.post(() -> phoneG.onConnected(bandG));
        Handler.runUntil(Handler.now + 60000);
        phoneG.startRrTest(60000);
        Handler.runUntil(Handler.now + 75000);
        check(bandG.stressPackets.size() == 1, "1g: the packet was sent once and refused (" + bandG.stressPackets.size() + ")");
        check(recG.stress != null && recG.measureError == null, "1g: the test result is still delivered");
        check(bandG.connected && phoneG.getState() == HuaweiSupportProvider.State.READY, "1g: refusal does not drop the connection");
        check(recG.unresponsive == 0, "1g: refusal is not taken as a dead link");
        phoneG.requestSync();
        Handler.runUntil(Handler.now + 180000);
        check(recG.syncs == 1 && recG.lastOk, "1g: a sync right afterwards still finishes OK");

        System.out.println("Scenario 2: reconnection (already paired), band goes silent during sync");
        FakeBand band2 = new FakeBand(base);
        Recorder rec2 = new Recorder();
        HuaweiSupportProvider phone2 = new HuaweiSupportProvider(new Context(), new BandDevice(MAC, "HUAWEI Band 10-ABC"), false, rec2);
        band2.phone = phone2;
        main.post(() -> phone2.onConnected(band2));
        Handler.runUntil(Handler.now + 60000);
        check(phone2.getState() == HuaweiSupportProvider.State.READY, "reconnect reaches READY (" + phone2.getState() + ")");
        band2.dropAfter = band2.seen.size() + 1; // answer battery, then silence
        phone2.requestSync();
        Handler.runUntil(Handler.now + 15 * 60000);
        check(rec2.syncs == 1 && !rec2.lastOk, "sync ends (as failed) when the band stops answering");
        check(!phone2.isSyncRunning(), "sync flag cleared");
        check(rec2.unresponsive == 1, "owner asked to rebuild the connection (" + rec2.unresponsive + ")");

        System.out.println("Scenario 2b: zombie link, band answers nothing at all when a scheduled sync starts");
        FakeBand band2b = new FakeBand(base);
        Recorder rec2b = new Recorder();
        HuaweiSupportProvider phone2b = new HuaweiSupportProvider(new Context(), new BandDevice(MAC, "HUAWEI Band 10-ABC"), false, rec2b);
        band2b.phone = phone2b;
        main.post(() -> phone2b.onConnected(band2b));
        Handler.runUntil(Handler.now + 60000);
        check(phone2b.getState() == HuaweiSupportProvider.State.READY, "reaches READY before the link dies");
        band2b.dropAfter = band2b.seen.size(); // silence from the next request on
        final long t0 = Handler.now;
        phone2b.requestSync();
        Handler.runUntil(Handler.now + 15 * 60000);
        check(rec2b.unresponsive == 1, "dead link reported to the owner (" + rec2b.unresponsive + ")");
        check(rec2b.unresponsiveAt > 0 && rec2b.unresponsiveAt - t0 <= 20000,
                "dead link spotted within 20 s (" + (rec2b.unresponsiveAt - t0) + " ms)");

        System.out.println("Scenario 3: older band without the sleep file, band never answers file requests");
        FakeBand band3 = new FakeBand(base, "11:22:33:44:55:77");
        band3.dictSleep = false;
        Recorder rec3 = new Recorder();
        HuaweiSupportProvider phone3 = new HuaweiSupportProvider(new Context(), new BandDevice("11:22:33:44:55:77", "x"), true, rec3);
        band3.phone = phone3;
        main.post(() -> phone3.onConnected(band3));
        Handler.runUntil(Handler.now + 60000);
        check(phone3.getState() == HuaweiSupportProvider.State.READY, "older band reaches READY");
        phone3.requestSync();
        Handler.runUntil(Handler.now + 10 * 60000);
        check(rec3.syncs == 1 && rec3.lastOk, "sync finishes even though the TruSleep file never arrives");
        check(rec3.steps == 6 && rec3.sleep == 2, "minute data and sleep messages kept (" + rec3.steps + ", " + rec3.sleep + ")");
        check(rec3.unresponsive == 0, "slow file download is not taken as a dead link");

        System.out.println("Scenario 4: band never answers link params");
        FakeBand band4 = new FakeBand(base, "11:22:33:44:55:66");
        band4.dropAfter = 0;
        Recorder rec4 = new Recorder();
        HuaweiSupportProvider phone4 = new HuaweiSupportProvider(new Context(), new BandDevice("11:22:33:44:55:66", "x"), true, rec4);
        band4.phone = phone4;
        main.post(() -> phone4.onConnected(band4));
        Handler.runUntil(Handler.now + 200000);
        check(!band4.connected, "phone gives up and disconnects");
        check(phone4.getState() == HuaweiSupportProvider.State.DISCONNECTED, "state DISCONNECTED");

        System.out.println("Scenario 5: automatic calibration policy (6 h gap, 3 a day) and participant code");
        long cT = 1_000_000_000_000L, h = 3600_000L;
        java.util.List<Long> none = br.dimensono.bandsono.CalibrationPolicy.recent("", cT);
        check(br.dimensono.bandsono.CalibrationPolicy.allowed(none, cT), "no previous attempt -> allowed");
        java.util.List<Long> one = br.dimensono.bandsono.CalibrationPolicy.recent(String.valueOf(cT - 5 * h), cT);
        check(!br.dimensono.bandsono.CalibrationPolicy.allowed(one, cT), "attempt 5 h ago -> blocked");
        check(br.dimensono.bandsono.CalibrationPolicy.allowed(one, cT + 1 * h), "attempt 6 h ago -> allowed");
        String three = (cT - 20 * h) + "," + (cT - 13 * h) + "," + (cT - 7 * h);
        java.util.List<Long> th = br.dimensono.bandsono.CalibrationPolicy.recent(three, cT);
        check(th.size() == 3 && !br.dimensono.bandsono.CalibrationPolicy.allowed(th, cT), "3 attempts in 24 h -> blocked");
        java.util.List<Long> oldAtt = br.dimensono.bandsono.CalibrationPolicy.recent((cT - 30 * h) + "," + (cT - 25 * h) + ",lixo," + (cT - 7 * h), cT);
        check(oldAtt.size() == 1 && br.dimensono.bandsono.CalibrationPolicy.allowed(oldAtt, cT), "attempts older than 24 h and garbage are dropped");
        check(br.dimensono.bandsono.CalibrationPolicy.serialize(java.util.Arrays.asList(1L, 2L)).equals("1,2"), "attempts serialize");
        check("ABC123".equals(br.dimensono.bandsono.ParticipantCode.normalize("  abc123 ")), "code is trimmed and upper-cased");
        check("P-1A2B3C".equals(br.dimensono.bandsono.ParticipantCode.normalize("p-1a2b3c")), "fallback style code accepted");
        check(br.dimensono.bandsono.ParticipantCode.normalize("ab") == null, "too short code refused");
        check(br.dimensono.bandsono.ParticipantCode.normalize("-ABC123") == null, "code starting with dash refused");
        check(br.dimensono.bandsono.ParticipantCode.normalize("ABC 123") == null, "code with space refused");
        check(br.dimensono.bandsono.ParticipantCode.normalize(null) == null, "null code refused");
        check(br.dimensono.bandsono.ParticipantCode.normalize("DMS-ABCD1234") == null, "professional code is never a participant code");
        check(br.dimensono.bandsono.ParticipantCode.normalize("5511999998888") != null, "phone digits accepted as participant code");
        check("DMS-ABCD1234".equals(br.dimensono.bandsono.ParticipantCode.normalizeProfessional(" dms-abcd1234 ")), "professional code is trimmed and upper-cased");
        check(br.dimensono.bandsono.ParticipantCode.normalizeProfessional("DMS-ABC") == null, "short professional code refused");
        check(br.dimensono.bandsono.ParticipantCode.normalizeProfessional("ABCD1234") == null, "professional code needs the DMS- prefix");

        for (int em = 0; em <= 1; em++) {
            String mac = "11:22:33:44:55:B" + em, tag = "6." + em + ": ";
            System.out.println("Scenario 6." + em + ": HiChain band (encryptMethod " + em + "): first pairing, sync, reconnect, untrusted phone");
            FakeBand hb = hiChainBand(base, mac, em);
            Recorder hr = new Recorder();
            BandDevice hd = new BandDevice(mac, "HUAWEI Band 10-ABC");
            HuaweiSupportProvider hp = pair(hb, hd, true, hr);
            check(hr.authFail == null, tag + "first pairing: no authentication failure (" + hr.authFail + ")");
            check(hp.getState() == HuaweiSupportProvider.State.READY, tag + "first pairing reaches READY (" + hp.getState() + ")");
            check(hb.seen.size() > 4 && hb.seen.subList(0, 4).equals(Arrays.asList("01/01", "01/33", "01/2c", "01/28")),
                    tag + "link params, security negotiation, PIN, then HiChain (no 0x13/0x0F)");
            check(hb.hiChainSteps.equals(Arrays.asList("1/1", "1/2", "1/3", "1/4", "2/1", "2/2", "2/4")),
                    tag + "bind (op 1, steps 1-4) then auth (op 2, steps 1, 2, 4): " + hb.hiChainSteps);
            String authIdHex = GB.hexdump(devPrefs(mac).getString("fake_android_id", "").getBytes(StandardCharsets.UTF_8));
            String storedKey = devPrefs(mac).getString("authkey", null);
            check(storedKey != null && hb.trust.containsKey(authIdHex) && GB.hexdump(hb.trust.get(authIdHex)).equals(storedKey),
                    tag + "phone stored the authToken the band trusts it with");
            byte[] k1 = hb.bp.getSecretKey();
            check(k1 != null && k1.length == 32 && Arrays.equals(k1, hp.getParamsProvider().getSecretKey()),
                    tag + "both sides derived the same 32-byte session key");
            check("HUAWEI Band 10".equals(hd.getModel()) && "5.0.0.167(SIM)".equals(hd.getFirmwareVersion()),
                    tag + "encrypted product info decoded (" + hd.getModel() + ", " + hd.getFirmwareVersion() + ")");
            check(hr.battery == 77, tag + "battery level read (" + hr.battery + ")");
            check(hb.errors.isEmpty(), tag + "band saw no protocol errors " + hb.errors);
            hp.requestSync();
            Handler.runUntil(Handler.now + 180000);
            check(hr.syncs == 1 && hr.lastOk, tag + "sync finished OK");
            check(hr.steps == 6 && hr.lastHr == 150 && hr.lastSpo == 96,
                    tag + "minute data decoded (" + hr.steps + ", HR " + hr.lastHr + ", SpO2 " + hr.lastSpo + ")");
            check(hr.nights.size() == 1 && hb.seen.contains("2c/04"), tag + "sleep file downloaded over 0x2C and parsed");
            check(hr.workouts.size() == 1 && hr.workoutSamples.size() == 10, tag + "workout and its 10 samples downloaded");
            check(hp.getState() == HuaweiSupportProvider.State.READY && hb.errors.isEmpty(), tag + "back to READY, no band errors " + hb.errors);

            // reconnect with the stored authkey: op 2 only
            hp.disconnect();
            Handler.runUntil(Handler.now + 1000);
            hb.reconnect();
            Recorder hr2 = new Recorder();
            HuaweiSupportProvider hp2 = pair(hb, new BandDevice(mac, "HUAWEI Band 10-ABC"), false, hr2);
            check(hr2.authFail == null && hp2.getState() == HuaweiSupportProvider.State.READY,
                    tag + "reconnect reaches READY (" + hp2.getState() + ", " + hr2.authFail + ")");
            check(!hb.seen.contains("01/2c"), tag + "reconnect asks no PIN (0x2C not sent)");
            check(hb.hiChainSteps.equals(Arrays.asList("2/1", "2/2", "2/4")), tag + "reconnect runs only HiChain op 2: " + hb.hiChainSteps);
            check(storedKey.equals(devPrefs(mac).getString("authkey", null)), tag + "stored authkey unchanged");
            byte[] k2 = hb.bp.getSecretKey();
            check(k2 != null && Arrays.equals(k2, hp2.getParamsProvider().getSecretKey()) && !Arrays.equals(k1, k2),
                    tag + "fresh session key, the same on both sides");
            hp2.requestSync();
            Handler.runUntil(Handler.now + 180000);
            check(hr2.syncs == 1 && hr2.lastOk && hb.errors.isEmpty(), tag + "sync after reconnect finished OK " + hb.errors);
            hp2.disconnect();
            Handler.runUntil(Handler.now + 1000);

            // a band that does not trust this phone: formatted, or bound to another phone
            for (int variant = 0; variant < 2; variant++) {
                String what = variant == 0 ? "formatted band" : "band bound to another phone";
                FakeBand ub = hiChainBand(base, mac, em);
                if (variant == 1) {
                    byte[] otherKey = new byte[32];
                    new Random(9).nextBytes(otherKey);
                    ub.trust.put(GB.hexdump("0123456789ABCDEF0123456789ABCDEF".getBytes(StandardCharsets.UTF_8)), otherKey);
                }
                Recorder ur = new Recorder();
                HuaweiSupportProvider up = pair(ub, new BandDevice(mac, "HUAWEI Band 10-ABC"), false, ur);
                check(ur.authFail != null, tag + what + ": phone reports an authentication failure (" + ur.authFail + ")");
                check(up.getState() == HuaweiSupportProvider.State.DISCONNECTED && !ub.connected, tag + what + ": phone disconnects");
                check(ub.errors.size() == 1 && ub.errors.get(0).contains("does not trust"), tag + what + ": band refused op 2 " + ub.errors);
                check(!ub.seen.contains("01/2c") && ub.hiChainSteps.isEmpty(), tag + what + ": phone does not start a new bind by itself " + ub.seen);
            }
            // the band trusts this phone under another key (the phone kept a stale authkey)
            FakeBand sb = hiChainBand(base, mac, em);
            byte[] stale = new byte[32];
            new Random(10).nextBytes(stale);
            sb.trust.put(authIdHex, stale);
            Recorder sr = new Recorder();
            HuaweiSupportProvider sp = pair(sb, new BandDevice(mac, "HUAWEI Band 10-ABC"), false, sr);
            check(sr.authFail != null && sp.getState() == HuaweiSupportProvider.State.DISCONNECTED,
                    tag + "stale key: phone rejects the band's step 1 token and disconnects (" + sr.authFail + ")");
            check(sb.hiChainSteps.equals(Arrays.asList("2/1")), tag + "stale key: nothing after op 2 step 1 " + sb.hiChainSteps);
        }

        System.out.println("Scenario 7: BandServer line protocol");
        List<String> out = new ArrayList<>();
        BandServer srv = new BandServer(BandServer.Options.parse(new String[]{"--encrypt-method", "1"}), out::add);
        check(out.size() == 1 && out.get(0).startsWith("L band ready"), "7: server announces itself with an L line");
        out.clear();
        srv.handleLine("W 5A000B0001010100020003000400F13B"); // GetLinkParams exactly as the Android app sends it
        StringBuilder nHex = new StringBuilder();
        boolean chunked = true;
        for (String l : out) if (l.startsWith("N ")) { nHex.append(l.substring(2)); chunked &= l.length() <= 2 + 40; }
        DeviceConfig.LinkParams.Response lpr = null;
        try {
            HuaweiPacket pkt = new HuaweiPacket(new HuaweiPacket.ParamsProvider()).parse(GB.hexStringToByteArray(nHex.toString()));
            if (pkt instanceof DeviceConfig.LinkParams.Response) {
                lpr = (DeviceConfig.LinkParams.Response) pkt;
                lpr.parseTlv();
            }
        } catch (HuaweiPacket.ParseException e) {
            lpr = null;
        }
        check(lpr != null && lpr.deviceSupportType == 4 && lpr.encryptMethod == 1 && lpr.authVersion == 2 && lpr.sliceSize == 0xF4,
                "7: link params answered in N lines (supportType 4, encryptMethod 1, authVersion 2, slice 244)");
        check(chunked, "7: notifications cut into 20-byte chunks");
        out.clear();
        srv.handleLine("W 5A000B0001010100020003000400F13C");
        check(out.size() == 1 && out.get(0).startsWith("E bad CRC"), "7: bad CRC reported as an E line " + out);
        out.clear();
        srv.handleLine("W 5A000B00010101000200");
        boolean waited = out.isEmpty();
        srv.handleLine("w 03000400f13b");
        check(waited && out.stream().anyMatch(l -> l.startsWith("N 5A")), "7: a frame split over two W lines (one lower case) is reassembled");
        out.clear();
        srv.handleLine("W " + GB.hexdump(frame(1, 7, new HuaweiTLV().put(0x07).put(0x0A))));
        check(out.stream().anyMatch(l -> l.startsWith("E ") && l.contains("before HiChain authentication"))
                && out.stream().noneMatch(l -> l.startsWith("N ")), "7: product info before authentication refused, not answered");
        out.clear();
        srv.handleLine("W " + GB.hexdump(frame(0x55, 0x01, new HuaweiTLV().put(0x01))));
        check(out.stream().anyMatch(l -> l.startsWith("E unexpected command 55/01")), "7: unknown command reported " + out);
        out.clear();
        srv.handleLine("hello");
        check(out.size() == 1 && out.get(0).startsWith("E "), "7: malformed input line reported");
        check(!srv.handleLine("Q"), "7: Q ends the session");

        try { // byte parity: the Java client's own packets pass, altered ones are reported (and still answered)
            HuaweiPacket.ParamsProvider pp = new HuaweiPacket.ParamsProvider();
            pp.setSliceSize(0xF4);
            byte[] uuid = "0123456789ABCDEF0123456789ABCDEF".getBytes(StandardCharsets.UTF_8), salt = new byte[16], seed = new byte[32];
            Arrays.fill(salt, (byte) 0xAB);
            Arrays.fill(seed, (byte) 0xCD);
            DeviceConfig.HiChain.Request hcr = new DeviceConfig.HiChain.Request(1, 1234567890123L, uuid, HuaweiConstants.GROUP_ID);
            HuaweiPacket step1 = hcr.new StepOne(pp, 1, salt, seed);
            String json = step1.getTlv().getString(0x01);
            String[] good = {"5A000B0001010100020003000400F13B",
                    hexFrames(new DeviceConfig.SecurityNegotiation.Request(pp, (byte) 4, uuid, "TestPhone").serialize()),
                    hexFrames(new DeviceConfig.PinCode.Request(pp).serialize()),
                    hexFrames(step1.serialize())}; // 3 slices in one W line
            String[] bad = {GB.hexdump(frame(1, 0x33, new HuaweiTLV().put(0x01, (byte) 4).put(0x02, (byte) 1).put(0x05, uuid)
                            .put(0x03, (byte) 1).put(0x04, (byte) 0).put(0x07, "TestPhone").put(0x06))), // tags 0x06/0x07 swapped
                    GB.hexdump(frame(1, 0x28, new HuaweiTLV().put(0x01, json.replace(GB.hexdump(seed), GB.hexdump(seed).toLowerCase()))
                            .put(0x02, (byte) 1).put(0x03, step1.getTlv().getBytes(0x03))))}; // seed hex in lower case
            for (boolean parityOn : new boolean[]{true, false}) {
                List<String> po = new ArrayList<>();
                BandServer ps = new BandServer(BandServer.Options.parse(parityOn ? new String[0] : new String[]{"--no-strict"}), po::add);
                for (String w : good) ps.handleLine("W " + w);
                List<String> goodErrors = new ArrayList<>();
                for (String l : po) if (l.startsWith("E ")) goodErrors.add(l);
                boolean goodAnswered = po.stream().anyMatch(l -> l.startsWith("L HiChain op 1 step 1 ok"));
                po.clear();
                for (String w : bad) ps.handleLine("W " + w);
                long parityErrors = po.stream().filter(l -> l.startsWith("E parity 01/33: expected ") || l.startsWith("E parity 01/28: expected ")).count();
                boolean badAnswered = po.stream().filter(l -> l.startsWith("N ")).count() >= 2;
                if (parityOn) {
                    check(goodErrors.isEmpty() && goodAnswered, "7: Java-built link params, 0x33, PIN and sliced HiChain step 1 pass parity " + goodErrors);
                    check(parityErrors == 2 && badAnswered, "7: reordered 0x33 tags and lower-case seed hex give 'E parity', still answered");
                } else {
                    check(goodErrors.isEmpty() && parityErrors == 0 && badAnswered, "7: --no-strict turns the parity check off");
                }
            }
        } catch (Exception e) {
            check(false, "7: parity test threw " + e);
        }

        String macL = "11:22:33:44:55:F7";
        LineLink link = new LineLink("--encrypt-method", "1", "--auth-version", "3", "--slice", "128", "--notify-chunk", "17");
        Recorder lr = new Recorder();
        BandDevice ld = new BandDevice(macL, "HUAWEI Band 10-ABC");
        HuaweiSupportProvider lp1 = new HuaweiSupportProvider(new Context(), ld, true, lr);
        link.phone = lp1;
        main.post(() -> lp1.onConnected(link));
        Handler.runUntil(Handler.now + 60000);
        check(lr.authFail == null && lp1.getState() == HuaweiSupportProvider.State.READY,
                "7: first pairing through the line protocol reaches READY (" + lp1.getState() + ", " + lr.authFail + ")");
        check(link.logs.stream().anyMatch(l -> l.startsWith("authenticated")), "7: server logs 'authenticated'");
        check("HUAWEI Band 10".equals(ld.getModel()), "7: product info from the server decoded (" + ld.getModel() + ")");
        check(link.wLines > link.server.band.seen.size(), "7: writes were re-cut (" + link.wLines + " W lines)");
        check(link.errors.isEmpty(), "7: no E lines " + link.errors);
        lp1.requestSync();
        Handler.runUntil(Handler.now + 180000);
        check(lr.syncs == 1 && lr.lastOk && link.errors.isEmpty(), "7: sync through the line protocol finished OK " + link.errors);
        lp1.disconnect();
        Handler.runUntil(Handler.now + 1000);
        int seenMark = link.server.band.seen.size(), stepMark = link.server.band.hiChainSteps.size();
        link.connected = true;
        Recorder lr2 = new Recorder();
        HuaweiSupportProvider lp2 = new HuaweiSupportProvider(new Context(), new BandDevice(macL, "HUAWEI Band 10-ABC"), false, lr2);
        link.phone = lp2;
        main.post(() -> lp2.onConnected(link));
        Handler.runUntil(Handler.now + 60000);
        List<String> seenAgain = link.server.band.seen.subList(seenMark, link.server.band.seen.size());
        List<String> stepsAgain = link.server.band.hiChainSteps.subList(stepMark, link.server.band.hiChainSteps.size());
        check(lr2.authFail == null && lp2.getState() == HuaweiSupportProvider.State.READY && !seenAgain.contains("01/2c")
                        && stepsAgain.equals(Arrays.asList("2/1", "2/2", "2/4")),
                "7: after D, reconnect runs op 2 only and reaches READY " + stepsAgain);
        check(link.errors.isEmpty(), "7: still no E lines " + link.errors);

        try {
            extrasScenarios(base);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
