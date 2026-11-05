package br.dimensono.bandsono;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.dimensono.bandsono.huawei.HuaweiSupportProvider;
import br.dimensono.bandsono.log.LogBuffer;
import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** Bridge between the Flutter UI and the native band service. */
@SuppressLint("MissingPermission")
public class BandChannel implements MethodChannel.MethodCallHandler {
    private static final Logger LOG = LoggerFactory.getLogger(BandChannel.class);
    private static final int PERMISSION_REQUEST = 7311;
    private static final long SCAN_DURATION_MS = 20000;

    private final Activity activity;
    private final MethodChannel methods;
    private final EventChannel statusEvents;
    private final EventChannel scanEvents;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private EventChannel.EventSink statusSink;
    private EventChannel.EventSink scanSink;
    private MethodChannel.Result pendingPermissionResult;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;

    private final BandStatus.Observer statusObserver = new BandStatus.Observer() {
        @Override
        public void onStatus(Map<String, Object> status) {
            if (statusSink != null)
                statusSink.success(enrich(status));
        }
    };

    private final Runnable stopScanRunnable = new Runnable() {
        @Override
        public void run() {
            stopScan();
        }
    };

    public BandChannel(Activity activity, BinaryMessenger messenger) {
        Settings.provisionDefaults(activity.getApplicationContext());
        this.activity = activity;
        methods = new MethodChannel(messenger, "bandsono/band");
        methods.setMethodCallHandler(this);
        statusEvents = new EventChannel(messenger, "bandsono/status");
        statusEvents.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                statusSink = events;
                BandStatus.addObserver(statusObserver);
            }

            @Override
            public void onCancel(Object arguments) {
                BandStatus.removeObserver(statusObserver);
                statusSink = null;
            }
        });
        scanEvents = new EventChannel(messenger, "bandsono/scan");
        scanEvents.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                scanSink = events;
            }

            @Override
            public void onCancel(Object arguments) {
                scanSink = null;
                stopScan();
            }
        });
    }

    public void dispose() {
        stopScan();
        BandStatus.removeObserver(statusObserver);
        methods.setMethodCallHandler(null);
        statusEvents.setStreamHandler(null);
        scanEvents.setStreamHandler(null);
        io.shutdown();
    }

    private Context ctx() {
        return activity.getApplicationContext();
    }

    private Map<String, Object> enrich(Map<String, Object> status) {
        Map<String, Object> m = new HashMap<>(status);
        Context c = ctx();
        if (m.get("address") == null)
            m.put("address", Settings.deviceAddress(c));
        if (m.get("name") == null)
            m.put("name", Settings.deviceName(c));
        m.put("lastSync", Settings.prefs(c).getLong(Settings.LAST_SYNC, 0));
        m.put("lastSyncOk", Settings.prefs(c).getBoolean(Settings.LAST_SYNC_OK, true));
        m.put("lastSyncSummary", Settings.prefs(c).getString(Settings.LAST_SYNC_SUMMARY, ""));
        m.put("nextSync", Settings.prefs(c).getLong(Settings.NEXT_SYNC, 0));
        // Supabase upload status (the pending count comes from the last published status)
        m.putAll(SupabaseUploader.status(c));
        m.put("participant", Settings.participant(c));
        m.put("participantLinked", Settings.participantLinked(c));
        m.put("participantDeleted", Settings.participantDeleted(c));
        m.put("consent", Settings.consent(c));
        m.put("consentAsked", Settings.consentAsked(c));
        m.put("pendingParticipant", Settings.pendingParticipant(c));
        m.put("linkError", Settings.linkError());
        m.put("hasSession", Account.hasSession(c));
        m.put("sessionExpired", Account.expired(c));
        m.put("accountPhone", Account.phoneSuffix(c));
        m.put("professionalPrefill", Settings.professionalPrefill());
        if (m.get("calibrating") == null)
            m.put("calibrating", false);
        return m;
    }

    // ------------------------------------------------------------------ method calls

    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {
        try {
            switch (call.method) {
                case "status":
                    result.success(enrich(BandStatus.snapshot()));
                    break;
                case "permissionsGranted":
                    result.success(missingPermissions().isEmpty());
                    break;
                case "requestPermissions":
                    requestPermissions(result);
                    break;
                // Phone features of the band (notifications, calls, weather, find phone) are off in this build.
                // The methods stay so an older UI keeps working; they report "off" and do nothing.
                case "featureStatus": {
                    Map<String, Object> f = new HashMap<>();
                    f.put("notificationAccess", false);
                    f.put("location", false);
                    f.put("calls", false);
                    result.success(f);
                    break;
                }
                case "openNotificationAccess":
                case "requestFeaturePermission":
                case "findBand":
                case "refreshWeather":
                    result.success(false);
                    break;
                case "setParticipantCode": {
                    boolean ok = Settings.applyParticipantCode(ctx(), call.argument("code"));
                    result.success(ok);
                    break;
                }
                case "accountSave":
                    Account.save(ctx(), call.argument("token"), call.argument("userId"), call.argument("phone"));
                    Settings.publishLinkStatus(ctx());
                    result.success(true);
                    break;
                case "accountLoad": {
                    Map<String, Object> a = new HashMap<>();
                    a.put("token", Account.token(ctx()));
                    a.put("userId", Account.userId(ctx()));
                    a.put("phoneSuffix", Account.phoneSuffix(ctx()));
                    a.put("expired", Account.expired(ctx()));
                    result.success(a);
                    break;
                }
                case "accountExpired":
                    Account.setExpired(ctx(), Boolean.TRUE.equals(call.argument("value")));
                    Settings.publishLinkStatus(ctx());
                    result.success(true);
                    break;
                case "accountClear":
                    Account.clear(ctx());
                    Settings.publishLinkStatus(ctx());
                    result.success(true);
                    break;
                case "clearProfessionalPrefill":
                    Settings.clearProfessionalPrefill(ctx());
                    result.success(true);
                    break;
                case "apiBaseUrl":
                    result.success(Settings.dimensonoApiUrl(ctx()));
                    break;
                case "openUrl": {
                    String url = call.argument("url");
                    boolean ok = false;
                    if (url != null && url.startsWith("https://dimensono.com.br/")) {
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(i);
                            ok = true;
                        } catch (Exception e) {
                            ok = false;
                        }
                    }
                    result.success(ok);
                    break;
                }
                case "wipeAll": {
                    // Account deleted on the server: erase this phone too (the band service stops first)
                    final boolean hadBand = Settings.deviceAddress(ctx()) != null;
                    if (hadBand)
                        BandService.start(ctx(), BandService.ACTION_FORGET);
                    runIo(result, () -> {
                        Settings.wipeLocal(ctx());
                        return true;
                    });
                    break;
                }
                case "unlinkParticipant":
                    Settings.unlinkParticipant(ctx());
                    result.success(true);
                    break;
                case "confirmParticipantChange":
                    result.success(Settings.resolvePendingParticipant(ctx(), true));
                    break;
                case "rejectParticipantChange":
                    result.success(Settings.resolvePendingParticipant(ctx(), false));
                    break;
                case "setConsent": {
                    Settings.applyConsent(ctx(), Boolean.TRUE.equals(call.argument("value")));
                    result.success(true);
                    break;
                }
                case "bluetoothEnabled":
                    result.success(bluetoothEnabled());
                    break;
                case "enableBluetooth":
                    try {
                        activity.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                        result.success(true);
                    } catch (Exception e) {
                        result.success(false);
                    }
                    break;
                case "startScan":
                    result.success(startScan());
                    break;
                case "stopScan":
                    stopScan();
                    result.success(true);
                    break;
                case "pair": {
                    stopScan();
                    String address = call.argument("address");
                    String name = call.argument("name");
                    Intent i = new Intent(ctx(), BandService.class)
                            .setAction(BandService.ACTION_PAIR)
                            .putExtra(BandService.EXTRA_ADDRESS, address)
                            .putExtra(BandService.EXTRA_NAME, name);
                    Settings.put(ctx(), Settings.BACKGROUND, true);
                    BandService.startCompat(ctx(), i);
                    result.success(true);
                    break;
                }
                case "syncNow":
                    BandService.start(ctx(), BandService.ACTION_SYNC);
                    result.success(true);
                    break;
                case "reconnect":
                    if (Settings.deviceAddress(ctx()) != null)
                        BandService.start(ctx(), BandService.ACTION_RECONNECT);
                    result.success(true);
                    break;
                case "startService":
                    if (Settings.deviceAddress(ctx()) != null && Settings.background(ctx()) && missingPermissions().isEmpty())
                        BandService.start(ctx(), BandService.ACTION_START);
                    SupabaseUploader.ensureScheduled(ctx(), false);
                    runIo(result, () -> {
                        BandStatus.update("sbPending", BandDatabase.get(ctx()).pendingUploads());
                        return true;
                    });
                    break;
                case "uploadNow":
                    if (!SupabaseUploader.enabled(ctx())) {
                        result.success(false);
                        break;
                    }
                    SupabaseUploader.runAsync(ctx(), "manual", null);
                    result.success(true);
                    break;
                case "uploadTest":
                    runIo(result, () -> SupabaseUploader.test(ctx()));
                    break;
                case "forget":
                    BandService.start(ctx(), BandService.ACTION_FORGET);
                    result.success(true);
                    break;
                case "getSettings":
                    result.success(Settings.all(ctx()));
                    break;
                case "setSetting":
                    setSetting(call.argument("key"), call.argument("value"));
                    result.success(true);
                    break;
                case "minutes": {
                    final long from = asLong(call.argument("from"));
                    final long to = asLong(call.argument("to"));
                    runIo(result, () -> BandDatabase.get(ctx()).minutes(from, to));
                    break;
                }
                case "sleep": {
                    final long from = asLong(call.argument("from"));
                    final long to = asLong(call.argument("to"));
                    runIo(result, () -> BandDatabase.get(ctx()).sleep(from, to));
                    break;
                }
                case "nights": {
                    final long from = asLong(call.argument("from"));
                    final long to = asLong(call.argument("to"));
                    runIo(result, () -> BandDatabase.get(ctx()).nights(from, to));
                    break;
                }
                case "stress": {
                    final long from = asLong(call.argument("from"));
                    final long to = asLong(call.argument("to"));
                    runIo(result, () -> BandDatabase.get(ctx()).stress(from, to));
                    break;
                }
                case "dict": {
                    final String kind = call.argument("kind");
                    final long from = asLong(call.argument("from"));
                    final long to = asLong(call.argument("to"));
                    runIo(result, () -> BandDatabase.get(ctx()).dict(kind, from, to));
                    break;
                }
                case "rrTests": {
                    final int limit = (int) Math.max(1, asLong(call.argument("limit")));
                    runIo(result, () -> BandDatabase.get(ctx()).rrTests(limit));
                    break;
                }
                case "startRrTest": {
                    int seconds = (int) Math.max(60, asLong(call.argument("seconds")));
                    Intent i = new Intent(ctx(), BandService.class)
                            .setAction(BandService.ACTION_RR_TEST)
                            .putExtra(BandService.EXTRA_DURATION, seconds);
                    BandService.startCompat(ctx(), i);
                    result.success(true);
                    break;
                }
                case "cancelRrTest":
                    BandService.start(ctx(), BandService.ACTION_RR_CANCEL);
                    result.success(true);
                    break;
                case "stats":
                    runIo(result, () -> BandDatabase.get(ctx()).stats());
                    break;
                case "overview": {
                    final long dayStart = asLong(call.argument("dayStart"));
                    runIo(result, () -> BandDatabase.get(ctx()).overview(dayStart));
                    break;
                }
                case "sleepStages": {
                    final long from = asLong(call.argument("from"));
                    final long to = asLong(call.argument("to"));
                    runIo(result, () -> BandDatabase.get(ctx()).sleepStages(from, to));
                    break;
                }
                case "workoutSamples": {
                    final long start = asLong(call.argument("start"));
                    runIo(result, () -> BandDatabase.get(ctx()).workoutSamples(start));
                    break;
                }
                case "workouts": {
                    final int limit = (int) Math.max(1, asLong(call.argument("limit")));
                    runIo(result, () -> BandDatabase.get(ctx()).workouts(limit));
                    break;
                }
                case "clearData":
                    runIo(result, () -> {
                        BandDatabase.get(ctx()).clearAll();
                        Settings.prefs(ctx()).edit().remove(Settings.LAST_SYNC).apply();
                        BandStatus.update("dataVersion", System.currentTimeMillis());
                        return true;
                    });
                    break;
                case "log":
                    result.success(LogBuffer.dump());
                    break;
                case "clearLog":
                    LogBuffer.clear();
                    result.success(true);
                    break;
                case "shareLog":
                    shareText("Log de conexão BandSono", tail(LogBuffer.dump(), 300000));
                    result.success(true);
                    break;
                case "exportCsv": {
                    final long from = asLong(call.argument("from"));
                    final long to = asLong(call.argument("to"));
                    runIo(result, () -> exportCsv(from, to));
                    break;
                }
                case "batteryOptimizationIgnored":
                    result.success(isIgnoringBatteryOptimizations());
                    break;
                case "requestIgnoreBatteryOptimization":
                    requestIgnoreBatteryOptimization();
                    result.success(true);
                    break;
                default:
                    result.notImplemented();
            }
        } catch (Exception e) {
            LOG.error("Method " + call.method + " failed", e);
            result.error("native_error", e.toString(), null);
        }
    }

    private interface IoTask {
        Object run() throws Exception;
    }

    private void runIo(final MethodChannel.Result result, final IoTask task) {
        io.execute(() -> {
            try {
                final Object value = task.run();
                main.post(() -> result.success(value));
            } catch (final Exception e) {
                LOG.error("IO task failed", e);
                main.post(() -> result.error("io_error", e.toString(), null));
            }
        });
    }

    private static long asLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        return 0;
    }

    private void setSetting(String key, Object value) {
        if (key == null)
            return;
        // Fixed for the study: ignore attempts to change these
        if (Settings.BACKGROUND.equals(key) || Settings.INTERVAL_HOURS.equals(key) || Settings.CONSENT.equals(key)
                || Settings.PARTICIPANT_LINKED.equals(key) || HuaweiSupportProvider.PREF_NOTIFICATIONS.equals(key)
                || HuaweiSupportProvider.PREF_LIFT_WRIST.equals(key))
            return;
        // New flow: the participant code comes only from the server
        if (SupabaseUploader.KEY_PARTICIPANT.equals(key) && Account.newFlow(ctx()))
            return;
        Object old = Settings.prefs(ctx()).getAll().get(key);
        Settings.put(ctx(), key, value);
        if (key.startsWith("sb_")) {
            boolean changed = old == null ? value != null : !old.equals(value);
            if (changed) {
                if (SupabaseUploader.KEY_ENABLED.equals(key) && value instanceof Boolean)
                    Settings.prefs(ctx()).edit().putBoolean(Settings.CONSENT, (Boolean) value).apply();
                SupabaseUploader.onSettingChanged(ctx(), key);
            }
            return;
        }
        boolean paired = Settings.deviceAddress(ctx()) != null;
        if (!paired)
            return;
        if (HuaweiSupportProvider.PREF_HR_AUTO.equals(key) || HuaweiSupportProvider.PREF_SPO2_AUTO.equals(key)
                || HuaweiSupportProvider.PREF_HR_REALTIME.equals(key) || HuaweiSupportProvider.PREF_STRESS_AUTO.equals(key)
                || HuaweiSupportProvider.PREF_TRUSLEEP.equals(key) || HuaweiSupportProvider.PREF_INACTIVITY.equals(key)
                || HuaweiSupportProvider.PREF_SLEEP_BREATH.equals(key) || HuaweiSupportProvider.PREF_LIFT_WRIST.equals(key)
                || HuaweiSupportProvider.PREF_WEAR_LEFT.equals(key)) {
            BandService.start(ctx(), BandService.ACTION_APPLY_SETTINGS);
        }
    }

    // ------------------------------------------------------------------ permissions

    private List<String> requiredPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return p;
    }

    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 23)
            return missing;
        for (String p : requiredPermissions())
            if (activity.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED)
                missing.add(p);
        return missing;
    }

    private void requestPermissions(MethodChannel.Result result) {
        List<String> toAsk = missingPermissions();
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            toAsk.add(Manifest.permission.POST_NOTIFICATIONS);
        if (toAsk.isEmpty() || Build.VERSION.SDK_INT < 23) {
            result.success(true);
            return;
        }
        if (pendingPermissionResult != null)
            pendingPermissionResult.success(false);
        pendingPermissionResult = result;
        activity.requestPermissions(toAsk.toArray(new String[0]), PERMISSION_REQUEST);
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST || pendingPermissionResult == null)
            return;
        MethodChannel.Result r = pendingPermissionResult;
        pendingPermissionResult = null;
        r.success(missingPermissions().isEmpty());
    }

    // ------------------------------------------------------------------ scanning

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) ctx().getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    private boolean bluetoothEnabled() {
        BluetoothAdapter a = adapter();
        return a != null && a.isEnabled();
    }

    /** Automatic retries of a failed BLE scan before falling back to classic discovery. */
    private int scanRetries = 0;
    private android.content.BroadcastReceiver discoveryReceiver = null;

    private boolean startScan() {
        scanRetries = 0;
        return startBleScan();
    }

    private boolean startBleScan() {
        if (!missingPermissions().isEmpty())
            return false;
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled())
            return false;
        stopScan();
        scanner = a.getBluetoothLeScanner();
        if (scanner == null)
            return false;
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                emitScanResult(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult r : results) emitScanResult(r);
            }

            @Override
            public void onScanFailed(int errorCode) {
                LOG.warn("Scan failed: {} (retry {})", errorCode, scanRetries);
                // 2 = registration failed, 3 = internal error, 6 = started too often: usually clears on retry
                main.post(() -> {
                    if (scanRetries < 2) {
                        scanRetries++;
                        stopScanQuietly();
                        main.postDelayed(() -> startBleScan(), 1500L * scanRetries);
                    } else if (!startClassicDiscovery()) {
                        if (scanSink != null) {
                            Map<String, Object> m = new HashMap<>();
                            m.put("error", errorCode);
                            scanSink.success(m);
                        }
                    }
                });
            }
        };
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
        } catch (SecurityException e) {
            LOG.error("Scan not permitted", e);
            return false;
        }
        main.removeCallbacks(stopScanRunnable);
        main.postDelayed(stopScanRunnable, SCAN_DURATION_MS);
        return true;
    }

    private void emitScanResult(ScanResult r) {
        if (scanSink == null || r.getDevice() == null)
            return;
        String name = null;
        if (r.getScanRecord() != null)
            name = r.getScanRecord().getDeviceName();
        if (name == null) {
            try {
                name = r.getDevice().getName();
            } catch (SecurityException ignored) {
            }
        }
        if (name == null || name.trim().isEmpty())
            return;
        final Map<String, Object> m = new HashMap<>();
        m.put("address", r.getDevice().getAddress());
        m.put("name", name);
        m.put("rssi", r.getRssi());
        main.post(() -> {
            if (scanSink != null) scanSink.success(m);
        });
    }

    /** Stops the BLE scan without telling the UI it ended (used before a retry). */
    private void stopScanQuietly() {
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanner = null;
        scanCallback = null;
    }

    /**
     * Fallback when the BLE scanner keeps failing: classic discovery also reports LE devices
     * (inquiry + LE scan) through ACTION_FOUND.
     */
    @SuppressLint("MissingPermission")
    private boolean startClassicDiscovery() {
        BluetoothAdapter a = adapter();
        if (a == null || !a.isEnabled()) return false;
        stopDiscovery();
        discoveryReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (!android.bluetooth.BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) return;
                android.bluetooth.BluetoothDevice d = intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
                if (d == null || scanSink == null) return;
                String name = intent.getStringExtra(android.bluetooth.BluetoothDevice.EXTRA_NAME);
                if (name == null) {
                    try {
                        name = d.getName();
                    } catch (SecurityException ignored) {
                    }
                }
                if (name == null || name.trim().isEmpty()) return;
                Map<String, Object> m = new HashMap<>();
                m.put("address", d.getAddress());
                m.put("name", name);
                m.put("rssi", (int) intent.getShortExtra(android.bluetooth.BluetoothDevice.EXTRA_RSSI, (short) -100));
                scanSink.success(m);
            }
        };
        try {
            ctx().registerReceiver(discoveryReceiver, new android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_FOUND));
            boolean ok = a.startDiscovery();
            LOG.info("Classic discovery fallback: {}", ok);
            if (!ok) stopDiscovery();
            else {
                main.removeCallbacks(stopScanRunnable);
                main.postDelayed(stopScanRunnable, SCAN_DURATION_MS);
            }
            return ok;
        } catch (Exception e) {
            LOG.warn("Classic discovery failed: {}", e.toString());
            stopDiscovery();
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void stopDiscovery() {
        if (discoveryReceiver != null) {
            try {
                ctx().unregisterReceiver(discoveryReceiver);
            } catch (Exception ignored) {
            }
            discoveryReceiver = null;
            BluetoothAdapter a = adapter();
            try {
                if (a != null) a.cancelDiscovery();
            } catch (Exception ignored) {
            }
        }
    }

    private void stopScan() {
        main.removeCallbacks(stopScanRunnable);
        boolean wasDiscovering = discoveryReceiver != null;
        stopDiscovery();
        if (wasDiscovering && scanSink != null && scanCallback == null) {
            Map<String, Object> m = new HashMap<>();
            m.put("done", true);
            scanSink.success(m);
        }
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
            if (scanSink != null) {
                Map<String, Object> m = new HashMap<>();
                m.put("done", true);
                scanSink.success(m);
            }
        }
        scanner = null;
        scanCallback = null;
    }

    // ------------------------------------------------------------------ battery optimisation

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) ctx().getSystemService(Context.POWER_SERVICE);
        return pm == null || Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(ctx().getPackageName());
    }

    @SuppressLint("BatteryLife")
    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT < 23)
            return;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + ctx().getPackageName()));
            activity.startActivity(i);
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ export & share

    private void shareText(String subject, String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, subject);
        send.putExtra(Intent.EXTRA_TEXT, text);
        activity.startActivity(Intent.createChooser(send, subject));
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private String exportCsv(long from, long to) throws Exception {
        BandDatabase db = BandDatabase.get(ctx());
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        iso.setTimeZone(TimeZone.getDefault());
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());

        StringBuilder minutes = new StringBuilder("timestamp_unix,data_hora,passos,calorias,fc_bpm,spo2_pct\n");
        Map<String, Object> m = db.minutes(from, to);
        int[] ts = (int[]) m.get("ts");
        int[] steps = (int[]) m.get("steps");
        int[] cal = (int[]) m.get("calories");
        int[] hr = (int[]) m.get("hr");
        int[] spo = (int[]) m.get("spo2");
        for (int i = 0; i < ts.length; i++) {
            minutes.append(ts[i]).append(',')
                    .append(iso.format(new Date(ts[i] * 1000L))).append(',')
                    .append(steps[i]).append(',')
                    .append(cal[i]).append(',')
                    .append(hr[i] < 0 ? "" : String.valueOf(hr[i])).append(',')
                    .append(spo[i] < 0 ? "" : String.valueOf(spo[i])).append('\n');
        }

        StringBuilder sleep = new StringBuilder("inicio_unix,fim_unix,inicio,fim,tipo,origem\n");
        Map<String, Object> s = db.sleep(from, to);
        int[] st = (int[]) s.get("start");
        int[] en = (int[]) s.get("end");
        int[] ty = (int[]) s.get("type");
        int[] so = (int[]) s.get("source");
        for (int i = 0; i < st.length; i++) {
            sleep.append(st[i]).append(',').append(en[i]).append(',')
                    .append(iso.format(new Date(st[i] * 1000L))).append(',')
                    .append(iso.format(new Date(en[i] * 1000L))).append(',')
                    .append(ty[i]).append(',').append(BandDatabase.sleepOrigin(so[i], ty[i])).append('\n');
        }

        String a = writeDownload("bandsono_minutos_" + stamp + ".csv", minutes.toString());
        writeDownload("bandsono_sono_" + stamp + ".csv", sleep.toString());
        // Night summaries computed by the band
        String[] nightCols = {"fallAsleep", "bedTime", "rising", "wakeup", "score", "latency", "efficiency", "wakeCount", "deepPart",
                "minHr", "maxHr", "avgHr", "minSpo2", "maxSpo2", "avgSpo2", "minBreath", "maxBreath", "avgBreath", "avgHrv",
                "minHrvBase", "maxHrvBase", "rdi"};
        StringBuilder nights = new StringBuilder("adormeceu,deitou,levantou,acordou,nota,latencia_min,eficiencia_pct,despertares,"
                + "continuidade_profundo,fc_min,fc_max,fc_media,spo2_min,spo2_max,spo2_media,resp_min,resp_max,resp_media,"
                + "vfc_ms,vfc_base_min,vfc_base_max,rdi\n");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> rows = (java.util.List<Map<String, Object>>) db.nights(from, to).get("rows");
        for (Map<String, Object> r : rows) {
            for (int i = 0; i < nightCols.length; i++) {
                Object v = r.get(nightCols[i]);
                if (i > 0) nights.append(',');
                if (v == null) continue;
                if (i < 4) {
                    long t = ((Number) v).longValue();
                    if (t > 0) nights.append(iso.format(new Date(t * 1000L)));
                } else {
                    double d = ((Number) v).doubleValue();
                    if (d >= 0) nights.append(d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d));
                }
            }
            nights.append('\n');
        }
        writeDownload("bandsono_noites_" + stamp + ".csv", nights.toString());

        StringBuilder stress = new StringBuilder("inicio,fim,estresse,nivel\n");
        Map<String, Object> sm = db.stress(from, to);
        int[] ss = (int[]) sm.get("start");
        int[] se = (int[]) sm.get("end");
        int[] sc = (int[]) sm.get("score");
        int[] sl = (int[]) sm.get("level");
        for (int i = 0; i < ss.length; i++) {
            stress.append(iso.format(new Date(ss[i] * 1000L))).append(',')
                    .append(iso.format(new Date(se[i] * 1000L))).append(',')
                    .append(sc[i]).append(',').append(sl[i]).append('\n');
        }
        writeDownload("bandsono_estresse_" + stamp + ".csv", stress.toString());

        StringBuilder dict = new StringBuilder("data_hora,medida,valor\n");
        for (String kind : new String[]{"hrv", "temp"}) {
            Map<String, Object> dm = db.dict(kind, from * 1000L, to * 1000L);
            long[] dts = (long[]) dm.get("ts");
            double[] dv = (double[]) dm.get("value");
            for (int i = 0; i < dts.length; i++) {
                dict.append(iso.format(new Date(dts[i]))).append(',')
                        .append(kind.equals("hrv") ? "vfc_rmssd_ms" : "temperatura_pele_c").append(',')
                        .append(dv[i]).append('\n');
            }
        }
        writeDownload("bandsono_vfc_temperatura_" + stamp + ".csv", dict.toString());

        StringBuilder rr = new StringBuilder("data_hora,duracao_s,batimentos,rmssd_ms,sdnn_ms,pnn50_pct,fc_media,estresse,rr_ms\n");
        for (Map<String, Object> t : db.rrTests(1000)) {
            long ts0 = ((Number) t.get("ts")).longValue();
            if (ts0 < from * 1000L || ts0 >= to * 1000L) continue;
            int[] rri = (int[]) t.get("rri");
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < rri.length; i++) {
                if (i > 0) list.append(' ');
                list.append(rri[i]);
            }
            rr.append(iso.format(new Date(ts0))).append(',')
                    .append(t.get("durationS")).append(',')
                    .append(t.get("n")).append(',')
                    .append(t.get("rmssd")).append(',')
                    .append(t.get("sdnn")).append(',')
                    .append(t.get("pnn50")).append(',')
                    .append(t.get("meanHr")).append(',')
                    .append(t.get("stress")).append(',')
                    .append(list).append('\n');
        }
        writeDownload("bandsono_medicoes_vfc_" + stamp + ".csv", rr.toString());
        return a;
    }


    private String writeDownload(String fileName, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BandSono");
            Uri uri = ctx().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null)
                throw new IllegalStateException("Não foi possível criar o arquivo");
            try (OutputStream os = ctx().getContentResolver().openOutputStream(uri)) {
                if (os == null)
                    throw new IllegalStateException("Não foi possível abrir o arquivo");
                os.write(bytes);
            }
            return "Downloads/BandSono/" + fileName;
        } else {
            File dir = ctx().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null)
                dir = ctx().getFilesDir();
            File f = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(bytes);
            }
            return f.getAbsolutePath();
        }
    }
}
