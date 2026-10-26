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

/** 用系统 LocationManager 取一次当前定位，带超时和最后已知兜底。 */
public final class LocationHelper {
    public static final class Fix {
        public final double lat;
        public final double lon;
        public final double accuracy;
        public final String provider;
        public final long time;
        public final String source;

        public Fix(Location l, String source) {
            this.lat = l.getLatitude();
            this.lon = l.getLongitude();
            this.accuracy = l.hasAccuracy() ? l.getAccuracy() : 0;
            this.provider = l.getProvider() == null ? "" : l.getProvider();
            this.time = l.getTime();
            this.source = source;
        }

        public Fix(double lat, double lon, double accuracy, String provider, long time, String source) {
            this.lat = lat;
            this.lon = lon;
            this.accuracy = accuracy;
            this.provider = provider;
            this.time = time;
            this.source = source;
        }
    }

    public interface Callback {
        void onFix(Fix fix);

        void onError(String message);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocationManager manager;
    private LocationListener listener;
    private Runnable timeout;
    private boolean deliveredAny;
    private Fix best;

    public LocationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
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

    public void requestFix(long timeoutMs, Callback cb) {
        main.post(() -> start(timeoutMs, cb));
    }

    private void start(long timeoutMs, Callback cb) {
        cancel();
        deliveredAny = false;
        best = null;
        if (manager == null) {
            cb.onError("本机没有定位服务");
            return;
        }
        if (!hasPermission()) {
            cb.onError("没有定位权限");
            return;
        }
        if (!isEnabled()) {
            cb.onError("定位/GPS 未开启，请在系统设置里打开定位");
            return;
        }

        best = bestLastKnown();
        if (best != null && System.currentTimeMillis() - best.time < 120_000) {
            // 先给一个“最后已知”，同时继续请求一次新定位；新 fix 到了会再回调覆盖。
            deliveredAny = true;
            cb.onFix(best);
        }

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
        if (providers.isEmpty()) {
            if (best != null) {
                deliveredAny = true;
                cb.onFix(best);
            } else {
                cb.onError("没有可用的定位 provider（GPS/网络都关闭）");
            }
            cleanup();
            return;
        }

        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null) {
                    deliveredAny = true;
                    cb.onFix(new Fix(location, providerName(location.getProvider())));
                    cleanup();
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
                manager.requestLocationUpdates(p, 0L, 0f, listener,
                        Looper.getMainLooper());
            } catch (SecurityException e) {
                // 权限在请求过程中被撤销：继续尝试其他 provider
            } catch (Throwable ignored) {
            }
        }

        timeout = () -> {
            if (!deliveredAny) {
                if (best != null) {
                    deliveredAny = true;
                    cb.onFix(new Fix(best.lat, best.lon, best.accuracy, best.provider,
                            best.time, "最后已知（可能较旧）"));
                } else {
                    cb.onError("定位超时：室内/GPS 信号弱，可重试或改用“最后蓝牙点”");
                }
            }
            cleanup();
        };
        main.postDelayed(timeout, timeoutMs);
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

    public void cancel() {
        if (timeout != null) {
            main.removeCallbacks(timeout);
            timeout = null;
        }
        if (manager != null && listener != null) {
            try {
                manager.removeUpdates(listener);
            } catch (Throwable ignored) {
            }
        }
        listener = null;
    }

    private void cleanup() {
        if (timeout != null) {
            main.removeCallbacks(timeout);
            timeout = null;
        }
        if (manager != null && listener != null) {
            try {
                manager.removeUpdates(listener);
            } catch (Throwable ignored) {
            }
            listener = null;
        }
    }
}
