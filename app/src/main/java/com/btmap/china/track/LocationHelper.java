package com.btmap.china.track;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统 LocationManager 封装：
 * <ul>
 *   <li>requestFix：一次性定位（10 秒超时 + 最后已知兜底）；</li>
 *   <li>startTracking/stopTracking：持续定位（BLE 连接期间每 2 秒更新）。</li>
 * </ul>
 */
public final class LocationHelper {
    public interface Listener {
        void onFix(Fix fix);

        void onError(String message);
    }

    /** 兼容旧的一次性回调。 */
    public interface Callback extends Listener {
    }

    public static final class Fix {
        public final double lat;
        public final double lon;
        public final double accuracy;
        public final double altitude;
        public final String provider;
        public final long time;
        public final String source;

        public Fix(Location l, String source) {
            this.lat = l.getLatitude();
            this.lon = l.getLongitude();
            this.accuracy = l.hasAccuracy() ? l.getAccuracy() : 0;
            this.altitude = l.hasAltitude() ? l.getAltitude() : 0;
            this.provider = l.getProvider() == null ? "" : l.getProvider();
            this.time = l.getTime();
            this.source = source;
        }

        public Fix(double lat, double lon, double accuracy, String provider, long time, String source) {
            this(lat, lon, accuracy, 0, provider, time, source);
        }

        public Fix(double lat, double lon, double accuracy, double altitude,
                   String provider, long time, String source) {
            this.lat = lat;
            this.lon = lon;
            this.accuracy = accuracy;
            this.altitude = altitude;
            this.provider = provider;
            this.time = time;
            this.source = source;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocationManager manager;
    private final List<Listener> listeners = new ArrayList<>();

    private LocationListener oneShotListener;
    private Runnable timeout;
    private boolean deliveredAny;
    private Fix best;

    private LocationListener trackingListener;
    private boolean tracking;
    private Fix lastFix;

    public LocationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public Fix lastFix() {
        return lastFix;
    }

    public boolean isTracking() {
        return tracking;
    }

    public boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasFinePermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isEnabled() {
        if (manager == null) {
            return false;
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return manager.isLocationEnabled();
            }
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ 持续定位

    public void startTracking(long intervalMs, long minDistanceM) {
        main.post(() -> {
            if (tracking) {
                return;
            }
            if (manager == null) {
                notifyError("本机没有定位服务");
                return;
            }
            if (!hasPermission()) {
                notifyError("没有定位权限");
                return;
            }
            if (!isEnabled()) {
                notifyError("定位/GPS 未开启，请在系统设置里打开定位");
                return;
            }
            List<String> providers = enabledProviders();
            if (providers.isEmpty()) {
                notifyError("没有可用的定位 provider（GPS/网络都关闭）");
                return;
            }

            trackingListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location != null) {
                        notifyFix(new Fix(location, providerName(location.getProvider())));
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                    notifyError("定位 provider 已关闭：" + provider);
                }
            };
            for (String p : providers) {
                try {
                    manager.requestLocationUpdates(p, Math.max(0, intervalMs),
                            Math.max(0, minDistanceM), trackingListener,
                            Looper.getMainLooper());
                } catch (SecurityException ignored) {
                } catch (Throwable ignored) {
                }
            }
            tracking = true;
        });
    }

    public void stopTracking() {
        main.post(() -> {
            if (manager != null && trackingListener != null) {
                try {
                    manager.removeUpdates(trackingListener);
                } catch (Throwable ignored) {
                }
            }
            trackingListener = null;
            tracking = false;
        });
    }

    public void requestFresh() {
        main.post(() -> {
            Fix f = lastFix;
            if (f != null) {
                notifyFix(f);
            }
        });
    }

    // ------------------------------------------------------------ 一次性定位

    public void requestFix(long timeoutMs, Callback cb) {
        main.post(() -> startOneShot(timeoutMs, cb));
    }

    private void startOneShot(long timeoutMs, Callback cb) {
        cancelOneShot();
        deliveredAny = false;
        best = null;
        if (manager == null) {
            error(cb, "本机没有定位服务");
            return;
        }
        if (!hasPermission()) {
            error(cb, "没有定位权限");
            return;
        }
        if (!isEnabled()) {
            error(cb, "定位/GPS 未开启，请在系统设置里打开定位");
            return;
        }

        best = bestLastKnown();
        if (best != null && System.currentTimeMillis() - best.time < 120_000) {
            deliveredAny = true;
            notifyFix(best);
            if (cb != null) {
                cb.onFix(best);
            }
        }

        List<String> providers = enabledProviders();
        if (providers.isEmpty()) {
            if (best != null) {
                deliveredAny = true;
                notifyFix(best);
                if (cb != null) {
                    cb.onFix(best);
                }
            } else {
                error(cb, "没有可用的定位 provider（GPS/网络都关闭）");
            }
            cleanupOneShot();
            return;
        }

        oneShotListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null) {
                    deliveredAny = true;
                    Fix f = new Fix(location, providerName(location.getProvider()));
                    notifyFix(f);
                    if (cb != null) {
                        cb.onFix(f);
                    }
                    cleanupOneShot();
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };

        for (String p : providers) {
            try {
                manager.requestLocationUpdates(p, 0L, 0f, oneShotListener,
                        Looper.getMainLooper());
            } catch (SecurityException ignored) {
            } catch (Throwable ignored) {
            }
        }

        timeout = () -> {
            if (!deliveredAny) {
                if (best != null) {
                    deliveredAny = true;
                    Fix f = new Fix(best.lat, best.lon, best.accuracy, best.altitude,
                            best.provider, best.time, "最后已知（可能较旧）");
                    notifyFix(f);
                    if (cb != null) {
                        cb.onFix(f);
                    }
                } else {
                    error(cb, "定位超时：室内/GPS 信号弱，可重试或改用“最后蓝牙点”");
                }
            }
            cleanupOneShot();
        };
        main.postDelayed(timeout, timeoutMs);
    }

    private List<String> enabledProviders() {
        List<String> providers = new ArrayList<>();
        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                providers.add(LocationManager.GPS_PROVIDER);
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                providers.add(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Throwable ignored) {
        }
        return providers;
    }

    private Fix bestLastKnown() {
        Fix best = null;
        String[] providers = {LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String p : providers) {
            try {
                Location l = manager.getLastKnownLocation(p);
                if (l == null) {
                    continue;
                }
                if (best == null || l.getTime() > best.time) {
                    best = new Fix(l, "最后已知");
                }
            } catch (SecurityException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    private static String providerName(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            return "GPS";
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            return "网络定位";
        }
        return provider == null ? "" : provider;
    }

    private void notifyFix(Fix fix) {
        lastFix = fix;
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onFix(fix);
            } catch (Throwable ignored) {
            }
        }
    }

    private void notifyError(String message) {
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onError(message);
            } catch (Throwable ignored) {
            }
        }
    }

    private void error(Callback cb, String message) {
        if (cb != null) {
            cb.onError(message);
        }
        notifyError(message);
    }

    public void cancel() {
        cancelOneShot();
    }

    public void stopAll() {
        stopTracking();
        cancelOneShot();
    }

    private void cancelOneShot() {
        if (timeout != null) {
            main.removeCallbacks(timeout);
            timeout = null;
        }
        if (manager != null && oneShotListener != null) {
            try {
                manager.removeUpdates(oneShotListener);
            } catch (Throwable ignored) {
            }
        }
        oneShotListener = null;
    }

    private void cleanupOneShot() {
        if (timeout != null) {
            main.removeCallbacks(timeout);
            timeout = null;
        }
        if (manager != null && oneShotListener != null) {
            try {
                manager.removeUpdates(oneShotListener);
            } catch (Throwable ignored) {
            }
            oneShotListener = null;
        }
    }
}
