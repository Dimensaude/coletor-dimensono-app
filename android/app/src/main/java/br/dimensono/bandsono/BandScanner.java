package br.dimensono.bandsono;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;

import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

/**
 * Short scan for the paired band's address before a reconnect. Connecting to the device object
 * from a fresh scan result avoids the stale cache / wrong address type problems that otherwise
 * make direct connections fail (status 133) until Bluetooth is switched off and on.
 */
@SuppressLint("MissingPermission")
final class BandScanner {
    private static final Logger LOG = LoggerFactory.getLogger(BandScanner.class);

    interface Result {
        /** @param device the band, or null when it was not seen in the window */
        void onResult(BluetoothDevice device);
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private Result pending;
    private final Runnable timeout = () -> finish(null);

    BandScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isScanning() {
        return pending != null;
    }

    /** @return false when a scan could not be started (Bluetooth off, no permission, stack error) */
    boolean start(String address, long windowMs, Result result) {
        stop();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled())
            return false;
        BluetoothLeScanner s = adapter.getBluetoothLeScanner();
        if (s == null)
            return false;
        ScanFilter filter;
        try {
            filter = new ScanFilter.Builder().setDeviceAddress(address).build();
        } catch (IllegalArgumentException e) {
            return false;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();
        final ScanCallback cb = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult r) {
                final BluetoothDevice d = r.getDevice();
                handler.post(() -> {
                    if (callback == this)
                        finish(d);
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                LOG.warn("Scan failed ({})", errorCode);
                handler.post(() -> {
                    if (callback == this)
                        finish(null);
                });
            }
        };
        scanner = s;
        callback = cb;
        pending = result;
        try {
            s.startScan(Collections.singletonList(filter), settings, cb);
        } catch (Exception e) {
            LOG.warn("startScan failed: {}", e.toString());
            pending = null;
            callback = null;
            return false;
        }
        LOG.info("Scanning for {} ({} s)", address, windowMs / 1000);
        handler.postDelayed(timeout, windowMs);
        return true;
    }

    void stop() {
        pending = null;
        stopScan();
    }

    private void finish(BluetoothDevice device) {
        Result r = pending;
        pending = null;
        stopScan();
        if (r != null) {
            LOG.info(device != null ? "Band found by scan" : "Band not seen in scan window");
            r.onResult(device);
        }
    }

    private void stopScan() {
        handler.removeCallbacks(timeout);
        ScanCallback cb = callback;
        callback = null;
        if (scanner != null && cb != null) {
            try {
                scanner.stopScan(cb);
            } catch (Exception ignored) {
            }
        }
    }
}
