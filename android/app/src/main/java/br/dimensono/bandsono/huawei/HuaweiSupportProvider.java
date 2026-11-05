/*  Copyright (C) 2024-2026 Damien Gaignon, Martin.JM, Vitaliy Tomin and Gadgetbridge contributors,
                  2026 BandSono adaptation

    Derived from Gadgetbridge's HuaweiSupportProvider. This file is free software:
    you can redistribute it and/or modify it under the terms of the GNU Affero
    General Public License as published by the Free Software Foundation, either
    version 3 of the License, or (at your option) any later version.

    It is distributed in the hope that it will be useful, but WITHOUT ANY
    WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
    FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for
    more details. <https://www.gnu.org/licenses/> */
package br.dimensono.bandsono.huawei;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import br.dimensono.bandsono.huawei.packets.DeviceConfig;
import br.dimensono.bandsono.huawei.packets.P2P;
import br.dimensono.bandsono.huawei.p2p.HuaweiP2PDataDictionarySyncService;
import br.dimensono.bandsono.huawei.p2p.dictionarysync.HuaweiDictionarySyncInterface;
import br.dimensono.bandsono.huawei.requests.AcceptAgreementsRequest;
import br.dimensono.bandsono.huawei.requests.GetAuthRequest;
import br.dimensono.bandsono.huawei.requests.GetBatteryLevelRequest;
import br.dimensono.bandsono.huawei.requests.GetBondParamsRequest;
import br.dimensono.bandsono.huawei.requests.GetBondRequest;
import br.dimensono.bandsono.huawei.requests.GetFitnessTotalsRequest;
import br.dimensono.bandsono.huawei.requests.GetHiChainRequest;
import br.dimensono.bandsono.huawei.requests.GetLinkParamsRequest;
import br.dimensono.bandsono.huawei.requests.GetPincodeRequest;
import br.dimensono.bandsono.huawei.requests.GetProductInformationRequest;
import br.dimensono.bandsono.huawei.requests.GetSecurityNegotiationRequest;
import br.dimensono.bandsono.huawei.requests.GetSettingRelatedRequest;
import br.dimensono.bandsono.huawei.requests.GetSleepDataCountRequest;
import br.dimensono.bandsono.huawei.requests.GetStepDataCountRequest;
import br.dimensono.bandsono.huawei.requests.GetSupportedServicesRequest;
import br.dimensono.bandsono.huawei.requests.Request;
import br.dimensono.bandsono.huawei.requests.Request.RequestCallback;
import br.dimensono.bandsono.huawei.packets.Workout;
import br.dimensono.bandsono.huawei.packets.Calls;
import br.dimensono.bandsono.huawei.packets.FindPhone;
import br.dimensono.bandsono.huawei.packets.MusicControl;
import br.dimensono.bandsono.huawei.packets.Notifications;
import br.dimensono.bandsono.huawei.packets.Weather;
import br.dimensono.bandsono.huawei.packets.FitnessData;
import br.dimensono.bandsono.huawei.packets.LocaleConfig;
import br.dimensono.bandsono.huawei.packets.AccountRelated;
import br.dimensono.bandsono.huawei.requests.SendNotifyRestHeartRateCapabilityRequest;
import br.dimensono.bandsono.huawei.requests.SendSleepBreathRequest;
import br.dimensono.bandsono.huawei.requests.SetActivateOnLiftRequest;
import br.dimensono.bandsono.huawei.requests.SetNotificationStateRequest;
import br.dimensono.bandsono.huawei.requests.SetWearMessagePushRequest;
import br.dimensono.bandsono.huawei.requests.SetWearLocationRequest;
import br.dimensono.bandsono.huawei.requests.WorkoutPacketRequest;
import br.dimensono.bandsono.huawei.requests.SetActivityReminderRequest;
import br.dimensono.bandsono.huawei.requests.SetAutomaticHeartrateRequest;
import br.dimensono.bandsono.huawei.requests.SetAutomaticSpoRequest;
import br.dimensono.bandsono.huawei.requests.SetStressRequest;
import br.dimensono.bandsono.huawei.requests.SetTimeRequest;
import br.dimensono.bandsono.huawei.requests.SetTruSleepRequest;
import br.dimensono.bandsono.huawei.util.GB;
import br.dimensono.bandsono.huawei.util.StringUtils;
import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

/**
 * Talks the Huawei Link protocol with the band over an already connected BLE transport:
 * authentication (normal, HiChain Lite and HiChain), configuration of the automatic
 * health monitoring, download of the recorded data and the on-demand RR interval test.
 * <p>
 * Everything here runs on the main looper.
 */
public class HuaweiSupportProvider {
    private static final Logger LOG = LoggerFactory.getLogger(HuaweiSupportProvider.class);

    public static final String PREFS_SETTINGS = "bandsono_settings";
    public static final String PREF_HR_AUTO = "hr_auto";
    public static final String PREF_HR_REALTIME = "hr_realtime";
    public static final String PREF_SPO2_AUTO = "spo2_auto";
    public static final String PREF_TRUSLEEP = "trusleep";
    public static final String PREF_INACTIVITY = "inactivity_reminder";
    public static final String PREF_STRESS_AUTO = "stress_auto";
    public static final String PREF_SLEEP_BREATH = "sleep_breath";
    /** Light the screen when the wrist is raised (off by default: no screen at night). */
    public static final String PREF_LIFT_WRIST = "lift_wrist";
    public static final String PREF_WEAR_LEFT = "wear_left";
    /** Forward the phone's notifications (and incoming calls) to the band. */
    public static final String PREF_NOTIFICATIONS = "band_notifications";

    /** Source code stored with sleep segments that come from the per-minute sleep stages. */
    public static final int SOURCE_STAGES = 0x2D;
    public static final int SOURCE_TRUSLEEP = 0x0A;
    /** Generic sleep records of the fitness history (not TruSleep). Stored but not shown. */
    public static final int SOURCE_FITNESS = 0x0D;

    /** Segment types written with SOURCE_STAGES. */
    public static final int SLEEP_TYPE_MAIN = 0x06;
    public static final int SLEEP_TYPE_NAP = 0x05;

    private static final int INIT_TIMEOUT = 2000;
    private static final int NO_RESPONSE_TIMEOUT = 400;
    private static final int SYNC_STEP_TIMEOUT = 15000;
    private static final int FILE_STAGE_TIMEOUT = 120000;
    private static final int P2P_STAGE_TIMEOUT = 60000;
    /** Whole sync, main part plus the extra downloads; below the gateway's 12 minute session. */
    private static final int SYNC_WATCHDOG_MS = 10 * 60 * 1000;

    public enum State { DISCONNECTED, CONNECTING, AUTHENTICATING, INITIALIZING, READY, SYNCING }

    public interface Transport {
        /** Queue a raw write on the band's write characteristic. */
        void write(byte[] data);

        /** Maximum payload of a single GATT write. */
        int getMaxWriteSize();

        boolean isConnected();

        void disconnect();
    }

    /** Summary of one night as computed by the band (sleep "sequence" data). */
    public static class SleepSummary {
        public long fallAsleep, bedTime, risingTime, wakeup; // unix seconds, 0 = unknown
        public int score = -1, latencyMin = -1, efficiency = -1, wakeCount = -1, deepPart = -1;
        public int minHr = -1, maxHr = -1, avgHr = -1;
        public double minSpo2 = -1, maxSpo2 = -1;
        public int avgSpo2 = -1;
        public double minBreath = -1, maxBreath = -1;
        public int avgBreath = -1;
        public int avgHrv = -1, minHrvBaseline = -1, maxHrvBaseline = -1;
        public int rdi = -1;
    }

    /** Weather to show on the band (temperatures in °C, timestamps in unix seconds). */
    public static class WeatherData {
        public String location = "";
        public int observedAt;
        public int wmoCode;            // WMO weather code (Open-Meteo)
        public double temperature, feelsLike, minToday, maxToday;
        public int humidity = -1;
        public double windKmh;
        public int windDirection;      // degrees
        public double uvIndex = -1;
        public int sunrise, sunset;    // today
        public final java.util.List<Hour> hours = new java.util.ArrayList<>();
        public final java.util.List<Day> days = new java.util.ArrayList<>();

        public static class Hour {
            public int ts, wmoCode, precipitation = -1;
            public double temperature, uvIndex = -1;
        }

        public static class Day {
            public int ts, wmoCode, sunrise, sunset;
            public double min, max;
        }
    }

    /** Summary of one workout recorded on the band (service 0x17 totals); -1 / null = not reported. */
    public static class WorkoutSummary {
        public int number;
        public int startTs, endTs; // unix seconds
        public int type = -1, status = -1;
        public int calories = -1, distance = -1, steps = -1, totalTime = -1, duration = -1;
        public int minHrPeak = -1, maxHrPeak = -1;
        public int load = -1, aerobicEffect = -1, anaerobicEffect = -1, recoveryTime = -1, maxMet = -1, trainingPoints = -1;
        public Integer elevationGain, elevationLoss, maxAltitude, minAltitude;
        public int strokes = -1, avgStrokeRate = -1, laps = -1, avgSwolf = -1, poolLength = -1, swimType = -1;
        public int dataCount, paceCount;
    }

    /** One sample of a workout (heart rate, speed, cadence…); -1 / null = not reported. */
    public static class WorkoutSample {
        public int ts;
        public int heartRate = -1, speed = -1, stepRate = -1, cadence = -1, stepLength = -1, groundContactTime = -1;
        public int calories = -1, spo2 = -1, temp = -1, cyclingPower = -1, rideCadence = -1, swolf = -1, strokeRate = -1;
        public Integer altitude;
    }

    public interface Listener {
        void onStateChanged(State state, String detail);

        void onDeviceInfo(BandDevice device);

        void onAuthenticationFailed(String reason);

        void onSleepSegment(int start, int end, int type, int source);

        void onStepSample(int timestamp, int steps, int calories, int distance, int spo, int heartRate, int restingHeartRate);

        void onSleepSummary(SleepSummary summary);

        void onStress(int startSec, int endSec, int score, int level);

        /** Values from the band's data dictionary ("hrv" = RMSSD ms, "temp" = skin °C); timestamps in ms. */
        void onDictValues(String kind, List<double[]> values);

        void onSyncFinished(boolean ok, String summary);

        /**
         * The link looks connected but the band stopped answering requests (a "zombie"
         * GATT connection). The owner should drop the connection and reconnect.
         */
        void onLinkUnresponsive(String reason);

        /** Raw bytes of a file downloaded from the band (kept for the raw data upload). */
        void onRawFile(String kind, int startSec, int endSec, byte[] data);

        /** Heart rate mode sent to the band: "tempo real", "inteligente" or "desligada". */
        void onHeartRateMode(String mode);

        /** Last timestamp (unix seconds) of stored minute data, 0 if none. */
        int getLastStepTimestamp();

        /** Last end timestamp (unix seconds) of stored sleep, 0 if none. */
        int getLastSleepTimestamp();

        /** Last timestamp (ms) stored for a dictionary kind, 0 if none. */
        long getLastDictTimestamp(String kind);

        /** One run of identical sleep stages (1 light, 2 REM, 3 deep, 4 awake, 5 nap), unix seconds. */
        default void onSleepStage(int start, int end, int stage) {
        }

        /** One workout's summary, before its samples. */
        default void onWorkout(WorkoutSummary workout) {
        }

        /** Samples of the workout that started at {@code workoutStart} (unix seconds). */
        default void onWorkoutSamples(int workoutStart, List<WorkoutSample> samples) {
        }

        /**
         * Everything of a night's sleep summary (after correctSummary), for the detail table. -1 = not reported.
         * Called right after {@link #onSleepSummary}; the same night can come again (the sync re-reads an overlap).
         */
        default void onNightDetails(HuaweiTrueSleepSequenceDataParser.SleepSummary summary) {
        }

        /** One automatic stress record of the band's RRI file, with all its fields (the score is also in onStress). */
        default void onStressDetails(HuaweiStressParser.StressData data) {
        }

        /** The totals of a workout with the fields onWorkout leaves out (called after onWorkout). */
        default void onWorkoutDetails(Workout.WorkoutTotals.Response totals) {
        }

        /** Raw sample objects of the workout that started at {@code workoutStart}, with the inner bitmap of each. */
        default void onWorkoutSampleDetails(int workoutStart, List<Workout.WorkoutData.Response.Data> samples) {
        }

        /**
         * Minute-record fields not decoded yet, seen in this sync: one entry per field (timestamps in unix seconds).
         */
        default void onUnknownStepTags(List<UnknownStepTag> tags) {
        }

        /** Workouts whose GPS/PDR files are still to be collected (kept durable by the owner; empty when none/unknown). */
        default List<PendingTrajectory> getPendingTrajectories() {
            return new ArrayList<>();
        }

        /** The whole current list of pending workouts (replaces the stored one). */
        default void savePendingTrajectories(List<PendingTrajectory> list) {
        }

        /** End (unix seconds) of the last window fetched for an extra raw file kind ("ecg_analysis", "arrhythmia_ppg"), 0 if none. */
        default int getLastExtraEnd(String kind) {
            return 0;
        }

        /** The window of an extra raw file kind was fully read (also when it had no data) up to {@code endSec}. */
        default void onExtraWindowDone(String kind, int endSec) {
        }

        /** The band pushed a "please sync" message (service 7, command 0x0f); {@code action} is -1 when not recognised. Not answered. */
        default void onBandRequestsSync(int action) {
        }

        /** The band opened its music screen and wants the now-playing info. */
        default void onMusicInfoRequested() {
        }

        /** A music button on the band: Play, Pause, Previous, Next, Volume_up, Volume_down. */
        default void onMusicButton(MusicControl.Control.Response.Button button, int volume) {
        }

        /** "Find my band" was started or stopped from the phone. */
        default void onFindBandState(boolean ringing) {
        }

        /** The user stopped "find my band" on the band itself. */
        default void onFindBandStopped() {
        }

        /** Raw TruSleep movement and pulse peaks of the downloaded range. */
        default void onTruSleepRaw(HuaweiTruSleepParser.TruSleepData data) {
        }

        /** The band asks for fresh weather (its weather screen was opened). */
        default void onWeatherRequested() {
        }

        /** "Find my phone" started (true) or stopped (false) on the band. */
        default void onFindPhone(boolean start) {
        }

        /** The user accepted (true) or rejected (false) an incoming call on the band. */
        default void onCallAction(boolean accept) {
        }

        /** Start (unix seconds) of the last stored workout, 0 if none. */
        default int getLastWorkoutTimestamp() {
            return 0;
        }

        void onMeasurementProgress(long millisLeft);

        void onMeasurementResult(HuaweiStressParser.StressData stress, int[] rri, int[] sqi);

        void onMeasurementError(String reason);
    }

    private final Context context;
    private final BandDevice device;
    private final HuaweiState state;
    private final HuaweiCoordinator coordinator = new HuaweiCoordinator();
    private final Listener listener;
    private final SharedPreferences devicePrefs;
    private final SharedPreferences settings;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final HuaweiPacket.ParamsProvider paramsProvider = new HuaweiPacket.ParamsProvider();
    private final ResponseManager responseManager;
    private final HuaweiFileDownloadManager fileDownloadManager;
    private final HuaweiP2PManager p2pManager;
    private HuaweiStressCalibration measurement;

    private Transport transport;
    private boolean firstConnection;
    private byte protocolVersion;
    private String macAddress;
    private String androidID;
    private State state_ = State.DISCONNECTED;
    private boolean dynamicServicesStarted = false;

    private boolean syncPending = false;
    private boolean syncRunning = false;
    private int syncSteps = 0;
    private int syncSleep = 0;
    private int syncNights = 0;
    private int syncStress = 0;
    private int syncDict = 0;
    private int syncWorkouts = 0;
    /** Minute records with a heart rate value in the current sync. */
    private int syncHr = 0;
    private int syncStepStart = 0;
    /** Raw minute records of the current sync: [ts u32][len u8][data], for the raw upload. */
    private final java.io.ByteArrayOutputStream stepRaw = new java.io.ByteArrayOutputStream();
    /** Step-data fields this code does not decode: "bitmap/tag" -> {count, first value, bitmap, tag, first ts, last ts}. */
    private final java.util.TreeMap<String, UnknownStepTag> unknownStepTags = new java.util.TreeMap<>();
    private int syncTimeouts = 0;
    /** Packets received from the band (to spot a link that stopped answering). */
    private long rxPackets = 0;
    private long rxAtSyncStart = 0;
    private Runnable stageTimeout = null;
    private long syncStartWall = 0;
    /** Counts the syncs: callbacks and timers of an earlier one (the extras especially) must not act on a later one. */
    private int syncGeneration = 0;
    private final ExtraDownloads extras;
    private final java.io.ByteArrayOutputStream workoutRawV2 = new java.io.ByteArrayOutputStream();

    private final Runnable syncWatchdog = new Runnable() {
        @Override
        public void run() {
            if (syncRunning) {
                LOG.warn("Sync watchdog fired, finishing sync");
                fileDownloadManager.cancelAll();
                finishSync(syncSteps + syncSleep > 0, "Tempo esgotado durante a sincronização");
            }
        }
    };

    private final Runnable initWatchdog = new Runnable() {
        @Override
        public void run() {
            if (state_ == State.AUTHENTICATING || state_ == State.INITIALIZING) {
                LOG.warn("Initialization watchdog fired in state {}", state_);
                if (state_ == State.AUTHENTICATING)
                    listener.onAuthenticationFailed("A pulseira não respondeu à autenticação");
                disconnect();
            }
        }
    };

    public HuaweiSupportProvider(Context context, BandDevice device, boolean firstConnection, Listener listener) {
        this.context = context.getApplicationContext();
        this.device = device;
        this.listener = listener;
        this.firstConnection = firstConnection;
        this.state = new HuaweiState(this.context, device.getAddress());
        this.devicePrefs = this.context.getSharedPreferences("huawei_dev_" + device.getAddress().replace(":", ""), Context.MODE_PRIVATE);
        this.settings = this.context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
        this.responseManager = new ResponseManager(this);
        this.fileDownloadManager = new HuaweiFileDownloadManager(this);
        this.extras = new ExtraDownloads(new ExtraDownloads.Host() {
            @Override public int generation() { return syncGeneration; }
            @Override public boolean running() { return syncRunning; }
            @Override public HuaweiState state() { return state; }
            @Override public Listener listener() { return HuaweiSupportProvider.this.listener; }
            @Override public HuaweiFileDownloadManager downloads() { return fileDownloadManager; }
            @Override public void rawFile(String kind, int startSec, int endSec, byte[] data) { HuaweiSupportProvider.this.rawFile(kind, startSec, endSec, data); }
            @Override public void safely(String what, Runnable r) { HuaweiSupportProvider.this.safely(what, r); }
            @Override public void syncArrhythmiaResult(Runnable done) { dictionaryStage(true, ExtraDownloads.FILE_TIMEOUT_MS, done); }
            @Override public long syncStartWallMs() { return syncStartWall; }
        }, handler, SYNC_WATCHDOG_MS);
        this.p2pManager = new HuaweiP2PManager(this);
        this.paramsProvider.setTransactionsCrypted(state.isTransactionCrypted());
    }

    // ------------------------------------------------------------------ transport glue

    public void onConnected(Transport transport) {
        this.transport = transport;
        initializeDevice();
    }

    public void onDataReceived(byte[] data) {
        rxPackets++;
        responseManager.handleData(data);
    }

    public void onDisconnected() {
        handler.removeCallbacks(initWatchdog);
        boolean wasSyncing = syncRunning;
        if (measurement != null && measurement.isRunning()) {
            measurement.cancel();
        }
        measurement = null;
        responseManager.clear();
        fileDownloadManager.cancelAll();
        fileDownloadManager.dispose();
        p2pManager.unregisterAllService();
        transport = null;
        if (wasSyncing)
            finishSync(false, "Conexão perdida durante a sincronização");
        setState(State.DISCONNECTED, "");
    }

    public void disconnect() {
        Transport t = transport;
        if (t != null)
            t.disconnect();
    }

    public boolean isConnected() {
        return transport != null && transport.isConnected();
    }

    public void sendPackets(List<byte[]> packets, String name) {
        Transport t = transport;
        if (t == null) {
            LOG.warn("Not connected, dropping {}", name);
            return;
        }
        int chunk = Math.max(20, Math.min(paramsProvider.getMtu(), t.getMaxWriteSize()));
        for (byte[] packet : packets) {
            if (packet.length <= chunk) {
                t.write(packet);
            } else {
                for (int offset = 0; offset < packet.length; offset += chunk) {
                    int len = Math.min(chunk, packet.length - offset);
                    byte[] part = new byte[len];
                    System.arraycopy(packet, offset, part, 0, len);
                    t.write(part);
                }
            }
        }
    }

    private void setState(State newState, String detail) {
        state_ = newState;
        listener.onStateChanged(newState, detail);
    }

    public State getState() {
        return state_;
    }

    // ------------------------------------------------------------------ authentication

    private void initializeDevice() {
        createRandomMacAddress();
        createAndroidID();
        dynamicServicesStarted = false;
        if (!firstConnection && devicePrefs.getString("authkey", null) == null) {
            // Never completed a pairing with this band: behave as a first connection
            firstConnection = true;
        }
        setState(State.AUTHENTICATING, firstConnection ? "Confirme o pareamento na pulseira" : "");
        handler.removeCallbacks(initWatchdog);
        handler.postDelayed(initWatchdog, firstConnection ? 120000 : 45000);

        final GetLinkParamsRequest linkParamsReq = new GetLinkParamsRequest(this);
        linkParamsReq.setFinalizeReq(new RequestCallback() {
            @Override
            public void call() {
                initializeDeviceDealHiChain(linkParamsReq);
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                LOG.error("Link params TLV exception", e);
                listener.onAuthenticationFailed("Resposta inesperada da pulseira (link params)");
                disconnect();
            }

            @Override
            public void timeout(Request request) {
                LOG.error("Link params timeout");
                disconnect();
            }
        });
        linkParamsReq.setupTimeoutUntilNext(10000);
        try {
            linkParamsReq.doPerform();
        } catch (IOException e) {
            LOG.error("Initialization of authenticating to Huawei device failed", e);
            disconnect();
        }
    }

    protected boolean isHiChain() {
        // HiChain : 1 || 3, HiChainLite : 2 || 3 || 8, HiChain3 : 4
        byte authMode = paramsProvider.getDeviceSupportType();
        return authMode == 0x01 || authMode == 0x03 || authMode == 0x04 || isHiChainLite();
    }

    protected boolean isHiChainLite() {
        return paramsProvider.getDeviceSupportType() == 0x02;
    }

    protected boolean isHiChain3(int authType) {
        return (authType ^ 0x01) == 0x04 || (authType ^ 0x02) == 0x04;
    }

    private void initializeDeviceDealHiChain(final Request linkParamsReq) {
        LOG.info("Link params: protocol={} supportType={} authVersion={} authAlgo={} encrypt={} mtu={} slice={}",
                protocolVersion, paramsProvider.getDeviceSupportType(), paramsProvider.getAuthVersion(),
                paramsProvider.getAuthAlgo(), paramsProvider.getEncryptMethod(), paramsProvider.getMtu(), paramsProvider.getSliceSize());
        try {
            if (isHiChain()) {
                if (paramsProvider.getDeviceSupportType() == 4)
                    paramsProvider.setAuthMode((byte) 4);
                else
                    paramsProvider.setAuthMode((byte) 2);
                final GetSecurityNegotiationRequest securityNegoReq = new GetSecurityNegotiationRequest(this);
                RequestCallback securityFinalizeReq = new RequestCallback(this) {
                    @Override
                    public void call() {
                        LOG.info("Security negotiation authType=0x{}", Integer.toHexString(securityNegoReq.authType));
                        if (securityNegoReq.authType == 0x0186A0 || isHiChain3(securityNegoReq.authType)) {
                            LOG.debug("HiChain mode");
                            initializeDeviceHiChainMode();
                        } else {
                            LOG.debug("HiChain Lite mode");
                            initializeDeviceHiChainLiteMode(linkParamsReq);
                        }
                    }

                    @Override
                    public void timeout(Request request) {
                        authenticationFailed("A pulseira não respondeu à negociação de segurança");
                    }
                };
                securityNegoReq.setFinalizeReq(securityFinalizeReq);
                securityNegoReq.setupTimeoutUntilNext(10000);
                securityNegoReq.doPerform();
            } else {
                LOG.debug("Normal mode");
                initializeDeviceNormalMode(linkParamsReq);
            }
        } catch (IOException e) {
            LOG.error("Init deal with HiChain of Huawei device failed", e);
            authenticationFailed("Falha ao iniciar a autenticação");
        }
    }

    private final RequestCallback configureReq = new RequestCallback() {
        @Override
        public void call() {
            initializeDeviceConfigure();
        }

        @Override
        public void timeout(Request request) {
            LOG.error("Authentication timed out");
            authenticationFailed(firstConnection
                    ? "Tempo esgotado. Confirme o pareamento na tela da pulseira e tente de novo."
                    : "Tempo esgotado na autenticação");
        }

        @Override
        public void handleException(Request.ResponseParseException e) {
            LOG.error("Authentication exception", e);
            authenticationFailed("Autenticação recusada pela pulseira");
        }
    };

    private void authenticationFailed(String reason) {
        handler.removeCallbacks(initWatchdog);
        listener.onAuthenticationFailed(reason);
        disconnect();
    }

    private void initializeDeviceHiChainMode() {
        try {
            GetHiChainRequest hiChainReq = new GetHiChainRequest(this, firstConnection);
            hiChainReq.setFinalizeReq(configureReq);
            if (firstConnection) {
                GetPincodeRequest pincodeReq = new GetPincodeRequest(this);
                pincodeReq.setupTimeoutUntilNext(10000);
                pincodeReq.setFinalizeReq(new RequestCallback() {
                    @Override
                    public void timeout(Request request) {
                        authenticationFailed("A pulseira não enviou o código de pareamento");
                    }

                    @Override
                    public void handleException(Request.ResponseParseException e) {
                        LOG.error("Pincode exception", e);
                        authenticationFailed("Falha ao obter o código de pareamento");
                    }
                });
                pincodeReq.nextRequest(hiChainReq);
                pincodeReq.doPerform();
            } else
                hiChainReq.doPerform();
        } catch (IOException e) {
            LOG.error("HiChain Mode init of Huawei device failed", e);
            authenticationFailed("Falha no modo HiChain");
        }
    }

    private void initializeDeviceHiChainLiteMode(Request linkParamsReq) {
        try {
            createSecretKey();
            GetAuthRequest authReq = new GetAuthRequest(this, linkParamsReq);
            GetBondParamsRequest bondParamsReq = new GetBondParamsRequest(this);
            GetBondRequest bondReq = new GetBondRequest(this);
            authReq.setupTimeoutUntilNext(10000);
            authReq.setFinalizeReq(configureReq);
            bondParamsReq.setupTimeoutUntilNext(10000);
            authReq.nextRequest(bondParamsReq);
            bondParamsReq.nextRequest(bondReq);
            bondParamsReq.setFinalizeReq(configureReq);
            bondReq.setFinalizeReq(configureReq);
            bondReq.setupTimeoutUntilNext(firstConnection ? 60000 : 10000);
            if (paramsProvider.getPinCode() == null & paramsProvider.getAuthVersion() != 0x02) {
                GetPincodeRequest pinCodeReq = new GetPincodeRequest(this);
                pinCodeReq.setupTimeoutUntilNext(10000);
                pinCodeReq.nextRequest(authReq);
                pinCodeReq.doPerform();
            } else {
                authReq.doPerform();
            }
        } catch (IOException e) {
            LOG.error("HiChainLite mode init of Huawei device failed", e);
            authenticationFailed("Falha no modo HiChain Lite");
        }
    }

    private void initializeDeviceNormalMode(Request linkParamsReq) {
        try {
            createSecretKey();
            GetAuthRequest authReq = new GetAuthRequest(this, linkParamsReq);
            GetBondParamsRequest bondParamsReq = new GetBondParamsRequest(this);
            GetBondRequest bondReq = new GetBondRequest(this);
            authReq.setupTimeoutUntilNext(10000);
            authReq.setFinalizeReq(configureReq);
            bondParamsReq.setupTimeoutUntilNext(10000);
            authReq.nextRequest(bondParamsReq);
            bondParamsReq.nextRequest(bondReq);
            bondParamsReq.setFinalizeReq(configureReq);
            bondReq.setFinalizeReq(configureReq);
            bondReq.setupTimeoutUntilNext(firstConnection ? 60000 : 10000);
            authReq.doPerform();
        } catch (IOException e) {
            LOG.error("Normal mode init of Huawei device failed", e);
            authenticationFailed("Falha na autenticação");
        }
    }

    // ------------------------------------------------------------------ configuration

    private void initializeDeviceConfigure() {
        LOG.info("Authenticated, configuring the band");
        // From here on, the band trusts this phone
        firstConnection = false;
        devicePrefs.edit().putBoolean("paired", true).apply();
        setState(State.INITIALIZING, "");
        p2pManager.unregisterAllService();
        try {
            final List<Request> initRequestQueue = new ArrayList<>();
            initRequestQueue.add(new GetProductInformationRequest(this));
            initRequestQueue.add(new SetTimeRequest(this, true));
            initRequestQueue.add(new GetBatteryLevelRequest(this));
            initRequestQueue.add(new GetSupportedServicesRequest(this)); // MUST BE LAST - it indirectly kicks off initializeDynamicServices

            for (int i = 1; i < initRequestQueue.size(); i++) {
                initRequestQueue.get(i - 1).setupTimeoutUntilNext(INIT_TIMEOUT);
                initRequestQueue.get(i - 1).nextRequest(initRequestQueue.get(i));
            }
            Request last = initRequestQueue.get(initRequestQueue.size() - 1);
            last.setupTimeoutUntilNext(5000);
            last.setFinalizeReq(new RequestCallback() {
                @Override
                public void timeout(Request request) {
                    // Supported services did not answer: continue with what we already know
                    LOG.warn("Supported services timeout, continuing");
                    initializeDynamicServices();
                }

                @Override
                public void handleException(Request.ResponseParseException e) {
                    LOG.warn("Supported services failed, continuing", e);
                    initializeDynamicServices();
                }
            });
            initRequestQueue.get(0).doPerform();
        } catch (IOException e) {
            LOG.error("Final initialization of Huawei device failed", e);
            disconnect();
        }
    }

    /**
     * Called (indirectly) once the band told us what it supports.
     * Turns on every automatic health measurement the band offers.
     */
    public void initializeDynamicServices() {
        if (dynamicServicesStarted)
            return;
        dynamicServicesStarted = true;
        state.printCommandsPerService();
        try {
            final List<Request> queue = new ArrayList<>();
            queue.add(new GetSettingRelatedRequest(this));
            queue.add(new AcceptAgreementsRequest(this));
            // First-setup steps of the official app: without them the band keeps asking for it
            // Emotion monitoring is switched on through the data-sync service, not the expand capability
            if (state.supportsEmotion() && state.supportsDeviceCommandConfig())
                queue.add(new WorkoutPacketRequest(this, br.dimensono.bandsono.huawei.packets.DataSync.emotionState(paramsProvider, true)));
            if (state.supportsCommandForService(0x1a, 0x05) && state.supportsCommandForService(0x1a, 0x06))
                queue.add(new WorkoutPacketRequest(this, new AccountRelated.SendExtendedAccountToDevice.Request(paramsProvider,
                        state.supportsDiffAccountPairingOptimization(), "")));
            if (state.supportsCommandForService(0x01, 0x3f))
                queue.add(new WorkoutPacketRequest(this, new DeviceConfig.ReverseCapabilities.Request(paramsProvider)));
            if (state.supportsCommandForService(0x01, 0x3e))
                queue.add(new WorkoutPacketRequest(this, new DeviceConfig.SetUpDeviceStatusRequest(paramsProvider, 1,
                        device.getName().isEmpty() ? "HUAWEI Band 10" : device.getName())));
            if (state.supportsCommandForService(0x01, 0x3d))
                queue.add(new WorkoutPacketRequest(this, new DeviceConfig.WearStatus.Request(paramsProvider)));
            if (state.supportsCommandForService(0x01, 0x35))
                queue.add(new WorkoutPacketRequest(this, new DeviceConfig.ConnectStatusRequest(paramsProvider)));
            if (state.supportsCommandForService(0x01, 0x32))
                queue.add(new WorkoutPacketRequest(this, new DeviceConfig.TimeZoneIdRequest(paramsProvider)));
            if (state.supportsCommandForService(0x01, 0x04))
                queue.add(new WorkoutPacketRequest(this, new DeviceConfig.DateFormat.Request(paramsProvider,
                        (byte) DeviceConfig.Date.dayFirst,
                        (byte) DeviceConfig.Time.hours24))); // dd/MM, 24 h (Brasil)
            if (state.supportsCommandForService(0x0c, 0x01)) {
                java.util.Locale l = java.util.Locale.getDefault();
                String country = l.getCountry().isEmpty() ? l.getLanguage() : l.getCountry();
                queue.add(new WorkoutPacketRequest(this, new LocaleConfig.SetLanguageSetting(paramsProvider,
                        (l.getLanguage() + "-" + country.toUpperCase(java.util.Locale.ROOT)).getBytes(StandardCharsets.UTF_8),
                        LocaleConfig.MeasurementSystem.metric)));
            }
            queue.add(new SendNotifyRestHeartRateCapabilityRequest(this));
            // When the band tells the phone to sync: every 500 steps, 100 kcal or 1 h (also at night; Gadgetbridge uses 6 h before 6 am)
            if (state.supportsCommandForService(0x07, 0x0e)) {
                java.util.List<br.dimensono.bandsono.huawei.HuaweiReportThreshold> th = new java.util.ArrayList<>();
                th.add(new br.dimensono.bandsono.huawei.HuaweiReportThreshold(1, 3, 500, 2));
                th.add(new br.dimensono.bandsono.huawei.HuaweiReportThreshold(3, 3, 100, 2));
                th.add(new br.dimensono.bandsono.huawei.HuaweiReportThreshold(4, 3, 3600, 2));
                queue.add(new WorkoutPacketRequest(this, new FitnessData.DeviceReportThreshold.Request(paramsProvider, th)));
            }
            queue.add(new SetActivityReminderRequest(this));
            queue.add(new WorkoutPacketRequest(this, new Notifications.NotificationCapabilities.Request(paramsProvider)));
            queue.add(new WorkoutPacketRequest(this, new Notifications.NotificationConstraints.Request(paramsProvider)));
            queue.add(new SetNotificationStateRequest(this));
            queue.add(new SetWearMessagePushRequest(this));
            queue.add(new SetActivateOnLiftRequest(this));
            queue.add(new SetWearLocationRequest(this));
            queue.add(new SetTruSleepRequest(this));
            queue.add(new SendSleepBreathRequest(this));
            queue.add(new SetAutomaticHeartrateRequest(this));
            queue.add(new SetAutomaticSpoRequest(this));

            for (int i = 1; i < queue.size(); i++) {
                Request r = queue.get(i - 1);
                r.setupTimeoutUntilNext(r instanceof SetAutomaticHeartrateRequest || r instanceof SendSleepBreathRequest
                        ? NO_RESPONSE_TIMEOUT : INIT_TIMEOUT);
                r.nextRequest(queue.get(i));
            }
            Request last = queue.get(queue.size() - 1);
            last.setupTimeoutUntilNext(NO_RESPONSE_TIMEOUT);
            last.setFinalizeReq(new RequestCallback() {
                @Override
                public void call() {
                    onReady();
                }

                @Override
                public void timeout(Request request) {
                    onReady();
                }

                @Override
                public void handleException(Request.ResponseParseException e) {
                    LOG.error("Configuration exception", e);
                    onReady();
                }
            });
            queue.get(0).doPerform();
        } catch (IOException e) {
            LOG.error("Initializing dynamic services of Huawei device failed", e);
            onReady();
        }
    }

    /** Re-send the monitoring configuration (after the user changed a toggle). */
    public void applyMonitoringSettings() {
        if (state_ != State.READY)
            return;
        try {
            Request a = new SetActivityReminderRequest(this);
            Request notif = new SetNotificationStateRequest(this);
            Request lift = new SetActivateOnLiftRequest(this);
            Request wear = new SetWearLocationRequest(this);
            Request b = new SetTruSleepRequest(this);
            Request breath = new SendSleepBreathRequest(this);
            Request c = new SetAutomaticHeartrateRequest(this);
            Request d = new SetAutomaticSpoRequest(this);
            a.setupTimeoutUntilNext(INIT_TIMEOUT);
            lift.setupTimeoutUntilNext(INIT_TIMEOUT);
            wear.setupTimeoutUntilNext(INIT_TIMEOUT);
            b.setupTimeoutUntilNext(INIT_TIMEOUT);
            breath.setupTimeoutUntilNext(NO_RESPONSE_TIMEOUT);
            c.setupTimeoutUntilNext(NO_RESPONSE_TIMEOUT);
            d.setupTimeoutUntilNext(NO_RESPONSE_TIMEOUT);
            a.nextRequest(notif);
            notif.setupTimeoutUntilNext(INIT_TIMEOUT);
            notif.nextRequest(lift);
            lift.nextRequest(wear);
            wear.nextRequest(b);
            b.nextRequest(breath);
            breath.nextRequest(c);
            c.nextRequest(d);
            d.setFinalizeReq(new RequestCallback() {
                @Override
                public void call() {
                    applyStressSetting();
                }

                @Override
                public void timeout(Request request) {
                    applyStressSetting();
                }
            });
            a.doPerform();
        } catch (IOException e) {
            LOG.error("Could not apply settings", e);
        }
    }

    /** Automatic stress needs a baseline from the one-minute RR test; without it we leave it off. */
    private void applyStressSetting() {
        if (!state.supportsAutoStress())
            return;
        boolean wanted = getSetting(PREF_STRESS_AUTO, true);
        HuaweiStressParser.StressData baseline = getLastStressData();
        if (wanted && baseline == null) {
            LOG.info("Automatic stress wanted but no calibration yet");
            return;
        }
        try {
            SetStressRequest req = new SetStressRequest(this, wanted);
            req.doPerform();
        } catch (IOException e) {
            LOG.error("Failed to set stress", e);
        }
    }

    private void onReady() {
        if (state_ == State.READY || state_ == State.SYNCING || !isConnected())
            return;
        handler.removeCallbacks(initWatchdog);
        LOG.info("Band ready (HR auto={}, SpO2={}, TruSleep={}, dict sleep={}, auto stress={}, HRV={}, P2P={})",
                state.supportsHeartRate(), state.supportsSPo2(), state.supportsTruSleep(), state.supportsDictSleepSync(),
                state.supportsAutoStress(), state.supportsHRV(), state.supportsP2PService());
        applyStressSetting();
        if (state.supportsP2PService() && HuaweiP2PDataDictionarySyncService.getRegisteredInstance(p2pManager) == null) {
            new HuaweiP2PDataDictionarySyncService(p2pManager).register();
        }
        listener.onDeviceInfo(device);
        setState(State.READY, "");
        if (syncPending) {
            syncPending = false;
            // give the P2P service a moment to answer its ping
            handler.postDelayed(this::startSyncIfReady, 1500);
        }
    }

    private void startSyncIfReady() {
        if (state_ == State.READY && !syncRunning)
            startSync();
    }

    // ------------------------------------------------------------------ data sync

    /** Request a sync; runs now if the band is ready, otherwise as soon as it is. */
    public void requestSync() {
        if (syncRunning)
            return;
        if (state_ == State.READY)
            startSync();
        else
            syncPending = true;
    }

    public boolean isSyncRunning() {
        return syncRunning;
    }

    private void startSync() {
        if (measurement != null && measurement.isRunning()) {
            LOG.info("RR test running, sync postponed");
            syncPending = true;
            return;
        }
        syncRunning = true;
        syncSteps = 0;
        syncSleep = 0;
        syncNights = 0;
        syncStress = 0;
        syncDict = 0;
        syncWorkouts = 0;
        syncTimeouts = 0;
        syncHr = 0;
        stepRaw.reset();
        unknownStepTags.clear();
        syncStartWall = System.currentTimeMillis();
        syncGeneration++;
        extras.reset();
        rxAtSyncStart = rxPackets;
        setState(State.SYNCING, "");
        handler.removeCallbacks(syncWatchdog);
        handler.postDelayed(syncWatchdog, SYNC_WATCHDOG_MS);

        final int end = (int) (System.currentTimeMillis() / 1000);
        final int minStart = end - 30 * 24 * 3600; // first sync: last 30 days
        int stepStart = listener.getLastStepTimestamp();
        int sleepStart = listener.getLastSleepTimestamp();
        stepStart = stepStart <= 0 ? minStart : Math.max(minStart, stepStart - 3600);
        sleepStart = sleepStart <= 0 ? minStart : Math.max(minStart, sleepStart - 18 * 3600);
        final int fStepStart = stepStart;
        syncStepStart = stepStart;
        final int fSleepStart = sleepStart;
        LOG.info("Starting sync: steps from {}, sleep from {} to {}", stepStart, sleepStart, end);

        GetBatteryLevelRequest battery = new GetBatteryLevelRequest(this);
        battery.setupTimeoutUntilNext(INIT_TIMEOUT);

        final GetSleepDataCountRequest sleepCount = new GetSleepDataCountRequest(this, fSleepStart, end);
        final GetStepDataCountRequest stepCount = new GetStepDataCountRequest(this, fStepStart, end);
        final GetFitnessTotalsRequest totals = new GetFitnessTotalsRequest(this);

        battery.setFinalizeReq(new RequestCallback() {
            @Override
            public void call() {
                performOrSkip(sleepCount);
            }

            @Override
            public void timeout(Request request) {
                LOG.warn("Battery request timed out");
                syncTimeouts++;
                performOrSkip(sleepCount);
            }

            @Override
            public void handleException(Request.ResponseParseException e) {
                performOrSkip(sleepCount);
            }
        });

        sleepCount.setupTimeoutUntilNext(SYNC_STEP_TIMEOUT);
        sleepCount.setFinalizeReq(new SyncStageCallback("sleep") {
            @Override
            void next() {
                if (rxPackets == rxAtSyncStart) {
                    // Battery and sleep requests went out and not a single byte came back:
                    // the GATT link is up on Android's side but dead on the band's side.
                    linkUnresponsive("A pulseira não respondeu aos pedidos de dados (conexão travada)");
                    return;
                }
                performOrSkip(stepCount);
            }
        });

        stepCount.setupTimeoutUntilNext(SYNC_STEP_TIMEOUT);
        stepCount.setFinalizeReq(new SyncStageCallback("steps") {
            @Override
            void next() {
                performOrSkip(totals);
            }
        });

        totals.setupTimeoutUntilNext(INIT_TIMEOUT);
        totals.setFinalizeReq(new SyncStageCallback("totals") {
            @Override
            void next() {
                if (syncTimeouts >= 3 && syncSteps == 0 && syncSleep == 0) {
                    linkUnresponsive("A pulseira não respondeu aos pedidos de dados");
                    return;
                }
                int lastWorkout = listener.getLastWorkoutTimestamp();
                final int workoutStart = lastWorkout > 0 ? Math.max(minStart, lastWorkout + 1) : minStart;
                downloadSleepDetails(fSleepStart, end, () -> downloadStress(fSleepStart, end, () -> syncDictionary(
                        () -> syncWorkouts(workoutStart, end, () -> extras.run(() -> finishSync(true, null))))));
            }
        });

        performOrSkip(battery);
    }

    private abstract class SyncStageCallback extends RequestCallback {
        private final String name;
        private boolean done = false;

        SyncStageCallback(String name) {
            this.name = name;
        }

        abstract void next();

        private void proceed() {
            if (done) return;
            done = true;
            next();
        }

        @Override
        public void call() {
            proceed();
        }

        @Override
        public void timeout(Request request) {
            LOG.warn("Sync stage {} timed out", name);
            syncTimeouts++;
            proceed();
        }

        @Override
        public void handleException(Request.ResponseParseException e) {
            LOG.error("Sync stage " + name + " failed", e);
            proceed();
        }
    }

    private void performOrSkip(Request request) {
        if (!syncRunning)
            return;
        try {
            request.doPerform();
        } catch (IOException e) {
            LOG.error("Could not send " + request.getName(), e);
            finishSync(false, "Falha ao enviar pedido à pulseira");
        }
    }

    /** Runs `next` exactly once: when the stage calls it or when the stage times out. */
    private Runnable stage(String name, int timeoutMs, final Runnable next) {
        final boolean[] done = {false};
        final Runnable[] self = new Runnable[1];
        final Runnable proceed = () -> {
            if (done[0]) return;
            done[0] = true;
            handler.removeCallbacks(self[0]);
            if (stageTimeout == self[0]) stageTimeout = null;
            if (syncRunning) next.run();
        };
        self[0] = () -> {
            if (done[0]) return;
            LOG.warn("Sync stage {} timed out", name);
            fileDownloadManager.cancelAll();
            proceed.run();
        };
        stageTimeout = self[0];
        handler.postDelayed(self[0], timeoutMs);
        return proceed;
    }

    /** Sleep: per-minute stages and nightly summary (new bands) or TruSleep sessions (older ones). */
    private void downloadSleepDetails(int start, int end, Runnable next) {
        if (!syncRunning) return;
        if (!state.supportsTruSleep() && !state.supportsDictSleepSync()) {
            next.run();
            return;
        }
        final Runnable done = stage("sleep details", FILE_STAGE_TIMEOUT, next);
        if (state.supportsDictSleepSync()) {
            fileDownloadManager.addToQueue(HuaweiFileDownloadManager.FileRequest.sequenceDataFileRequest(
                    start, end, HuaweiDictTypes.SLEEP_DETAILS_CLASS,
                    new HuaweiFileDownloadManager.FileDownloadCallback() {
                        @Override
                        public void downloadComplete(HuaweiFileDownloadManager.FileRequest fileRequest, File localRawFile) {
                            rawFile("sleep_sequence", start, end, fileRequest.getData());
                            try {
                                handleSleepSequenceFile(fileRequest.getData());
                            } catch (Exception e) {
                                LOG.error("Sleep sequence parse failed", e);
                            }
                            downloadTruSleepRaw(start, end, done);
                        }

                        @Override
                        public void downloadException(HuaweiFileDownloadManager.HuaweiFileDownloadException e) {
                            super.downloadException(e);
                            downloadTruSleepRaw(start, end, done);
                        }
                    }), true);
        } else {
            boolean newSync = state.getSupportsTruSleepNewSync();
            fileDownloadManager.addToQueue(HuaweiFileDownloadManager.FileRequest.sleepStateFileRequest(
                    newSync, start, end,
                    new HuaweiFileDownloadManager.FileDownloadCallback() {
                        @Override
                        public void downloadComplete(HuaweiFileDownloadManager.FileRequest fileRequest, File localRawFile) {
                            rawFile("sleep_state", start, end, fileRequest.getData());
                            if (fileRequest.getData().length != 0) {
                                HuaweiTruSleepParser.TruSleepStatus[] results = HuaweiTruSleepParser.parseState(fileRequest.getData());
                                for (HuaweiTruSleepParser.TruSleepStatus status : results) {
                                    if (status == null) continue;
                                    syncNights++;
                                    listener.onSleepSegment(status.startTime, status.endTime, 0x06, SOURCE_TRUSLEEP);
                                }
                            }
                            done.run();
                        }

                        @Override
                        public void downloadException(HuaweiFileDownloadManager.HuaweiFileDownloadException e) {
                            super.downloadException(e);
                            done.run();
                        }
                    }), true);
        }
    }

    /**
     * Raw TruSleep sensors: movement (accelerometer, one sample a minute) and pulse peaks (PPG,
     * about every second at night). Kept as raw files and summarised in the log.
     */
    private void downloadTruSleepRaw(final int start, final int end, final Runnable next) {
        if (!syncRunning || !state.supportsTruSleep()) {
            next.run();
            return;
        }
        final boolean newSync = state.getSupportsTruSleepNewSync();
        fileDownloadManager.addToQueue(HuaweiFileDownloadManager.FileRequest.sleepStateFileRequest(newSync, start, end,
                new HuaweiFileDownloadManager.FileDownloadCallback() {
                    @Override
                    public void downloadComplete(HuaweiFileDownloadManager.FileRequest fileRequest, File localRawFile) {
                        byte[] d = fileRequest.getData();
                        LOG.info("TruSleep state file: {} bytes", d == null ? 0 : d.length);
                        rawFile("sleep_state", start, end, d);
                        downloadTruSleepData(start, end, newSync, next);
                    }

                    @Override
                    public void downloadException(HuaweiFileDownloadManager.HuaweiFileDownloadException e) {
                        LOG.info("TruSleep state file not available: {}", e.getMessage());
                        downloadTruSleepData(start, end, newSync, next);
                    }
                }), true);
    }

    private void downloadTruSleepData(final int start, final int end, boolean newSync, final Runnable next) {
        fileDownloadManager.addToQueue(HuaweiFileDownloadManager.FileRequest.sleepDataFileRequest(newSync, start, end,
                new HuaweiFileDownloadManager.FileDownloadCallback() {
                    @Override
                    public void downloadComplete(HuaweiFileDownloadManager.FileRequest fileRequest, File localRawFile) {
                        byte[] d = fileRequest.getData();
                        LOG.info("TruSleep data file: {} bytes", d == null ? 0 : d.length);
                        rawFile("sleep_data", start, end, d);
                        if (d != null && d.length > 0) {
                            try {
                                HuaweiTruSleepParser.TruSleepData t = HuaweiTruSleepParser.parseData(d);
                                LOG.info("TruSleep raw: {} movement samples, {} pulse peaks", t.dataACCs.size(), t.dataPPGs.size());
                                listener.onTruSleepRaw(t);
                            } catch (Exception e) {
                                LOG.error("TruSleep data parse failed", e);
                            }
                        }
                        next.run();
                    }

                    @Override
                    public void downloadException(HuaweiFileDownloadManager.HuaweiFileDownloadException e) {
                        LOG.info("TruSleep data file not available: {}", e.getMessage());
                        next.run();
                    }
                }), true);
    }

    private void handleSleepSequenceFile(byte[] data) {
        HuaweiSequenceDataFileParser.SequenceFileData fileData = HuaweiSequenceDataFileParser.parseSequenceFileData(data);
        if (fileData == null || fileData.getSequenceDataList() == null)
            return;
        for (HuaweiSequenceDataFileParser.SequenceData sd : fileData.getSequenceDataList()) {
            HuaweiTrueSleepSequenceDataParser parser = new HuaweiTrueSleepSequenceDataParser();
            HuaweiTrueSleepSequenceDataParser.SleepSummary s = parser.parseData(fileData, sd);
            if (s == null)
                continue;
            HuaweiTrueSleepSequenceDataParser.correctSummary(s);
            // the summary holds health values: it is never logged

            SleepSummary out = new SleepSummary();
            out.fallAsleep = Math.max(0, s.fallAsleepTime);
            out.bedTime = Math.max(0, s.bedTime);
            out.risingTime = Math.max(0, s.risingTime);
            out.wakeup = Math.max(0, s.wakeupTime);
            out.score = s.sleepScore;
            out.latencyMin = s.sleepLatency;
            out.efficiency = s.sleepEfficiency;
            out.wakeCount = s.wakeCount;
            out.deepPart = s.deepPart;
            out.minHr = s.minHeartRate;
            out.maxHr = s.maxHeartRate;
            out.avgHr = s.avgHeartRate;
            out.minSpo2 = s.minOxygenSaturation;
            out.maxSpo2 = s.maxOxygenSaturation;
            out.avgSpo2 = s.avgOxygenSaturation;
            out.minBreath = s.minBreathRate;
            out.maxBreath = s.maxBreathRate;
            out.avgBreath = s.avgBreathRate;
            out.avgHrv = s.avgHrv;
            out.minHrvBaseline = s.minHrvBaseline;
            out.maxHrvBaseline = s.maxHrvBaseline;
            out.rdi = s.rdi;
            listener.onSleepSummary(out);
            safely("night details", () -> listener.onNightDetails(s));
            syncNights++;

            // Per-minute stages: 1 light, 2 REM, 3 deep, 4 awake, 5 nap. Only asleep/awake matters here.
            long time = HuaweiTrueSleepSequenceDataParser.getTime(s.fallAsleepTime, s.bedTime, s.validData, state.supportsBedTime());
            List<HuaweiTrueSleepSequenceDataParser.SleepStage> stages = HuaweiTrueSleepSequenceDataParser.parseSleepDetails(sd.getDetails(), time);
            if (stages == null || stages.isEmpty()) {
                if (s.fallAsleepTime > 0 && s.wakeupTime > s.fallAsleepTime)
                    listener.onSleepSegment((int) s.fallAsleepTime, (int) s.wakeupTime, 0x06, SOURCE_STAGES);
                continue;
            }
            // Hypnogram: runs of the same stage (1 light, 2 REM, 3 deep, 4 awake, 5 nap)
            long stStart = -1, stLast = -1;
            int stStage = -1;
            for (HuaweiTrueSleepSequenceDataParser.SleepStage st : stages) {
                long t = st.getTime();
                if (stStart < 0 || st.getStage() != stStage || t != stLast + 60) {
                    if (stStart >= 0)
                        listener.onSleepStage((int) stStart, (int) (stLast + 60), stStage);
                    stStart = t;
                    stStage = st.getStage();
                }
                stLast = t;
            }
            if (stStart >= 0)
                listener.onSleepStage((int) stStart, (int) (stLast + 60), stStage);
            // Runs of consecutive asleep minutes. Naps (stage 5) are emitted as separate runs
            // with type SLEEP_TYPE_NAP so the app can show or hide them.
            long runStart = -1, last = -1;
            boolean runNap = false;
            for (HuaweiTrueSleepSequenceDataParser.SleepStage st : stages) {
                int stage = st.getStage();
                boolean asleep = stage == 1 || stage == 2 || stage == 3 || stage == 5;
                boolean nap = stage == 5;
                long t = st.getTime();
                if (asleep) {
                    if (runStart < 0 || t != last + 60 || nap != runNap) {
                        if (runStart >= 0)
                            listener.onSleepSegment((int) runStart, (int) (last + 60), runNap ? SLEEP_TYPE_NAP : SLEEP_TYPE_MAIN, SOURCE_STAGES);
                        runStart = t;
                        runNap = nap;
                    }
                    last = t;
                } else if (runStart >= 0) {
                    listener.onSleepSegment((int) runStart, (int) (last + 60), runNap ? SLEEP_TYPE_NAP : SLEEP_TYPE_MAIN, SOURCE_STAGES);
                    runStart = -1;
                }
            }
            if (runStart >= 0)
                listener.onSleepSegment((int) runStart, (int) (last + 60), runNap ? SLEEP_TYPE_NAP : SLEEP_TYPE_MAIN, SOURCE_STAGES);
        }
    }

    /** Runs a listener call for the extra detail tables: a failure there must never stop the sync (no data in the message). */
    private void safely(String what, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            LOG.error("Could not store " + what + ": " + e.getClass().getSimpleName());
        }
    }

    private void rawFile(String kind, int start, int end, byte[] data) {
        if (data == null || data.length == 0) return;
        try {
            listener.onRawFile(kind, start, end, data);
        } catch (Exception e) {
            LOG.error("Could not keep raw file " + kind, e);
        }
    }

    /** Automatic stress scores computed by the band (needs a one-minute calibration first). */
    private void downloadStress(int start, int end, Runnable next) {
        if (!syncRunning) return;
        if (!state.supportsAutoStress() || getLastStressData() == null) {
            next.run();
            return;
        }
        final Runnable done = stage("stress", FILE_STAGE_TIMEOUT, next);
        fileDownloadManager.addToQueue(HuaweiFileDownloadManager.FileRequest.rriFileRequest(
                state.getSupportsRriNewSync(), start, end,
                new HuaweiFileDownloadManager.FileDownloadCallback() {
                    @Override
                    public void downloadComplete(HuaweiFileDownloadManager.FileRequest fileRequest, File localRawFile) {
                        rawFile("rri", start, end, fileRequest.getData());
                        try {
                            if (fileRequest.getData().length != 0) {
                                HuaweiStressParser.RriFileData results = HuaweiStressParser.parseRri(fileRequest.getData());
                                if (results != null && results.stressData != null && !results.stressData.isEmpty()) {
                                    HuaweiStressParser.StressData latest = results.stressData.get(results.stressData.size() - 1);
                                    HuaweiStressParser.StressData current = getLastStressData();
                                    if (current == null || latest.endTime > current.endTime)
                                        storeLastStressData(latest);
                                    for (HuaweiStressParser.StressData dt : results.stressData) {
                                        syncStress++;
                                        listener.onStress((int) (dt.startTime / 1000), (int) (dt.endTime / 1000), dt.score & 0xff, dt.level & 0xff);
                                        safely("stress details", () -> listener.onStressDetails(dt));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            LOG.error("Stress parse failed", e);
                        }
                        done.run();
                    }

                    @Override
                    public void downloadException(HuaweiFileDownloadManager.HuaweiFileDownloadException e) {
                        super.downloadException(e);
                        done.run();
                    }
                }), true);
    }

    /** HRV (RMSSD), skin temperature, emotion and breathing from the band's data dictionary (P2P service), in the handler order. */
    private void syncDictionary(Runnable next) {
        dictionaryStage(false, P2P_STAGE_TIMEOUT, next);
    }

    /**
     * One dictionary stage: the main classes, or (extra) the arrhythmia result, which runs among the extras so a band that
     * does not answer it cannot hold back the others. Pending requests are forgotten at the start and at the end, so a
     * silent band never leaves a class "in progress" for the next sync.
     */
    private void dictionaryStage(final boolean extra, int timeoutMs, final Runnable next) {
        if (!syncRunning) return;
        final int gen = syncGeneration;
        final HuaweiP2PDataDictionarySyncService service = HuaweiP2PDataDictionarySyncService.getRegisteredInstance(p2pManager);
        if (service == null) {
            next.run();
            return;
        }
        final Map<Integer, HuaweiDictionarySyncInterface> supported = extra ? service.getExtraSupported(state) : service.getAllSupported(state);
        if (supported.isEmpty()) {
            next.run();
            return;
        }
        service.reset();
        final Runnable done = stage(extra ? "arrhythmia result" : "dictionary", timeoutMs, () -> {
            service.reset();
            next.run();
        });
        service.startSync(new ArrayList<>(supported.keySet()), new HuaweiP2PDataDictionarySyncService.DictionarySyncCallback() {
            @Override
            public long onGetLastDataSyncTimestamp(int dictClass) {
                HuaweiDictionarySyncInterface h = supported.get(dictClass);
                if (h == null) return -1;
                long last = listener.getLastDictTimestamp(h.getKind());
                long min = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
                return Math.max(last, min);
            }

            @Override
            public void onData(int dictClass, List<HuaweiP2PDataDictionarySyncService.DictData> dictData) {
                if (gen != syncGeneration) return; // a late answer of an earlier sync
                HuaweiDictionarySyncInterface h = supported.get(dictClass);
                if (h == null) return;
                for (Map.Entry<String, List<double[]>> e : h.parseByKind(dictData).entrySet()) {
                    List<double[]> values = e.getValue();
                    syncDict += values.size();
                    if (!values.isEmpty())
                        listener.onDictValues(e.getKey(), values);
                }
            }

            @Override
            public void onComplete(boolean complete) {
                LOG.info("Dictionary sync complete: {}", complete);
                done.run();
            }
        });
    }

    // ------------------------------------------------------------------ workouts (service 0x17)

    /** Workouts recorded on the band since {@code start}: summary, then every sample block. */
    private void syncWorkouts(int start, int end, Runnable next) {
        if (!syncRunning) return;
        if (!state.supportsWorkouts()) {
            next.run();
            return;
        }
        final Runnable done = stage("workouts", FILE_STAGE_TIMEOUT, next);
        sendWorkoutPacket(new Workout.WorkoutCount.Request(paramsProvider, start, end), resp -> {
            if (!(resp instanceof Workout.WorkoutCount.Response)) {
                done.run();
                return;
            }
            Workout.WorkoutCount.Response count = (Workout.WorkoutCount.Response) resp;
            if (count.error != null || count.workoutNumbers == null || count.workoutNumbers.isEmpty()) {
                if (count.error != null) LOG.warn("Workout count error {}", count.error);
                done.run();
                return;
            }
            List<Workout.WorkoutCount.Response.WorkoutNumbers> list = new ArrayList<>(count.workoutNumbers);
            list.sort((a, b) -> Integer.compare(a.workoutNumber, b.workoutNumber));
            LOG.info("{} workouts to download", list.size());
            syncNextWorkout(list, 0, done);
        });
    }

    private void syncNextWorkout(final List<Workout.WorkoutCount.Response.WorkoutNumbers> list, final int index, final Runnable done) {
        if (!syncRunning) return;
        if (index >= list.size()) {
            done.run();
            return;
        }
        final Workout.WorkoutCount.Response.WorkoutNumbers numbers = list.get(index);
        sendWorkoutPacket(new Workout.WorkoutTotals.Request(paramsProvider, numbers.workoutNumber), resp -> {
            if (!(resp instanceof Workout.WorkoutTotals.Response) || ((Workout.WorkoutTotals.Response) resp).error != null) {
                LOG.warn("No totals for workout {}", numbers.workoutNumber);
                syncNextWorkout(list, index + 1, done);
                return;
            }
            Workout.WorkoutTotals.Response t = (Workout.WorkoutTotals.Response) resp;
            WorkoutSummary w = workoutSummary(t, numbers);
            listener.onWorkout(w);
            safely("workout details", () -> listener.onWorkoutDetails(t));
            extras.noteTrajectory(numbers, t);
            workoutRawV2.reset();
            syncWorkouts++;
            rawFile("workout_totals", t.startTime, t.endTime, t.rawData);
            syncWorkoutData(list, index, w, (short) 0, new ArrayList<>(), new ArrayList<>(), new java.io.ByteArrayOutputStream(), done);
        });
    }

    private void syncWorkoutData(final List<Workout.WorkoutCount.Response.WorkoutNumbers> list, final int index, final WorkoutSummary w,
                                 final short dataNumber, final List<WorkoutSample> samples,
                                 final List<Workout.WorkoutData.Response.Data> rawSamples, final java.io.ByteArrayOutputStream raw,
                                 final Runnable done) {
        if (!syncRunning) return;
        final Workout.WorkoutCount.Response.WorkoutNumbers numbers = list.get(index);
        if (dataNumber >= numbers.dataCount) {
            if (!samples.isEmpty())
                listener.onWorkoutSamples(w.startTs, samples);
            if (!rawSamples.isEmpty())
                safely("workout sample details", () -> listener.onWorkoutSampleDetails(w.startTs, rawSamples));
            if (raw.size() > 0)
                rawFile("workout_data", w.startTs, w.endTs, raw.toByteArray());
            if (workoutRawV2.size() > 0) {
                rawFile("workout_data_v2", w.startTs, w.endTs, workoutRawV2.toByteArray());
                workoutRawV2.reset();
            }
            syncNextWorkout(list, index + 1, done);
            return;
        }
        sendWorkoutPacket(new Workout.WorkoutData.Request(paramsProvider, numbers.workoutNumber, dataNumber, false), resp -> {
            if (resp instanceof Workout.WorkoutData.Response) {
                Workout.WorkoutData.Response d = (Workout.WorkoutData.Response) resp;
                if (d.error == null && d.dataList != null) {
                    for (Workout.WorkoutData.Response.Data x : d.dataList)
                        samples.add(workoutSample(x));
                    rawSamples.addAll(d.dataList);
                    // Kept as [header length u8][header][data] blocks to reparse later
                    raw.write(d.rawHeader.length);
                    raw.write(d.rawHeader, 0, d.rawHeader.length);
                    raw.write(d.rawData, 0, d.rawData.length);
                    // v2 also keeps the inner bitmap of the block (which optional fields exist), so a later reparse is complete:
                    // [header length u8][header][inner bitmap u32 big-endian][data]
                    workoutRawV2.write(d.rawHeader.length);
                    workoutRawV2.write(d.rawHeader, 0, d.rawHeader.length);
                    workoutRawV2.write(java.nio.ByteBuffer.allocate(4).putInt(d.innerBitmap).array(), 0, 4);
                    workoutRawV2.write(d.rawData, 0, d.rawData.length);
                }
            }
            syncWorkoutData(list, index, w, (short) (dataNumber + 1), samples, rawSamples, raw, done);
        });
    }

    /** Sends one workout packet; {@code then} gets the parsed answer, or null on timeout/error. */
    private void sendWorkoutPacket(HuaweiPacket packet, final java.util.function.Consumer<HuaweiPacket> then) {
        if (!syncRunning) return;
        final WorkoutPacketRequest r = new WorkoutPacketRequest(this, packet);
        final boolean[] fired = {false};
        r.setupTimeoutUntilNext(SYNC_STEP_TIMEOUT);
        r.setFinalizeReq(new RequestCallback() {
            @Override
            public void call(Request request) {
                if (fired[0]) return;
                fired[0] = true;
                then.accept(r.getResponse());
            }

            @Override
            public void timeout(Request request) {
                if (fired[0]) return;
                fired[0] = true;
                LOG.warn("Workout request {} timed out", Integer.toHexString(packet.commandId & 0xff));
                then.accept(null);
            }

            @Override
            public void handleException(Request request, Request.ResponseParseException e) {
                if (fired[0]) return;
                fired[0] = true;
                LOG.error("Workout request failed", e);
                then.accept(null);
            }
        });
        try {
            r.doPerform();
        } catch (IOException e) {
            LOG.error("Could not send workout request", e);
            if (!fired[0]) {
                fired[0] = true;
                then.accept(null);
            }
        }
    }

    private static WorkoutSummary workoutSummary(Workout.WorkoutTotals.Response t, Workout.WorkoutCount.Response.WorkoutNumbers n) {
        WorkoutSummary w = new WorkoutSummary();
        w.number = t.number;
        w.startTs = t.startTime;
        w.endTs = t.endTime;
        w.type = t.type == -1 ? -1 : t.type & 0xff;
        w.status = t.status == -1 ? -1 : t.status & 0xff;
        w.calories = t.calories;
        w.distance = t.distance;
        w.steps = t.stepCount;
        w.totalTime = t.totalTime;
        w.duration = t.duration;
        w.minHrPeak = t.minHeartRatePeak == 0 ? -1 : t.minHeartRatePeak & 0xff;
        w.maxHrPeak = t.maxHeartRatePeak == 0 ? -1 : t.maxHeartRatePeak & 0xff;
        w.load = t.workoutLoad;
        w.aerobicEffect = t.workoutAerobicEffect;
        w.anaerobicEffect = t.workoutAnaerobicEffect;
        w.recoveryTime = t.recoveryTime;
        w.maxMet = t.maxMET;
        w.trainingPoints = t.trainingPoints;
        w.elevationGain = t.elevationGain;
        w.elevationLoss = t.elevationLoss;
        w.maxAltitude = t.maxAltitude;
        w.minAltitude = t.minAltitude;
        w.strokes = t.strokes;
        w.avgStrokeRate = t.avgStrokeRate;
        w.laps = t.laps;
        w.avgSwolf = t.avgSwolf;
        w.poolLength = t.poolLength;
        w.swimType = t.swimType;
        w.dataCount = n.dataCount;
        w.paceCount = n.paceCount;
        return w;
    }

    private static WorkoutSample workoutSample(Workout.WorkoutData.Response.Data x) {
        WorkoutSample s = new WorkoutSample();
        s.ts = x.timestamp;
        s.heartRate = x.heartRate == -1 ? -1 : x.heartRate & 0xff;
        s.speed = x.speed;
        s.stepRate = x.stepRate == -1 ? -1 : x.stepRate & 0xff;
        s.cadence = x.cadence;
        s.stepLength = x.stepLength;
        s.groundContactTime = x.groundContactTime;
        s.calories = x.calories;
        s.spo2 = x.spo2 == -1 ? -1 : x.spo2 & 0xff;
        s.temp = x.temp;
        s.cyclingPower = x.cyclingPower;
        s.rideCadence = x.rideCadence == -1 ? -1 : x.rideCadence & 0xff;
        s.swolf = x.swolf;
        s.strokeRate = x.strokeRate;
        s.altitude = x.altitude;
        return s;
    }

    // ------------------------------------------------------------------ the band's push (after the sync code)

    /**
     * The band pushed "please sync" (service 7, command 0x0f). Which tag holds the action (1 simple sync, 2/3 activity,
     * 4 sleep, 8 workouts) is not confirmed, so every one-byte tag is looked at: only the numbers are logged, the first
     * known action wins. The band is not answered.
     */
    private void handleSyncPush(HuaweiPacket packet) {
        int action = -1;
        StringBuilder seen = new StringBuilder();
        HuaweiTLV t = packet.getTlv();
        if (t != null) {
            for (HuaweiTLV.TLV x : t.get()) {
                byte[] v = x.getValue();
                if (v == null || v.length != 1) continue;
                int val = v[0] & 0xff;
                seen.append(' ').append(x.getTag() & 0xff).append('=').append(val);
                if (action < 0 && (val == 1 || val == 2 || val == 3 || val == 4 || val == 8)) action = val;
            }
        }
        LOG.info("The band asked for a sync (0x07/0x0f); one-byte tags:{} action={}", seen, action);
        final int a = action;
        safely("sync request", () -> listener.onBandRequestsSync(a));
    }

    private void finishSync(boolean ok, String message) {
        if (!syncRunning)
            return;
        syncRunning = false;
        handler.removeCallbacks(syncWatchdog);
        if (stageTimeout != null) {
            handler.removeCallbacks(stageTimeout);
            stageTimeout = null;
        }
        extras.cancel();
        syncGeneration++;
        String summary = message != null ? message
                : String.format(java.util.Locale.US, "%d min de atividade (%d com FC), %d trechos de sono, %d noites detalhadas, %d estresse, %d VFC/temperatura/emoção/respiração, %d treinos",
                syncSteps, syncHr, syncSleep, syncNights, syncStress, syncDict, syncWorkouts);
        if (syncSteps > 0) {
            LOG.info("Minute records: {}, with heart rate: {} ({}%)", syncSteps, syncHr, Math.round(100.0 * syncHr / syncSteps));
            if (!unknownStepTags.isEmpty()) {
                // only which fields and how many times: the values are health data and never go to the log
                StringBuilder u = new StringBuilder();
                java.util.List<UnknownStepTag> tags = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, UnknownStepTag> e : unknownStepTags.entrySet()) {
                    u.append(' ').append(e.getKey()).append(" x").append(e.getValue().count);
                    tags.add(e.getValue());
                }
                LOG.info("Step data fields not decoded (bitmap/tag):{}", u);
                safely("step tags", () -> listener.onUnknownStepTags(tags));
            }
        }
        if (stepRaw.size() > 0) {
            rawFile("step_data", syncStepStart, (int) (System.currentTimeMillis() / 1000), stepRaw.toByteArray());
            stepRaw.reset();
        }
        LOG.info("Sync finished ok={} {}", ok, summary);
        if (isConnected())
            setState(State.READY, "");
        listener.onSyncFinished(ok, summary);
        if (syncPending && isConnected()) {
            syncPending = false;
        }
    }

    /** Ends the sync as failed and asks the owner to rebuild the connection. */
    private void linkUnresponsive(String reason) {
        LOG.warn("Link unresponsive: {}", reason);
        fileDownloadManager.cancelAll();
        finishSync(false, reason);
        listener.onLinkUnresponsive(reason);
    }

    // ------------------------------------------------------------------ RR interval test

    /**
     * Records RR intervals for `durationMs` (at least one minute). The first minute also gives the
     * baseline the band needs for automatic stress, which is switched on afterwards if wanted.
     */
    public boolean startRrTest(long durationMs) {
        if (state_ != State.READY) {
            listener.onMeasurementError(syncRunning ? "Aguarde a sincronização terminar" : "A pulseira não está pronta");
            return false;
        }
        if (measurement != null && measurement.isRunning())
            return false;
        measurement = new HuaweiStressCalibration(this, durationMs);
        return measurement.startMeasurements(new HuaweiStressCalibration.HuaweiStressCalibrateCallback() {
            @Override
            public void onFinish(HuaweiStressParser.StressData stressData, int[] rri, int[] sqi) {
                measurement = null;
                resumePendingSync();
                // Only a usable baseline is kept: SetStressRequest refuses score 0 / no end time
                if (stressData != null && stressData.score != 0 && stressData.endTime != 0) {
                    storeLastStressData(stressData);
                    applyStressSetting();
                }
                listener.onMeasurementResult(stressData, rri, sqi);
            }

            @Override
            public void onProgress(long j) {
                listener.onMeasurementProgress(j);
            }

            @Override
            public void onError() {
                measurement = null;
                resumePendingSync();
                listener.onMeasurementError("Não foi possível medir. Fique parado, com a pulseira firme no punho, e tente de novo.");
            }
        });
    }

    public void cancelRrTest() {
        if (measurement != null) {
            HuaweiStressCalibration m = measurement;
            measurement = null;
            m.cancel();
        }
        resumePendingSync();
    }

    private void resumePendingSync() {
        if (syncPending) {
            syncPending = false;
            handler.postDelayed(this::startSyncIfReady, 1000);
        }
    }

    public void storeLastStressData(HuaweiStressParser.StressData data) {
        String str = HuaweiStressParser.stressDataToJsonStr(data);
        if (str == null || str.isEmpty())
            return;
        devicePrefs.edit().putString(HuaweiConstants.PREF_HUAWEI_STRESS_LAST_DATA, str).apply();
    }

    public HuaweiStressParser.StressData getLastStressData() {
        String str = devicePrefs.getString(HuaweiConstants.PREF_HUAWEI_STRESS_LAST_DATA, "");
        if (str == null || str.isEmpty())
            return null;
        HuaweiStressParser.StressData d = HuaweiStressParser.stressDataFromJsonStr(str);
        // Old installs may hold a baseline SetStressRequest cannot use (score 0 / no end time)
        return d == null || d.score == 0 || d.endTime == 0 ? null : d;
    }

    public boolean hasStressBaseline() {
        return getLastStressData() != null;
    }

    // ------------------------------------------------------------------ called by requests

    public void addSleepActivities(List<int[]> data, byte source) {
        for (int[] d : data) {
            syncSleep++;
            listener.onSleepSegment(d[0], d[1], d[2], source & 0xff);
        }
    }

    /** Every minute record as received (also the ones that failed to parse), for diagnostics and the raw upload. */
    public void noteStepRecord(int timestamp, byte[] data, List<int[]> unknownTags) {
        if (data != null && data.length <= 255) {
            stepRaw.write((timestamp >>> 24) & 0xff);
            stepRaw.write((timestamp >>> 16) & 0xff);
            stepRaw.write((timestamp >>> 8) & 0xff);
            stepRaw.write(timestamp & 0xff);
            stepRaw.write(data.length);
            stepRaw.write(data, 0, data.length);
        }
        if (unknownTags != null) {
            for (int[] t : unknownTags) {
                String k = t[0] + "/0x" + Integer.toHexString(t[1] & 0xff);
                UnknownStepTag c = unknownStepTags.get(k);
                if (c == null) {
                    unknownStepTags.put(k, new UnknownStepTag(t[0], t[1] & 0xff, 1, t[2], timestamp, timestamp));
                } else {
                    c.add(timestamp);
                }
            }
        }
    }

    public boolean isMeasuring() {
        return measurement != null && measurement.isRunning();
    }

    public void reportHeartRateMode(String mode) {
        LOG.info("Heart rate mode sent to the band: {}", mode);
        listener.onHeartRateMode(mode);
    }

    public void addStepData(int timestamp, int steps, int calories, int distance, int spo, int heartrate, int restingHeartRate) {
        syncSteps++;
        int h = heartrate & 0xff;
        if (h >= 25 && h <= 230) syncHr++;
        listener.onStepSample(timestamp, steps, calories, distance, spo & 0xff, heartrate & 0xff, restingHeartRate & 0xff);
    }

    public void addTotalFitnessData(int steps, int calories, int distance) {
        // totals are not logged (health data); they can be derived from the minutes
    }

    public void downloadQueueEmpty(boolean needSync) {
        // Nothing to do: each file has its own callback
    }

    public void onBatteryLevel(int level) {
        device.setBatteryLevel(level);
        listener.onDeviceInfo(device);
    }

    /** Packets the band sends without being asked (battery changes, P2P messages). */
    public void handleAsyncPacket(HuaweiPacket packet) {
        try {
            if (packet instanceof DeviceConfig.BatteryLevel.Response) {
                packet.parseTlv();
                DeviceConfig.BatteryLevel.Response r = (DeviceConfig.BatteryLevel.Response) packet;
                if (r.multi_level == null)
                    onBatteryLevel(r.level & 0xff);
                else if (r.multi_level.length > 0)
                    onBatteryLevel(r.multi_level[0] & 0xff);
            } else if (packet instanceof P2P.P2PCommand.Response) {
                packet.parseTlv();
                p2pManager.handlePacket((P2P.P2PCommand.Response) packet);
            } else if (packet instanceof MusicControl.MusicStatusResponse || packet instanceof MusicControl.Control.Response) {
                // Collection only: the band's music screen is not served
                LOG.info("Music request from the band ignored");
            } else if ((packet.serviceId & 0xff) == 0x0f && (packet.commandId & 0xff) == 0x04) {
                // Like Gadgetbridge: 0x186AA = "send me the weather"; no code = acknowledge.
                // Any other code is the band answering our acknowledgement: never answer it back.
                int code = packet.getTlv() == null ? -1 : packet.getTlv().getInteger(0x7f, -1);
                if (code == 0x000186AA) {
                    long now = System.currentTimeMillis();
                    if (now - lastWeatherAsk > 5 * 60 * 1000L) {
                        lastWeatherAsk = now;
                        listener.onWeatherRequested();
                    }
                }
            } else if (packet instanceof DeviceConfig.PhoneInfo.Response) {
                if (packet.getTlv() == null || packet.getTlv().contains(0x7f)) return; // band's answer, not a question
                packet.parseTlv();
                DeviceConfig.PhoneInfo.Response r = (DeviceConfig.PhoneInfo.Response) packet;
                if (!r.isAck && r.info != null)
                    sendPacketAuthed(new DeviceConfig.PhoneInfo.Request(paramsProvider, r.info), "PhoneInfo");
            } else if (packet instanceof DeviceConfig.PermissionCheck.PermissionCheckRequest) {
                // Only a real question carries tag 0x01; an 0x7f code is the band answering us: never answer back
                if (packet.getTlv() == null || !packet.getTlv().contains(0x01) || packet.getTlv().contains(0x7f)) return;
                packet.parseTlv();
                short permission = ((DeviceConfig.PermissionCheck.PermissionCheckRequest) packet).permission;
                LOG.info("Band permission check {}", permission);
                sendPacketAuthed(new DeviceConfig.PermissionCheck.PermissionCheckResponse(paramsProvider, permission, (short) 0),
                        "PermissionCheck");
            } else if ((packet.serviceId & 0xff) == 0x37 && (packet.commandId & 0xff) == 0x01) {
                // Data-sync config from a band app; "findDevice" action 4 = the user stopped "find my band"
                HuaweiTLV t = packet.getTlv();
                if (t != null && t.contains(0x01) && "findDevice".equals(t.getString(0x01)) && t.contains(0x83)) {
                    for (HuaweiTLV item : t.getObject(0x83).getObjects(0x84)) {
                        if (item.contains(0x06) && item.getByte(0x06) == 4) {
                            LOG.info("Find band stopped on the band");
                            listener.onFindBandStopped();
                        }
                    }
                }
            } else if ((packet.serviceId & 0xff) == 0x07 && (packet.commandId & 0xff) == 0x0f) {
                handleSyncPush(packet);
            } else if (packet instanceof FindPhone.Response) {
                LOG.info("Find phone request from the band ignored");
            } else if (packet instanceof Calls.AnswerCallResponse) {
                LOG.info("Call action from the band ignored");
            }
        } catch (Exception e) {
            LOG.warn("Could not handle asynchronous packet", e);
        }
    }

    // ------------------------------------------------------------------ phone features (to the band)

    private short notificationSeq = 1;

    /** Like sendPacket, but also during the setup that follows authentication (the band asks things then). */
    private void sendPacketAuthed(HuaweiPacket packet, String name) {
        if (!isConnected() || paramsProvider.getSecretKey() == null)
            return;
        try {
            sendPackets(packet.serialize(), name);
        } catch (Exception e) {
            LOG.warn("Could not send " + name, e);
        }
    }

    /** Fire-and-forget packet to the band; ignored when not connected/authenticated. */
    private void sendPacket(HuaweiPacket packet, String name) {
        if (!isConnected() || (state_ != State.READY && state_ != State.SYNCING))
            return;
        try {
            sendPackets(packet.serialize(), name);
        } catch (Exception e) {
            LOG.warn("Could not send " + name, e);
        }
    }

    public boolean canNotify() {
        return false; // collection only: nothing from the phone is shown on the band
    }

    /**
     * Shows a phone notification on the band. {@code type}: one of Notifications.NotificationType
     * (generic, sms, email, call…). Text is cut to what the band shows comfortably.
     */
    public void sendNotification(byte type, String title, String sender, String text, String sourceAppId) {
        if (!canNotify()) return;
        // Plain variant (no additional params), kept for diagnostics
        java.util.ArrayList<Notifications.NotificationActionRequest.TextElement> content = new java.util.ArrayList<>();
        content.add(new Notifications.NotificationActionRequest.TextElement((byte) Notifications.TextType.title, (byte) 2, cut(title, 60)));
        content.add(new Notifications.NotificationActionRequest.TextElement((byte) Notifications.TextType.sender, (byte) 2, cut(sender, 60)));
        content.add(new Notifications.NotificationActionRequest.TextElement((byte) Notifications.TextType.text, (byte) 2, cut(text, 300)));
        sendPacket(new Notifications.NotificationActionRequest(paramsProvider, notificationSeq++, type, content, sourceAppId, null),
                "NotificationPlain");
    }

    /** Same, with the Android notification identity the band uses to update/remove it. */
    public void sendNotification(byte type, String title, String sender, String text, String sourceAppId,
                                 String key, int androidId, String channelId, String category, long when) {
        if (!canNotify()) return;
        java.util.ArrayList<Notifications.NotificationActionRequest.TextElement> content = new java.util.ArrayList<>();
        content.add(new Notifications.NotificationActionRequest.TextElement((byte) Notifications.TextType.title, (byte) 2,
                cut(title, 60)));
        content.add(new Notifications.NotificationActionRequest.TextElement((byte) Notifications.TextType.sender, (byte) 2,
                cut(sender, 60)));
        content.add(new Notifications.NotificationActionRequest.TextElement((byte) Notifications.TextType.text, (byte) 2,
                cut(text, 300)));
        short id = notificationSeq++;
        if (notificationSeq > 30000) notificationSeq = 1;
        LOG.info("Notification to the band: type {} from {}", type & 0xff, sourceAppId);
        Notifications.NotificationActionRequest.AdditionalParams params = new Notifications.NotificationActionRequest.AdditionalParams();
        // Expand capability bits as in Gadgetbridge's HuaweiState (the Band 10 reports all of them)
        params.supportsReply = state.supportsExpandBit(89);
        params.supportsRepeatedNotify = state.supportsExpandBit(94);
        params.supportsRemoveSingle = state.supportsExpandBit(120);
        params.supportsReplyActions = state.supportsExpandBit(73);
        params.supportsTimestamp = state.supportsExpandBit(77);
        params.notificationKey = key == null ? ("coletor." + id) : key;
        params.notificationId = androidId >= 0 ? androidId : id;
        params.channelId = channelId == null ? "" : channelId;
        params.category = category == null ? "" : category;
        params.replyKey = "";
        params.when = when;
        sendPacket(new Notifications.NotificationActionRequest(paramsProvider, id, type, content, sourceAppId, params),
                "Notification");
    }

    /** Removes the call screen from the band (call answered elsewhere or ended). */
    public void stopCallNotification() {
        if (!canNotify()) return;
        java.util.ArrayList<Notifications.NotificationActionRequest.TextElement> content = new java.util.ArrayList<>();
        content.add(new Notifications.NotificationActionRequest.TextElement((byte) Notifications.TextType.text, (byte) 2, ""));
        sendPacket(new Notifications.NotificationActionRequest(paramsProvider, notificationSeq++,
                Notifications.NotificationType.stopNotification, content, null, null), "StopCallNotification");
    }

    /** Now-playing info for the band's music screen. playState: 1 playing, 2 paused. */
    public void sendMusicInfo(String artist, String title, boolean playing, int maxVolume, int volume) {
        if (!state.supportsCommandForService(0x25, 0x02)) return;
        sendPacket(new MusicControl.MusicInfo.Request(paramsProvider, artist == null ? "" : cut(artist, 60),
                title == null ? "" : cut(title, 60),
                // Android PlaybackState codes, like Gadgetbridge: 3 = playing, 2 = paused (1 would mean "stopped")
                (byte) (playing ? 3 : 2),
                (byte) maxVolume, (byte) volume), "MusicInfo");
    }

    /**
     * "Find my band": the band rings and vibrates until stopped (data-sync config 900100002,
     * action 2 = start, 1 = stop, like Gadgetbridge's HuaweiDataSyncFindDevice).
     * @return false when this band does not offer it
     */
    public boolean findBand(boolean start) {
        if (!state.supportsFindDeviceAbility() || !state.supportsDeviceCommandConfig()) return false;
        LOG.info("Find band: {}", start ? "start" : "stop");
        listener.onFindBandState(start);
        sendPacket(new br.dimensono.bandsono.huawei.packets.DataSync.ConfigCommand.Request(paramsProvider,
                "hw.unitedevice.finddevice", "findDevice", 900100002, (byte) (start ? 2 : 1), null), "FindBand");
        return true;
    }

    /** Stops the band's "find phone" screen once the user found the phone. */
    public void stopFindPhone() {
        sendPacket(new FindPhone.StopRequest(paramsProvider), "StopFindPhone");
    }

    public boolean supportsWeather() {
        return state.supportsCommandForService(0x0f, 0x01);
    }

    private boolean weatherRunning = false;
    private long lastWeatherAsk = 0;

    /**
     * Sends weather to the band like the official app: start → unit → support → extended → sun/moon
     * → current → forecast. Each step waits for the band (or a short timeout) and never blocks the sync.
     */
    public void sendWeather(final WeatherData w) {
        if (!supportsWeather() || weatherRunning || !isConnected() || (state_ != State.READY && state_ != State.SYNCING)) return;
        weatherRunning = true;
        final Weather.Settings s = new Weather.Settings();
        s.uvIndexSupported = state.supportsWeatherUv();
        final Runnable current = () -> sendExpecting(currentWeather(s, w), 3000, r -> {
            if (state.supportsCommandForService(0x0f, 0x08))
                if (forecastVariant == -2) weatherDone(true); // refused in every layout on this connection
                else tryForecast(s, w, forecastVariant < 0 ? 0 : forecastVariant);
            else
                weatherDone(true);
        });
        final Runnable sunMoon = () -> {
            if (state.supportsCommandForService(0x0f, 0x0a))
                sendExpecting(new Weather.WeatherSunMoonSupport.Request(paramsProvider), 3000, r -> {
                    if (r instanceof Weather.WeatherSunMoonSupport.Response) {
                        s.sunRiseSetSupported = ((Weather.WeatherSunMoonSupport.Response) r).sunRiseSetSupported;
                        s.moonPhaseSupported = ((Weather.WeatherSunMoonSupport.Response) r).moonPhaseSupported;
                    }
                    current.run();
                });
            else current.run();
        };
        final Runnable extended = () -> {
            if (state.supportsCommandForService(0x0f, 0x06))
                sendExpecting(new Weather.WeatherExtendedSupport.Request(paramsProvider), 3000, r -> {
                    if (r instanceof Weather.WeatherExtendedSupport.Response) {
                        Weather.WeatherExtendedSupport.Response e = (Weather.WeatherExtendedSupport.Response) r;
                        s.timeSupported = e.timeSupported;
                        s.sourceSupported = e.sourceSupported;
                        s.weatherIconSupported = e.weatherIconSupported;
                    }
                    sunMoon.run();
                });
            else sunMoon.run();
        };
        final Runnable support = () -> sendExpecting(new Weather.WeatherSupport.Request(paramsProvider), 3000, r -> {
            if (r instanceof Weather.WeatherSupport.Response) {
                Weather.WeatherSupport.Response x = (Weather.WeatherSupport.Response) r;
                s.weatherSupported = x.weatherSupported;
                s.windSupported = x.windSupported;
                s.pm25Supported = x.pm25Supported;
                s.temperatureSupported = x.temperatureSupported;
                s.locationNameSupported = x.locationNameSupported;
                s.currentTemperatureSupported = x.currentTemperatureSupported;
                s.unitSupported = x.unitSupported;
                s.airQualityIndexSupported = x.airQualityIndexSupported;
            } else {
                // No answer: assume the basics every band shows
                s.weatherSupported = s.temperatureSupported = s.currentTemperatureSupported = s.locationNameSupported = true;
            }
            extended.run();
        });
        final Runnable unit = () -> {
            if (state.supportsCommandForService(0x0f, 0x05))
                sendExpecting(new Weather.WeatherUnitRequest(paramsProvider, Weather.HuaweiTemperatureFormat.CELSIUS), 2000,
                        r -> support.run());
            else support.run();
        };
        sendExpecting(new Weather.WeatherStart.Request(paramsProvider), 2000, r -> {
            if (r instanceof Weather.WeatherStart.Response && !((Weather.WeatherStart.Response) r).success) {
                LOG.info("Band refused weather start");
                weatherDone(false);
                return;
            }
            unit.run();
        });
    }

    private void weatherDone(boolean ok) {
        weatherRunning = false;
        LOG.info("Weather sent to the band: {}", ok);
    }

    private HuaweiPacket currentWeather(Weather.Settings s, WeatherData w) {
        return new Weather.CurrentWeatherRequest(paramsProvider, s, wmoToIcon(w.wmoCode), w.windDirection,
                (byte) beaufort(w.windKmh), (byte) Math.round(w.minToday), (byte) Math.round(w.maxToday), null,
                w.location == null ? "" : cut(w.location, 30), (byte) Math.round(w.temperature),
                Weather.HuaweiTemperatureFormat.CELSIUS, null, w.observedAt, w.uvIndex < 0 ? null : (float) w.uvIndex,
                "Open-Meteo", (byte) Math.max(0, w.humidity), (int) Math.round(w.windKmh), (int) Math.round(w.feelsLike));
    }

    private static int status(HuaweiPacket p) {
        try {
            return p == null || p.getTlv() == null ? -1 : p.getTlv().getInteger(0x7f, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Forecast layout the band accepted last time (-1 = not found yet, -2 = band refuses them all). */
    private int forecastVariant = -1;
    private static final int FORECAST_VARIANTS = 6;

    /**
     * The Band 10 answers 0x1C139 to the Gadgetbridge layout. Try a few layouts once, log each
     * answer and keep the first one the band accepts.
     */
    private void tryForecast(final Weather.Settings s, final WeatherData w, final int variant) {
        if (variant >= FORECAST_VARIANTS) {
            LOG.info("Forecast: no layout accepted by the band; not retrying until the next connection");
            forecastVariant = -2;
            weatherDone(true);
            return;
        }
        sendExpecting(forecastVariant(s, w, variant), 3000, r -> {
            int st = status(r);
            LOG.info("Forecast layout {} answer: {}", variant, Integer.toHexString(st));
            if (st == 0x000186A0) {
                forecastVariant = variant;
                weatherDone(true);
            } else if (forecastVariant >= 0 && variant == forecastVariant) {
                forecastVariant = -1; // what worked before stopped working: search again
                tryForecast(s, w, 0);
            } else {
                tryForecast(s, w, variant + 1);
            }
        });
    }

    /**
     * 0 = 24 h + 8 days (Gadgetbridge); 1 = 24 h + 7 days at local midnight; 2 = 7 days at local
     * midnight; 3 = 12 hours only; 4 = today only, no sun times; 5 = 24 h + 7 days, no extras.
     */
    private HuaweiPacket forecastVariant(Weather.Settings s, WeatherData w, int v) {
        if (v == 0) return forecast(s, w, true);
        Weather.Settings plain = new Weather.Settings();
        plain.weatherIconSupported = true;
        plain.temperatureSupported = true;
        Weather.Settings use = (v == 4 || v == 5) ? plain : s;
        int maxHours = v == 2 || v == 4 ? 0 : v == 3 ? 12 : 24;
        int maxDays = v == 3 ? 0 : v == 4 ? 1 : 7;
        java.util.ArrayList<Weather.WeatherForecastData.TimeData> hours = new java.util.ArrayList<>();
        for (WeatherData.Hour h : w.hours) {
            if (hours.size() >= maxHours) break;
            if (h.ts <= w.observedAt) continue;
            Weather.WeatherForecastData.TimeData t = new Weather.WeatherForecastData.TimeData();
            t.timestamp = h.ts;
            t.icon = wmoToIcon(h.wmoCode);
            t.temperature = (byte) Math.round(h.temperature);
            if (v != 5) {
                t.precipitation = (byte) Math.max(0, h.precipitation);
                t.uvIndex = (byte) Math.max(0, Math.round(h.uvIndex));
            }
            hours.add(t);
        }
        java.util.ArrayList<Weather.WeatherForecastData.DayData> days = new java.util.ArrayList<>();
        for (WeatherData.Day d : w.days) {
            if (days.size() >= maxDays) break;
            Weather.WeatherForecastData.DayData x = new Weather.WeatherForecastData.DayData();
            x.timestamp = d.ts; // Open-Meteo: local midnight of that day
            x.icon = wmoToIcon(d.wmoCode);
            x.highTemperature = (byte) Math.round(d.max);
            x.lowTemperature = (byte) Math.round(d.min);
            if (v != 4 && v != 5) {
                x.sunriseTime = d.sunrise;
                x.sunsetTime = d.sunset;
            }
            days.add(x);
        }
        return new Weather.WeatherForecastData.Request(paramsProvider, use, hours, days);
    }

    private HuaweiPacket forecast(Weather.Settings s, WeatherData w, boolean withHours) {
        java.util.ArrayList<Weather.WeatherForecastData.TimeData> hours = new java.util.ArrayList<>();
        for (WeatherData.Hour h : w.hours) {
            if (!withHours || hours.size() >= 24) break;
            if (h.ts <= w.observedAt) continue; // the band refuses past hours
            Weather.WeatherForecastData.TimeData t = new Weather.WeatherForecastData.TimeData();
            t.timestamp = h.ts;
            t.icon = wmoToIcon(h.wmoCode);
            t.temperature = (byte) Math.round(h.temperature);
            t.precipitation = (byte) Math.max(0, h.precipitation);
            t.uvIndex = (byte) Math.max(0, Math.round(h.uvIndex));
            hours.add(t);
        }
        java.util.ArrayList<Weather.WeatherForecastData.DayData> days = new java.util.ArrayList<>();
        for (WeatherData.Day d : w.days) {
            if (days.size() >= 8) break;
            Weather.WeatherForecastData.DayData x = new Weather.WeatherForecastData.DayData();
            // Like the official/Gadgetbridge flow: today = observation time, then +24 h per day
            x.timestamp = w.observedAt + 86400 * days.size();
            x.icon = wmoToIcon(d.wmoCode);
            x.highTemperature = (byte) Math.round(d.max);
            x.lowTemperature = (byte) Math.round(d.min);
            x.sunriseTime = d.sunrise;
            x.sunsetTime = d.sunset;
            x.moonRiseTime = 0;
            x.moonSetTime = 0;
            x.moonPhase = Weather.degreesToMoonPhase(0);
            days.add(x);
        }
        return new Weather.WeatherForecastData.Request(paramsProvider, s, hours, days);
    }

    private static int beaufort(double kmh) {
        double[] limits = {1, 6, 12, 20, 29, 39, 50, 62, 75, 89, 103, 118};
        for (int i = 0; i < limits.length; i++) if (kmh < limits[i]) return i;
        return 12;
    }

    /** WMO weather interpretation codes (Open-Meteo) to the band's icons. */
    static Weather.WeatherIcon wmoToIcon(int code) {
        switch (code) {
            case 0: return Weather.WeatherIcon.SUNNY;
            case 1: case 2: return Weather.WeatherIcon.CLOUDY;
            case 3: return Weather.WeatherIcon.OVERCAST;
            case 45: case 48: return Weather.WeatherIcon.FOG;
            case 51: case 53: case 55: case 61: return Weather.WeatherIcon.LIGHT_RAIN;
            case 56: case 57: case 66: case 67: return Weather.WeatherIcon.FREEZING_RAIN;
            case 63: case 81: return Weather.WeatherIcon.RAIN;
            case 65: case 82: return Weather.WeatherIcon.HEAVY_RAIN;
            case 71: return Weather.WeatherIcon.LIGHT_SNOW;
            case 73: case 85: return Weather.WeatherIcon.SNOW;
            case 75: case 86: return Weather.WeatherIcon.HEAVY_SNOW;
            case 77: return Weather.WeatherIcon.SNOW_FLURRIES;
            case 80: return Weather.WeatherIcon.SHOWERS;
            case 95: return Weather.WeatherIcon.THUNDERSTORMS;
            case 96: case 99: return Weather.WeatherIcon.THUNDER_AND_HAIL;
            default: return Weather.WeatherIcon.CLOUDY;
        }
    }

    /** Sends one packet and hands its answer (or null on timeout/error) to {@code then}. */
    private void sendExpecting(HuaweiPacket packet, int timeoutMs, final java.util.function.Consumer<HuaweiPacket> then) {
        final WorkoutPacketRequest r = new WorkoutPacketRequest(this, packet);
        final boolean[] fired = {false};
        r.setupTimeoutUntilNext(timeoutMs);
        r.setFinalizeReq(new RequestCallback() {
            @Override
            public void call(Request request) {
                if (fired[0]) return;
                fired[0] = true;
                then.accept(r.getResponse());
            }

            @Override
            public void timeout(Request request) {
                if (fired[0]) return;
                fired[0] = true;
                then.accept(null);
            }

            @Override
            public void handleException(Request request, Request.ResponseParseException e) {
                if (fired[0]) return;
                fired[0] = true;
                then.accept(null);
            }
        });
        try {
            r.doPerform();
        } catch (IOException e) {
            if (!fired[0]) {
                fired[0] = true;
                weatherRunning = false;
            }
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public void addInProgressRequest(Request request) {
        responseManager.addHandler(request);
    }

    public void removeInProgressRequests(Request req) {
        responseManager.removeHandler(req);
    }

    public HuaweiState getDeviceState() {
        return state;
    }

    public HuaweiCoordinator getCoordinator() {
        return coordinator;
    }

    public HuaweiP2PManager getHuaweiP2PManager() {
        return p2pManager;
    }

    public HuaweiCoordinator.HuaweiDeviceType getHuaweiType() {
        return HuaweiCoordinator.HuaweiDeviceType.BLE;
    }

    public HuaweiPacket.ParamsProvider getParamsProvider() {
        return paramsProvider;
    }

    public boolean isBLE() {
        return true;
    }

    public Context getContext() {
        return context;
    }

    public BandDevice getDevice() {
        return device;
    }

    public void setProtocolVersion(byte protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public byte getProtocolVersion() {
        return this.protocolVersion;
    }

    public boolean getSetting(String key, boolean def) {
        try {
            return settings.getBoolean(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public byte[] getPakePeerAuthId() {
        return null;
    }

    // ------------------------------------------------------------------ identity & keys

    public void createSecretKey() {
        String authKey = devicePrefs.getString("authkey", null);
        if (authKey == null || authKey.isEmpty()) {
            authKey = StringUtils.bytesToHex(HuaweiCrypto.generateNonce());
            devicePrefs.edit().putString("authkey", authKey).apply();
        }
        paramsProvider.setSecretKey(GB.hexStringToByteArray(authKey));
    }

    public byte[] getSecretKey() {
        String authKey = devicePrefs.getString("authkey", null);
        if (authKey == null)
            return null;
        return GB.hexStringToByteArray(authKey);
    }

    public void setSecretKey(byte[] authKey) {
        devicePrefs.edit().putString("authkey", StringUtils.bytesToHex(authKey)).apply();
        paramsProvider.setSecretKey(authKey);
    }

    private void createRandomMacAddress() {
        macAddress = devicePrefs.getString(HuaweiConstants.PREF_HUAWEI_ADDRESS, null);
        if (macAddress == null || macAddress.isEmpty()) {
            StringBuilder mac = new StringBuilder("FF:FF:FF");
            Random r = new Random();
            for (int i = 0; i < 3; i++) {
                int n = r.nextInt(255);
                mac.append(String.format(":%02x", n));
            }
            macAddress = mac.toString().toUpperCase(java.util.Locale.US);
            devicePrefs.edit().putString(HuaweiConstants.PREF_HUAWEI_ADDRESS, macAddress).apply();
        }
    }

    public byte[] getMacAddress() {
        return macAddress.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] getSerial() {
        return macAddress.replace(":", "").substring(6, 12).getBytes(StandardCharsets.UTF_8);
    }

    public String getDeviceMac() {
        return device.getAddress();
    }

    private void createAndroidID() {
        androidID = devicePrefs.getString("fake_android_id", null);
        if (androidID == null || androidID.isEmpty()) {
            androidID = StringUtils.bytesToHex(HuaweiCrypto.generateNonce());
            devicePrefs.edit().putString("fake_android_id", androidID).apply();
        }
    }

    public byte[] getAndroidId() {
        return androidID.getBytes(StandardCharsets.UTF_8);
    }

    /** Forget the pairing keys (used when the user removes the band). */
    public static void forget(Context context, String address) {
        String key = address.replace(":", "");
        context.getSharedPreferences("huawei_dev_" + key, Context.MODE_PRIVATE).edit().clear().apply();
        context.getSharedPreferences("huawei_caps_" + key, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
