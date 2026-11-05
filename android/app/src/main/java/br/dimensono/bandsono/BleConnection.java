package br.dimensono.bandsono;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.UUID;

import br.dimensono.bandsono.huawei.HuaweiConstants;
import br.dimensono.bandsono.huawei.HuaweiSupportProvider;
import br.dimensono.bandsono.log.Logger;
import br.dimensono.bandsono.log.LoggerFactory;

/**
 * GATT connection to the band: connects, negotiates the MTU, enables notifications on the
 * Huawei read characteristic and serialises writes (one outstanding write at a time).
 * All callbacks are delivered on the main looper.
 * <p>
 * The link is closed (so the owner reconnects from scratch) whenever it stops making
 * progress: setup steps that never complete, writes the stack keeps refusing, or several
 * writes in a row that never get their completion callback. Those are the situations in
 * which only switching Bluetooth off and on used to bring the band back.
 */
@SuppressLint("MissingPermission")
public class BleConnection implements HuaweiSupportProvider.Transport {
    private static final Logger LOG = LoggerFactory.getLogger(BleConnection.class);
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int CONNECT_TIMEOUT_MS = 35000;
    /** From link-layer connection to notifications enabled (also for auto-connect). */
    private static final int SETUP_TIMEOUT_MS = 25000;
    private static final int MTU_TIMEOUT_MS = 3000;
    private static final int WRITE_TIMEOUT_MS = 5000;
    private static final int MAX_WRITE_TIMEOUTS = 3;
    private static final int MAX_WRITE_REFUSALS = 60; // x 50 ms = 3 s
    /** Status reported by many Android stacks for a failed/aborted connection. */
    public static final int GATT_ERROR_133 = 133;

    public interface Callback {
        void onReady(BleConnection connection);

        void onData(byte[] data);

        void onDisconnected(BleConnection connection, boolean wasReady);
    }

    private final Context context;
    private final String address;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic readChar;
    private boolean writing = false;
    private boolean ready = false;
    private boolean closed = false;
    private boolean linkUp = false;
    private boolean servicesRequested = false;
    private boolean refreshCacheOnConnect = false;
    private int mtu = 23;
    private int writeTimeouts = 0;
    private int writeRefusals = 0;
    private int lastStatus = 0;
    private long lastRxMillis = 0;
    private String closeReason = "";

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            if (!ready) {
                LOG.warn("Connection timeout");
                closeWith("tempo esgotado ao conectar");
            }
        }
    };

    private final Runnable setupTimeout = new Runnable() {
        @Override
        public void run() {
            if (!ready) {
                LOG.warn("GATT setup did not finish (mtu/services/notifications)");
                refreshCacheOnConnect = true;
                closeWith("configuração GATT não terminou");
            }
        }
    };

    private final Runnable mtuTimeout = new Runnable() {
        @Override
        public void run() {
            if (!closed && gatt != null && !servicesRequested) {
                LOG.warn("No MTU callback, discovering services anyway");
                discoverServices(gatt);
            }
        }
    };

    private final Runnable writeTimeout = new Runnable() {
        @Override
        public void run() {
            if (writing) {
                writeTimeouts++;
                LOG.warn("Write timeout ({} in a row)", writeTimeouts);
                writing = false;
                if (writeTimeouts >= MAX_WRITE_TIMEOUTS) {
                    closeWith("escritas sem confirmação");
                    return;
                }
                pumpWrites();
            }
        }
    };

    public BleConnection(Context context, String address, Callback callback) {
        this.context = context.getApplicationContext();
        this.address = address;
        this.callback = callback;
    }

    public String getAddress() {
        return address;
    }

    /** Last GATT status seen in a connection state callback (133 = generic stack error). */
    public int getLastStatus() {
        return lastStatus;
    }

    /** Why the connection was closed by this class ("" if closed by the remote side or the owner). */
    public String getCloseReason() {
        return closeReason;
    }

    /** System.currentTimeMillis() of the last notification received, 0 if none. */
    public long getLastRxMillis() {
        return lastRxMillis;
    }

    /** Ask Android to drop its cached GATT table before discovering services on this connection. */
    public void setRefreshCache(boolean refresh) {
        this.refreshCacheOnConnect = refresh;
    }

    /**
     * @param autoConnect true to let Android connect whenever the band comes in range
     *                    (no timeout), false for a direct attempt with timeout.
     */
    public boolean connect(boolean autoConnect) {
        return connect(null, autoConnect);
    }

    /**
     * @param found the device object from a scan result (carries the right address type),
     *              or null to build it from the stored address
     */
    public boolean connect(BluetoothDevice found, boolean autoConnect) {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            LOG.warn("Bluetooth is off");
            return false;
        }
        BluetoothDevice device = found;
        if (device == null) {
            try {
                device = adapter.getRemoteDevice(address);
            } catch (IllegalArgumentException e) {
                LOG.error("Invalid address " + address, e);
                return false;
            }
        }
        LOG.info("Connecting to {} (autoConnect={}, fromScan={})", address, autoConnect, found != null);
        try {
            gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            LOG.error("Missing Bluetooth permission", e);
            return false;
        }
        if (gatt == null)
            return false;
        if (!autoConnect)
            handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        return true;
    }

    /** Faster connection interval while downloading, balanced otherwise. */
    public void setHighPriority(boolean high) {
        BluetoothGatt g = gatt;
        if (g == null || closed)
            return;
        try {
            g.requestConnectionPriority(high ? BluetoothGatt.CONNECTION_PRIORITY_HIGH : BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
        } catch (Exception e) {
            LOG.warn("requestConnectionPriority failed: {}", e.toString());
        }
    }

    @Override
    public boolean isConnected() {
        return ready && !closed;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Link-layer connection established (GATT setup may still be running). */
    public boolean isLinkUp() {
        return linkUp && !closed;
    }

    @Override
    public int getMaxWriteSize() {
        return Math.max(20, mtu - 3);
    }

    @Override
    public void write(byte[] data) {
        if (closed)
            return;
        writeQueue.add(data);
        pumpWrites();
    }

    @Override
    public void disconnect() {
        close();
    }

    private void closeWith(String reason) {
        if (closed)
            return;
        closeReason = reason;
        LOG.warn("Closing connection: {}", reason);
        close();
    }

    public void close() {
        if (closed)
            return;
        closed = true;
        boolean wasReady = ready;
        ready = false;
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(setupTimeout);
        handler.removeCallbacks(mtuTimeout);
        handler.removeCallbacks(writeTimeout);
        handler.removeCallbacksAndMessages(null);
        writeQueue.clear();
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try {
                g.disconnect();
            } catch (Exception e) {
                LOG.error("disconnect failed", e);
            }
            if (refreshCacheOnConnect)
                refreshDeviceCache(g);
            try {
                g.close();
            } catch (Exception e) {
                LOG.error("close failed", e);
            }
        }
        callback.onDisconnected(this, wasReady);
    }

    /**
     * Clears Android's cached GATT table for the band (hidden API, used by most BLE libraries).
     * A stale cache can make the Huawei service "disappear" until Bluetooth is toggled.
     */
    private static boolean refreshDeviceCache(BluetoothGatt g) {
        try {
            Method m = g.getClass().getMethod("refresh");
            Object r = m.invoke(g);
            boolean ok = r instanceof Boolean && (Boolean) r;
            LOG.info("GATT cache refresh: {}", ok);
            return ok;
        } catch (Throwable t) {
            LOG.warn("GATT cache refresh unavailable: {}", t.toString());
            return false;
        }
    }

    private void discoverServices(BluetoothGatt g) {
        if (servicesRequested || closed)
            return;
        servicesRequested = true;
        handler.removeCallbacks(mtuTimeout);
        try {
            if (refreshCacheOnConnect) {
                refreshDeviceCache(g);
                refreshCacheOnConnect = false;
                // give the stack a moment after dropping the cache
                handler.postDelayed(() -> {
                    if (closed || g != gatt) return;
                    try {
                        if (!g.discoverServices()) closeWith("descoberta de serviços recusada");
                    } catch (SecurityException e) {
                        closeWith("sem permissão Bluetooth");
                    }
                }, 300);
                return;
            }
            if (!g.discoverServices())
                closeWith("descoberta de serviços recusada");
        } catch (SecurityException e) {
            LOG.error("discoverServices failed", e);
            closeWith("sem permissão Bluetooth");
        }
    }

    private void pumpWrites() {
        if (writing || closed || gatt == null || writeChar == null)
            return;
        byte[] data = writeQueue.poll();
        if (data == null)
            return;
        writing = true;
        int writeType = (writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeCharacteristic(writeChar, data, writeType) == 0; // BluetoothStatusCodes.SUCCESS
            } else {
                writeChar.setWriteType(writeType);
                writeChar.setValue(data);
                ok = gatt.writeCharacteristic(writeChar);
            }
        } catch (SecurityException e) {
            LOG.error("write failed", e);
            ok = false;
        }
        if (!ok) {
            writing = false;
            writeRefusals++;
            if (writeRefusals >= MAX_WRITE_REFUSALS) {
                closeWith("o Android recusou as escritas");
                return;
            }
            if (writeRefusals == 1 || writeRefusals % 20 == 0)
                LOG.warn("writeCharacteristic returned false ({}), retrying shortly", writeRefusals);
            writeQueue.addFirst(data);
            handler.postDelayed(this::pumpWrites, 50);
            return;
        }
        writeRefusals = 0;
        handler.removeCallbacks(writeTimeout);
        handler.postDelayed(writeTimeout, WRITE_TIMEOUT_MS);
    }

    private void onServicesReady(BluetoothGatt g) {
        BluetoothGattService service = g.getService(HuaweiConstants.UUID_SERVICE_HUAWEI_SERVICE);
        if (service == null) {
            LOG.error("Huawei service FE86 not found on the device");
            refreshCacheOnConnect = true; // stale GATT cache is the usual reason
            closeWith("serviço FE86 não encontrado");
            return;
        }
        writeChar = service.getCharacteristic(HuaweiConstants.UUID_CHARACTERISTIC_HUAWEI_WRITE);
        readChar = service.getCharacteristic(HuaweiConstants.UUID_CHARACTERISTIC_HUAWEI_READ);
        if (writeChar == null || readChar == null) {
            LOG.error("Huawei characteristics not found");
            close();
            return;
        }
        try {
            g.setCharacteristicNotification(readChar, true);
            BluetoothGattDescriptor d = readChar.getDescriptor(CCCD);
            if (d == null) {
                LOG.warn("No CCCD on read characteristic, assuming notifications are on");
                markReady();
                return;
            }
            byte[] value = (readChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) {
                ok = g.writeDescriptor(d, value) == 0;
            } else {
                d.setValue(value);
                ok = g.writeDescriptor(d);
            }
            if (!ok) {
                LOG.warn("writeDescriptor returned false, continuing anyway");
                handler.postDelayed(this::markReady, 300);
            }
        } catch (SecurityException e) {
            LOG.error("Could not enable notifications", e);
            close();
        }
    }

    private void markReady() {
        if (ready || closed)
            return;
        ready = true;
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(setupTimeout);
        LOG.info("BLE ready (mtu={})", mtu);
        callback.onReady(this);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            handler.post(() -> {
                if (closed || g != gatt)
                    return;
                LOG.info("Connection state {} (status {})", newState, status);
                lastStatus = status;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (linkUp)
                        return;
                    linkUp = true;
                    handler.removeCallbacks(setupTimeout);
                    handler.postDelayed(setupTimeout, SETUP_TIMEOUT_MS);
                    try {
                        if (g.requestMtu(247)) {
                            handler.postDelayed(mtuTimeout, MTU_TIMEOUT_MS);
                        } else {
                            discoverServices(g);
                        }
                    } catch (SecurityException e) {
                        LOG.error("requestMtu failed", e);
                        closeWith("sem permissão Bluetooth");
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    close();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
            handler.post(() -> {
                if (closed || g != gatt)
                    return;
                if (status == BluetoothGatt.GATT_SUCCESS)
                    mtu = newMtu;
                LOG.info("MTU {} (status {})", newMtu, status);
                discoverServices(g);
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            handler.post(() -> {
                if (closed || g != gatt)
                    return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    LOG.error("Service discovery failed: {}", status);
                    refreshCacheOnConnect = true;
                    closeWith("descoberta de serviços falhou (" + status + ")");
                    return;
                }
                onServicesReady(g);
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> {
                if (closed || g != gatt)
                    return;
                LOG.debug("Descriptor write status {}", status);
                markReady();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            handler.post(() -> {
                if (closed || g != gatt)
                    return;
                if (status != BluetoothGatt.GATT_SUCCESS)
                    LOG.warn("Write status {}", status);
                handler.removeCallbacks(writeTimeout);
                writeTimeouts = 0;
                writing = false;
                pumpWrites();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            deliver(g, characteristic, value);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] v = characteristic.getValue();
            deliver(g, characteristic, v == null ? new byte[0] : v.clone());
        }

        private void deliver(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (!HuaweiConstants.UUID_CHARACTERISTIC_HUAWEI_READ.equals(characteristic.getUuid()))
                return;
            final byte[] copy = value.clone();
            handler.post(() -> {
                if (closed || g != gatt)
                    return;
                lastRxMillis = System.currentTimeMillis();
                callback.onData(copy);
            });
        }
    };
}
