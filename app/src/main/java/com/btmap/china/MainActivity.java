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
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.btmap.china.bt.BtLink;
import com.btmap.china.map.ChinaMapView;
import com.btmap.china.track.CoordParser;
import com.btmap.china.track.CoordTransform;
import com.btmap.china.track.TrackStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements BtLink.Callback {
    private static final int REQ_PERMS = 2001;
    private static final int REQ_ENABLE = 2002;

    private static final int COORD_NONE = 0;
    private static final int COORD_WGS_TO_GCJ = 1;
    private static final int COORD_GCJ_TO_WGS = 2;

    private ChinaMapView mapView;
    private TextView statusText;
    private TextView coordText;
    private TextView countText;
    private TextView logText;
    private ScrollView logPanel;
    private Button btnConnect;
    private Button btnFollow;
    private Button btnLog;

    private BtLink bt;
    private final CoordParser parser = new CoordParser();
    private final StringBuilder logBuffer = new StringBuilder();
    private int logLines;

    private int mode = BtLink.MODE_SPP;
    private int coordMode = COORD_NONE;
    private boolean connected;
    private boolean pendingConnect;
    private boolean logVisible;

    private AlertDialog deviceDialog;
    private DeviceAdapter deviceAdapter;
    private TextView scanStateView;
    private ListView deviceList;
    private RadioButton rbSpp;
    private RadioButton rbBle;

    private SharedPreferences prefs;

    private final TrackStore.Listener trackListener = () -> runOnUiThread(this::refreshCount);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        coordMode = prefs.getInt("coordMode", COORD_NONE);
        mode = prefs.getInt("mode", BtLink.MODE_SPP);

        mapView = findViewById(R.id.map);
        statusText = findViewById(R.id.status);
        coordText = findViewById(R.id.coordText);
        countText = findViewById(R.id.countText);
        logPanel = findViewById(R.id.logPanel);
        logText = findViewById(R.id.logText);
        btnConnect = findViewById(R.id.btnConnect);
        btnFollow = findViewById(R.id.btnFollow);
        btnLog = findViewById(R.id.btnLog);
        Button btnMore = findViewById(R.id.btnMore);

        mapView.setPickListener((lat, lon) -> showPickedCoordinate(lat, lon));

        bt = new BtLink(this, this);

        btnConnect.setOnClickListener(v -> requestConnect());
        btnMore.setOnClickListener(v -> showMoreMenu());
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
        refreshCount();
        if (TrackStore.INSTANCE.size() > 0) {
            double[] last = TrackStore.INSTANCE.lastLatLon();
            if (last != null) {
                coordText.setText(formatCoord(last[0], last[1]));
            }
        }
        addLog("应用启动，地图数据已内置（离线）");

        handleIntent(getIntent());
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
        String demoPoints = intent.getStringExtra("demo_points");
        boolean demo = intent.getBooleanExtra("demo", false);
        if (demoPoints != null) {
            feedDemoPoints(demoPoints);
        } else if (demo) {
            injectDemo();
        }
        if (intent.getBooleanExtra("show_log", false)) {
            if (!logVisible) {
                toggleLog();
            }
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
        rbSpp = view.findViewById(R.id.modeSpp);
        rbBle = view.findViewById(R.id.modeBle);
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
        statusText.setText(text);
        statusText.setTextColor(isConnected ? Color.parseColor("#2E7D32") : Color.parseColor("#607D8B"));
        btnConnect.setText(isConnected ? "已连接" : "连接设备");
        boolean wasConnected = connected;
        connected = isConnected;
        if (isConnected) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (isConnected != wasConnected) {
            addLog("状态: " + text);
        }
        refreshCount();
    }

    @Override
    public void onData(byte[] data, int len) {
        parser.feed(data, len, parserListener);
    }

    @Override
    public void onLog(String line) {
        addLog(line);
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

    private final CoordParser.Listener parserListener = new CoordParser.Listener() {
        @Override
        public void onPoint(double lat, double lon, double alt, boolean hasAlt, String rawLine) {
            double[] p = applyCoordMode(lat, lon);
            boolean added = TrackStore.INSTANCE.add(p[0], p[1], alt, hasAlt);
            coordText.setText(formatCoord(p[0], p[1]));
            if (added) {
                addLog("定位: " + formatCoord(p[0], p[1])
                        + (hasAlt ? String.format(Locale.US, "  高度 %.1fm", alt) : ""));
            }
            mapView.onTrackChanged();
        }

        @Override
        public void onInvalid(String rawLine) {
            addLog("未识别: " + trim(rawLine, 120));
        }
    };

    private double[] applyCoordMode(double lat, double lon) {
        if (coordMode == COORD_WGS_TO_GCJ) {
            return CoordTransform.wgs84ToGcj02(lat, lon);
        }
        if (coordMode == COORD_GCJ_TO_WGS) {
            return CoordTransform.gcj02ToWgs84(lat, lon);
        }
        return new double[]{lat, lon};
    }

    // ------------------------------------------------------------ 菜单
    private void showMoreMenu() {
        String coordLabel;
        switch (coordMode) {
            case COORD_WGS_TO_GCJ:
                coordLabel = "坐标转换：WGS84 → GCJ-02";
                break;
            case COORD_GCJ_TO_WGS:
                coordLabel = "坐标转换：GCJ-02 → WGS84";
                break;
            default:
                coordLabel = "坐标转换：不转换";
                break;
        }
        final List<String> items = new ArrayList<>();
        items.add(coordLabel);
        items.add("演示数据（画一条示例轨迹）");
        items.add("输入测试坐标");
        items.add(logVisible ? "隐藏日志面板" : "显示日志面板");
        items.add("向模块发送指令…");
        if (connected) {
            items.add("断开蓝牙连接");
        }
        items.add("关于 / 数据格式");
        final String[] labels = items.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("更多")
                .setItems(labels, (d, which) -> {
                    String label = labels[which];
                    if (label.startsWith("坐标转换")) {
                        showCoordModeDialog();
                    } else if (label.startsWith("演示数据")) {
                        injectDemo();
                    } else if (label.startsWith("输入测试坐标")) {
                        showInputDialog();
                    } else if (label.contains("日志面板")) {
                        toggleLog();
                    } else if (label.startsWith("向模块发送")) {
                        showSendDialog();
                    } else if (label.startsWith("断开蓝牙")) {
                        bt.disconnect();
                        addLog("手动断开蓝牙");
                    } else {
                        showAbout();
                    }
                })
                .show();
    }

    private void showCoordModeDialog() {
        String[] items = {"不转换（默认）", "WGS84 → GCJ-02（国内 GPS 打点用）", "GCJ-02 → WGS84"};
        new AlertDialog.Builder(this)
                .setTitle("坐标转换")
                .setSingleChoiceItems(items, coordMode, (d, which) -> {
                    coordMode = which;
                    prefs.edit().putInt("coordMode", which).apply();
                    addLog("坐标转换模式: " + items[which]);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showInputDialog() {
        EditText et = new EditText(this);
        et.setHint("例如 31.2304,121.4737");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(3);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("输入测试坐标")
                .setView(wrap)
                .setPositiveButton("发送到地图", (d, w) -> {
                    String text = et.getText().toString();
                    if (text.trim().isEmpty()) {
                        return;
                    }
                    feedDemoPoints(text);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSendDialog() {
        EditText et = new EditText(this);
        et.setHint("HELLO 或 $PMTK220,1000*1F");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("发送给蓝牙模块")
                .setView(wrap)
                .setPositiveButton("发送", (d, w) -> {
                    String s = et.getText().toString();
                    if (!s.isEmpty()) {
                        bt.send(s);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAbout() {
        String msg = "中国地图蓝牙轨迹  v1.0\n\n"
                + "· 地图数据完全内置，不联网、不请求 INTERNET 权限\n"
                + "· 支持经典蓝牙 SPP 与 BLE GATT 两种接收方式\n"
                + "· 坐标格式：31.2304,121.4737 / lat:.. lon:.. / JSON / NMEA GGA·RMC·GLL\n"
                + "· 地图基准为 GCJ-02，可在“更多 → 坐标转换”里对齐 GPS 原始坐标\n\n"
                + "地图数据：阿里云 DataV.GeoAtlas（省级 + 地级市边界）\n"
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

    // ------------------------------------------------------------ 演示/测试数据
    private void injectDemo() {
        // 北京五环附近的一条示例轨迹（也是蓝牙解析链路的自测数据）
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
        addLog("注入演示轨迹 " + pts.length + " 点");
        feedDemoPoints(sb.toString());
    }

    /** 支持多种分隔符：换行、分号；每段交给正式的解析器处理。 */
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

    // ------------------------------------------------------------ UI 刷新
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
        String stamp = String.format(Locale.US, "%tH:%tM:%tS ", System.currentTimeMillis(),
                System.currentTimeMillis(), System.currentTimeMillis());
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
        return String.format(Locale.US, "%.6f, %.6f", lat, lon);
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
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

    // ------------------------------------------------------------ 设备列表 Adapter
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
