package com.btmap.china.bt;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 蓝牙连接控制：BLE GATT（自动枚举/订阅全部 notify 特征）+ 经典 SPP。
 * 面向 PHY6222 这类“UUID 由固件决定”的 BLE 透传模块，不做 UUID 硬编码。
 */
@SuppressLint("MissingPermission")
public final class BtLink {
    private static final String TAG = "BtLink";

    public static final int MODE_SPP = 0;
    public static final int MODE_BLE = 1;

    public static final String STEP_PERMISSION = "权限";
    public static final String STEP_ADAPTER = "蓝牙开关";
    public static final String STEP_SCAN = "扫描";
    public static final String STEP_CONNECT = "连接";
    public static final String STEP_DISCOVER = "发现服务";
    public static final String STEP_SUBSCRIBE = "订阅通知";
    public static final String STEP_DATA = "收到数据";
    private static final String[] STEPS = {STEP_PERMISSION, STEP_ADAPTER, STEP_SCAN,
            STEP_CONNECT, STEP_DISCOVER, STEP_SUBSCRIBE, STEP_DATA};
    public static final int STATE_PENDING = 0, STATE_OK = 1, STATE_FAIL = 2;

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    public interface Callback {
        void onStatus(String text, boolean connected);

        void onData(byte[] data, int len);

        void onLog(String line);

        void onDevices(List<DeviceItem> items);

        void onScanState(String text);

        void onDiagnosticsChanged();
    }

    /** 指尖陀螺单条指令的结果回调（主线程）。 */
    public interface SpinnerCallback {
        void onResult(boolean ok, String message, byte[] tx, byte[] rx,
                      int writeType, int status);
    }

    public static final class DeviceItem {
        public final String address;
        public final String name;
        public final int rssi;
        public final boolean bonded;
        public final BluetoothDevice device;

        DeviceItem(BluetoothDevice device, String name, int rssi, boolean bonded) {
            this.device = device;
            this.address = device.getAddress();
            this.name = name == null || name.trim().isEmpty() ? "(未命名设备)" : name.trim();
            this.rssi = rssi;
            this.bonded = bonded;
        }
    }

    public static final class GattCharInfo {
        public final String serviceUuid;
        public final String charUuid;
        public final int properties;
        public boolean subscribed;
        public long bytes;

        GattCharInfo(String serviceUuid, String charUuid, int properties) {
            this.serviceUuid = serviceUuid;
            this.charUuid = charUuid;
            this.properties = properties;
        }

        public boolean notifiable() {
            return (properties & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                    | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
        }

        public boolean writable() {
            return (properties & (BluetoothGattCharacteristic.PROPERTY_WRITE
                    | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
        }

        public String propsLabel() {
            StringBuilder sb = new StringBuilder();
            if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0) sb.append("R");
            if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) sb.append("W");
            if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) sb.append("w");
            if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) sb.append("N");
            if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) sb.append("I");
            return sb.length() == 0 ? "-" : sb.toString();
        }
    }

    public static final class Diagnostics {
        public String deviceName = "";
        public String address = "";
        public int rssi = -127;
        public int mtu;
        public boolean connected;
        public int mode = MODE_BLE;
        public final String[] steps = STEPS.clone();
        public final int[] stepState = new int[STEPS.length];
        public final String[] stepDetail = new String[STEPS.length];
        public final List<GattCharInfo> chars = new ArrayList<>();
        public long bytesReceived;
        public String lastError = "";
        public String lastData = "";
    }

    private final Context context;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;

    private BluetoothLeScanner scanner;
    private boolean scanning;
    private final Map<String, DeviceItem> found = new LinkedHashMap<>();
    private final Runnable scanTimeout = this::stopScan;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic uartWriteChar;
    private BluetoothGattCharacteristic uartNotifyChar;
    private final ArrayDeque<CccdWrite> cccdQueue = new ArrayDeque<>();
    private final List<GattCharInfo> charInfos = new ArrayList<>();
    private int subscribed;

    private BluetoothSocket socket;
    private volatile boolean sppRunning;

    private boolean connected;
    private DeviceItem lastDevice;
    private int mode = MODE_SPP;
    private int reconnectAttempts;
    private int connectAttempts;
    private boolean autoConnectFallback;
    private boolean manualDisconnect;
    private boolean released;
    private boolean discovering;
    private int mtu;

    private PendingSpinner pendingSpinner;

    private static final class PendingSpinner {
        final byte[] tx;
        final SpinnerCallback callback;
        final boolean generic;
        final SpinnerProtocol.Parser parser = new SpinnerProtocol.Parser();
        final ByteArrayOutputStream rx = new ByteArrayOutputStream();
        int attempt;
        int lastStatus = -1;
        int lastWriteType;
        boolean finished;
        Runnable probe;
        Runnable retry;
        Runnable timeout;

        PendingSpinner(byte[] tx, SpinnerCallback callback, boolean generic) {
            this.tx = tx;
            this.callback = callback;
            this.generic = generic;
        }
    }

    private final int[] stepState = new int[STEPS.length];
    private final String[] stepDetail = new String[STEPS.length];
    private long bytesReceived;
    private String lastError = "";
    private String lastData = "";
    private int rssi = -127;

    private boolean bondReceiverRegistered;
    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                if (d != null && state == BluetoothDevice.BOND_BONDED) {
                    postLog("配对完成，重新连接");
                    setStep(STEP_CONNECT, STATE_PENDING, "已配对，重新连接");
                    main.postDelayed(() -> {
                        if (lastDevice != null && !released) {
                            connect(mode, lastDevice);
                        }
                    }, 600);
                }
            }
        }
    };

    public BtLink(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        android.bluetooth.BluetoothManager bm =
                (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm == null ? null : bm.getAdapter();
        this.scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
    }

    // ------------------------------------------------------------ 状态
    public boolean isSupported() {
        return adapter != null;
    }

    public boolean isEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    public boolean isConnected() {
        return connected;
    }

    public DeviceItem lastDevice() {
        return lastDevice;
    }

    public int mode() {
        return mode;
    }

    public void setMode(int m) {
        mode = m;
    }

    private void setStep(String step, int state, String detail) {
        for (int i = 0; i < STEPS.length; i++) {
            if (STEPS[i].equals(step)) {
                stepState[i] = state;
                stepDetail[i] = detail;
            }
        }
        main.post(() -> callback.onDiagnosticsChanged());
    }

    private void fail(String step, String detail) {
        setStep(step, STATE_FAIL, detail);
        lastError = step + ": " + detail;
        postLog("失败 " + lastError);
    }

    /** 让诊断页反映当前的权限/蓝牙开关状态。 */
    public void noteEnvironment(boolean perms, boolean enabled) {
        if (perms) {
            setStep(STEP_PERMISSION, STATE_OK, "已授权");
        }
        if (enabled) {
            setStep(STEP_ADAPTER, STATE_OK, "蓝牙已开启");
        }
    }

    public Diagnostics getDiagnostics() {
        Diagnostics d = new Diagnostics();
        d.deviceName = lastDevice == null ? "" : lastDevice.name;
        d.address = lastDevice == null ? "" : lastDevice.address;
        d.rssi = rssi;
        d.mtu = mtu;
        d.connected = connected;
        d.mode = mode;
        System.arraycopy(stepState, 0, d.stepState, 0, STEPS.length);
        System.arraycopy(stepDetail, 0, d.stepDetail, 0, STEPS.length);
        d.chars.addAll(charInfos);
        d.bytesReceived = bytesReceived;
        d.lastError = lastError;
        d.lastData = lastData;
        return d;
    }

    private void resetDiagnostics(DeviceItem item) {
        Arrays.fill(stepState, STATE_PENDING);
        Arrays.fill(stepDetail, null);
        charInfos.clear();
        cccdQueue.clear();
        subscribed = 0;
        bytesReceived = 0;
        lastError = "";
        lastData = "";
        mtu = 0;
        rssi = item == null ? -127 : item.rssi;
        setStep(STEP_PERMISSION, STATE_OK, "已授权");
        setStep(STEP_ADAPTER, STATE_OK, "蓝牙已开启");
    }

    // ------------------------------------------------------------ 扫描
    public void scan(int m) {
        mode = m;
        if (adapter == null) {
            postLog("本机不支持蓝牙");
            fail(STEP_ADAPTER, "本机不支持蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            postStatus("蓝牙未开启", false);
            fail(STEP_ADAPTER, "蓝牙未开启");
            return;
        }
        found.clear();
        setStep(STEP_SCAN, STATE_PENDING, "正在扫描…");
        if (m == MODE_BLE) {
            startBleScan();
        } else {
            loadBonded();
        }
    }

    private void loadBonded() {
        List<DeviceItem> items = new ArrayList<>();
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                items.add(new DeviceItem(d, safeName(d), -127, true));
            }
        } catch (SecurityException e) {
            postLog("缺少蓝牙权限: " + e.getMessage());
        }
        postDevices(items);
        if (items.isEmpty()) {
            setStep(STEP_SCAN, STATE_FAIL, "没有已配对设备");
            postScanState("没有已配对的设备。请先到系统蓝牙设置里配对，再回来连接。");
        } else {
            setStep(STEP_SCAN, STATE_OK, "已配对 " + items.size() + " 个");
            postScanState("已配对设备 " + items.size() + " 个，点选后立即连接。");
        }
    }

    private void startBleScan() {
        if (scanner == null) {
            scanner = adapter.getBluetoothLeScanner();
        }
        if (scanner == null) {
            postLog("BLE 扫描不可用");
            fail(STEP_SCAN, "BLE 扫描不可用");
            return;
        }
        if (scanning) {
            return;
        }
        scanning = true;
        postScanState("正在扫描 BLE 设备…（15 秒）");
        main.postDelayed(scanTimeout, 15000);
        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, scanCallback);
        } catch (SecurityException e) {
            scanning = false;
            main.removeCallbacks(scanTimeout);
            fail(STEP_SCAN, "缺少权限: " + e.getMessage());
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null || name.isEmpty()) {
                name = safeName(d);
            }
            boolean bonded = false;
            try {
                bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
            } catch (SecurityException ignored) {
            }
            DeviceItem item = new DeviceItem(d, name, result.getRssi(), bonded);
            boolean isLast = lastDevice != null && lastDevice.address.equals(item.address);
            rssi = isLast ? item.rssi : rssi;
            found.put(item.address, item);
            setStep(STEP_SCAN, STATE_OK, "发现 " + found.size() + " 个设备");
            postDevices(new ArrayList<>(found.values()));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) {
                onScanResult(0, r);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            fail(STEP_SCAN, "扫描失败 code=" + errorCode);
        }
    };

    public void stopScan() {
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        if (scanning) {
            main.removeCallbacks(scanTimeout);
        }
        scanning = false;
        if (found.isEmpty()) {
            setStep(STEP_SCAN, STATE_FAIL, "未发现设备");
            postScanState("扫描结束，未发现设备。可重试或先在系统设置里配对。");
        } else {
            setStep(STEP_SCAN, STATE_OK, "发现 " + found.size() + " 个设备");
            postScanState("扫描结束。");
        }
    }

    // ------------------------------------------------------------ 连接
    public void connect(int m, DeviceItem item) {
        if (item == null) {
            return;
        }
        stopScan();
        disconnect();
        mode = m;
        lastDevice = item;
        manualDisconnect = false;
        connectAttempts = 0;
        autoConnectFallback = false;
        reconnectAttempts = 0;
        resetDiagnostics(item);
        try {
            adapter.cancelDiscovery();
        } catch (Exception ignored) {
        }
        if (m == MODE_BLE) {
            connectBleAttempts(item);
        } else {
            connectSpp(item);
        }
    }

    public void connectByAddress(int m, String address) {
        if (address == null || address.isEmpty() || adapter == null) {
            return;
        }
        try {
            BluetoothDevice d = adapter.getRemoteDevice(address);
            connect(m, new DeviceItem(d, safeName(d), -127,
                    d.getBondState() == BluetoothDevice.BOND_BONDED));
        } catch (Exception e) {
            postLog("自动连接失败: " + e.getMessage());
        }
    }

    private void connectBleAttempts(DeviceItem item) {
        if (released) {
            return;
        }
        if (connectAttempts >= 3 && !autoConnectFallback) {
            autoConnectFallback = true;
            postLog("普通连接失败，尝试 autoConnect 兜底（15 秒）");
            setStep(STEP_CONNECT, STATE_PENDING, "autoConnect 兜底中…");
            main.postDelayed(() -> openGatt(item, true), 400);
            return;
        }
        if (connectAttempts >= 4) {
            fail(STEP_CONNECT, "多次连接失败，检查设备是否已被占用/不在范围");
            postStatus("连接失败", false);
            return;
        }
        connectAttempts++;
        main.postDelayed(() -> openGatt(item, autoConnectFallback), 200L * (connectAttempts - 1));
    }

    private void openGatt(DeviceItem item, boolean autoConnect) {
        if (released) {
            return;
        }
        closeGatt();
        setStep(STEP_CONNECT, STATE_PENDING, autoConnect ? "autoConnect 连接中…" : "连接中…");
        postStatus("正在连接 BLE: " + item.name, false);
        postLog("connectGatt autoConnect=" + autoConnect + " 第 " + connectAttempts + " 次");
        try {
            gatt = item.device.connectGatt(context, autoConnect, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            fail(STEP_CONNECT, "缺少权限: " + e.getMessage());
            return;
        }
        if (gatt == null) {
            postLog("connectGatt 返回 null，重试");
            connectBleAttempts(item);
            return;
        }
        final BluetoothGatt g = gatt;
        main.postDelayed(() -> {
            if (gatt == g && !connected && !released) {
                postLog("连接超时，重试");
                closeGatt();
                connectBleAttempts(item);
            }
        }, 12000);
    }

    private void closeGatt() {
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        discovering = false;
        cccdQueue.clear();
        writeChar = null;
    }

    private void refreshGattCache(BluetoothGatt g) {
        try {
            Method m = g.getClass().getMethod("refresh");
            m.invoke(g);
            postLog("已刷新 GATT 缓存");
        } catch (Exception ignored) {
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            postLog("GATT state=" + newState + " status=" + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                connectAttempts = 0;
                autoConnectFallback = false;
                setStep(STEP_CONNECT, STATE_OK, "已连接");
                postStatus("已连接，发现服务…", true);
                try {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                } catch (Exception ignored) {
                }
                // 先发现服务：不要在这里 refresh() / requestMtu()，
                // 部分机型（OPPO/ColorOS、Android 11）会因此永远收不到 onServicesDiscovered。
                main.postDelayed(() -> discover(g), 150);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean wasConnected = connected;
                connected = false;
                postStatus("已断开", false);
                if (status == 133 || status == 257) {
                    postLog("连接状态异常 " + status + "，准备重试");
                    closeGatt();
                    if (!manualDisconnect && !released) {
                        connectBleAttempts(lastDevice);
                    }
                    return;
                }
                if (status == 5) {
                    postLog("需要配对/绑定，发起 createBond");
                    setStep(STEP_CONNECT, STATE_PENDING, "需要配对，正在绑定…");
                    try {
                        registerBondReceiver();
                        if (lastDevice != null && lastDevice.device.createBond()) {
                            return;
                        }
                    } catch (Exception e) {
                        postLog("createBond 失败: " + e.getMessage());
                    }
                }
                closeGatt();
                if (wasConnected) {
                    maybeReconnect();
                } else {
                    connectBleAttempts(lastDevice);
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtuValue, int status) {
            mtu = mtuValue;
            postLog("MTU=" + mtuValue + " status=" + status);
            callback.onDiagnosticsChanged();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            discovering = false;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(STEP_DISCOVER, "发现服务失败 status=" + status);
                return;
            }
            charInfos.clear();
            writeChar = null;
            uartWriteChar = null;
            uartNotifyChar = null;
            int totalChars = 0;
            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    totalChars++;
                    GattCharInfo info = new GattCharInfo(s.getUuid().toString(),
                            c.getUuid().toString(), c.getProperties());
                    charInfos.add(info);
                    String cu = c.getUuid().toString().toLowerCase(Locale.US);
                    // WCH/常见 BLE 透传：FFF1 通知、FFF2 写；FFE1 读写通知一体
                    if (cu.equals("0000fff2-0000-1000-8000-00805f9b34fb")) {
                        uartWriteChar = c;
                    } else if (cu.equals("0000fff1-0000-1000-8000-00805f9b34fb")) {
                        uartNotifyChar = c;
                    } else if (cu.equals("0000ffe1-0000-1000-8000-00805f9b34fb")) {
                        if (info.writable()) {
                            uartWriteChar = c;
                        }
                        if (info.notifiable()) {
                            uartNotifyChar = c;
                        }
                    }
                    if (writeChar == null && info.writable()) {
                        writeChar = c;
                    }
                }
            }
            if (uartWriteChar != null) {
                writeChar = uartWriteChar;
                postLog("UART 写特征 → " + uartWriteChar.getUuid());
            }
            if (uartNotifyChar != null) {
                postLog("UART 通知特征 → " + uartNotifyChar.getUuid());
            }
            setStep(STEP_DISCOVER, STATE_OK, "服务 " + g.getServices().size()
                    + " 个 / 特征 " + totalChars + " 个");
            postLog("发现服务 " + g.getServices().size() + " 个，特征 " + totalChars + " 个");
            if (totalChars == 0) {
                fail(STEP_SUBSCRIBE, "没有特征，可能不是透传模块");
                return;
            }
            subscribeAll(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                subscribed++;
            } else {
                postLog("写 CCCD 失败 status=" + status);
                lastError = "CCCD 写入失败 status=" + status;
            }
            writeNextCccd(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            if (value != null && value.length > 0) {
                handleSpinnerBytes(value, "notify");
                onData(ch.getUuid().toString(), value);
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] value = ch.getValue();
            if (value != null && value.length > 0) {
                handleSpinnerBytes(value, "notify");
                onData(ch.getUuid().toString(), value);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch,
                                         byte[] value, int status) {
            postLog("读取完成 status=" + status + " " + ch.getUuid()
                    + " " + SpinnerProtocol.hex(value));
            if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.length > 0) {
                handleSpinnerBytes(value, "read");
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            byte[] value = ch.getValue();
            postLog("读取完成 status=" + status + " " + ch.getUuid()
                    + " " + SpinnerProtocol.hex(value));
            if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.length > 0) {
                handleSpinnerBytes(value, "read");
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            postLog("写入完成 status=" + status + " " + ch.getUuid());
        }
    };

    private void discover(BluetoothGatt g) {
        if (g == null || discovering || g != gatt) {
            return;
        }
        discovering = true;
        setStep(STEP_DISCOVER, STATE_PENDING, "发现服务中…");
        boolean ok = g.discoverServices();
        postLog("discoverServices 调用 → " + ok);
        if (!ok) {
            discovering = false;
            fail(STEP_DISCOVER, "discoverServices 调用失败");
            return;
        }
        main.postDelayed(() -> {
            if (gatt == g && discovering && connected) {
                postLog("发现服务超时，重试一次");
                boolean retry = g.discoverServices();
                postLog("discoverServices 重试 → " + retry);
                if (!retry) {
                    discovering = false;
                    fail(STEP_DISCOVER, "服务发现超时");
                }
            }
        }, 5000);
    }

    private void subscribeAll(BluetoothGatt g) {
        cccdQueue.clear();
        subscribed = 0;
        for (BluetoothGattService s : g.getServices()) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int props = c.getProperties();
                boolean notify = (props & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
                if (!notify) {
                    continue;
                }
                g.setCharacteristicNotification(c, true);
                BluetoothGattDescriptor d = c.getDescriptor(CCCD_UUID);
                if (d == null) {
                    subscribed++;
                    continue;
                }
                byte[] value = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                        ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                cccdQueue.add(new CccdWrite(d, value, c.getUuid().toString()));
            }
        }
        if (cccdQueue.isEmpty()) {
            finishSubscribe();
        } else {
            setStep(STEP_SUBSCRIBE, STATE_PENDING, "订阅 " + cccdQueue.size() + " 个特征…");
            main.postDelayed(() -> writeNextCccd(g), 120);
        }
    }

    private void writeNextCccd(BluetoothGatt g) {
        if (g == null || g != gatt) {
            return;
        }
        CccdWrite w = cccdQueue.poll();
        if (w == null) {
            finishSubscribe();
            return;
        }
        try {
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) {
                ok = g.writeDescriptor(w.descriptor, w.value) == BluetoothGatt.GATT_SUCCESS;
            } else {
                w.descriptor.setValue(w.value);
                ok = g.writeDescriptor(w.descriptor);
            }
            if (!ok) {
                postLog("CCCD 写入调用失败: " + w.charUuid);
                writeNextCccd(g);
            }
        } catch (SecurityException e) {
            postLog("订阅失败（权限）: " + e.getMessage());
            writeNextCccd(g);
        }
    }

    private void finishSubscribe() {
        for (GattCharInfo info : charInfos) {
            info.subscribed = info.notifiable();
        }
        if (subscribed > 0) {
            setStep(STEP_SUBSCRIBE, STATE_OK, "已订阅 " + subscribed + " 个特征");
            postStatus("BLE 已连接：" + (lastDevice == null ? "" : lastDevice.name)
                    + "（" + subscribed + " 个通知特征）", true);
            postLog("订阅完成，共 " + subscribed + " 个 notify/indicate 特征");
        } else {
            setStep(STEP_SUBSCRIBE, STATE_FAIL, "没有可通知特征");
            postStatus("已连接，但未找到可通知特征", true);
            postLog("没有 notify/indicate 特征，可在诊断页手动发送指令");
        }
        callback.onDiagnosticsChanged();
    }

    private static final class CccdWrite {
        final BluetoothGattDescriptor descriptor;
        final byte[] value;
        final String charUuid;

        CccdWrite(BluetoothGattDescriptor descriptor, byte[] value, String charUuid) {
            this.descriptor = descriptor;
            this.value = value;
            this.charUuid = charUuid;
        }
    }

    public void resubscribe() {
        if (gatt != null && connected) {
            subscribeAll(gatt);
        }
    }

    private void onData(String charUuid, byte[] value) {
        bytesReceived += value.length;
        lastData = new String(value, SpinnerProtocol.GB2312).trim();
        if (lastData.length() > 80) {
            lastData = lastData.substring(0, 80) + "…";
        }
        for (GattCharInfo info : charInfos) {
            if (info.charUuid.equals(charUuid)) {
                info.bytes += value.length;
            }
        }
        setStep(STEP_DATA, STATE_OK, "已收 " + bytesReceived + " 字节");
        callback.onDiagnosticsChanged();
        main.post(() -> callback.onData(value, value.length));
    }

    // ------------------------------------------------------------ SPP
    private void connectSpp(DeviceItem item) {
        setStep(STEP_CONNECT, STATE_PENDING, "经典蓝牙连接中…");
        postStatus("正在连接经典蓝牙: " + item.name, false);
        postLog("SPP connect → " + item.address);
        sppRunning = true;
        new Thread(() -> {
            BluetoothSocket s = null;
            try {
                s = item.device.createRfcommSocketToServiceRecord(SPP_UUID);
                s.connect();
            } catch (Exception first) {
                postLog("标准 SPP UUID 连接失败: " + first.getMessage() + "，尝试 channel 1");
                try {
                    if (s != null) {
                        s.close();
                    }
                    Method m = item.device.getClass().getMethod("createRfcommSocket", int.class);
                    s = (BluetoothSocket) m.invoke(item.device, 1);
                    s.connect();
                } catch (Exception second) {
                    fail(STEP_CONNECT, "经典蓝牙连接失败: " + second.getMessage());
                    postStatus("经典蓝牙连接失败", false);
                    return;
                }
            }
            if (!sppRunning) {
                closeQuietly(s);
                return;
            }
            socket = s;
            connected = true;
            main.post(() -> {
                setStep(STEP_CONNECT, STATE_OK, "SPP 已连接");
                setStep(STEP_DISCOVER, STATE_OK, "经典蓝牙无需发现");
                setStep(STEP_SUBSCRIBE, STATE_OK, "经典蓝牙无需订阅");
                postStatus("经典蓝牙已连接：" + item.name, true);
                postLog("SPP 已连接");
                callback.onDiagnosticsChanged();
            });
            readSpp(s);
        }, "spp-connect").start();
    }

    private void readSpp(BluetoothSocket s) {
        byte[] buf = new byte[1024];
        try {
            InputStream in = s.getInputStream();
            while (sppRunning) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                if (n > 0) {
                    byte[] copy = Arrays.copyOf(buf, n);
                    bytesReceived += n;
                    setStep(STEP_DATA, STATE_OK, "已收 " + bytesReceived + " 字节");
                    main.post(() -> callback.onData(copy, copy.length));
                }
            }
        } catch (IOException e) {
            if (sppRunning) {
                postLog("SPP 读取结束: " + e.getMessage());
            }
        } finally {
            boolean wasConnected = connected;
            connected = false;
            closeQuietly(s);
            socket = null;
            main.post(() -> {
                postStatus("经典蓝牙已断开", false);
                if (wasConnected) {
                    setStep(STEP_CONNECT, STATE_FAIL, "链路断开");
                }
            });
            maybeReconnect();
        }
    }

    private void maybeReconnect() {
        if (connected || manualDisconnect || released || lastDevice == null) {
            return;
        }
        if (reconnectAttempts >= 3) {
            return;
        }
        reconnectAttempts++;
        long delay = 2000L << (reconnectAttempts - 1);
        postLog(delay / 1000 + " 秒后自动重连（第 " + reconnectAttempts + " 次）");
        main.postDelayed(() -> {
            if (!connected && lastDevice != null && !released && !manualDisconnect) {
                connect(mode, lastDevice);
            }
        }, delay);
    }

    private void registerBondReceiver() {
        if (bondReceiverRegistered) {
            return;
        }
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        context.registerReceiver(bondReceiver, f);
        bondReceiverRegistered = true;
    }

    private void unregisterBondReceiver() {
        if (bondReceiverRegistered) {
            try {
                context.unregisterReceiver(bondReceiver);
            } catch (Exception ignored) {
            }
            bondReceiverRegistered = false;
        }
    }

    // ------------------------------------------------------------ 发送 / 断开
    public String uartTxUuid() {
        return writeChar == null ? "-" : writeChar.getUuid().toString();
    }

    public String uartRxUuid() {
        return uartNotifyChar == null ? "-" : uartNotifyChar.getUuid().toString();
    }

    /** 已连接、已发现服务、CCCD 已订阅成功，才允许发陀螺指令。 */
    public boolean isSpinnerReady() {
        return connected && gatt != null && writeChar != null && subscribed > 0;
    }

    /**
     * 发送一条指尖陀螺指令，等待 Txx OK / DEL OK 回包。
     * 先使用 Write Request；无回包时自动改用 Write Without Response 重试，
     * 并在中途尝试读取 FFE1（部分固件把回包放在可读特征里）。
     */
    public void sendSpinner(byte[] payload, SpinnerCallback callback) {
        if (payload == null || payload.length == 0) {
            return;
        }
        main.post(() -> startSpinner(payload.clone(), callback, false));
    }

    /** 通用指令：同样等待回包，但不要求必须是 Txx OK/DEL OK。 */
    public void sendCommand(byte[] payload, SpinnerCallback callback) {
        if (payload == null || payload.length == 0) {
            return;
        }
        main.post(() -> startSpinner(payload.clone(), callback, true));
    }

    private void startSpinner(byte[] payload, SpinnerCallback callback, boolean generic) {
        if (pendingSpinner != null) {
            if (callback != null) {
                callback.onResult(false, "上一条指令还在等待回包", payload, new byte[0], 0, -1);
            }
            return;
        }
        if (!connected || gatt == null || writeChar == null || subscribed <= 0) {
            if (callback != null) {
                callback.onResult(false, "蓝牙未连接或 FFE1 通知尚未订阅", payload,
                        new byte[0], 0, -1);
            }
            return;
        }
        PendingSpinner p = new PendingSpinner(payload, callback, generic);
        pendingSpinner = p;
        postLog("陀螺 TX " + SpinnerProtocol.hex(p.tx) + "  「"
                + SpinnerProtocol.decode(p.tx).replace("\r", "\\r").replace("\n", "\\n") + "」");
        writeSpinner(p, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    }

    private void writeSpinner(PendingSpinner p, int writeType) {
        if (pendingSpinner != p || p.finished) {
            return;
        }
        cancelSpinnerTimers(p);
        p.lastWriteType = writeType;
        try {
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) {
                int rc = gatt.writeCharacteristic(writeChar, p.tx, writeType);
                ok = rc == android.bluetooth.BluetoothStatusCodes.SUCCESS;
                p.lastStatus = rc;
            } else {
                writeChar.setWriteType(writeType);
                writeChar.setValue(p.tx);
                ok = gatt.writeCharacteristic(writeChar);
                p.lastStatus = ok ? BluetoothGatt.GATT_SUCCESS : -1;
            }
            postLog("陀螺写入 writeType=" + writeType + " ok=" + ok
                    + " status=" + p.lastStatus);
            if (!ok && writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    && p.attempt == 0) {
                p.retry = () -> {
                    if (pendingSpinner == p && !p.finished && p.rx.size() == 0) {
                        p.attempt = 1;
                        writeSpinner(p, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    }
                };
                main.postDelayed(p.retry, 250);
                return;
            }
        } catch (SecurityException e) {
            finishSpinner(p, false, "发送失败（权限）: " + e.getMessage(), p.lastWriteType,
                    p.lastStatus);
            return;
        }

        p.probe = () -> {
            if (pendingSpinner == p && !p.finished && p.rx.size() == 0) {
                readSpinner(p);
            }
        };
        main.postDelayed(p.probe, 350);

        p.retry = () -> {
            if (pendingSpinner == p && !p.finished && p.rx.size() == 0 && p.attempt == 0) {
                p.attempt = 1;
                writeSpinner(p, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            }
        };
        main.postDelayed(p.retry, 1200);

        p.timeout = () -> {
            if (pendingSpinner == p && !p.finished) {
                java.util.List<SpinnerProtocol.Reply> rest = p.parser.flush();
                if (!rest.isEmpty()) {
                    String text = joinReplies(rest);
                    finishSpinner(p, p.generic || rest.get(0).ok, text,
                            p.lastWriteType, p.lastStatus);
                } else if (p.generic && p.rx.size() > 0) {
                    finishSpinner(p, true, SpinnerProtocol.decode(p.rx.toByteArray()).trim(),
                            p.lastWriteType, p.lastStatus);
                } else {
                    finishSpinner(p, false,
                            "超时：未收到回包（已尝试 Write Request / No-Response / Read FFE1）",
                            p.lastWriteType, p.lastStatus);
                }
            }
        };
        main.postDelayed(p.timeout, 3000);
    }

    private void readSpinner(PendingSpinner p) {
        if (gatt == null || writeChar == null) {
            return;
        }
        if ((writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            return;
        }
        try {
            boolean ok = gatt.readCharacteristic(writeChar);
            postLog("陀螺读取 FFE1 → " + ok);
        } catch (SecurityException e) {
            postLog("陀螺读取失败（权限）: " + e.getMessage());
        }
    }

    private void handleSpinnerBytes(byte[] value, String source) {
        if (value == null || value.length == 0) {
            return;
        }
        byte[] copy = value.clone();
        main.post(() -> consumeSpinner(copy, source));
    }

    private void consumeSpinner(byte[] value, String source) {
        PendingSpinner p = pendingSpinner;
        if (p == null || p.finished) {
            return;
        }
        if ("read".equals(source) && Arrays.equals(value, p.tx)) {
            postLog("陀螺读取到的是刚写入的命令，忽略");
            return;
        }
        p.rx.write(value, 0, value.length);
        postLog("陀螺 RX(" + source + ") " + SpinnerProtocol.hex(value));
        java.util.List<SpinnerProtocol.Reply> replies = p.parser.feed(value);
        if (!replies.isEmpty()) {
            if (p.generic) {
                finishSpinner(p, true, joinReplies(replies), p.lastWriteType, p.lastStatus);
            } else {
                SpinnerProtocol.Reply first = replies.get(0);
                finishSpinner(p, first.ok, first.text, p.lastWriteType, p.lastStatus);
            }
        }
    }

    private static String joinReplies(java.util.List<SpinnerProtocol.Reply> replies) {
        StringBuilder sb = new StringBuilder();
        for (SpinnerProtocol.Reply r : replies) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(r.text);
        }
        return sb.toString();
    }

    private void cancelSpinnerTimers(PendingSpinner p) {
        if (p.probe != null) {
            main.removeCallbacks(p.probe);
            p.probe = null;
        }
        if (p.retry != null) {
            main.removeCallbacks(p.retry);
            p.retry = null;
        }
        if (p.timeout != null) {
            main.removeCallbacks(p.timeout);
            p.timeout = null;
        }
    }

    private void finishSpinner(PendingSpinner p, boolean ok, String message,
                               int writeType, int status) {
        if (p == null || p.finished) {
            return;
        }
        p.finished = true;
        cancelSpinnerTimers(p);
        if (pendingSpinner == p) {
            pendingSpinner = null;
        }
        byte[] rx = p.rx.toByteArray();
        postLog("陀螺结果 " + (ok ? "OK" : "FAIL") + " " + message
                + "  RX=" + SpinnerProtocol.hex(rx));
        if (p.callback != null) {
            p.callback.onResult(ok, message, p.tx, rx, writeType, status);
        }
    }

    public void send(String text) {
        send(text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendHex(String hex) {
        String clean = hex.replaceAll("[^0-9a-fA-F]", "");
        if (clean.length() % 2 != 0) {
            postLog("HEX 长度必须是偶数");
            return;
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        send(out);
    }

    private void send(byte[] bytes) {
        if (!connected) {
            postLog("未连接，无法发送");
            return;
        }
        if (socket != null) {
            try {
                OutputStream out = socket.getOutputStream();
                out.write(bytes);
                out.flush();
                postLog("已发送 " + bytes.length + " 字节");
            } catch (IOException e) {
                postLog("发送失败: " + e.getMessage());
            }
            return;
        }
        if (gatt != null && writeChar != null) {
            try {
                boolean ok;
                if (Build.VERSION.SDK_INT >= 33) {
                    int rc = gatt.writeCharacteristic(writeChar, bytes,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    ok = rc == android.bluetooth.BluetoothStatusCodes.SUCCESS;
                } else {
                    writeChar.setValue(bytes);
                    ok = gatt.writeCharacteristic(writeChar);
                }
                postLog("已提交 " + bytes.length + " 字节 → " + writeChar.getUuid()
                        + "，writeCharacteristic=" + ok);
            } catch (SecurityException e) {
                postLog("发送失败（权限）: " + e.getMessage());
            }
        } else {
            postLog("该设备没有可写特征，无法发送");
        }
    }

    public void disconnect() {
        manualDisconnect = true;
        sppRunning = false;
        connected = false;
        closeQuietly(socket);
        socket = null;
        closeGatt();
        unregisterBondReceiver();
        setStep(STEP_CONNECT, STATE_PENDING, "已断开");
        postStatus("蓝牙未连接", false);
    }

    public void release() {
        released = true;
        stopScan();
        disconnect();
    }

    private void postStatus(String text, boolean isConnected) {
        main.post(() -> callback.onStatus(text, isConnected));
    }

    private void postLog(String line) {
        Log.d(TAG, line);
        main.post(() -> callback.onLog(line));
    }

    private void postDevices(List<DeviceItem> items) {
        List<DeviceItem> sorted = new ArrayList<>(items);
        Collections.sort(sorted, (a, b) -> {
            if (lastDevice != null && !a.address.equals(b.address)) {
                if (a.address.equals(lastDevice.address)) return -1;
                if (b.address.equals(lastDevice.address)) return 1;
            }
            if (a.bonded != b.bonded) {
                return a.bonded ? -1 : 1;
            }
            return Integer.compare(b.rssi, a.rssi);
        });
        main.post(() -> {
            if (!released) {
                callback.onDevices(sorted);
            }
        });
    }

    private void postScanState(String text) {
        main.post(() -> {
            if (!released) {
                callback.onScanState(text);
            }
        });
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null || n.isEmpty() ? "" : n;
        } catch (SecurityException e) {
            return "";
        }
    }

    private void closeQuietly(BluetoothSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static String formatBytes(long v) {
        return String.format(Locale.US, "%d B", v);
    }
}
