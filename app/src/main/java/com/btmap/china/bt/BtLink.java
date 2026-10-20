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
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 蓝牙连接控制：经典蓝牙 SPP（串口透传）与 BLE GATT（订阅所有 notify/indicate 特征）。
 * 所有回调都在主线程。
 */
@SuppressLint("MissingPermission")
public final class BtLink {
    private static final String TAG = "BtLink";

    public static final int MODE_SPP = 0;
    public static final int MODE_BLE = 1;

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

        @Override
        public String toString() {
            return name + "\n" + address + (bonded ? "  · 已配对" : "")
                    + (rssi > -127 ? "  · " + rssi + "dBm" : "");
        }
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
    private final List<DescriptorWrite> pendingDescs = new ArrayList<>();
    private int subscribed;

    private BluetoothSocket socket;
    private volatile boolean sppRunning;

    private boolean connected;
    private DeviceItem lastDevice;
    private int reconnectAttempts;
    private boolean manualDisconnect;
    private boolean released;

    public BtLink(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        android.bluetooth.BluetoothManager bm =
                (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm == null ? null : bm.getAdapter();
        this.scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
    }

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

    // ------------------------------------------------------------ 扫描
    public void scan(int mode) {
        if (adapter == null) {
            postLog("本机不支持蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            postStatus("蓝牙未开启", false);
            return;
        }
        found.clear();
        if (mode == MODE_BLE) {
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
            postScanState("没有已配对的设备。请先到系统蓝牙设置里配对模块（如 HC-05），再回来连接。");
        } else {
            postScanState("已配对设备 " + items.size() + " 个，点选后立即连接。");
        }
    }

    private void startBleScan() {
        if (scanner == null) {
            scanner = adapter.getBluetoothLeScanner();
        }
        if (scanner == null) {
            postLog("BLE 扫描不可用");
            return;
        }
        if (scanning) {
            return;
        }
        scanning = true;
        postScanState("正在扫描 BLE 设备…（12 秒）");
        main.postDelayed(scanTimeout, 12000);
        try {
            scanner.startScan(scanCallback);
        } catch (SecurityException e) {
            scanning = false;
            main.removeCallbacks(scanTimeout);
            postLog("扫描失败，缺少权限: " + e.getMessage());
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null) {
                name = safeName(d);
            }
            boolean bonded = false;
            try {
                bonded = d.getBondState() == BluetoothDevice.BOND_BONDED;
            } catch (SecurityException ignored) {
            }
            DeviceItem item = new DeviceItem(d, name, result.getRssi(), bonded);
            found.put(item.address, item);
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
            postScanState("扫描失败，错误码 " + errorCode);
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
        postScanState(found.isEmpty() ? "扫描结束，未发现设备。可重试或先到系统设置里配对。" : "扫描结束。");
    }

    // ------------------------------------------------------------ 连接
    public void connect(int mode, DeviceItem item) {
        if (item == null) {
            return;
        }
        disconnect();
        manualDisconnect = false;
        lastDevice = item;
        reconnectAttempts = 0;
        try {
            adapter.cancelDiscovery();
        } catch (Exception ignored) {
        }
        if (mode == MODE_BLE) {
            connectBle(item);
        } else {
            connectSpp(item);
        }
    }

    private void connectBle(DeviceItem item) {
        postStatus("正在连接 BLE: " + item.name, false);
        postLog("BLE connectGatt → " + item.address);
        try {
            gatt = item.device.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            postLog("连接失败（权限）: " + e.getMessage());
            postStatus("缺少附近设备权限", false);
            return;
        }
        if (gatt == null) {
            postLog("connectGatt 返回 null");
            postStatus("连接失败", false);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            postLog("GATT state=" + newState + " status=" + status);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                boolean ok = g.discoverServices();
                if (!ok) {
                    postLog("discoverServices 调用失败");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                boolean wasConnected = connected;
                connected = false;
                postStatus("已断开", false);
                if (g != null) {
                    g.close();
                }
                gatt = null;
                postLog(wasConnected ? "BLE 链路断开" : "BLE 连接失败（status=" + status + "），检查设备是否在广播/是否已被占用");
                maybeReconnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postLog("发现服务失败 status=" + status);
                postStatus("发现服务失败", false);
                return;
            }
            subscribed = 0;
            pendingDescs.clear();
            writeChar = null;
            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int props = c.getProperties();
                    if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                            || (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        if (writeChar == null) {
                            writeChar = c;
                        }
                    }
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
                    pendingDescs.add(new DescriptorWrite(d, value));
                }
            }
            main.post(() -> {
                if (pendingDescs.isEmpty()) {
                    finishBleSetup();
                } else {
                    writeNextDescriptor(g);
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                subscribed++;
            } else {
                postLog("写入 CCCD 失败 status=" + status);
            }
            main.post(() -> {
                if (pendingDescs.isEmpty()) {
                    finishBleSetup();
                } else {
                    writeNextDescriptor(g);
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            if (value != null && value.length > 0) {
                emit(value);
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] value = ch.getValue();
            if (value != null && value.length > 0) {
                emit(value);
            }
        }
    };

    private static final class DescriptorWrite {
        final BluetoothGattDescriptor descriptor;
        final byte[] value;

        DescriptorWrite(BluetoothGattDescriptor descriptor, byte[] value) {
            this.descriptor = descriptor;
            this.value = value;
        }
    }

    private void writeNextDescriptor(BluetoothGatt g) {
        if (g == null || pendingDescs.isEmpty()) {
            return;
        }
        DescriptorWrite w = pendingDescs.remove(0);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(w.descriptor, w.value);
            } else {
                w.descriptor.setValue(w.value);
                g.writeDescriptor(w.descriptor);
            }
        } catch (SecurityException e) {
            postLog("订阅通知失败（权限）: " + e.getMessage());
        }
    }

    private void finishBleSetup() {
        if (released || !pendingDescs.isEmpty()) {
            return;
        }
        if (subscribed == 0) {
            postStatus("已连接，但未找到可通知特征", false);
            postLog("该设备没有 notify/indicate 特征；可在日志面板手动发送指令触发出数");
            return;
        }
        connected = true;
        String name = lastDevice == null ? "" : lastDevice.name;
        postStatus("BLE 已连接：" + name + "（" + subscribed + " 个通知特征）", true);
        postLog("订阅完成，共 " + subscribed + " 个通知特征");
    }

    private void connectSpp(DeviceItem item) {
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
                    postLog("经典蓝牙连接失败: " + second.getMessage());
                    postStatus("经典蓝牙连接失败，请确认已配对且模块未连其他设备", false);
                    maybeReconnect();
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
                postStatus("经典蓝牙已连接：" + item.name, true);
                postLog("SPP 已连接");
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
                    emit(Arrays.copyOf(buf, n));
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
                    postLog("SPP 链路断开");
                }
            });
            maybeReconnect();
        }
    }

    private void maybeReconnect() {
        if (connected || manualDisconnect || released || lastDevice == null) {
            return;
        }
        if (reconnectAttempts >= 2) {
            return;
        }
        reconnectAttempts++;
        postLog("2 秒后尝试重连（第 " + reconnectAttempts + " 次）…");
        main.postDelayed(() -> {
            if (!connected && lastDevice != null && !released && !manualDisconnect) {
                connect(lastMode, lastDevice);
            }
        }, 2000);
    }

    private int lastMode = MODE_SPP;

    public void setMode(int mode) {
        lastMode = mode;
    }

    // ------------------------------------------------------------ 数据发送
    public void send(String text) {
        if (!connected) {
            postLog("未连接，无法发送");
            return;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (socket != null) {
            try {
                OutputStream out = socket.getOutputStream();
                out.write(bytes);
                out.flush();
                postLog("已发送: " + text);
            } catch (IOException e) {
                postLog("发送失败: " + e.getMessage());
            }
            return;
        }
        if (gatt != null && writeChar != null) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(writeChar, bytes,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                } else {
                    writeChar.setValue(bytes);
                    gatt.writeCharacteristic(writeChar);
                }
                postLog("已发送: " + text);
            } catch (SecurityException e) {
                postLog("发送失败（权限）: " + e.getMessage());
            }
        } else {
            postLog("该设备没有可写特征，无法发送");
        }
    }

    // ------------------------------------------------------------ 断开/释放
    public void disconnect() {
        manualDisconnect = true;
        sppRunning = false;
        connected = false;
        closeQuietly(socket);
        socket = null;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        pendingDescs.clear();
        writeChar = null;
        subscribed = 0;
    }

    public void release() {
        released = true;
        stopScan();
        disconnect();
    }

    private void emit(byte[] data) {
        if (released) {
            return;
        }
        main.post(() -> callback.onData(data, data.length));
    }

    private void closeQuietly(BluetoothSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
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
}
