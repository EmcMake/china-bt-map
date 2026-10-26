package com.btmap.china;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
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
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import com.btmap.china.track.LocationHelper;
import com.btmap.china.track.LocationPayload;
import com.btmap.china.track.LocationProtocol;
import com.btmap.china.track.TrackStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements BtLink.Callback {
    private static final int REQ_PERMS = 2001;
    private static final int REQ_ENABLE = 2002;
    private static final int REQ_LOCATION = 2003;

    private static final String[] PROTOCOL_PRESETS = {
            "{lat},{lon}",
            "LAT={lat},LON={lon}",
            "{\"lat\":{lat},\"lon\":{lon}}",
            "{lat},{lon},{acc}"
    };
    private static final String[] PROTOCOL_NAMES = {
            "lat,lon", "LAT=..,LON=..", "JSON", "lat,lon,acc", "自定义"
    };

    private ChinaMapView mapView;
    private TextView titleText;
    private TextView statusText;
    private TextView coordText;
    private TextView countText;
    private TextView datumChip;
    private TextView mapChip;
    private FrameLayout floatSwitch;
    private View floatBall;
    private View floatCard;
    private TextView floatBadge;
    private TextView floatWorld;
    private TextView floatChina;
    private TextView floatZh;
    private TextView floatEn;
    private boolean floatHidden;
    private boolean floatExpanded;
    private float floatDownRawX;
    private float floatDownRawY;
    private float floatStartX;
    private float floatStartY;
    private boolean floatDragging;
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
    private int viewMode = ChinaMapView.VIEW_WORLD;
    private int mapLanguage = ChinaMapView.LANG_ZH;
    private int tileProvider = TileSource.PROVIDER_ESRI;
    private String tdtKey = "";

    private int protocolPreset;
    private String protocolTemplate = LocationProtocol.DEFAULT_TEMPLATE;
    private int protocolEncoding = LocationProtocol.ENC_UTF8;
    private int protocolPrecision = 6;
    private int protocolChecksum = LocationProtocol.CK_NONE;
    private boolean protocolCrlf = true;
    private boolean connected;
    private boolean pendingConnect;
    private boolean logVisible;

    private boolean showCity = true;
    private boolean showCounty = true;
    private boolean showLabels = true;
    private boolean showGrid = true;
    private boolean showAccuracy = true;
    private boolean showBubble = true;
    private boolean showPhoneMarker = true;
    private boolean continuousLocation = true;
    private boolean locationPermissionAsked;
    private boolean pendingLocationDialog;
    private boolean locationAutoCentered;
    private long lastPhonePersist;

    private LocationHelper locationHelper;

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
        viewMode = prefs.getInt("viewMode", ChinaMapView.VIEW_WORLD);
        mapLanguage = prefs.getInt("mapLanguage", ChinaMapView.LANG_ZH);
        tileProvider = prefs.getInt("tileProvider", TileSource.PROVIDER_AMAP);
        tdtKey = prefs.getString("tdtKey", "");
        protocolPreset = prefs.getInt("protocolPreset", 0);
        protocolTemplate = prefs.getString("protocolTemplate",
                LocationProtocol.DEFAULT_TEMPLATE);
        protocolEncoding = prefs.getInt("protocolEncoding", LocationProtocol.ENC_UTF8);
        protocolPrecision = prefs.getInt("protocolPrecision", 6);
        protocolChecksum = prefs.getInt("protocolChecksum", LocationProtocol.CK_NONE);
        protocolCrlf = prefs.getBoolean("protocolCrlf", true);
        showCity = prefs.getBoolean("showCity", true);
        showCounty = prefs.getBoolean("showCounty", true);
        showLabels = prefs.getBoolean("showLabels", true);
        showGrid = prefs.getBoolean("showGrid", true);
        showAccuracy = prefs.getBoolean("showAccuracy", true);
        showBubble = prefs.getBoolean("showBubble", true);
        showPhoneMarker = prefs.getBoolean("showPhoneMarker", true);
        continuousLocation = prefs.getBoolean("continuousLocation", true);

        mapView = findViewById(R.id.map);
        titleText = findViewById(R.id.title);
        locationHelper = new LocationHelper(this);
        locationHelper.addListener(locationListener);
        restorePhoneLocation();
        statusText = findViewById(R.id.status);
        coordText = findViewById(R.id.coordText);
        countText = findViewById(R.id.countText);
        datumChip = findViewById(R.id.datumChip);
        mapChip = findViewById(R.id.mapChip);
        floatSwitch = findViewById(R.id.floatSwitch);
        floatBall = findViewById(R.id.floatBall);
        floatCard = findViewById(R.id.floatCard);
        floatBadge = findViewById(R.id.floatBadge);
        floatWorld = findViewById(R.id.floatWorld);
        floatChina = findViewById(R.id.floatChina);
        floatZh = findViewById(R.id.floatZh);
        floatEn = findViewById(R.id.floatEn);
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
        mapView.setViewMode(viewMode);
        mapView.setMapLanguage(mapLanguage);
        updateTitle();
        applyDisplaySettings();
        applyMapType(false);

        bt = new BtLink(this, this);

        btnConnect.setOnClickListener(v -> requestConnect());
        btnMore.setOnClickListener(v -> showMoreMenu());
        floatWorld.setOnClickListener(v -> selectViewMode(ChinaMapView.VIEW_WORLD));
        floatChina.setOnClickListener(v -> selectViewMode(ChinaMapView.VIEW_CHINA));
        floatZh.setOnClickListener(v -> selectMapLanguage(ChinaMapView.LANG_ZH));
        floatEn.setOnClickListener(v -> selectMapLanguage(ChinaMapView.LANG_EN));
        findViewById(R.id.floatCollapse).setOnClickListener(v -> collapseFloat());
        findViewById(R.id.floatHide).setOnClickListener(v -> hideFloat());
        bindFloatDrag(floatBall, true);
        bindFloatDrag(floatCard, false);
        setupFloatSwitch();
        datumChip.setOnClickListener(v -> showDatumDialog());
        mapChip.setOnClickListener(v -> showMapTypeDialog());
        findViewById(R.id.btnZoomIn).setOnClickListener(v -> mapView.zoomBy(1.8));
        findViewById(R.id.btnZoomOut).setOnClickListener(v -> mapView.zoomBy(1 / 1.8));
        findViewById(R.id.btnFit).setOnClickListener(v -> {
            mapView.setFollow(false);
            updateFollowButton();
            mapView.fitForView();
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
        updateFloatSwitch();
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
        if (intent.getBooleanExtra("start_location", false)) {
            continuousLocation = true;
            showPhoneMarker = true;
            prefs.edit().putBoolean("continuousLocation", true)
                    .putBoolean("showPhoneMarker", true).apply();
            if (locationHelper.hasPermission()) {
                locationAutoCentered = false;
                locationHelper.startTracking(2000, 1);
                mapView.setPhoneTracking(true);
            } else {
                pendingLocationDialog = false;
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            }
        }
        if (intent.getBooleanExtra("clear_location", false)) {
            mapView.clearPhoneLocation();
            showPhoneMarker = false;
            prefs.edit().putBoolean("showPhoneMarker", false)
                    .remove("phoneLat").remove("phoneLon")
                    .remove("phoneAcc").remove("phoneProvider")
                    .remove("phoneSource").remove("phoneTime").apply();
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
        if (requestCode == REQ_LOCATION) {
            boolean granted = hasLocationPermission();
            boolean openDialog = pendingLocationDialog;
            pendingLocationDialog = false;
            locationPermissionAsked = true;
            if (granted) {
                if (openDialog) {
                    showLocationSendDialog();
                } else if (connected && continuousLocation) {
                    startContinuousLocation();
                }
            } else {
                toast("需要定位权限才能获取当前经纬度");
                addLog("定位权限被拒绝：请在系统设置 → 应用 → 权限中允许“位置”");
            }
            return;
        }
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
                locationAutoCentered = false;
                startContinuousLocation();
                BtLink.DeviceItem d = bt.lastDevice();
                if (d != null) {
                    prefs.edit()
                            .putString("lastAddress", d.address)
                            .putInt("lastMode", bt.mode())
                            .apply();
                }
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (locationHelper != null) {
                    locationHelper.stopTracking();
                }
                if (mapView != null) {
                    mapView.setPhoneTracking(false);
                }
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
        sb.append("TX 特征: ").append(bt.uartTxUuid()).append('\n');
        sb.append("RX 特征: ").append(bt.uartRxUuid()).append('\n');
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
    private static final class MoreItem {
        final int icon;
        final String title;
        final String subtitle;
        final boolean selected;
        final boolean danger;
        final Runnable action;

        MoreItem(int icon, String title, String subtitle, boolean selected,
                 boolean danger, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.selected = selected;
            this.danger = danger;
            this.action = action;
        }
    }

    private void showMoreMenu() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final View root = LayoutInflater.from(this)
                .inflate(R.layout.dialog_more, null, false);
        dialog.setContentView(root);

        TextView subtitle = root.findViewById(R.id.moreSubtitle);
        subtitle.setText((viewMode == ChinaMapView.VIEW_WORLD ? "世界" : "中国")
                + " · " + (mapLanguage == ChinaMapView.LANG_ZH ? "中文" : "EN")
                + " · " + TileSource.typeName(mapType));

        View close = root.findViewById(R.id.moreClose);
        close.setOnClickListener(v -> dialog.dismiss());

        LinearLayout content = root.findViewById(R.id.moreContent);
        addMoreSection(content, "地图");
        addMoreRow(content, new MoreItem(R.drawable.ic_map, "地图类型",
                TileSource.typeName(mapType), false, false,
                this::showMapTypeDialog), new MoreItem(R.drawable.ic_settings, "地图设置",
                "在线源 / tk / 缓存", false, false,
                this::showMapSettingsDialog), dialog);
        addMoreRow(content, new MoreItem(R.drawable.ic_layers, "地图显示",
                "图层 / 网格 / 标注", false, false,
                this::showMapDisplayDialog), new MoreItem(R.drawable.ic_globe, "坐标系",
                CoordSystems.chipLabel(datum), false, false,
                this::showDatumDialog), dialog);

        addMoreSection(content, "定位与协议");
        addMoreRow(content, new MoreItem(R.drawable.ic_pin, "定位并发送",
                "手机位置 / 蓝牙点", false, false,
                this::showLocationSendDialog), new MoreItem(R.drawable.ic_protocol, "定位协议",
                "模板、编码、校验", false, false,
                () -> showProtocolDialog(null)), dialog);
        addMoreRow(content, new MoreItem(R.drawable.ic_edit, "输入测试坐标",
                "手动输入经纬度", false, false,
                this::showInputDialog), new MoreItem(R.drawable.ic_demo, "演示数据",
                "画一条示例轨迹", false, false,
                this::injectDemo), dialog);

        addMoreSection(content, "工具");
        addMoreRow(content, new MoreItem(R.drawable.ic_log, "日志面板",
                logVisible ? "当前显示" : "当前隐藏", logVisible, false,
                this::toggleLog), new MoreItem(R.drawable.ic_bluetooth, "蓝牙诊断",
                "服务 / 特征 / 错误", false, false,
                this::showDiagnostics), dialog);

        addMoreSection(content, "界面");
        addMoreFull(content, new MoreItem(R.drawable.ic_globe, "快捷切换球",
                floatHidden ? "当前隐藏" : "当前显示", !floatHidden, false,
                this::toggleFloatVisibility), dialog);

        if (connected) {
            addMoreSection(content, "连接");
            addMoreFull(content, new MoreItem(R.drawable.ic_power, "断开蓝牙连接",
                    "停止连接，轨迹与红点保留", false, true, () -> {
                        bt.disconnect();
                        addLog("手动断开蓝牙");
                    }), dialog);
        }

        addMoreSection(content, "关于");
        addMoreFull(content, new MoreItem(R.drawable.ic_info, "关于 / 数据格式",
                "版本、坐标格式、数据来源", false, false, this::showAbout), dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.45f;
            window.setAttributes(attrs);
            window.setWindowAnimations(android.R.style.Animation_Dialog);
        }
        dialog.setOnShowListener(d -> {
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();

        final View sheet = root.findViewById(R.id.moreSheet);
        final ScrollView scroll = root.findViewById(R.id.moreScroll);
        final int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.80f);
        final int[] tries = {0};
        final Runnable fitSheet = new Runnable() {
            @Override
            public void run() {
                int h = sheet.getHeight();
                if (h <= 0 && tries[0] < 4) {
                    tries[0]++;
                    root.postDelayed(this, 60);
                    return;
                }
                if (h + dp(32) > max) {
                    ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                    lp.height = max - dp(32);
                    sheet.setLayoutParams(lp);
                    LinearLayout.LayoutParams slp =
                            (LinearLayout.LayoutParams) scroll.getLayoutParams();
                    slp.height = 0;
                    slp.weight = 1;
                    scroll.setLayoutParams(slp);
                    Window w = dialog.getWindow();
                    if (w != null) {
                        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, max);
                    }
                }
            }
        };
        root.postDelayed(fitSheet, 60);
    }

    private void addMoreSection(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(Color.parseColor("#90A4AE"));
        tv.setTextSize(10.5f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(6), dp(10), dp(6), dp(5));
        parent.addView(tv);
    }

    private void addMoreRow(LinearLayout parent, MoreItem a, MoreItem b, Dialog dialog) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View va = buildMoreItem(a, dialog);
        row.addView(va, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(dp(6), 1));
        if (b != null) {
            View vb = buildMoreItem(b, dialog);
            row.addView(vb, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            View empty = new View(this);
            row.addView(empty, new LinearLayout.LayoutParams(0, 1, 1f));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        parent.addView(row, lp);
    }

    private void addMoreFull(LinearLayout parent, MoreItem item, Dialog dialog) {
        View v = buildMoreItem(item, dialog);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        parent.addView(v, lp);
    }

    private View buildMoreItem(MoreItem item, Dialog dialog) {
        View v = LayoutInflater.from(this).inflate(R.layout.more_item, null, false);
        ImageView icon = v.findViewById(R.id.moreItemIcon);
        TextView title = v.findViewById(R.id.moreItemTitle);
        TextView subtitle = v.findViewById(R.id.moreItemSubtitle);
        ImageView chevron = v.findViewById(R.id.moreItemChevron);

        icon.setImageResource(item.icon);
        title.setText(item.title);
        if (item.subtitle.isEmpty()) {
            subtitle.setVisibility(View.GONE);
        } else {
            subtitle.setText(item.subtitle);
            subtitle.setVisibility(View.VISIBLE);
        }
        v.setSelected(item.selected);

        int accent = item.danger ? Color.parseColor("#C62828")
                : (item.selected ? Color.parseColor("#0D47A1") : Color.parseColor("#263238"));
        title.setTextColor(accent);
        if (item.danger) {
            icon.setColorFilter(accent);
            chevron.setColorFilter(Color.parseColor("#EF9A9A"));
        } else if (item.selected) {
            icon.setColorFilter(accent);
            chevron.setColorFilter(Color.parseColor("#1565C0"));
        }
        v.setOnClickListener(x -> {
            dialog.dismiss();
            new Handler(Looper.getMainLooper()).postDelayed(item.action, 100);
        });
        return v;
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

    private void updateTitle() {
        if (titleText != null) {
            titleText.setText(viewMode == ChinaMapView.VIEW_WORLD
                    ? "世界地图 · 蓝牙轨迹" : "中国地图 · 蓝牙轨迹");
        }
    }

    private void selectViewMode(int mode) {
        if (viewMode == mode) {
            return;
        }
        viewMode = mode;
        prefs.edit().putInt("viewMode", mode).apply();
        mapView.setViewMode(mode);
        updateTitle();
        applyMapType(false);
        updateFloatSwitch();
        toast(mode == ChinaMapView.VIEW_WORLD ? "世界视图" : "中国视图");
    }

    private void selectMapLanguage(int lang) {
        if (mapLanguage == lang) {
            return;
        }
        mapLanguage = lang;
        prefs.edit().putInt("mapLanguage", lang).apply();
        mapView.setMapLanguage(lang);
        updateMapChip();
        updateFloatSwitch();
        toast(lang == ChinaMapView.LANG_ZH ? "地图：中文" : "Map: English");
    }

    private void setupFloatSwitch() {
        floatHidden = prefs.getBoolean("floatHidden", false);
        floatSwitch.post(() -> {
            if (floatHidden) {
                floatSwitch.setVisibility(View.GONE);
                return;
            }
            floatSwitch.setVisibility(View.VISIBLE);
            float x = prefs.getFloat("floatX", -1f);
            float y = prefs.getFloat("floatY", -1f);
            if (x < 0 || y < 0) {
                x = dp(12);
                y = findViewById(R.id.topBar).getBottom() + dp(16);
            }
            floatSwitch.setX(x);
            floatSwitch.setY(y);
            floatCard.setVisibility(View.GONE);
            floatBall.setVisibility(View.VISIBLE);
            floatExpanded = false;
            clampFloat();
            updateFloatSwitch();
        });
    }

    private void updateFloatSwitch() {
        styleQuick(floatWorld, viewMode == ChinaMapView.VIEW_WORLD);
        styleQuick(floatChina, viewMode == ChinaMapView.VIEW_CHINA);
        styleQuick(floatZh, mapLanguage == ChinaMapView.LANG_ZH);
        styleQuick(floatEn, mapLanguage == ChinaMapView.LANG_EN);
        if (floatBadge != null) {
            floatBadge.setText(mapLanguage == ChinaMapView.LANG_ZH ? "中" : "EN");
        }
        if (floatBall != null) {
            floatBall.setContentDescription("地图快捷切换："
                    + (viewMode == ChinaMapView.VIEW_WORLD ? "世界" : "中国")
                    + " · " + (mapLanguage == ChinaMapView.LANG_ZH ? "中文" : "English"));
        }
    }

    private void expandFloat() {
        if (floatHidden) {
            return;
        }
        floatExpanded = true;
        floatBall.setVisibility(View.GONE);
        floatCard.setVisibility(View.VISIBLE);
        floatCard.setAlpha(0f);
        floatCard.animate().alpha(1f).setDuration(120).start();
        floatSwitch.post(this::clampFloat);
    }

    private void collapseFloat() {
        floatExpanded = false;
        floatCard.setVisibility(View.GONE);
        floatBall.setVisibility(View.VISIBLE);
        floatSwitch.post(this::clampFloat);
    }

    private void hideFloat() {
        floatHidden = true;
        prefs.edit().putBoolean("floatHidden", true).apply();
        floatSwitch.setVisibility(View.GONE);
        toast("快捷球已隐藏，可在“更多”中恢复");
    }

    private void showFloat() {
        floatHidden = false;
        prefs.edit().putBoolean("floatHidden", false).apply();
        floatSwitch.setVisibility(View.VISIBLE);
        floatSwitch.setAlpha(1f);
        collapseFloat();
        if (floatSwitch.getX() <= 0 && floatSwitch.getY() <= 0) {
            floatSwitch.setX(dp(12));
            floatSwitch.setY(findViewById(R.id.topBar).getBottom() + dp(16));
        }
        clampFloat();
        updateFloatSwitch();
    }

    private void toggleFloatVisibility() {
        if (floatHidden) {
            showFloat();
            toast("快捷球已显示");
        } else {
            hideFloat();
        }
    }

    private void bindFloatDrag(View v, boolean tapToExpand) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    floatDownRawX = event.getRawX();
                    floatDownRawY = event.getRawY();
                    floatStartX = floatSwitch.getX();
                    floatStartY = floatSwitch.getY();
                    floatDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - floatDownRawX;
                    float dy = event.getRawY() - floatDownRawY;
                    if (!floatDragging && Math.hypot(dx, dy) > dp(6)) {
                        floatDragging = true;
                    }
                    if (floatDragging) {
                        floatSwitch.setX(floatStartX + dx);
                        floatSwitch.setY(floatStartY + dy);
                        clampFloat();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (floatDragging) {
                        clampFloat();
                        prefs.edit().putFloat("floatX", floatSwitch.getX())
                                .putFloat("floatY", floatSwitch.getY()).apply();
                    } else if (tapToExpand) {
                        expandFloat();
                    }
                    floatDragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    floatDragging = false;
                    return true;
                default:
                    return false;
            }
        });
    }

    private void clampFloat() {
        if (floatSwitch == null || floatSwitch.getWidth() == 0
                || mapView == null || mapView.getWidth() == 0) {
            return;
        }
        float w = floatSwitch.getWidth();
        float h = floatSwitch.getHeight();
        View top = findViewById(R.id.topBar);
        View bottom = findViewById(R.id.bottomBar);
        float minX = dp(8);
        float maxX = Math.max(minX, mapView.getWidth() - w - dp(8));
        float minY = Math.max(dp(8), top.getBottom() + dp(8));
        float maxY = Math.max(minY, bottom.getTop() - h - dp(8));
        float nx = Math.max(minX, Math.min(maxX, floatSwitch.getX()));
        float ny = Math.max(minY, Math.min(maxY, floatSwitch.getY()));
        floatSwitch.setX(nx);
        floatSwitch.setY(ny);
    }

    private static void styleQuick(TextView v, boolean selected) {
        if (v == null) {
            return;
        }
        v.setSelected(selected);
        v.setTextColor(selected ? Color.WHITE : Color.parseColor("#37474F"));
        v.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void updateMapChip() {
        if (mapChip != null) {
            mapChip.setText("地图：" + TileSource.typeName(mapType)
                    + (mapLanguage == ChinaMapView.LANG_EN ? " · EN" : ""));
        }
    }

    private int effectiveTileProvider() {
        if (viewMode == ChinaMapView.VIEW_WORLD
                && tileProvider == TileSource.PROVIDER_AMAP) {
            return TileSource.PROVIDER_ESRI;
        }
        return tileProvider;
    }

    private void applyMapType(boolean announce) {
        mapView.setMapType(mapType, effectiveTileProvider(), tdtKey);
        updateMapChip();
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
        esri.setText("Esri（免 key，街道只到低缩放）");
        esri.setId(1003);
        RadioButton osm = new RadioButton(this);
        osm.setText("OSM 街道（全球高精度，z19）");
        osm.setId(1004);
        group.addView(amap);
        group.addView(tdt);
        group.addView(esri);
        group.addView(osm);
        int checkedId = tileProvider == TileSource.PROVIDER_TIANDITU ? 1002
                : tileProvider == TileSource.PROVIDER_ESRI ? 1003
                : tileProvider == TileSource.PROVIDER_OSM ? 1004 : 1001;
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
                            : checked == 1004 ? TileSource.PROVIDER_OSM
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
        String[] items = {"显示市界", "显示县界", "显示地名", "显示经纬网",
                "显示定位精度圈", "显示坐标气泡", "显示当前定位", "持续定位"};
        boolean[] checked = {showCity, showCounty, showLabels, showGrid,
                showAccuracy, showBubble, showPhoneMarker, continuousLocation};
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
                        case 5:
                            showBubble = isChecked;
                            break;
                        case 6:
                            showPhoneMarker = isChecked;
                            break;
                        default:
                            continuousLocation = isChecked;
                            break;
                    }
                    prefs.edit()
                            .putBoolean("showCity", showCity)
                            .putBoolean("showCounty", showCounty)
                            .putBoolean("showLabels", showLabels)
                            .putBoolean("showGrid", showGrid)
                            .putBoolean("showAccuracy", showAccuracy)
                            .putBoolean("showBubble", showBubble)
                            .putBoolean("showPhoneMarker", showPhoneMarker)
                            .putBoolean("continuousLocation", continuousLocation)
                            .apply();
                    if (which == 7) {
                        if (continuousLocation && connected) {
                            startContinuousLocation();
                        } else if (!continuousLocation && locationHelper != null) {
                            locationHelper.stopTracking();
                            if (mapView != null) {
                                mapView.setPhoneTracking(false);
                            }
                        }
                    }
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
        mapView.setPhoneMarkerVisible(showPhoneMarker);
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
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        final EditText et = new EditText(this);
        et.setHint("文本指令，例如 AT 或 T01=1234567");
        et.setSingleLine(true);
        box.addView(et);

        final CheckBox hex = new CheckBox(this);
        hex.setText("按 HEX 发送（如 0D0A）");
        box.addView(hex);

        final boolean[] appendCrlf = {true};
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final Button crlfBtn = new Button(this);
        crlfBtn.setText("回车：开");
        crlfBtn.setTextSize(11);
        crlfBtn.setMinWidth(0);
        crlfBtn.setPadding(dp(8), 0, dp(8), 0);
        row.addView(crlfBtn);

        Button send = new Button(this);
        send.setText("发送");
        send.setTextSize(11);
        send.setMinWidth(0);
        send.setPadding(dp(8), 0, dp(8), 0);
        row.addView(send);

        Button clear = new Button(this);
        clear.setText("清空结果");
        clear.setTextSize(11);
        clear.setMinWidth(0);
        clear.setPadding(dp(8), 0, dp(8), 0);
        row.addView(clear);
        box.addView(row);

        final TextView result = new TextView(this);
        result.setTextSize(11);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextColor(0xFF263238);
        result.setText("等待发送…");
        ScrollView resultScroll = new ScrollView(this);
        resultScroll.setPadding(0, dp(8), 0, 0);
        resultScroll.addView(result);
        resultScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
        box.addView(resultScroll);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("发送给蓝牙模块")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();

        crlfBtn.setOnClickListener(v -> {
            appendCrlf[0] = !appendCrlf[0];
            crlfBtn.setText(appendCrlf[0] ? "回车：开" : "回车：关");
            result.append("\n回车追加：" + (appendCrlf[0] ? "开" : "关") + "\n");
        });

        clear.setOnClickListener(v -> result.setText("等待发送…"));

        final BtLink.SpinnerCallback cb = (ok, message, tx, rx, writeType, writeStatus) ->
                runOnUiThread(() -> {
                    String now = new java.text.SimpleDateFormat("HH:mm:ss",
                            Locale.US).format(new java.util.Date());
                    result.append("[" + now + "] " + (ok ? "收到回包" : "失败") + "\n");
                    result.append("TX " + SpinnerProtocol.hex(tx) + "  「"
                            + SpinnerProtocol.decode(tx).trim() + "」\n");
                    result.append("RX " + SpinnerProtocol.hex(rx) + "  「"
                            + SpinnerProtocol.decode(rx).trim() + "」\n");
                    result.append(message + "  (writeType=" + writeType
                            + ", status=" + writeStatus + ")\n\n");
                });

        send.setOnClickListener(v -> {
            String raw = et.getText().toString();
            byte[] body;
            try {
                if (hex.isChecked()) {
                    String clean = raw.replaceAll("[^0-9a-fA-F]", "");
                    if (clean.length() % 2 != 0) {
                        toast("HEX 长度必须是偶数");
                        return;
                    }
                    body = new byte[clean.length() / 2];
                    for (int i = 0; i < body.length; i++) {
                        body[i] = (byte) Integer.parseInt(
                                clean.substring(i * 2, i * 2 + 2), 16);
                    }
                } else {
                    String text = raw.replace("\\r", "\r").replace("\\n", "\n");
                    body = text.getBytes(SpinnerProtocol.GB2312);
                }
            } catch (Exception e) {
                toast("指令格式不对: " + e.getMessage());
                return;
            }
            if (appendCrlf[0] && !endsWithCrlf(body)) {
                byte[] merged = java.util.Arrays.copyOf(body, body.length + 2);
                merged[body.length] = 0x0D;
                merged[body.length + 1] = 0x0A;
                body = merged;
            }
            if (body.length == 0) {
                toast("请输入指令");
                return;
            }
            result.append("\n→ TX " + SpinnerProtocol.hex(body) + "，等待回包…\n");
            bt.sendCommand(body, cb);
        });

        send.setOnLongClickListener(v -> {
            et.setText("T01=1234567");
            hex.setChecked(false);
            appendCrlf[0] = true;
            crlfBtn.setText("回车：开");
            return true;
        });

        dialog.show();
    }

    private static boolean endsWithCrlf(byte[] data) {
        return data.length >= 2 && data[data.length - 2] == 0x0D
                && data[data.length - 1] == 0x0A;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String protocolTemplateFor(int preset) {
        if (preset >= 0 && preset < PROTOCOL_PRESETS.length) {
            return PROTOCOL_PRESETS[preset];
        }
        return protocolTemplate;
    }

    private LocationProtocol protocolForPreset(int preset) {
        return new LocationProtocol(protocolTemplateFor(preset),
                protocolEncoding, protocolPrecision, protocolChecksum, protocolCrlf);
    }

    private LocationProtocol currentProtocol() {
        return protocolForPreset(protocolPreset);
    }

    private LocationProtocol.Values sampleProtocolValues() {
        return new LocationProtocol.Values(39.9042, 116.4074, 8, 50,
                "网络定位", "network", "GCJ-02", System.currentTimeMillis());
    }

    private void showProtocolDialog(Runnable onSaved) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        final EditText template = new EditText(this);
        template.setText(protocolTemplateFor(protocolPreset));
        template.setTextSize(12);
        template.setTypeface(Typeface.MONOSPACE);
        template.setMinLines(3);
        template.setHint("例如 {lat},{lon} 或 LAT={lat},LON={lon}");
        box.addView(template);

        TextView help = new TextView(this);
        help.setTextSize(11);
        help.setTextColor(0xFF607D8B);
        help.setText("占位符：{lat} {lon} {lat6} {lon6} {acc} {alt} {src} "
                + "{provider} {datum} {time} {date}\n"
                + "校验：{xor8} {sum8} {crc16}；转义：\\r \\n \\t \\ \\xNN");
        box.addView(help);

        TextView encLabel = new TextView(this);
        encLabel.setText("编码 / 精度 / 校验");
        encLabel.setTextSize(12);
        encLabel.setPadding(0, dp(8), 0, 0);
        box.addView(encLabel);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final Spinner enc = new Spinner(this);
        ArrayAdapter<String> ea = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"UTF-8", "GB2312"});
        ea.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        enc.setAdapter(ea);
        enc.setSelection(protocolEncoding == LocationProtocol.ENC_GB2312 ? 1 : 0);
        row.addView(enc, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

        final Spinner prec = new Spinner(this);
        ArrayAdapter<String> pa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"4", "6", "7", "8"});
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        prec.setAdapter(pa);
        int psel = protocolPrecision == 4 ? 0 : protocolPrecision == 7 ? 2
                : protocolPrecision == 8 ? 3 : 1;
        prec.setSelection(psel);
        row.addView(prec, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));

        final Spinner ck = new Spinner(this);
        ArrayAdapter<String> ca = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"无校验", "XOR8", "SUM8", "CRC16"});
        ca.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ck.setAdapter(ca);
        ck.setSelection(protocolChecksum);
        row.addView(ck, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        box.addView(row);

        final CheckBox crlf = new CheckBox(this);
        crlf.setText("末尾自动追加 \r\n");
        crlf.setChecked(protocolCrlf);
        box.addView(crlf);

        final TextView preview = new TextView(this);
        preview.setTextSize(11);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextColor(0xFF263238);
        preview.setText("预览…");
        ScrollView previewScroll = new ScrollView(this);
        previewScroll.addView(preview);
        previewScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130)));
        box.addView(previewScroll);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final Runnable refresh = () -> {
            try {
                LocationProtocol p = new LocationProtocol(
                        template.getText().toString(),
                        enc.getSelectedItemPosition() == 1
                                ? LocationProtocol.ENC_GB2312 : LocationProtocol.ENC_UTF8,
                        Integer.parseInt(prec.getSelectedItem().toString()),
                        ck.getSelectedItemPosition(), crlf.isChecked());
                LocationProtocol.Values v = sampleProtocolValues();
                preview.setText((p.isBinary() ? "(二进制协议，按 hex 发送)"
                        : p.text(v)) + "\nHEX " + p.hex(v));
            } catch (Exception e) {
                preview.setText("模板错误：" + e.getMessage());
            }
        };

        template.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refresh.run();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        android.widget.AdapterView.OnItemSelectedListener l =
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent,
                                               View view, int position, long id) {
                        refresh.run();
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                };
        enc.setOnItemSelectedListener(l);
        prec.setOnItemSelectedListener(l);
        ck.setOnItemSelectedListener(l);
        crlf.setOnCheckedChangeListener((b, checked) -> refresh.run());

        refresh.run();

        new AlertDialog.Builder(this)
                .setTitle("定位协议（自定义）")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        LocationProtocol p = new LocationProtocol(
                                template.getText().toString(),
                                enc.getSelectedItemPosition() == 1
                                        ? LocationProtocol.ENC_GB2312
                                        : LocationProtocol.ENC_UTF8,
                                Integer.parseInt(prec.getSelectedItem().toString()),
                                ck.getSelectedItemPosition(), crlf.isChecked());
                        p.render(sampleProtocolValues());
                    } catch (Exception e) {
                        toast("协议不合法：" + e.getMessage());
                        return;
                    }
                    protocolTemplate = template.getText().toString();
                    protocolEncoding = enc.getSelectedItemPosition() == 1
                            ? LocationProtocol.ENC_GB2312 : LocationProtocol.ENC_UTF8;
                    protocolPrecision = Integer.parseInt(
                            prec.getSelectedItem().toString());
                    protocolChecksum = ck.getSelectedItemPosition();
                    protocolCrlf = crlf.isChecked();
                    protocolPreset = 4;
                    prefs.edit()
                            .putInt("protocolPreset", protocolPreset)
                            .putString("protocolTemplate", protocolTemplate)
                            .putInt("protocolEncoding", protocolEncoding)
                            .putInt("protocolPrecision", protocolPrecision)
                            .putInt("protocolChecksum", protocolChecksum)
                            .putBoolean("protocolCrlf", protocolCrlf)
                            .apply();
                    if (onSaved != null) {
                        onSaved.run();
                    }
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    template.setText(LocationProtocol.DEFAULT_TEMPLATE);
                    enc.setSelection(0);
                    prec.setSelection(1);
                    ck.setSelection(0);
                    crlf.setChecked(true);
                    refresh.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private final LocationHelper.Listener locationListener = new LocationHelper.Listener() {
        @Override
        public void onFix(LocationHelper.Fix fix) {
            runOnUiThread(() -> handlePhoneFix(fix));
        }

        @Override
        public void onError(String message) {
            runOnUiThread(() -> addLog("定位: " + message));
        }
    };

    private void handlePhoneFix(LocationHelper.Fix f) {
        if (mapView == null) {
            return;
        }
        boolean center = showPhoneMarker && !locationAutoCentered;
        locationAutoCentered = true;
        mapView.setPhoneLocation(f.lat, f.lon, f.accuracy, f.provider, f.source,
                f.time, center);
        mapView.setPhoneTracking(locationHelper.isTracking());
        long now = System.currentTimeMillis();
        if (now - lastPhonePersist >= 5000) {
            lastPhonePersist = now;
            prefs.edit()
                    .putLong("phoneLat", Math.round(f.lat * 1_000_000L))
                    .putLong("phoneLon", Math.round(f.lon * 1_000_000L))
                    .putFloat("phoneAcc", (float) f.accuracy)
                    .putString("phoneProvider", f.provider)
                    .putString("phoneSource", f.source)
                    .putLong("phoneTime", f.time)
                    .apply();
        }
    }

    private void restorePhoneLocation() {
        long lat = prefs.getLong("phoneLat", 0);
        long lon = prefs.getLong("phoneLon", 0);
        long time = prefs.getLong("phoneTime", 0);
        if (lat == 0 && lon == 0 || time <= 0) {
            return;
        }
        String provider = prefs.getString("phoneProvider", "");
        String source = prefs.getString("phoneSource", "上次定位");
        float acc = prefs.getFloat("phoneAcc", 0f);
        mapView.setPhoneLocation(lat / 1_000_000.0, lon / 1_000_000.0, acc,
                provider, source, time, false);
        mapView.setPhoneTracking(false);
        locationAutoCentered = true;
    }

    private void startContinuousLocation() {
        if (!continuousLocation || locationHelper == null) {
            return;
        }
        if (!locationHelper.hasPermission()) {
            if (!locationPermissionAsked) {
                locationPermissionAsked = true;
                pendingLocationDialog = false;
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            }
            return;
        }
        locationAutoCentered = false;
        locationHelper.startTracking(2000, 1);
        if (mapView != null) {
            mapView.setPhoneTracking(true);
        }
        addLog("开始持续定位（GPS/网络，约 2 秒一次）");
    }

    private void showLocationSendDialog() {
        if (!hasLocationPermission()) {
            pendingLocationDialog = true;
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        final TextView status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(0xFF546E7A);
        status.setText(bt.isConnected()
                ? "BLE：已连接（" + bt.uartTxUuid() + "）"
                : "BLE：未连接，请先连接模块");
        box.addView(status);

        final TextView coords = new TextView(this);
        coords.setTextSize(13);
        coords.setTextColor(0xFF263238);
        coords.setPadding(0, dp(8), 0, 0);
        coords.setText("WGS-84：—\nGCJ-02：—");
        box.addView(coords);

        final TextView meta = new TextView(this);
        meta.setTextSize(11);
        meta.setTextColor(0xFF607D8B);
        meta.setText("来源/精度/时间：—");
        box.addView(meta);

        RadioGroup sourceGroup = new RadioGroup(this);
        sourceGroup.setOrientation(RadioGroup.HORIZONTAL);
        final RadioButton phone = new RadioButton(this);
        phone.setId(View.generateViewId());
        phone.setText("手机定位");
        phone.setChecked(true);
        final RadioButton lastBt = new RadioButton(this);
        lastBt.setId(View.generateViewId());
        lastBt.setText("最后蓝牙点");
        sourceGroup.addView(phone);
        sourceGroup.addView(lastBt);
        box.addView(sourceGroup);

        TextView protoLabel = new TextView(this);
        protoLabel.setText("发送协议");
        protoLabel.setTextSize(12);
        protoLabel.setPadding(0, dp(6), 0, 0);
        box.addView(protoLabel);

        final Spinner proto = new Spinner(this);
        ArrayAdapter<String> pa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PROTOCOL_NAMES);
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        proto.setAdapter(pa);
        proto.setSelection(Math.max(0, Math.min(protocolPreset,
                PROTOCOL_NAMES.length - 1)));
        box.addView(proto);

        Button editProto = new Button(this);
        editProto.setText("编辑协议…");
        editProto.setTextSize(11);
        editProto.setMinWidth(0);
        editProto.setPadding(dp(8), 0, dp(8), 0);
        box.addView(editProto);

        RadioGroup datumGroup = new RadioGroup(this);
        datumGroup.setOrientation(RadioGroup.HORIZONTAL);
        final RadioButton wgs = new RadioButton(this);
        wgs.setId(View.generateViewId());
        wgs.setText("WGS-84");
        wgs.setChecked(true);
        final RadioButton cur = new RadioButton(this);
        cur.setId(View.generateViewId());
        cur.setText("当前坐标系 " + CoordSystems.name(datum));
        datumGroup.addView(wgs);
        datumGroup.addView(cur);
        box.addView(datumGroup);

        final TextView preview = new TextView(this);
        preview.setTextSize(12);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextColor(0xFF263238);
        preview.setMinLines(3);
        preview.setText("等待定位…");
        box.addView(preview);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button locate = new Button(this);
        locate.setText("重新定位");
        locate.setTextSize(11);
        locate.setMinWidth(0);
        locate.setPadding(dp(8), 0, dp(8), 0);
        buttons.addView(locate);

        Button send = new Button(this);
        send.setText("发送");
        send.setTextSize(11);
        send.setMinWidth(0);
        send.setPadding(dp(8), 0, dp(8), 0);
        buttons.addView(send);

        Button clearMark = new Button(this);
        clearMark.setText("清除标注");
        clearMark.setTextSize(11);
        clearMark.setMinWidth(0);
        clearMark.setPadding(dp(8), 0, dp(8), 0);
        buttons.addView(clearMark);
        box.addView(buttons);

        final TextView result = new TextView(this);
        result.setTextSize(11);
        result.setTypeface(Typeface.MONOSPACE);
        result.setTextColor(0xFF263238);
        result.setText("等待定位/发送…");
        ScrollView resultScroll = new ScrollView(this);
        resultScroll.setPadding(0, dp(6), 0, 0);
        resultScroll.addView(result);
        resultScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        box.addView(resultScroll);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("定位并发送（BLE）")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();

        final LocationHelper.Fix[] current = {locationHelper.lastFix()};
        final int[] source = {0};
        final int[] sendDatum = {CoordSystems.WGS84};

        final byte[][] lastBytes = {null};
        final Runnable render = () -> {
            LocationHelper.Fix f = current[0];
            if (f == null) {
                coords.setText("WGS-84：—\nGCJ-02：—");
                meta.setText("来源/精度/时间：—");
                preview.setText("等待定位…");
                lastBytes[0] = null;
                return;
            }
            double[] gcj = CoordSystems.toMap(CoordSystems.WGS84, f.lat, f.lon);
            coords.setText(String.format(Locale.US,
                    "WGS-84 %.6f, %.6f\nGCJ-02 %.6f, %.6f", f.lat, f.lon, gcj[0], gcj[1]));
            meta.setText(String.format(Locale.US, "%s · %s · ±%.0fm · %s",
                    f.source, f.provider, f.accuracy,
                    new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                            .format(new java.util.Date(f.time))));

            double lat = f.lat;
            double lon = f.lon;
            String datumName = "WGS-84";
            if (sendDatum[0] != CoordSystems.WGS84) {
                double[] d = CoordSystems.fromWgs84(sendDatum[0], f.lat, f.lon);
                lat = d[0];
                lon = d[1];
                datumName = CoordSystems.name(sendDatum[0]);
            }
            try {
                LocationProtocol p = protocolForPreset(proto.getSelectedItemPosition());
                LocationProtocol.Values v = new LocationProtocol.Values(
                        lat, lon, f.accuracy, f.altitude, f.source, f.provider,
                        datumName, f.time);
                byte[] data = p.render(v);
                lastBytes[0] = data;
                String hex = SpinnerProtocol.hex(data);
                if (p.isBinary()) {
                    preview.setText("HEX " + hex);
                } else {
                    preview.setText(p.text(v) + "\nHEX " + hex);
                }
            } catch (IllegalArgumentException e) {
                lastBytes[0] = null;
                preview.setText("协议错误：" + e.getMessage());
            }

        };

        final Runnable locateRun = () -> {
            if (!locationHelper.hasPermission()) {
                pendingLocationDialog = true;
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                return;
            }
            LocationHelper.Fix last = locationHelper.lastFix();
            if (last != null) {
                current[0] = last;
                render.run();
            }
            if (locationHelper.isTracking()) {
                status.setText("持续定位中（GPS/网络）");
                locationHelper.requestFresh();
            } else {
                status.setText("正在定位…");
                locationHelper.requestFix(10000, null);
            }
        };

        final Runnable lastRun = () -> {
            int n = TrackStore.INSTANCE.size();
            if (n == 0) {
                status.setText("还没有蓝牙坐标点");
                current[0] = null;
                render.run();
                return;
            }
            int i = n - 1;
            current[0] = new LocationHelper.Fix(
                    TrackStore.INSTANCE.wgsLat(i), TrackStore.INSTANCE.wgsLon(i),
                    TrackStore.INSTANCE.accuracy(i), TrackStore.INSTANCE.alt(i),
                    "最后蓝牙点", TrackStore.INSTANCE.time(i), "最后蓝牙点");
            source[0] = 1;
            status.setText("使用最后一个蓝牙坐标");
            render.run();
        };

        sourceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == lastBt.getId()) {
                lastRun.run();
            } else {
                locateRun.run();
            }
        });
        proto.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                protocolPreset = position;
                prefs.edit().putInt("protocolPreset", protocolPreset).apply();
                render.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        editProto.setOnClickListener(v -> showProtocolDialog(() -> {
            protocolPreset = 4;
            proto.setSelection(4);
            render.run();
        }));
        clearMark.setOnClickListener(v -> {
            mapView.clearPhoneLocation();
            showPhoneMarker = false;
            prefs.edit()
                    .putBoolean("showPhoneMarker", false)
                    .remove("phoneLat").remove("phoneLon")
                    .remove("phoneAcc").remove("phoneProvider")
                    .remove("phoneSource").remove("phoneTime")
                    .apply();
            applyDisplaySettings();
            result.append("\n已清除地图上的当前定位标注（可在“地图显示”里重新打开）\n");
        });
        datumGroup.setOnCheckedChangeListener((group, checkedId) -> {
            sendDatum[0] = checkedId == cur.getId() ? datum : CoordSystems.WGS84;
            render.run();
        });

        final BtLink.SpinnerCallback cb = (ok, message, tx, rx, writeType, writeStatus) ->
                runOnUiThread(() -> {
                    String now = new java.text.SimpleDateFormat("HH:mm:ss",
                            Locale.US).format(new java.util.Date());
                    result.append("[" + now + "] " + (ok ? "收到回包" : "已发送/超时") + "\n");
                    result.append("TX " + SpinnerProtocol.hex(tx) + "  「"
                            + SpinnerProtocol.decode(tx).trim() + "」\n");
                    result.append("RX " + SpinnerProtocol.hex(rx) + "  「"
                            + SpinnerProtocol.decode(rx).trim() + "」\n");
                    result.append(message + "  (writeType=" + writeType
                            + ", status=" + writeStatus + ")\n\n");
                });

        locate.setOnClickListener(v -> {
            phone.setChecked(true);
            locateRun.run();
        });

        send.setOnClickListener(v -> {
            byte[] payload = lastBytes[0];
            if (payload == null || payload.length == 0) {
                toast("没有可发送的协议内容，请先定位或检查协议");
                return;
            }
            result.append("\n→ 发送 " + payload.length + " 字节，等待回包…\n");
            bt.sendCommand(payload, cb);
        });

        final LocationHelper.Listener live = new LocationHelper.Listener() {
            @Override
            public void onFix(LocationHelper.Fix fix) {
                runOnUiThread(() -> {
                    if (!dialog.isShowing()) {
                        return;
                    }
                    current[0] = fix;
                    status.setText("已定位（" + fix.source + "）");
                    render.run();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    if (!dialog.isShowing()) {
                        return;
                    }
                    status.setText(message);
                });
            }
        };
        locationHelper.addListener(live);
        dialog.setOnDismissListener(d -> locationHelper.removeListener(live));
        dialog.show();
        locateRun.run();
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
        String msg = "世界地图蓝牙轨迹  v1.3.5\n\n"
                + "· 悬浮球：世界/中国、中文/EN，可拖动/隐藏；更多面板分组\n"
                + "· 世界/中国铺满屏幕；滑动时标签暂隐，不漂移\n"
                + "· 世界最小缩放清晰化，detail 边界与标签更大\n"
                + "· 其他国家显示省州/城市信息，最小显示首都\n"
                + "· 修复在线瓦片每帧上限导致的右侧空白\n"
                + "· 世界 10m 边界 + 更深缩放；可选 OSM 全球街道\n"
                + "· 世界地图数据抽稀 + 后台 Path，切换/缩放更流畅\n"
                + "· 世界地图 + 中国到县级，离线；中文/English 地图标签\n"
                + "· 中国省/市/县（GCJ-02）；世界国家/省州/城市（Natural Earth）\n"
                + "· 街道/卫星/卫星+路网/地形为在线瓦片，需要网络\n"
                + "· 输入坐标系可选：GCJ-02 / WGS-84 / BD-09 / CGCS2000\n"
                + "· 蓝牙：经典 SPP + BLE GATT（自动枚举并订阅所有通知特征）\n"
                + "· 坐标格式：31.2304,121.4737 / lat:.. lon:.. / JSON / NMEA GGA·RMC·GLL\n"
                + "· 支持 31.2304N,121.4737E；NMEA GGA 的 HDOP 会换算成精度圈\n\n"
                + "底图：DataV.GeoAtlas（中国省/市/县）+ Natural Earth（世界）；\n"
                + "在线瓦片：高德/Esri/天地图。\n"
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
        if (locationHelper != null) {
            locationHelper.removeListener(locationListener);
            locationHelper.stopAll();
        }
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
