/*  Derived from Gadgetbridge's HuaweiState (AGPL-3.0-or-later).
    Keeps the list of services/commands and the "expand capabilities" the band reports,
    so requests can skip features the band does not have. */
package br.dimensono.bandsono.huawei;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.TreeMap;

import br.dimensono.bandsono.huawei.util.GB;
import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

public class HuaweiState {
    private static final Logger LOG = LoggerFactory.getLogger(HuaweiState.class);

    private final SharedPreferences prefs;
    private final TreeMap<Integer, byte[]> commandsPerService = new TreeMap<>();
    private byte[] expandCapabilities = null;
    private boolean supportsTruSleepNewSync = false;
    private boolean supportsRriNewSync = false;
    private boolean supportsGpsNewSync = false;
    private boolean transactionCrypted = true;

    public HuaweiState(Context context, String mac) {
        this.prefs = context.getSharedPreferences("huawei_caps_" + mac.replace(":", ""), Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("svc_") && e.getValue() instanceof String) {
                try {
                    int service = Integer.parseInt(k.substring(4));
                    commandsPerService.put(service, GB.hexStringToByteArray((String) e.getValue()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String exp = prefs.getString("expandCapabilities", null);
        if (exp != null && !exp.isEmpty())
            expandCapabilities = GB.hexStringToByteArray(exp);
        supportsTruSleepNewSync = prefs.getBoolean("truSleepNewSync", false);
        supportsRriNewSync = prefs.getBoolean("rriNewSync", false);
    }

    public void clear() {
        commandsPerService.clear();
        expandCapabilities = null;
        prefs.edit().clear().apply();
    }

    // ------------------------------------------------------------------ bookkeeping

    public void addCommandsForService(int service, byte[] commands) {
        commandsPerService.put(service, commands);
        prefs.edit().putString("svc_" + service, GB.hexdump(commands)).apply();
    }

    public byte[] getCommandsForService(int service) {
        return commandsPerService.get(service);
    }

    public void printCommandsPerService() {
        StringBuilder msg = new StringBuilder();
        for (Map.Entry<Integer, byte[]> entry : commandsPerService.entrySet()) {
            msg.append("ServiceID: ").append(Integer.toHexString(entry.getKey())).append(" => Commands: ");
            for (byte b : entry.getValue()) {
                msg.append(Integer.toHexString(b & 0xff)).append(" ");
            }
            msg.append("\n");
        }
        LOG.info(msg.toString());
    }

    public void saveExpandCapabilities(byte[] capabilities) {
        expandCapabilities = capabilities;
        prefs.edit().putString("expandCapabilities", GB.hexdump(capabilities)).apply();
        // the full list (no health data) so the bits the extra downloads depend on can be checked on the real band
        LOG.info("Expand capabilities: {}", GB.hexdump(capabilities));
        LOG.info("Expand capability bits of interest: 106(ecg)={} 113(arrhythmia)={} 143(sleep dict)={} 168(arrhythmia, China)={}",
                bit(106), bit(113), bit(143), bit(168));
    }

    public boolean supportsCommandForService(int service, int command) {
        byte[] commands = commandsPerService.get(service);
        if (commands == null)
            return false;
        for (byte b : commands)
            if (b == (byte) command)
                return true;
        return false;
    }

    private boolean supportsExpandCapability(int which) {
        if (expandCapabilities == null)
            return false;
        if (which >= expandCapabilities.length * 8)
            return false;
        int capability = 1 << (which % 8);
        return (expandCapabilities[which / 8] & capability) == capability;
    }

    public boolean hasCommandList() { return !commandsPerService.isEmpty(); }

    // ------------------------------------------------------------------ capabilities

    public boolean supportsExpandCapability() { return supportsCommandForService(0x01, 0x37); }
    public boolean supportsSettingRelated() { return supportsCommandForService(0x01, 0x31); }
    public boolean supportsAcceptAgreement() { return supportsCommandForService(0x01, 0x30); }
    public boolean supportsMotionGoal() { return supportsCommandForService(0x07, 0x01); }
    public boolean supportsActivityReminder() { return supportsCommandForService(0x07, 0x07); }
    public boolean supportsTruSleep() { return supportsCommandForService(0x07, 0x16); }
    public boolean supportsHeartRate() { return supportsCommandForService(0x07, 0x17); }
    public boolean supportsRealtimeHeartRate() { return supportsCommandForService(0x07, 0x1c); }
    public boolean supportsFitnessRestHeartRate() { return supportsCommandForService(0x07, 0x23); }
    public boolean supportsSPo2() { return supportsCommandForService(0x07, 0x24); }
    public boolean supportsFileDownload0A() { return supportsCommandForService(0x0a, 0x01); }
    public boolean supportsFileDownload2C() { return supportsCommandForService(0x2c, 0x01); }
    public boolean supportsAutoStress() { return supportsCommandForService(0x20, 0x09); }
    public boolean supportsP2PService() { return supportsCommandForService(0x34, 0x1); }
    public boolean supportsActivateOnLift() { return supportsCommandForService(0x01, 0x09); }
    public boolean supportsWearLocation() { return supportsCommandForService(0x01, 0x1a); }
    public boolean supportsWorkouts() { return supportsCommandForService(0x17, 0x01); }
    public boolean supportsSleepBreath() { return supportsCommandForService(0x2d, 0x01); }

    public boolean supportsTemperature() { return supportsExpandCapability(0x1d); }
    public boolean supportsP2PGetAppVersion() { return supportsExpandCapability() && supportsExpandCapability(14); }
    public boolean supportsDictSleepSync() { return supportsExpandCapability() && supportsExpandCapability(143); }
    public boolean supportsBedTime() { return supportsExpandCapability() && supportsExpandCapability(199); }
    public boolean supportsFindDeviceAbility() { return supportsExpandCapability() && supportsExpandCapability(79); }
    public boolean supportsDeviceCommandConfig() { return supportsExpandCapability() && supportsExpandCapability(83); }
    public boolean supportsEmotion() { return supportsExpandCapability() && supportsExpandCapability(206); }
    public boolean supportsHRV() { return supportsExpandCapability() && supportsExpandCapability(235); }
    /** One bit of the band's expand capabilities (0x01/0x37). */
    public boolean supportsExpandBit(int bit) { return supportsExpandCapability() && supportsExpandCapability(bit); }
    public boolean supportsWeatherUv() { return supportsExpandCapability() && supportsExpandCapability(0x2f); }
    public boolean supportsDiffAccountPairingOptimization() { return supportsExpandCapability() && supportsExpandCapability(0xac); }
    /** ECG analysis file (ecg_analysis_data.bin): the official app only asks when bit 106 is set. */
    public boolean supportsEcgAnalysis() { return supportsExpandBit(106); }
    /** Arrhythmia (PPG sequence 700004 and result class 500007), international variant: bit 113. Bit 168 is the Chinese one, only logged. */
    public boolean supportsArrhythmia() { return supportsExpandBit(113); }
    private boolean bit(int b) { return supportsExpandBit(b); }
    public boolean supportsSleepApnea() { return supportsExpandCapability() && supportsExpandCapability(107); }

    // ------------------------------------------------------------------ sync flavours

    public boolean getSupportsTruSleepNewSync() { return supportsTruSleepNewSync; }

    public void setSupportsTruSleepNewSync(boolean v) {
        supportsTruSleepNewSync = v;
        prefs.edit().putBoolean("truSleepNewSync", v).apply();
    }

    public boolean getSupportsRriNewSync() { return supportsRriNewSync; }

    public void setSupportsRriNewSync(boolean v) {
        supportsRriNewSync = v;
        prefs.edit().putBoolean("rriNewSync", v).apply();
    }

    public void setSupportsGpsNewSync(boolean v) { supportsGpsNewSync = v; }
    public boolean getSupportsGpsNewSync() { return supportsGpsNewSync; }

    public void setOtaSoftwareVersion(String v) { }
    public void setOtaSignatureLength(int v) { }

    public void setTransactionCrypted(boolean crypted) { this.transactionCrypted = crypted; }
    public boolean isTransactionCrypted() { return this.transactionCrypted; }
}
