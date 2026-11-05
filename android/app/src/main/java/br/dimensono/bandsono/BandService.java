package br.dimensono.bandsono;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import br.dimensono.bandsono.huawei.BandDevice;
import br.dimensono.bandsono.sync.DetailRows;
import br.dimensono.bandsono.huawei.packets.Workout;
import br.dimensono.bandsono.huawei.HuaweiTrueSleepSequenceDataParser;
import br.dimensono.bandsono.huawei.HuaweiStressParser;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;
import br.dimensono.bandsono.huawei.PendingTrajectory;
import br.dimensono.bandsono.huawei.UnknownStepTag;
import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

/**
 * Foreground service that keeps the band connected, activates the automatic health
 * measurements on every connection and downloads the data every N hours (default 2).
 * <p>
 * Reconnection: a few quick active attempts (direct connect, then scan + connect), then the
 * band is left to Android's passive auto-connect and a "kick" alarm (5 → 15 min) refreshes
 * that pending connection with a short scan. A link that is up but no longer answers is torn
 * down and rebuilt, and switching Bluetooth on triggers an immediate reconnect.
 */
public class BandService extends Service implements BleConnection.Callback, HuaweiSupportProvider.Listener {
    private static final Logger LOG = LoggerFactory.getLogger(BandService.class);

    public static final String ACTION_START = "br.dimensono.bandsono.START";
    public static final String ACTION_SYNC = "br.dimensono.bandsono.SYNC";
    public static final String ACTION_PAIR = "br.dimensono.bandsono.PAIR";
    public static final String ACTION_STOP = "br.dimensono.bandsono.STOP";
    public static final String ACTION_APPLY_SETTINGS = "br.dimensono.bandsono.APPLY_SETTINGS";
    public static final String ACTION_FORGET = "br.dimensono.bandsono.FORGET";
    public static final String ACTION_RESCHEDULE = "br.dimensono.bandsono.RESCHEDULE";
    public static final String ACTION_RR_TEST = "br.dimensono.bandsono.RR_TEST";
    public static final String ACTION_RR_CANCEL = "br.dimensono.bandsono.RR_CANCEL";
    /** Fresh connection now (user's "Reconectar" button). */
    public static final String ACTION_RECONNECT = "br.dimensono.bandsono.RECONNECT";
    /** Alarm while parked on passive auto-connect: scan once and reconnect if the band is around. */
    public static final String ACTION_KICK = "br.dimensono.bandsono.KICK";
    public static final String EXTRA_DURATION = "duration_s";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_NAME = "name";

    private static final String CHANNEL_ID = "band_connection";
    private static final String ALERT_CHANNEL_ID = "band_alerts";
    private static final int NOTIFICATION_ID = 4210;
    private static final int STUCK_NOTIFICATION_ID = 4211;
    private static final long FAILED_SYNC_RETRY_MS = 30 * 60 * 1000L;

    /** Delays of the quick active attempts after a disconnection. */
    private static final long[] RETRY_DELAYS_MS = {3_000L, 10_000L, 30_000L};
    private static final long SCAN_WINDOW_MS = 12_000L;
    private static final long KICK_FIRST_MS = 5 * 60_000L;
    private static final long KICK_MAX_MS = 15 * 60_000L;
    /** Unresponsive links in a row (without a good sync) before asking the user to toggle Bluetooth. */
    private static final int STUCK_ALERT_AFTER = 3;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BandDatabase db;
    private BleConnection connection;
    private HuaweiSupportProvider provider;
    private String address;
    private String name;
    private boolean firstConnection = false;
    private boolean wantSync = false;
    private boolean stopping = false;
    private int reconnectAttempts = 0;
    private int kickCount = 0;
    private int stuckCount = 0;
    private boolean refreshCacheNext = false;
    private boolean batchOpen = false;
    private boolean connectionIsDirect = false;
    private int rrTestSeconds = 60;
    /** True while the automatic stress calibration (RR test) started by the service is running. */
    private boolean autoCalibrating = false;
    private PowerManager.WakeLock wakeLock;
    private PowerManager.WakeLock connectWakeLock;
    private String stateLabel = "Iniciando…";
    private BandScanner scanner;
    private boolean receiverRegistered = false;

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (stopping || address == null)
                return;
            if (connection != null && !connection.isClosed() && connectionIsDirect)
                return;
            activeAttempt(false);
        }
    };

    /** Bluetooth switched off: drop everything. Switched on: reconnect right away. */
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction()))
                return;
            int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) {
                LOG.info("Bluetooth turned off");
                handler.removeCallbacks(reconnectRunnable);
                scanner.stop();
                cancelKick();
                closeConnection();
                closeBatch();
                releaseConnectWakeLock();
                if (address != null && !stopping)
                    publish("DISCONNECTED", "Bluetooth desligado");
            } else if (st == BluetoothAdapter.STATE_ON) {
                LOG.info("Bluetooth turned on");
                if (address == null || stopping)
                    return;
                if (syncIsDue() || !Settings.prefs(BandService.this).getBoolean(Settings.LAST_SYNC_OK, true))
                    wantSync = true;
                reconnectAttempts = 0;
                kickCount = 0;
                acquireConnectWakeLock();
                handler.removeCallbacks(reconnectRunnable);
                handler.postDelayed(reconnectRunnable, 2000);
            }
        }
    };

    public static void start(Context context, String action) {
        Intent i = new Intent(context, BandService.class).setAction(action);
        startCompat(context, i);
    }

    /** PendingIntent that (re)starts this service as a foreground service (also from an alarm). */
    static PendingIntent servicePendingIntent(Context context, int requestCode, String action) {
        Intent i = new Intent(context, BandService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= 26)
            return PendingIntent.getForegroundService(context, requestCode, i, flags);
        return PendingIntent.getService(context, requestCode, i, flags);
    }

    public static void startCompat(Context context, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26)
                context.startForegroundService(i);
            else
                context.startService(i);
        } catch (Exception e) {
            LOG.error("Could not start BandService", e);
        }
    }

    /** The running service (null when stopped), for the notification listener. */
    private static volatile BandService running;

    /** True while the band is connected and authenticated. */
    static boolean isConnected() {
        final BandService s = running;
        return s != null && s.provider != null && s.provider.isConnected();
    }

    /** Runs {@code action} on the main thread with the band protocol, if a band session exists. */
    static void withProvider(java.util.function.Consumer<HuaweiSupportProvider> action) {
        final BandService s = running;
        if (s == null) return;
        s.handler.post(() -> {
            if (s.provider != null) action.accept(s.provider);
        });
    }

    static void post(Runnable r, long delayMs) {
        BandService s = running;
        if (s != null) s.handler.postDelayed(r, delayMs);
    }

    static Context context() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        db = BandDatabase.get(this);
        Settings.provisionDefaults(this);
        scanner = new BandScanner(this);
        createChannel();
        goForeground("Iniciando…");
        try {
            registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            receiverRegistered = true;
        } catch (Exception e) {
            LOG.error("Could not register Bluetooth receiver", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!goForeground(stateLabel)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_START;
        LOG.info("onStartCommand {}", action);

        switch (action) {
            case ACTION_PAIR: {
                String newAddress = intent.getStringExtra(EXTRA_ADDRESS);
                String newName = intent.getStringExtra(EXTRA_NAME);
                if (newAddress == null)
                    break;
                String old = Settings.deviceAddress(this);
                if (old != null && !old.equals(newAddress))
                    HuaweiSupportProvider.forget(this, old);
                HuaweiSupportProvider.forget(this, newAddress);
                Settings.put(this, Settings.DEVICE_ADDRESS, newAddress);
                Settings.put(this, Settings.DEVICE_NAME, newName == null ? "" : newName);
                address = newAddress;
                name = newName;
                firstConnection = true;
                wantSync = true;
                reconnectAttempts = 0;
                stopping = false;
                connect(true);
                break;
            }
            case ACTION_STOP:
                stopping = true;
                teardown();
                publish("DISCONNECTED", "Monitoramento em segundo plano desligado");
                cancelSyncAlarm();
                stopForegroundCompat();
                stopSelf();
                return START_NOT_STICKY;
            case ACTION_FORGET:
                stopping = true;
                teardown();
                if (address != null)
                    HuaweiSupportProvider.forget(this, address);
                Settings.put(this, Settings.DEVICE_ADDRESS, null);
                Settings.put(this, Settings.DEVICE_NAME, null);
                address = null;
                cancelSyncAlarm();
                publish("DISCONNECTED", "");
                stopForegroundCompat();
                stopSelf();
                return START_NOT_STICKY;
            case ACTION_APPLY_SETTINGS:
                if (provider != null)
                    provider.applyMonitoringSettings();
                break;
            case ACTION_RR_TEST: {
                int seconds = intent.getIntExtra(EXTRA_DURATION, 60);
                if (provider == null || provider.getState() != HuaweiSupportProvider.State.READY) {
                    publishMeasurement("error", 0, "A pulseira precisa estar conectada e sem sincronizar");
                } else {
                    rrTestSeconds = seconds;
                    acquireWakeLock();
                    if (provider.startRrTest(seconds * 1000L))
                        publishMeasurement("running", seconds * 1000L, "");
                }
                break;
            }
            case ACTION_RR_CANCEL:
                if (provider != null)
                    provider.cancelRrTest();
                setAutoCalibrating(false);
                publishMeasurement("idle", 0, "");
                releaseWakeLock();
                break;
            case ACTION_RESCHEDULE:
                scheduleNextSync(false);
                break;
            case ACTION_RECONNECT:
                if (!loadDevice())
                    break;
                stopping = false;
                LOG.info("Reconnect requested by the user");
                wantSync = true;
                reconnectAttempts = 0;
                kickCount = 0;
                refreshCacheNext = true;
                acquireConnectWakeLock();
                activeAttempt(true);
                break;
            case ACTION_KICK: {
                if (!loadDevice())
                    break;
                stopping = false;
                HuaweiSupportProvider.State ks = provider != null ? provider.getState() : null;
                boolean busy = ks == HuaweiSupportProvider.State.READY || ks == HuaweiSupportProvider.State.SYNCING
                        || ks == HuaweiSupportProvider.State.AUTHENTICATING || ks == HuaweiSupportProvider.State.INITIALIZING
                        || scanner.isScanning()
                        || (connection != null && !connection.isClosed() && (connectionIsDirect || connection.isLinkUp()));
                if (!busy) {
                    LOG.info("Kick: looking for the band");
                    acquireConnectWakeLock();
                    // one attempt only; if it fails the band goes back to passive auto-connect
                    reconnectAttempts = RETRY_DELAYS_MS.length;
                    kickAttempt();
                }
                break;
            }
            case ACTION_SYNC: {
                // Every sync (alarm or "Sincronizar agora") starts from a fresh connection, exactly
                // like the "Reconectar" button: a link kept open for hours often stops delivering
                // everything, while a new one (scan, authentication, setup) always does.
                if (!loadDevice()) {
                    publish("DISCONNECTED", "Nenhuma pulseira pareada");
                    stopForegroundCompat();
                    stopSelf();
                    return START_NOT_STICKY;
                }
                stopping = false;
                wantSync = true;
                acquireWakeLock();
                HuaweiSupportProvider.State ss = provider != null ? provider.getState() : null;
                if (ss == HuaweiSupportProvider.State.SYNCING) {
                    wantSync = false; // already downloading
                } else if (ss == HuaweiSupportProvider.State.AUTHENTICATING || ss == HuaweiSupportProvider.State.INITIALIZING
                        || scanner.isScanning()
                        || (ss != HuaweiSupportProvider.State.READY && connection != null && !connection.isClosed()
                            && (connectionIsDirect || connection.isLinkUp()))) {
                    // a fresh connection is already being set up; READY starts the sync
                } else if (ss == HuaweiSupportProvider.State.READY && provider.isMeasuring()) {
                    wantSync = false;
                    provider.requestSync(); // RR test running: the sync waits for it on this link
                } else {
                    LOG.info("Sync: reconnecting from scratch before downloading");
                    reconnectAttempts = 0;
                    kickCount = 0;
                    refreshCacheNext = true;
                    acquireConnectWakeLock();
                    activeAttempt(true);
                }
                break;
            }
            case ACTION_START:
            default:
                if (!loadDevice()) {
                    publish("DISCONNECTED", "Nenhuma pulseira pareada");
                    stopForegroundCompat();
                    stopSelf();
                    return START_NOT_STICKY;
                }
                stopping = false;
                HuaweiSupportProvider.State st = provider != null ? provider.getState() : null;
                boolean attemptInFlight = connection != null && !connection.isClosed()
                        && (connectionIsDirect || connection.isLinkUp());
                if (st == HuaweiSupportProvider.State.READY) {
                    if (wantSync) {
                        wantSync = false;
                        provider.requestSync();
                    }
                } else if (st == HuaweiSupportProvider.State.SYNCING) {
                    wantSync = false;
                } else if (st == HuaweiSupportProvider.State.AUTHENTICATING || st == HuaweiSupportProvider.State.INITIALIZING) {
                    // READY will start the sync
                } else if (!attemptInFlight && !scanner.isScanning()) {
                    reconnectAttempts = 0;
                    if (wantSync)
                        acquireConnectWakeLock();
                    activeAttempt(false);
                }
                break;
        }
        scheduleNextSync(false);
        return START_STICKY;
    }

    private boolean loadDevice() {
        if (address == null) {
            address = Settings.deviceAddress(this);
            name = Settings.deviceName(this);
        }
        return address != null;
    }

    // ------------------------------------------------------------------ connection

    private void connect(boolean direct) {
        connect(direct, null);
    }

    private void connect(boolean direct, BluetoothDevice found) {
        closeConnection();
        if (address == null)
            return;
        if (!bluetoothOn()) {
            publish("DISCONNECTED", "Bluetooth desligado");
            return; // the Bluetooth receiver reconnects when it is switched on
        }
        provider = new HuaweiSupportProvider(this, new BandDevice(address, name), firstConnection, this);
        connection = new BleConnection(this, address, this);
        if (refreshCacheNext) {
            connection.setRefreshCache(true);
            refreshCacheNext = false;
        }
        connectionIsDirect = direct;
        publish("CONNECTING", direct ? "Conectando à pulseira…" : "Aguardando a pulseira ficar ao alcance");
        if (!connection.connect(found, !direct)) {
            connection = null;
            publish("DISCONNECTED", "Bluetooth desligado ou sem permissão");
            if (direct)
                scheduleReconnect(); // a failed passive attempt is retried by the kick alarm set in park()
        }
    }

    /**
     * One active reconnect attempt: a direct connection first; from the second attempt on (or
     * when {@code scanFirst}), a short scan so the connection uses a fresh device object.
     */
    private void activeAttempt(boolean scanFirst) {
        if (address == null || stopping)
            return;
        if (!bluetoothOn()) {
            closeConnection();
            publish("DISCONNECTED", "Bluetooth desligado");
            return;
        }
        cancelKick();
        if (!scanFirst && reconnectAttempts < 1) {
            connect(true);
            return;
        }
        closeConnection();
        publish("CONNECTING", "Procurando a pulseira…");
        boolean started = scanner.start(address, SCAN_WINDOW_MS, device -> {
            if (stopping || address == null)
                return;
            // Not seen: it may still be connected at link level (no advertising), so try anyway
            connect(true, device);
        });
        if (!started)
            connect(true);
    }

    /** Kick from the alarm: connect only if a scan sees the band, otherwise stay passive. */
    private void kickAttempt() {
        cancelKick();
        if (!bluetoothOn()) {
            publish("DISCONNECTED", "Bluetooth desligado");
            releaseConnectWakeLock();
            return;
        }
        closeConnection();
        boolean started = scanner.start(address, SCAN_WINDOW_MS, device -> {
            if (stopping || address == null)
                return;
            if (device != null)
                connect(true, device);
            else
                park();
        });
        if (!started)
            connect(true);
    }

    /** Leaves the reconnection to Android (passive auto-connect) and sets the next kick. */
    private void park() {
        connect(false);
        scheduleKick();
        releaseConnectWakeLock();
    }

    private boolean bluetoothOn() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm != null ? bm.getAdapter() : null;
        return a != null && a.isEnabled();
    }

    private void closeConnection() {
        handler.removeCallbacks(reconnectRunnable);
        if (scanner != null)
            scanner.stop();
        BleConnection c = connection;
        connection = null;
        if (c != null)
            c.close();
        HuaweiSupportProvider p = provider;
        if (p != null && p.getState() != HuaweiSupportProvider.State.DISCONNECTED)
            p.onDisconnected();
    }

    private void teardown() {
        handler.removeCallbacks(reconnectRunnable);
        closeConnection();
        provider = null;
        closeBatch();
        cancelKick();
        releaseWakeLock();
        releaseConnectWakeLock();
    }

    private void scheduleReconnect() {
        if (stopping || address == null)
            return;
        handler.removeCallbacks(reconnectRunnable);
        if (!bluetoothOn()) {
            publish("DISCONNECTED", "Bluetooth desligado");
            releaseConnectWakeLock();
            return;
        }
        reconnectAttempts++;
        if (reconnectAttempts <= RETRY_DELAYS_MS.length) {
            acquireConnectWakeLock();
            handler.postDelayed(reconnectRunnable, RETRY_DELAYS_MS[reconnectAttempts - 1]);
        } else {
            park();
        }
    }

    private PendingIntent kickPendingIntent() {
        return servicePendingIntent(this, 2, ACTION_KICK);
    }

    private void scheduleKick() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null)
            return;
        long delay = Math.min(KICK_MAX_MS, KICK_FIRST_MS * (1L << Math.min(kickCount, 2)));
        kickCount++;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, kickPendingIntent());
            LOG.info("Waiting for the band; next active search in {} min", delay / 60000);
        } catch (Exception e) {
            LOG.error("Could not schedule reconnect kick", e);
        }
    }

    private void cancelKick() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null)
            am.cancel(kickPendingIntent());
    }

    /** The band stopped answering on a link Android still reports as connected: rebuild it. */
    private void restartLink(String reason) {
        if (stopping || address == null)
            return;
        stuckCount++;
        LOG.warn("Rebuilding the connection ({} in a row): {}", stuckCount, reason);
        wantSync = true;
        refreshCacheNext = true;
        BleConnection c = connection;
        if (c != null)
            c.setRefreshCache(true);
        closeConnection();
        closeBatch();
        publish("CONNECTING", "Reiniciando a conexão com a pulseira…");
        if (stuckCount >= STUCK_ALERT_AFTER)
            showStuckAlert();
        reconnectAttempts = 1; // next attempt scans first
        acquireConnectWakeLock();
        handler.postDelayed(reconnectRunnable, 2500);
    }

    @Override
    public void onReady(BleConnection c) {
        if (c != connection || provider == null)
            return;
        provider.onConnected(c);
    }

    @Override
    public void onData(byte[] data) {
        if (provider != null)
            provider.onDataReceived(data);
    }

    @Override
    public void onDisconnected(BleConnection c, boolean wasReady) {
        if (c != connection)
            return;
        LOG.info("Disconnected (wasReady={}, status={}{})", wasReady, c.getLastStatus(),
                c.getCloseReason().isEmpty() ? "" : ", " + c.getCloseReason());
        connection = null;
        setAutoCalibrating(false);
        HuaweiSupportProvider p = provider;
        if (p != null)
            p.onDisconnected();
        closeBatch();
        releaseWakeLock();
        if (!stopping) {
            if (wasReady) {
                // Dropped after working: start the quick attempts from the beginning
                reconnectAttempts = 0;
                kickCount = 0;
            }
            scheduleReconnect();
        }
    }

    // ------------------------------------------------------------------ protocol listener

    @Override
    public void onStateChanged(HuaweiSupportProvider.State state, String detail) {
        switch (state) {
            case READY:
                reconnectAttempts = 0;
                kickCount = 0;
                cancelKick();
                releaseConnectWakeLock();
                if (connection != null)
                    connection.setHighPriority(false);
                firstConnection = false;
                if (provider != null)
                    BandStatus.update("stressBaseline", provider.hasStressBaseline());
                publish("READY", detail);
                if (wantSync || syncIsDue()) {
                    wantSync = false;
                    if (provider != null)
                        provider.requestSync();
                } else {
                    scheduleAutoCalibration();
                }
                break;
            case SYNCING:
                if (connection != null)
                    connection.setHighPriority(true);
                publish("SYNCING", "Baixando dados da pulseira…");
                break;
            default:
                publish(state.name(), detail);
                break;
        }
    }

    private boolean syncIsDue() {
        long last = Settings.prefs(this).getLong(Settings.LAST_SYNC, 0);
        return System.currentTimeMillis() - last >= Settings.intervalHours(this) * 3600_000L - 5 * 60_000L;
    }

    @Override
    public void onHeartRateMode(String mode) {
        BandStatus.update("hrMode", mode);
    }

    @Override
    public void onDeviceInfo(BandDevice device) {
        Map<String, Object> m = new HashMap<>();
        m.put("battery", device.getBatteryLevel());
        m.put("model", device.getModel());
        m.put("firmware", device.getFirmwareVersion());
        BandStatus.update(m);
        try {
            db.noteDeviceStatus(device.getAddress(), device.getBatteryLevel(), device.getHardwareVersion(), device.getFirmwareVersion(),
                    device.getModel(), System.currentTimeMillis() / 1000);
        } catch (RuntimeException e) {
            LOG.warn("Could not record the band status: {}", e.getClass().getSimpleName());
        }
        if (device.getModel() != null && !device.getModel().isEmpty())
            Settings.put(this, SupabaseUploader.KEY_MODEL, device.getModel());
        if (device.getFirmwareVersion() != null && !device.getFirmwareVersion().isEmpty())
            Settings.put(this, SupabaseUploader.KEY_FIRMWARE, device.getFirmwareVersion());
        updateNotification();
    }

    @Override
    public void onAuthenticationFailed(String reason) {
        LOG.warn("Authentication failed: {}", reason);
        BandStatus.update("detail", reason);
        BandStatus.update("authError", reason);
    }

    @Override
    public void onSleepSegment(int start, int end, int type, int source) {
        openBatch();
        db.insertSleep(start, end, type, source);
    }

    @Override
    public void onStepSample(int timestamp, int steps, int calories, int distance, int spo, int heartRate, int restingHeartRate) {
        openBatch();
        db.insertMinute(timestamp, steps, calories, distance, heartRate, spo, restingHeartRate);
    }

    @Override
    public void onSleepSummary(HuaweiSupportProvider.SleepSummary n) {
        openBatch();
        db.insertNight(n.fallAsleep, n.bedTime, n.risingTime, n.wakeup, n.score, n.latencyMin, n.efficiency, n.wakeCount,
                n.deepPart, n.minHr, n.maxHr, n.avgHr, n.minSpo2, n.maxSpo2, n.avgSpo2, n.minBreath, n.maxBreath,
                n.avgBreath, n.avgHrv, n.minHrvBaseline, n.maxHrvBaseline, n.rdi);
    }

    @Override
    public void onStress(int startSec, int endSec, int score, int level) {
        openBatch();
        db.insertStress(startSec, endSec, score, level);
    }

    @Override
    public void onNightDetails(HuaweiTrueSleepSequenceDataParser.SleepSummary summary) {
        openBatch();
        db.insertNightDetails(summary);
    }

    @Override
    public void onStressDetails(HuaweiStressParser.StressData data) {
        openBatch();
        db.insertStressDetails(data, DetailRows.SOURCE_AUTO);
    }

    @Override
    public void onWorkoutDetails(Workout.WorkoutTotals.Response totals) {
        openBatch();
        db.insertWorkoutDetails(totals);
    }

    @Override
    public void onWorkoutSampleDetails(int workoutStart, List<Workout.WorkoutData.Response.Data> samples) {
        openBatch();
        db.insertWorkoutSampleDetails(workoutStart, samples);
    }

    @Override
    public void onUnknownStepTags(List<UnknownStepTag> tags) {
        openBatch();
        db.addUnknownStepTags(tags);
    }

    @Override
    public void onDictValues(String kind, List<double[]> values) {
        openBatch();
        for (double[] v : values)
            db.insertDict(kind, (long) v[0], v[1]);
    }

    @Override
    public void onRawFile(String kind, int startSec, int endSec, byte[] data) {
        openBatch();
        db.insertRawFile(kind, startSec, endSec, data);
    }

    @Override
    public void onSleepStage(int start, int end, int stage) {
        openBatch();
        db.insertSleepStage(start, end, stage);
    }

    @Override
    public void onWorkout(HuaweiSupportProvider.WorkoutSummary workout) {
        openBatch();
        db.insertWorkout(workout);
    }

    @Override
    public void onWorkoutSamples(int workoutStart, List<HuaweiSupportProvider.WorkoutSample> samples) {
        openBatch();
        for (HuaweiSupportProvider.WorkoutSample s : samples)
            db.insertWorkoutSample(workoutStart, s);
    }

    @Override
    public int getLastWorkoutTimestamp() {
        return db.lastWorkoutStart();
    }

    // GPS/PDR still to collect and the windows of the extra raw files (ECG, arrhythmia): small durable markers
    private static final String PREF_PENDING_TRAJ = "pending_trajectories";

    @Override
    public List<PendingTrajectory> getPendingTrajectories() {
        return PendingTrajectory.parse(Settings.prefs(this).getString(PREF_PENDING_TRAJ, ""));
    }

    @Override
    public void savePendingTrajectories(List<PendingTrajectory> list) {
        Settings.prefs(this).edit().putString(PREF_PENDING_TRAJ, PendingTrajectory.serialize(list)).apply();
    }

    @Override
    public int getLastExtraEnd(String kind) {
        return (int) Settings.prefs(this).getLong("extra_end_" + kind, 0);
    }

    @Override
    public void onExtraWindowDone(String kind, int endSec) {
        Settings.prefs(this).edit().putLong("extra_end_" + kind, endSec).apply();
    }

    @Override
    public void onBandRequestsSync(int action) {
        LOG.info("The band asked for a sync (action {}); only logged for now", action);
    }

    @Override
    public void onLinkUnresponsive(String reason) {
        // Called from inside the provider; rebuild the link once it has unwound
        handler.post(() -> restartLink(reason));
    }

    @Override
    public long getLastDictTimestamp(String kind) {
        return db.lastDictTs(kind);
    }

    @Override
    public void onMeasurementProgress(long millisLeft) {
        publishMeasurement("running", millisLeft, "");
    }

    @Override
    public void onMeasurementResult(HuaweiStressParser.StressData stress, int[] rri, int[] sqi) {
        long now = System.currentTimeMillis();
        int score = stress != null ? stress.score & 0xff : 0;
        Map<String, Object> r = db.insertRrTest(now, rrTestSeconds, rri, sqi, score);
        if (stress != null) {
            try {
                // the on-demand test's stress record keeps its own row (source 'manual'); its start is the test's start
                db.insertStressDetails(stress, DetailRows.SOURCE_MANUAL);
            } catch (RuntimeException e) {
                LOG.warn("Could not store the stress details: {}", e.getClass().getSimpleName());
            }
        }
        Map<String, Object> m = new HashMap<>();
        m.put("measureState", "done");
        m.put("measureLeftMs", 0L);
        m.put("measureMessage", "");
        m.put("measureResult", r);
        m.put("stressBaseline", provider != null && provider.hasStressBaseline());
        m.put("dataVersion", now);
        BandStatus.update(m);
        setAutoCalibrating(false);
        releaseWakeLock();
        SupabaseUploader.requestNow(this);
    }

    @Override
    public void onMeasurementError(String reason) {
        publishMeasurement("error", 0, reason);
        setAutoCalibrating(false);
        releaseWakeLock();
    }

    private void publishMeasurement(String state, long leftMs, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("measureState", state);
        m.put("measureLeftMs", leftMs);
        m.put("measureMessage", message == null ? "" : message);
        BandStatus.update(m);
    }

    @Override
    public void onSyncFinished(boolean ok, String summary) {
        closeBatch();
        long now = System.currentTimeMillis();
        db.logSync(now / 1000, ok, summary);
        if (ok) {
            Settings.prefs(this).edit()
                    .putLong(Settings.LAST_SYNC, now)
                    .putBoolean(Settings.LAST_SYNC_OK, true)
                    .putString(Settings.LAST_SYNC_SUMMARY, summary)
                    .apply();
        } else {
            Settings.prefs(this).edit()
                    .putBoolean(Settings.LAST_SYNC_OK, false)
                    .putString(Settings.LAST_SYNC_SUMMARY, summary)
                    .apply();
        }
        Map<String, Object> m = new HashMap<>();
        m.put("lastSync", Settings.prefs(this).getLong(Settings.LAST_SYNC, 0));
        m.put("lastSyncOk", ok);
        m.put("lastSyncSummary", summary);
        m.put("dataVersion", now);
        m.put("authError", null);
        BandStatus.update(m);
        if (ok) {
            stuckCount = 0;
            cancelStuckAlert();
        }
        scheduleNextSync(!ok);
        releaseWakeLock();
        updateNotification();
        try {
            db.pruneRawFiles(); // raw files are kept 3 days after upload, 30 days at most
        } catch (Exception e) {
            LOG.warn("pruneRawFiles: {}", e.toString());
        }
        // Raw data to Supabase after every sync (runs when there is network)
        SupabaseUploader.requestNow(this);
        if (ok)
            scheduleAutoCalibration();
    }

    // ------------------------------------------------------------------ automatic stress calibration

    static final String PREF_CALIB_ATTEMPTS = "calib_attempts";
    private static final long CALIB_DELAY_MS = 4000;

    private void setAutoCalibrating(boolean on) {
        if (autoCalibrating == on)
            return;
        autoCalibrating = on;
        BandStatus.update("calibrating", on);
    }

    /** Waits a moment (the provider leaves SYNCING right after the callback), then tries the calibration. */
    private void scheduleAutoCalibration() {
        handler.removeCallbacks(autoCalibrationRunnable);
        handler.postDelayed(autoCalibrationRunnable, CALIB_DELAY_MS);
    }

    private final Runnable autoCalibrationRunnable = this::maybeAutoCalibrate;

    /**
     * Starts the 60 s RR test by itself when the band is ready, the first sync is done and there is no
     * valid stress reference. Never during a sync or another measurement.
     */
    private void maybeAutoCalibrate() {
        final HuaweiSupportProvider p = provider;
        if (p == null || autoCalibrating || stopping)
            return;
        if (p.getState() != HuaweiSupportProvider.State.READY || p.isMeasuring() || p.hasStressBaseline())
            return;
        if (Settings.prefs(this).getLong(Settings.LAST_SYNC, 0) == 0)
            return; // initial sync not finished yet
        long now = System.currentTimeMillis();
        List<Long> recent = CalibrationPolicy.recent(Settings.prefs(this).getString(PREF_CALIB_ATTEMPTS, ""), now);
        if (!CalibrationPolicy.allowed(recent, now))
            return;
        recent.add(now);
        Settings.prefs(this).edit().putString(PREF_CALIB_ATTEMPTS, CalibrationPolicy.serialize(recent)).apply();
        LOG.info("Automatic stress calibration (attempt {} in 24 h)", recent.size());
        rrTestSeconds = 60;
        setAutoCalibrating(true);
        acquireWakeLock();
        if (p.startRrTest(60_000L)) {
            publishMeasurement("running", 60_000L, "");
        } else {
            setAutoCalibrating(false);
            releaseWakeLock();
        }
    }

    @Override
    public int getLastStepTimestamp() {
        return db.lastMinuteTs();
    }

    @Override
    public int getLastSleepTimestamp() {
        return db.lastSleepEnd();
    }

    private void openBatch() {
        if (!batchOpen) {
            db.beginBatch();
            batchOpen = true;
        }
    }

    private void closeBatch() {
        if (batchOpen) {
            batchOpen = false;
            db.endBatch();
        }
    }

    // ------------------------------------------------------------------ scheduling

    private PendingIntent syncPendingIntent() {
        return servicePendingIntent(this, 1, ACTION_SYNC);
    }

    private void scheduleNextSync(boolean afterFailure) {
        if (address == null || stopping)
            return;
        long now = System.currentTimeMillis();
        long last = Settings.prefs(this).getLong(Settings.LAST_SYNC, 0);
        long interval = Settings.intervalHours(this) * 3600_000L;
        long next = afterFailure ? now + FAILED_SYNC_RETRY_MS : Math.max(now + 60_000L, last + interval);
        if (last == 0 && !afterFailure)
            next = now + interval;
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null)
            return;
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, syncPendingIntent());
        } catch (Exception e) {
            LOG.error("Could not schedule sync", e);
        }
        Settings.prefs(this).edit().putLong(Settings.NEXT_SYNC, next).apply();
        BandStatus.update("nextSync", next);
    }

    private void cancelSyncAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null)
            am.cancel(syncPendingIntent());
        Settings.prefs(this).edit().remove(Settings.NEXT_SYNC).apply();
        BandStatus.update("nextSync", 0L);
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null)
            return;
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bandsono:sync");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(10 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld())
            wakeLock.release();
    }

    /** Short wake lock for the quick reconnect attempts (the handler delays need the CPU awake). */
    private void acquireConnectWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null)
            return;
        if (connectWakeLock == null) {
            connectWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bandsono:connect");
            connectWakeLock.setReferenceCounted(false);
        }
        connectWakeLock.acquire(3 * 60 * 1000L);
    }

    private void releaseConnectWakeLock() {
        if (connectWakeLock != null && connectWakeLock.isHeld())
            connectWakeLock.release();
    }

    // ------------------------------------------------------------------ status & notification

    private void publish(String state, String detail) {
        Map<String, Object> m = new HashMap<>();
        m.put("state", state);
        m.put("detail", detail == null ? "" : detail);
        m.put("syncing", "SYNCING".equals(state));
        m.put("address", address);
        m.put("name", name);
        m.put("lastSync", Settings.prefs(this).getLong(Settings.LAST_SYNC, 0));
        m.put("nextSync", Settings.prefs(this).getLong(Settings.NEXT_SYNC, 0));
        BandStatus.update(m);
        switch (state) {
            case "READY":
                stateLabel = "Conectada";
                break;
            case "SYNCING":
                stateLabel = "Sincronizando…";
                break;
            case "AUTHENTICATING":
                stateLabel = firstConnection ? "Confirme o pareamento na pulseira" : "Autenticando…";
                break;
            case "INITIALIZING":
                stateLabel = "Ativando monitoramentos…";
                break;
            case "CONNECTING":
                stateLabel = "Procurando a pulseira…";
                break;
            default:
                stateLabel = "Desconectada";
                break;
        }
        updateNotification();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Conexão com a pulseira", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationChannel alerts = new NotificationChannel(ALERT_CHANNEL_ID, "Avisos de conexão", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
                nm.createNotificationChannel(alerts);
            }
        }
    }

    /** Last resort: Android keeps a dead link up and apps can no longer switch Bluetooth off themselves. */
    private void showStuckAlert() {
        BandStatus.update("linkStuck", true);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null)
            return;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ALERT_CHANNEL_ID)
                : new Notification.Builder(this);
        String text = "O app reiniciou a conexão " + stuckCount + " vezes sem resposta da pulseira. "
                + "Se a próxima sincronização também falhar, desligue e ligue o Bluetooth.";
        b.setContentTitle("A pulseira parou de responder")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_stat_band)
                .setAutoCancel(true);
        try {
            b.setContentIntent(PendingIntent.getActivity(this, 3,
                    new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            nm.notify(STUCK_NOTIFICATION_ID, b.build());
        } catch (Exception ignored) {
        }
    }

    private void cancelStuckAlert() {
        BandStatus.update("linkStuck", false);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null)
            nm.cancel(STUCK_NOTIFICATION_ID);
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        long last = Settings.prefs(this).getLong(Settings.LAST_SYNC, 0);
        String sub = last > 0
                ? "Última sincronização " + new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(last))
                : "Ainda sem sincronização";
        Object battery = BandStatus.snapshot().get("battery");
        if (battery instanceof Integer && (Integer) battery >= 0)
            text = text + " · bateria " + battery + "%";
        b.setContentTitle("BandSono · " + text)
                .setContentText(sub)
                .setSmallIcon(R.drawable.ic_stat_band)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            b.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        return b.build();
    }

    private boolean goForeground(String text) {
        try {
            Notification n = buildNotification(text);
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else
                startForeground(NOTIFICATION_ID, n);
            return true;
        } catch (Exception e) {
            LOG.error("Could not start foreground (missing Bluetooth permission?)", e);
            return false;
        }
    }

    private void updateNotification() {
        if (stopping)
            return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIFICATION_ID, buildNotification(stateLabel));
            } catch (SecurityException ignored) {
            }
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24)
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        else
            stopForeground(true);
    }

    @Override
    public void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        teardown();
        handler.removeCallbacks(autoCalibrationRunnable);
        if (running == this) running = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ band → phone features (ignored)

    @Override
    public void onFindPhone(boolean start) {
        LOG.info("Find phone request from the band ignored (collection only)");
    }

    @Override
    public void onCallAction(boolean accept) {
        LOG.info("Call action from the band ignored (collection only)");
    }
}
