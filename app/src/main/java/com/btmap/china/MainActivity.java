package com.btmap.china;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.btmap.china.bt.BtLink;
import com.btmap.china.bt.SpinnerProtocol;
import com.btmap.china.map.ChinaMapView;
import com.btmap.china.map.TileSource;
import com.btmap.china.track.CoordParser;
import com.btmap.china.track.CoordSystems;
import com.btmap.china.track.TrackStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements BtLink.Callback {
    private static final int REQ_PERMS = 2001;
    private static final int REQ_ENABLE = 2002;

    private ChinaMapView mapView;
    private TextView statusText;
    private TextView coordText;
    private TextView countText;
    private TextView datumChip;
    private TextView mapChip;
    private TextView logText;
    private ScrollView logPanel;
    private Button btnConnect;
    private Button btnFollow;
    private Button btnLog;

    private BtLink bt;
    private final CoordParser parser = new CoordParser();
    private final StringBuilder logBuffer = new StringBuilder();
    private int logLines;

    private int mode = BtLink.MODE_BLE;
    private int datum = CoordSystems.DEFAULT;
    private int mapType = TileSource.TYPE_VECTOR;
    private int tileProvider = TileSource.PROVIDER_ESRI;
    private String tdtKey = "";
    private boolean connected;
    private boolean pendingConnect;
    private boolean logVisible;

    private boolean showCity = true;
    private boolean showCounty = true;
    private boolean showLabels = true;
    private boolean showGrid = true;
    private boolean showAccuracy = true;
    private boolean showBubble = true;

    private AlertDialog deviceDialog;
    private DeviceAdapter deviceAdapter;
    private TextView scanStateView;
    private ListView deviceList;

    private AlertDialog diagnosticsDialog;
    private TextView diagnosticsText;

    private SharedPreferences prefs;

    private final TrackStore.Listener trackListener = () -> runOnUiThread(this::refreshCount);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        migrateSettings();
        datum = prefs.getInt("datum", CoordSystems.DEFAULT);
        mode = prefs.getInt("mode", BtLink.MODE_BLE);
        mapType = prefs.getInt("mapType", TileSource.TYPE_VECTOR);
        tileProvider = prefs.getInt("tileProvider", TileSource.PROVIDER_AMAP);
        tdtKey = prefs.getString("tdtKey", "");
        showCity = prefs.getBoolean("showCity", true);
        showCounty = prefs.getBoolean("showCounty", true);
        showLabels = prefs.getBoolean("showLabels", true);
        showGrid = prefs.getBoolean("showGrid", true);
        showAccuracy = prefs.getBoolean("showAccuracy", true);
        showBubble = prefs.getBoolean("showBubble", true);

        mapView = findViewById(R.id.map);
        statusText = findViewById(R.id.status);
        coordText = findViewById(R.id.coordText);
        countText = findViewById(R.id.countText);
        datumChip = findViewById(R.id.datumChip);
        mapChip = findViewById(R.id.mapChip);
        logPanel = findViewById(R.id.logPanel);
        logText = findViewById(R.id.logText);
        btnConnect = findViewById(R.id.btnConnect);
        btnFollow = findViewById(R.id.btnFollow);
        btnLog = findViewById(R.id.btnLog);
        Button btnMore = findViewById(R.id.btnMore);

        mapView.setPickListener((lat, lon) -> showPickedCoordinate(lat, lon));
        mapView.setOnZoomLimitListener(() ->
                toast(mapView.isRasterMode()
                        ? "已到在线瓦片最大缩放（约 1 米/像素）"
                        : "已到矢量底图精度上限（约 5 米/像素）"));
        mapView.setCountyVisible(showCounty);
        applyDisplaySettings();
        applyMapType(false);

        bt = new BtLink(this, this);

        btnConnect.setOnClickListener(v -> requestConnect());
        btnMore.setOnClickListener(v -> showMoreMenu());
        datumChip.setOnClickListener(v -> showDatumDialog());
        mapChip.setOnClickListener(v -> showMapTypeDialog());
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> mapView.zoomBy(1.8));
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> mapView.zoomBy(1 / 1.8));
        findViewById(R.id.btnFit).setOnClickListener(v -> {
            mapView.setFollow(false);
            updateFollowButton();
            mapView.fitChina();
        });
        btnFollow.setOnClickListener(v -> {
            mapView.setFollow(!mapView.isFollow());
            updateFollowButton();
        });
        findViewById(R.id.btnClear).setOnClickListener(v -> confirmClear());
        btnLog.setOnClickListener(v -> toggleLog());

        TrackStore.INSTANCE.addListener(trackListener);
        updateFollowButton();
        updateDatumChip();
        refreshCount();
        if (TrackStore.INSTANCE.size() > 0) {
            double[] last = TrackStore.INSTANCE.lastLatLon();
            if (last != null) {
                coordText.setText(formatCoord(last[0], last[1]));
            }
        }
        addLog("应用启动，内置 GCJ-02 省市县底图；在线街道/卫星需网络；输入坐标系："
                + CoordSystems.name(datum));

        handleIntent(getIntent());
        maybeAutoConnect();
    }

    private void migrateSettings() {
        if (prefs.contains("coordMode")) {
            int legacy = prefs.getInt("coordMode", 0);
            int converted = CoordSystems.fromLegacy(legacy);
            prefs.edit().putInt("datum", converted).remove("coordMode").apply();
            addLog("已迁移旧坐标设置到 " + CoordSystems.name(converted));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.hasExtra("map_type")) {
            mapType = intent.getIntExtra("map_type", mapType);
            if (intent.hasExtra("tile_provider")) {
                tileProvider = intent.getIntExtra("tile_provider", tileProvider);
            }
            prefs.edit().putInt("mapType", mapType)
                    .putInt("tileProvider", tileProvider).apply();
            applyMapType(false);
        }
        String demoPoints = intent.getStringExtra("demo_points");
        boolean demo = intent.getBooleanExtra("demo", false);
        if (demoPoints != null) {
            feedDemoPoints(demoPoints);
        } else if (demo) {
            injectDemo();
        }
        if (intent.getBooleanExtra("show_log", false) && !logVisible) {
            toggleLog();
        }
    }

    // ------------------------------------------------------------ 蓝牙
    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermissions() {
        for (String p : requiredPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestConnect() {
        if (!bt.isSupported()) {
            toast("本机不支持蓝牙");
            return;
        }
        if (!hasPermissions()) {
            pendingConnect = true;
            requestPermissions(requiredPermissions(), REQ_PERMS);
            return;
        }
        if (!bt.isEnabled()) {
            pendingConnect = true;
            toast("请先开启蓝牙");
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
            } catch (Exception e) {
                toast("无法打开蓝牙设置: " + e.getMessage());
            }
            return;
        }
        showDeviceDialog();
    }

    private void maybeAutoConnect() {
        if (!prefs.getBoolean("autoConnect", true)) {
            return;
        }
        String addr = prefs.getString("lastAddress", "");
        if (addr == null || addr.isEmpty()) {
            return;
        }
        if (!hasPermissions() || !bt.isEnabled()) {
            return;
        }
        final int lastMode = prefs.getInt("lastMode", BtLink.MODE_BLE);
        addLog("尝试自动连接上次设备 " + addr);
        new android.os.Handler(getMainLooper()).postDelayed(
                () -> bt.connectByAddress(lastMode, addr), 1200);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) {
            return;
        }
        boolean allGranted = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
            }
        }
        if (allGranted) {
            if (pendingConnect) {
                pendingConnect = false;
                requestConnect();
            }
        } else {
            toast("需要“附近的设备”权限才能连接蓝牙");
            addLog("权限被拒绝：请在系统设置 → 应用 → 权限中允许“附近的设备”");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE) {
            if (resultCode == RESULT_OK && pendingConnect) {
                pendingConnect = false;
                requestConnect();
            } else {
                pendingConnect = false;
                toast("蓝牙未开启");
            }
        }
    }

    private void showDeviceDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_devices, null);
        RadioGroup group = view.findViewById(R.id.modeGroup);
        RadioButton rbSpp = view.findViewById(R.id.modeSpp);
        RadioButton rbBle = view.findViewById(R.id.modeBle);
        deviceList = view.findViewById(R.id.deviceList);
        scanStateView = view.findViewById(R.id.scanState);
        if (mode == BtLink.MODE_BLE) {
            rbBle.setChecked(true);
        } else {
            rbSpp.setChecked(true);
        }
        group.setOnCheckedChangeListener((g, checkedId) -> {
            mode = checkedId == R.id.modeBle ? BtLink.MODE_BLE : BtLink.MODE_SPP;
            prefs.edit().putInt("mode", mode).apply();
            bt.setMode(mode);
            bt.scan(mode);
        });

        deviceAdapter = new DeviceAdapter(this);
        deviceList.setAdapter(deviceAdapter);
        deviceList.setOnItemClickListener((parent, v, position, id) -> {
            BtLink.DeviceItem item = deviceAdapter.items.get(position);
            prefs.edit().putInt("lastMode", mode).apply();
            bt.setMode(mode);
            bt.connect(mode, item);
            addLog("连接设备: " + item.name + " / " + item.address + " / 模式="
                    + (mode == BtLink.MODE_BLE ? "BLE" : "SPP"));
            if (deviceDialog != null) {
                deviceDialog.dismiss();
            }
        });

        view.findViewById(R.id.btnRescan).setOnClickListener(v -> bt.scan(mode));
        view.findViewById(R.id.btnBtSettings).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception e) {
                toast("无法打开系统蓝牙设置");
            }
        });
        view.findViewById(R.id.btnDiagnostics).setOnClickListener(v -> showDiagnostics());

        deviceDialog = new AlertDialog.Builder(this)
                .setTitle("选择蓝牙设备")
                .setView(view)
                .setNegativeButton("关闭", null)
                .setOnDismissListener(d -> {
                    bt.stopScan();
                    deviceAdapter = null;
                })
                .create();
        deviceDialog.show();
        bt.setMode(mode);
        bt.scan(mode);
    }

    // ------------------------------------------------------------ BtLink.Callback
    @Override
    public void onStatus(String text, boolean isConnected) {
        runOnUiThread(() -> {
            statusText.setText(text);
            statusText.setTextColor(isConnected ? Color.parseColor("#2E7D32") : Color.parseColor("#607D8B"));
            btnConnect.setText(isConnected ? "已连接" : "连接设备");
            boolean wasConnected = connected;
            connected = isConnected;
            if (isConnected) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                BtLink.DeviceItem d = bt.lastDevice();
                if (d != null) {
                    prefs.edit()
                            .putString("lastAddress", d.address)
                            .putInt("lastMode", bt.mode())
                            .apply();
                }
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            if (isConnected != wasConnected) {
                addLog("状态: " + text);
            }
            refreshCount();
        });
    }

    @Override
    public void onData(byte[] data, int len) {
        parser.feed(data, len, parserListener);
    }

    @Override
    public void onLog(String line) {
        runOnUiThread(() -> addLog(line));
    }

    @Override
    public void onDevices(List<BtLink.DeviceItem> items) {
        if (deviceAdapter != null) {
            deviceAdapter.setItems(items);
        }
    }

    @Override
    public void onScanState(String text) {
        if (scanStateView != null) {
            scanStateView.setText(text);
        }
    }

    @Override
    public void onDiagnosticsChanged() {
        runOnUiThread(this::refreshDiagnostics);
    }

    private final CoordParser.Listener parserListener = new CoordParser.Listener() {
        @Override
        public void onPoint(double lat, double lon, double alt, boolean hasAlt,
                            double accuracy, String rawLine) {
            boolean added = TrackStore.INSTANCE.add(lat, lon, alt, hasAlt, accuracy, datum);
            double[] map = CoordSystems.toMap(datum, lat, lon);
            coordText.setText(formatCoord(map[0], map[1]));
            if (added) {
                StringBuilder sb = new StringBuilder("定位: ");
                if (CoordSystems.needsConversion(datum)) {
                    sb.append(String.format(Locale.US, "%.6f,%.6f", lat, lon))
                            .append(" [").append(CoordSystems.name(datum)).append("] → ");
                }
                sb.append(formatCoord(map[0], map[1]));
                if (hasAlt) {
                    sb.append(String.format(Locale.US, "  高度 %.1fm", alt));
                }
                if (accuracy > 0.5) {
                    sb.append(String.format(Locale.US, "  ±%.0fm", accuracy));
                }
                addLog(sb.toString());
            }
            mapView.onTrackChanged();
        }

        @Override
        public void onInvalid(String rawLine) {
            addLog("未识别: " + trim(rawLine, 120));
        }
    };

    // ------------------------------------------------------------ 坐标系
    private void updateDatumChip() {
        datumChip.setText(CoordSystems.chipLabel(datum));
    }

    private void showDatumDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_datum, null);
        RadioGroup group = view.findViewById(R.id.datumGroup);
        int[] ids = {R.id.datumGcj, R.id.datumWgs, R.id.datumBd, R.id.datumCgcs};
        int[] values = {CoordSystems.GCJ02, CoordSystems.WGS84, CoordSystems.BD09, CoordSystems.CGCS2000};
        int checkedId = ids[0];
        for (int i = 0; i < values.length; i++) {
            if (values[i] == datum) {
                checkedId = ids[i];
            }
        }
        group.check(checkedId);
        CheckBox redatum = view.findViewById(R.id.redatum);
        boolean hasTrack = TrackStore.INSTANCE.size() > 0;
        redatum.setEnabled(hasTrack);
        redatum.setChecked(hasTrack);
        redatum.setText(hasTrack
                ? "同时按新坐标系重算已有轨迹（" + TrackStore.INSTANCE.size() + " 点）"
                : "同时按新坐标系重算已有轨迹（暂无轨迹）");

        new AlertDialog.Builder(this)
                .setTitle("输入的坐标系")
                .setView(view)
                .setPositiveButton("确定", (d, w) -> {
                    int selected = CoordSystems.GCJ02;
                    int id = group.getCheckedRadioButtonId();
                    for (int i = 0; i < ids.length; i++) {
                        if (ids[i] == id) {
                            selected = values[i];
                        }
                    }
                    datum = selected;
                    prefs.edit().putInt("datum", datum).apply();
                    if (redatum.isChecked() && TrackStore.INSTANCE.size() > 0) {
                        TrackStore.INSTANCE.redatumAll(datum);
                        addLog("已按 " + CoordSystems.name(datum) + " 重算 " + TrackStore.INSTANCE.size() + " 个点");
                        double[] last = TrackStore.INSTANCE.lastLatLon();
                        if (last != null) {
                            coordText.setText(formatCoord(last[0], last[1]));
                        }
                    }
                    updateDatumChip();
                    addLog("输入坐标系: " + CoordSystems.name(datum) + " → 底图 GCJ-02");
                    toast("已切换：" + CoordSystems.chipLabel(datum));
                    mapView.invalidate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 诊断
    private void showDiagnostics() {
        bt.noteEnvironment(hasPermissions(), bt.isEnabled());
        diagnosticsText = new TextView(this);
        diagnosticsText.setTextSize(10f);
        diagnosticsText.setTypeface(Typeface.MONOSPACE);
        diagnosticsText.setTextColor(Color.parseColor("#263238"));
        ScrollView scroll = new ScrollView(this);
        int pad = dp(14);
        scroll.setPadding(pad, pad, pad, pad);
        scroll.addView(diagnosticsText);

        diagnosticsDialog = new AlertDialog.Builder(this)
                .setTitle("蓝牙连接诊断")
                .setView(scroll)
                .setPositiveButton("重连", (d, w) -> {
                    BtLink.DeviceItem item = bt.lastDevice();
                    if (item != null) {
                        bt.connect(bt.mode(), item);
                    } else {
                        toast("还没有选择过设备");
                    }
                })
                .setNeutralButton("重新订阅", (d, w) -> bt.resubscribe())
                .setNegativeButton("关闭", null)
                .create();
        diagnosticsDialog.show();
        refreshDiagnostics();
    }

    private void refreshDiagnostics() {
        if (diagnosticsDialog == null || !diagnosticsDialog.isShowing() || diagnosticsText == null) {
            return;
        }
        BtLink.Diagnostics d = bt.getDiagnostics();
        StringBuilder sb = new StringBuilder();
        sb.append("设备: ").append(d.deviceName.isEmpty() ? "(未选择)" : d.deviceName).append('\n');
        sb.append("地址: ").append(d.address.isEmpty() ? "-" : d.address).append('\n');
        sb.append("模式: ").append(d.mode == BtLink.MODE_BLE ? "BLE" : "经典 SPP").append('\n');
        sb.append(String.format(Locale.US, "RSSI: %d dBm   MTU: %d  状态: %s\n",
                d.rssi, d.mtu, d.connected ? "已连接" : "未连接"));
        sb.append("收包: ").append(d.bytesReceived).append(" 字节\n");
        sb.append("最近数据: ").append(d.lastData.isEmpty() ? "-" : d.lastData).append("\n\n");
        for (int i = 0; i < d.steps.length; i++) {
            String mark = d.stepState[i] == BtLink.STATE_OK ? "✓"
                    : d.stepState[i] == BtLink.STATE_FAIL ? "✗" : "·";
            sb.append(mark).append(' ').append(d.steps[i]);
            String detail = d.stepDetail[i];
            if (detail != null && !detail.isEmpty()) {
                sb.append("：").append(detail);
            }
            sb.append('\n');
        }
        sb.append('\n');
        if (d.chars.isEmpty()) {
            sb.append("(尚未发现服务/特征)\n");
        } else {
            sb.append("服务/特征：\n");
            String lastService = "";
            for (BtLink.GattCharInfo c : d.chars) {
                if (!c.serviceUuid.equals(lastService)) {
                    lastService = c.serviceUuid;
                    sb.append("  [S] ").append(lastService).append('\n');
                }
                sb.append("      ").append(c.charUuid)
                        .append("  ").append(c.propsLabel())
                        .append(c.subscribed ? "  已订阅" : "")
                        .append(c.bytes > 0 ? "  收" + c.bytes + "B" : "")
                        .append('\n');
            }
        }
        if (!d.lastError.isEmpty()) {
            sb.append("\n错误: ").append(d.lastError).append('\n');
        }
        sb.append("\n提示：PHY6222 透传模块的服务 UUID 由固件决定，\n");
        sb.append("App 会自动订阅所有 notify 特征；若没有数据，\n");
        sb.append("用“发送指令”发一次 AT 或换行符试试。\n");
        diagnosticsText.setText(sb.toString());
    }

    // ------------------------------------------------------------ 菜单
    private void showMoreMenu() {
        final List<String> items = new ArrayList<>();
        items.add("地图类型：" + TileSource.typeName(mapType));
        items.add("地图设置（在线源 / 天地图 tk / 清缓存）");
        items.add("坐标系：" + CoordSystems.chipLabel(datum));
        items.add("演示数据（画一条示例轨迹）");
        items.add("输入测试坐标");
        items.add(logVisible ? "隐藏日志面板" : "显示日志面板");
        items.add("蓝牙诊断");
        items.add("向模块发送指令…");
        items.add("指尖陀螺（用户模式）…");
        items.add("地图显示…");
        if (connected) {
            items.add("断开蓝牙连接");
        }
        items.add("关于 / 数据格式");
        final String[] labels = items.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("更多")
                .setItems(labels, (d, which) -> {
                    String label = labels[which];
                    if (label.startsWith("地图类型")) {
                        showMapTypeDialog();
                    } else if (label.startsWith("地图设置")) {
                        showMapSettingsDialog();
                    } else if (label.startsWith("坐标系")) {
                        showDatumDialog();
                    } else if (label.startsWith("演示数据")) {
                        injectDemo();
                    } else if (label.startsWith("输入测试坐标")) {
                        showInputDialog();
                    } else if (label.contains("日志面板")) {
                        toggleLog();
                    } else if (label.startsWith("蓝牙诊断")) {
                        showDiagnostics();
                    } else if (label.startsWith("向模块发送")) {
                        showSendDialog();
                    } else if (label.startsWith("指尖陀螺")) {
                        showSpinnerDialog();
                    } else if (label.startsWith("地图显示")) {
                        showMapDisplayDialog();
                    } else if (label.startsWith("断开蓝牙")) {
                        bt.disconnect();
                        addLog("手动断开蓝牙");
                    } else {
                        showAbout();
                    }
                })
                .show();
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) {
            return false;
        }
    }

    private void applyMapType(boolean announce) {
        mapView.setMapType(mapType, tileProvider, tdtKey);
        if (mapChip != null) {
            mapChip.setText("地图：" + TileSource.typeName(mapType));
        }
        if (announce && mapType != TileSource.TYPE_VECTOR && !hasNetwork()) {
            toast("该图层需要网络；标准底图仍可离线使用");
        }
    }

    private void showMapTypeDialog() {
        final String[] names = {
                "标准（离线县界）",
                "街道（在线）",
                "卫星（在线）",
                "卫星+路网（在线）",
                "地形（在线）"
        };
        final int[] types = {
                TileSource.TYPE_VECTOR,
                TileSource.TYPE_STREET,
                TileSource.TYPE_SATELLITE,
                TileSource.TYPE_HYBRID,
                TileSource.TYPE_TERRAIN
        };
        int checked = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == mapType) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("地图类型")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    mapType = types[which];
                    prefs.edit().putInt("mapType", mapType).apply();
                    applyMapType(true);
                    d.dismiss();
                })
                .setNegativeButton("关闭", null)
                .setNeutralButton("说明", (d, w) -> new AlertDialog.Builder(this)
                        .setTitle("地图类型说明")
                        .setMessage("标准：内置省市县边界，完全离线，约 5 米/像素。\n\n"
                                + "街道/卫星/卫星+路网/地形：在线瓦片，需要网络；"
                                + "卫星+路网在影像上叠加道路和地名。\n\n"
                                + "在线瓦片会缓存到手机，已看过的区域断网也能显示。")
                        .setPositiveButton("知道了", null)
                        .show())
                .show();
    }

    private void showMapSettingsDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView label = new TextView(this);
        label.setText("瓦片源");
        label.setTextSize(13);
        label.setTextColor(0xFF37474F);
        box.addView(label);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        RadioButton amap = new RadioButton(this);
        amap.setText("高德（推荐，免 key，国内道路最全）");
        amap.setId(1001);
        RadioButton tdt = new RadioButton(this);
        tdt.setText("天地图（需要免费 tk，官方合规）");
        tdt.setId(1002);
        RadioButton esri = new RadioButton(this);
        esri.setText("Esri（免 key，国内道路只到低缩放）");
        esri.setId(1003);
        group.addView(amap);
        group.addView(tdt);
        group.addView(esri);
        int checkedId = tileProvider == TileSource.PROVIDER_TIANDITU ? 1002
                : tileProvider == TileSource.PROVIDER_ESRI ? 1003 : 1001;
        group.check(checkedId);
        box.addView(group);

        EditText key = new EditText(this);
        key.setHint("天地图 tk（只在选天地图时使用）");
        key.setText(tdtKey);
        key.setSingleLine(true);
        box.addView(key);

        TextView cache = new TextView(this);
        cache.setTextSize(12);
        cache.setTextColor(0xFF607D8B);
        double mb = mapView.tileCacheBytes() / 1048576.0;
        cache.setText(String.format(Locale.US, "瓦片缓存：%.1f MB（上限约 200MB）", mb));
        box.addView(cache);

        new AlertDialog.Builder(this)
                .setTitle("地图设置")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    int checked = group.getCheckedRadioButtonId();
                    tileProvider = checked == 1002 ? TileSource.PROVIDER_TIANDITU
                            : checked == 1003 ? TileSource.PROVIDER_ESRI
                            : TileSource.PROVIDER_AMAP;
                    tdtKey = key.getText().toString().trim();
                    prefs.edit()
                            .putInt("tileProvider", tileProvider)
                            .putString("tdtKey", tdtKey)
                            .apply();
                    applyMapType(true);
                    if (tileProvider == TileSource.PROVIDER_TIANDITU && tdtKey.isEmpty()) {
                        toast("未填天地图 tk，已自动回退高德");
                    }
                })
                .setNeutralButton("清除缓存", (d, w) -> {
                    mapView.clearTileCache();
                    toast("瓦片缓存已清除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMapDisplayDialog() {
        String[] items = {"显示市界", "显示县界", "显示地名", "显示经纬网", "显示定位精度圈", "显示坐标气泡"};
        boolean[] checked = {showCity, showCounty, showLabels, showGrid, showAccuracy, showBubble};
        new AlertDialog.Builder(this)
                .setTitle("地图显示")
                .setMultiChoiceItems(items, checked, (d, which, isChecked) -> {
                    switch (which) {
                        case 0:
                            showCity = isChecked;
                            break;
                        case 1:
                            showCounty = isChecked;
                            break;
                        case 2:
                            showLabels = isChecked;
                            break;
                        case 3:
                            showGrid = isChecked;
                            break;
                        case 4:
                            showAccuracy = isChecked;
                            break;
                        default:
                            showBubble = isChecked;
                            break;
                    }
                    prefs.edit()
                            .putBoolean("showCity", showCity)
                            .putBoolean("showCounty", showCounty)
                            .putBoolean("showLabels", showLabels)
                            .putBoolean("showGrid", showGrid)
                            .putBoolean("showAccuracy", showAccuracy)
                            .putBoolean("showBubble", showBubble)
                            .apply();
                    applyDisplaySettings();
                })
                .setPositiveButton("完成", null)
                .show();
    }

    private void applyDisplaySettings() {
        mapView.setCityVisible(showCity);
        mapView.setCountyVisible(showCounty);
        mapView.setLabelsVisible(showLabels);
        mapView.setGridVisible(showGrid);
        mapView.setAccuracyVisible(showAccuracy);
        mapView.setBubbleVisible(showBubble);
    }

    private void showInputDialog() {
        EditText et = new EditText(this);
        et.setHint("例如 31.2304,121.4737");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(3);
        int pad = dp(16);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("输入测试坐标（按当前坐标系）")
                .setView(wrap)
                .setPositiveButton("发送到地图", (d, w) -> {
                    String text = et.getText().toString();
                    if (!text.trim().isEmpty()) {
                        feedDemoPoints(text);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSendDialog() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        wrap.setPadding(pad, pad / 2, pad, 0);
        EditText et = new EditText(this);
        et.setHint("文本指令，例如 AT 或 \\r\\n");
        et.setSingleLine(true);
        CheckBox hex = new CheckBox(this);
        hex.setText("按 HEX 发送（如 0D0A）");
        wrap.addView(et);
        wrap.addView(hex);
        new AlertDialog.Builder(this)
                .setTitle("发送给蓝牙模块")
                .setView(wrap)
                .setPositiveButton("发送", (d, w) -> {
                    String s = et.getText().toString().replace("\\r", "\r").replace("\\n", "\n");
                    if (s.isEmpty()) {
                        return;
                    }
                    if (hex.isChecked()) {
                        bt.sendHex(s);
                    } else {
                        bt.send(s);
                    }
                    addLog("发送: " + (hex.isChecked() ? "HEX " + s : s));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSpinnerDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        TextView status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(0xFF546E7A);
        status.setText(bt.isConnected()
                ? (bt.isSpinnerReady() ? "已连接，FFE1 通知已订阅"
                : "已连接，等待服务/订阅完成…")
                : "未连接：请先在“连接设备”里连接陀螺（BLE）");
        box.addView(status);

        TextView frameLabel = new TextView(this);
        frameLabel.setText("幅号（1-20，每幅最多 20 个字，GB2312）");
        frameLabel.setTextSize(12);
        frameLabel.setPadding(0, dp(10), 0, dp(4));
        box.addView(frameLabel);

        final Spinner frame = new Spinner(this);
        String[] frames = new String[SpinnerProtocol.MAX_FRAMES];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = "第 " + (i + 1) + " 幅";
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, frames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        frame.setAdapter(adapter);
        box.addView(frame);

        final EditText text = new EditText(this);
        text.setHint("例如 1234567，或 20 个以内的中文/英文");
        text.setSingleLine(true);
        box.addView(text);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(8), 0, 0);

        Button send = new Button(this);
        send.setText("发送本幅");
        send.setTextSize(11);
        send.setMinWidth(0);
        send.setPadding(dp(8), 0, dp(8), 0);
        buttons.addView(send);

        Button test = new Button(this);
        test.setText("测试 T01");
        test.setTextSize(11);
        test.setMinWidth(0);
        test.setPadding(dp(8), 0, dp(8), 0);
        buttons.addView(test);

        Button del = new Button(this);
        del.setText("DEL ALL");
        del.setTextSize(11);
        del.setMinWidth(0);
        del.setPadding(dp(8), 0, dp(8), 0);
        buttons.addView(del);
        box.addView(buttons);

        TextView result = new TextView(this);
        result.setTextSize(11);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextColor(0xFF263238);
        result.setText("等待指令…");
        ScrollView resultScroll = new ScrollView(this);
        resultScroll.setPadding(0, dp(8), 0, 0);
        resultScroll.addView(result);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170));
        resultScroll.setLayoutParams(rlp);
        box.addView(resultScroll);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("指尖陀螺（用户模式，红灯）")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();

        final BtLink.SpinnerCallback cb = (ok, message, tx, rx, writeType, writeStatus) ->
                runOnUiThread(() -> {
                    String now = new java.text.SimpleDateFormat("HH:mm:ss",
                            Locale.US).format(new java.util.Date());
                    result.append("[" + now + "] " + (ok ? "成功" : "失败") + "\n");
                    result.append("TX " + SpinnerProtocol.hex(tx) + "  「"
                            + SpinnerProtocol.decode(tx).trim() + "」\n");
                    result.append("RX " + SpinnerProtocol.hex(rx) + "  「"
                            + SpinnerProtocol.decode(rx).trim() + "」\n");
                    result.append(message + "  (writeType=" + writeType
                            + ", status=" + writeStatus + ")\n\n");
                    status.setText(bt.isSpinnerReady()
                            ? "已连接，FFE1 通知已订阅"
                            : "连接/订阅已断开");
                });

        send.setOnClickListener(v -> {
            int index = frame.getSelectedItemPosition() + 1;
            try {
                byte[] payload = SpinnerProtocol.encodeFrame(index,
                        text.getText().toString());
                result.append("\n→ 第 " + index + " 幅，等待回包…\n");
                bt.sendSpinner(payload, cb);
            } catch (IllegalArgumentException e) {
                toast(e.getMessage());
            }
        });

        test.setOnClickListener(v -> {
            frame.setSelection(0);
            text.setText("1234567");
            try {
                result.append("\n→ 测试 T01=1234567，等待回包…\n");
                bt.sendSpinner(SpinnerProtocol.encodeFrame(1, "1234567"), cb);
            } catch (IllegalArgumentException e) {
                toast(e.getMessage());
            }
        });

        del.setOnClickListener(v -> {
            result.append("\n→ DEL ALL，等待回包…\n");
            bt.sendSpinner(SpinnerProtocol.encodeDelAll(), cb);
        });

        dialog.show();
    }

    private void showAbout() {
        String msg = "中国地图蓝牙轨迹  v1.2.2\n\n"
                + "· 标准底图内置到县级（GCJ-02），完全离线\n"
                + "· 街道/卫星/卫星+路网/地形为在线瓦片，需要网络\n"
                + "· 输入坐标系可选：GCJ-02 / WGS-84 / BD-09 / CGCS2000\n"
                + "· 蓝牙：经典 SPP + BLE GATT（自动枚举并订阅所有通知特征）\n"
                + "· 坐标格式：31.2304,121.4737 / lat:.. lon:.. / JSON / NMEA GGA·RMC·GLL\n"
                + "· 支持 31.2304N,121.4737E；NMEA GGA 的 HDOP 会换算成精度圈\n\n"
                + "底图：DataV.GeoAtlas（省/市/县）；在线瓦片：高德/Esri/天地图。\n"
                + "高德瓦片为免 key 接口，仅建议个人测试使用。\n"
                + "CGCS2000 与 WGS-84 差异 <10cm，按 WGS-84 处理。\n"
                + "轨迹仅保存在本次运行内存中，退出应用后清空。";
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showPickedCoordinate(double lat, double lon) {
        String text = formatCoord(lat, lon);
        new AlertDialog.Builder(this)
                .setTitle("该点经纬度")
                .setMessage(text)
                .setPositiveButton("复制", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("坐标", text));
                    toast("已复制");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ------------------------------------------------------------ 演示/测试
    private void injectDemo() {
        double[][] pts = {
                {39.9042, 116.4074}, {39.9142, 116.4200}, {39.9250, 116.4340},
                {39.9330, 116.4520}, {39.9350, 116.4700}, {39.9280, 116.4880},
                {39.9150, 116.5000}, {39.9000, 116.5070}, {39.8850, 116.5040},
                {39.8720, 116.4930}, {39.8630, 116.4780}, {39.8580, 116.4620},
                {39.8590, 116.4440}, {39.8660, 116.4300}, {39.8760, 116.4180},
                {39.8880, 116.4100}, {39.8990, 116.4060}, {39.9042, 116.4074}
        };
        StringBuilder sb = new StringBuilder();
        for (double[] p : pts) {
            sb.append(String.format(Locale.US, "%.6f,%.6f\n", p[0], p[1]));
        }
        addLog("注入演示轨迹 " + pts.length + " 点（" + CoordSystems.name(datum) + "）");
        feedDemoPoints(sb.toString());
    }

    private void feedDemoPoints(String text) {
        String[] lines = text.split("[\\n;；|/]+");
        int count = 0;
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            byte[] bytes = (s + "\n").getBytes(StandardCharsets.UTF_8);
            parser.feed(bytes, bytes.length, parserListener);
            count++;
        }
        if (count == 0) {
            toast("没有有效坐标");
        }
    }

    // ------------------------------------------------------------ UI
    private void refreshCount() {
        int n = TrackStore.INSTANCE.size();
        countText.setText(String.format(Locale.US, "%d 点%s", n, connected ? " · 已连接" : ""));
    }

    private void updateFollowButton() {
        btnFollow.setText(mapView.isFollow() ? R.string.follow_on : R.string.follow_off);
    }

    private void toggleLog() {
        logVisible = !logVisible;
        logPanel.setVisibility(logVisible ? View.VISIBLE : View.GONE);
        btnLog.setText(logVisible ? "收起日志" : "日志");
        if (logVisible) {
            logPanel.post(() -> logPanel.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void addLog(String line) {
        long now = System.currentTimeMillis();
        String stamp = String.format(Locale.US, "%tH:%tM:%tS ", now, now, now);
        if (logLines > 250) {
            int cut = logBuffer.indexOf("\n");
            if (cut > 0) {
                logBuffer.delete(0, cut + 1);
            }
        } else {
            logLines++;
        }
        logBuffer.append(stamp).append(line).append('\n');
        if (logText != null) {
            logText.setText(logBuffer.toString());
            if (logVisible) {
                logPanel.post(() -> logPanel.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空轨迹")
                .setMessage("将删除本次会话收集的全部 " + TrackStore.INSTANCE.size() + " 个点，无法恢复。")
                .setPositiveButton("清空", (d, w) -> {
                    TrackStore.INSTANCE.clear();
                    parser.reset();
                    coordText.setText(R.string.waiting);
                    mapView.invalidate();
                    refreshCount();
                    addLog("轨迹已清空");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String formatCoord(double lat, double lon) {
        return String.format(Locale.US, "%.7f, %.7f", lat, lon);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        TrackStore.INSTANCE.removeListener(trackListener);
        if (bt != null) {
            bt.release();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ 设备列表
    private static final class DeviceAdapter extends BaseAdapter {
        private final Context context;
        final List<BtLink.DeviceItem> items = new ArrayList<>();

        DeviceAdapter(Context context) {
            this.context = context;
        }

        void setItems(List<BtLink.DeviceItem> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView name;
            TextView detail;
            if (convertView == null) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(10);
                row.setPadding(pad, pad / 2, pad, pad / 2);
                name = new TextView(context);
                name.setTextSize(14);
                name.setTextColor(Color.parseColor("#263238"));
                detail = new TextView(context);
                detail.setTextSize(11);
                detail.setTextColor(Color.parseColor("#78909C"));
                row.addView(name);
                row.addView(detail);
            } else {
                row = (LinearLayout) convertView;
                name = (TextView) row.getChildAt(0);
                detail = (TextView) row.getChildAt(1);
            }
            BtLink.DeviceItem item = items.get(position);
            name.setText(item.name);
            String d = item.address + (item.bonded ? "  · 已配对" : "")
                    + (item.rssi > -127 ? "  · " + item.rssi + " dBm" : "");
            detail.setText(d);
            return row;
        }

        private int dp(int v) {
            return (int) (v * context.getResources().getDisplayMetrics().density);
        }
    }
}
