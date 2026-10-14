package com.helalguard.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity implements SensorEventListener {
    private static final String TAG = "HelalGuard";
    private static final int ALL_PERMISSIONS_REQUEST = 2000;
    private static final int CAMERA_PERMISSION_REQUEST = 2001;
    private static final int LOCATION_PERMISSION_REQUEST = 2002;
    private static final int FILECHOOSER_RESULTCODE = 1001;

    private WebView mWebView;
    private ValueCallback<Uri[]> mFilePathCallback;
    private PermissionRequest mCurrentPermissionRequest;
    private GeolocationPermissions.Callback mGeoCallback;
    private String mGeoOrigin;

    // Hardware Magnetic Compass Sensor Fields
    private SensorManager mSensorManager;
    private Sensor mRotationVectorSensor;
    private Sensor mAccelerometer;
    private Sensor mMagnetometer;
    private final float[] mGravity = new float[3];
    private final float[] mGeomagnetic = new float[3];
    private boolean mHasGravity = false;
    private boolean mHasGeomagnetic = false;
    private double mSmoothedSin = 0.0;
    private double mSmoothedCos = 1.0;
    private boolean mCompassInitialized = false;
    private volatile float mCurrentCompassHeading = -1f;
    private volatile int mCompassAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    private volatile float mMagneticFieldStrength = 45.0f;
    private volatile double mCachedLat = 0.0;
    private volatile double mCachedLng = 0.0;
    private long mLastCompassEmitTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Initialize Hardware Compass Sensors
        try {
            mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (mSensorManager != null) {
                mRotationVectorSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                mMagnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            }
        } catch (Exception ignored) {}

        mWebView = new WebView(this);
        mWebView.setBackgroundColor(Color.parseColor("#0f172a"));
        setContentView(mWebView);

        WebSettings ws = mWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setGeolocationEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        mWebView.addJavascriptInterface(new WebAppInterface(this), "AndroidNative");

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;
                if (url.contains("/privacy") || url.contains("/gizlilik")) {
                    view.loadUrl("file:///android_asset/privacy/index.html");
                    return true;
                }
                if (url.contains("/delete-account") || url.contains("/veri-silme")) {
                    view.loadUrl("file:///android_asset/delete-account/index.html");
                    return true;
                }
                if (url.startsWith("file://") || url.startsWith("data:")) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.e(TAG, "WebView error: " + error.getDescription() + " code: " + error.getErrorCode());
                }
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(final String origin, final GeolocationPermissions.Callback callback) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        mGeoOrigin = origin;
                        mGeoCallback = callback;
                        requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        }, LOCATION_PERMISSION_REQUEST);
                        return;
                    }
                }
                callback.invoke(origin, true, false);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                if (request == null) return;
                mCurrentPermissionRequest = request;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
                        return;
                    }
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            request.grant(request.getResources());
                        } catch (Exception e) {
                            Log.e(TAG, "Error granting permission: " + e.getMessage());
                        }
                    }
                });
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, "[JS] " + consoleMessage.message());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;

                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                    String[] mimeTypes = {"image/jpeg", "image/png", "image/webp", "image/heic"};
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                    startActivityForResult(Intent.createChooser(intent, "Ürün / Etiket Fotoğrafı Seç"), FILECHOOSER_RESULTCODE);
                } catch (Exception e) {
                    mFilePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Request all necessary permissions on launch (Location, Notifications, Camera)
        requestAllAppPermissions();

        // Start persistent Ezan Vakti Pro style background notification bar immediately
        BootReceiver.startPrayerService(this);

        // Load offline HTML from android_asset for HalalLife Pro
        mWebView.loadUrl("file:///android_asset/index.html?project=halallife");
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerCompassSensors();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterCompassSensors();
    }

    private void registerCompassSensors() {
        try {
            if (mSensorManager == null) return;
            // Register physical Accelerometer + Magnetometer for true Magnetic North
            if (mAccelerometer != null && mMagnetometer != null) {
                mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_GAME);
                mSensorManager.registerListener(this, mMagnetometer, SensorManager.SENSOR_DELAY_GAME);
            }
            // Also register Rotation Vector as secondary fallback
            if (mRotationVectorSensor != null) {
                mSensorManager.registerListener(this, mRotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
            }
        } catch (Exception ignored) {}
    }

    private void unregisterCompassSensors() {
        try {
            if (mSensorManager != null) {
                mSensorManager.unregisterListener(this);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null) return;
        float rawAzimuth = -1f;

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            final float alpha = 0.15f;
            if (!mHasGravity) {
                System.arraycopy(event.values, 0, mGravity, 0, 3);
                mHasGravity = true;
            } else {
                mGravity[0] = alpha * event.values[0] + (1f - alpha) * mGravity[0];
                mGravity[1] = alpha * event.values[1] + (1f - alpha) * mGravity[1];
                mGravity[2] = alpha * event.values[2] + (1f - alpha) * mGravity[2];
            }
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            final float alpha = 0.15f;
            float bx = event.values[0];
            float by = event.values[1];
            float bz = event.values[2];
            mMagneticFieldStrength = (float) Math.sqrt(bx * bx + by * by + bz * bz);
            if (!mHasGeomagnetic) {
                System.arraycopy(event.values, 0, mGeomagnetic, 0, 3);
                mHasGeomagnetic = true;
            } else {
                mGeomagnetic[0] = alpha * event.values[0] + (1f - alpha) * mGeomagnetic[0];
                mGeomagnetic[1] = alpha * event.values[1] + (1f - alpha) * mGeomagnetic[1];
                mGeomagnetic[2] = alpha * event.values[2] + (1f - alpha) * mGeomagnetic[2];
            }
        }

        // Primary: True Magnetic North from physical Accelerometer + Magnetometer (with 3D tilt compensation)
        float[] R = new float[9];
        boolean hasMatrix = false;

        if (mHasGravity && mHasGeomagnetic) {
            float[] I = new float[9];
            if (SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic)) {
                hasMatrix = true;
            }
        }
        if (!hasMatrix && event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            if (event.values.length > 4) {
                float[] truncated = new float[4];
                System.arraycopy(event.values, 0, truncated, 0, 4);
                SensorManager.getRotationMatrixFromVector(R, truncated);
            } else {
                SensorManager.getRotationMatrixFromVector(R, event.values);
            }
            hasMatrix = true;
        }

        if (hasMatrix) {
            // 3D Tilt-Compensated Forward Vector: works seamlessly both flat on a table (0° tilt)
            // and held at 30°-85° in the user's hand without Gimbal Lock or roll-induced flips!
            float east = R[1] - 0.8f * R[2];
            float north = R[4] - 0.8f * R[5];
            if (Math.hypot(east, north) > 0.05f) {
                rawAzimuth = (float) ((Math.toDegrees(Math.atan2(east, north)) + 360.0) % 360.0);
            } else {
                float[] orientation = new float[3];
                SensorManager.getOrientation(R, orientation);
                rawAzimuth = (float) ((Math.toDegrees(orientation[0]) + 360.0) % 360.0);
            }

            // Apply True Geographic North magnetic declination correction if coordinates are known
            try {
                float lat = (mCachedLat != 0.0) ? (float) mCachedLat : 41.0082f;
                float lng = (mCachedLng != 0.0) ? (float) mCachedLng : 28.9784f;
                GeomagneticField geoField = new GeomagneticField(lat, lng, 0f, System.currentTimeMillis());
                rawAzimuth = (rawAzimuth + geoField.getDeclination() + 360.0f) % 360.0f;
            } catch (Exception ignored) {}
        }

        if (rawAzimuth >= 0 && !Float.isNaN(rawAzimuth)) {
            double rad = Math.toRadians(rawAzimuth);
            double smoothAlpha = 0.22;
            if (!mCompassInitialized) {
                mSmoothedSin = Math.sin(rad);
                mSmoothedCos = Math.cos(rad);
                mCompassInitialized = true;
            } else {
                mSmoothedSin = smoothAlpha * Math.sin(rad) + (1.0 - smoothAlpha) * mSmoothedSin;
                mSmoothedCos = smoothAlpha * Math.cos(rad) + (1.0 - smoothAlpha) * mSmoothedCos;
            }
            float smoothedAzimuth = (float) ((Math.toDegrees(Math.atan2(mSmoothedSin, mSmoothedCos)) + 360.0) % 360.0);
            mCurrentCompassHeading = smoothedAzimuth;

            long now = System.currentTimeMillis();
            if (now - mLastCompassEmitTime > 70 && mWebView != null) {
                mLastCompassEmitTime = now;
                final int rounded = Math.round(smoothedAzimuth) % 360;
                mWebView.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mWebView.evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('native-compass-heading', { detail: " + rounded + " }));",
                                null
                            );
                        } catch (Exception ignored) {}
                    }
                });
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, final int accuracy) {
        if (sensor != null && (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD || sensor.getType() == Sensor.TYPE_ROTATION_VECTOR)) {
            mCompassAccuracy = accuracy;
            if (mWebView != null) {
                mWebView.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mWebView.evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('native-compass-accuracy', { detail: " + accuracy + " }));",
                                null
                            );
                        } catch (Exception ignored) {}
                    }
                });
            }
        }
    }

    public void requestAllAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> needed = new ArrayList<String>();
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.CAMERA);
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    needed.add("android.permission.POST_NOTIFICATIONS");
                }
            }
            if (!needed.isEmpty()) {
                requestPermissions(needed.toArray(new String[0]), ALL_PERMISSIONS_REQUEST);
            }
        }
    }

    public void promptBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean cameraGranted = false;
        boolean locationGranted = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                              checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        if (cameraGranted) {
            if (mCurrentPermissionRequest != null) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            mCurrentPermissionRequest.grant(mCurrentPermissionRequest.getResources());
                            mCurrentPermissionRequest = null;
                        } catch (Exception ignored) {}
                    }
                });
            }
            if (mWebView != null) {
                mWebView.loadUrl("javascript:window.dispatchEvent(new CustomEvent('camera-permission-granted'))");
            }
        }

        if (locationGranted) {
            if (mGeoCallback != null && mGeoOrigin != null) {
                mGeoCallback.invoke(mGeoOrigin, true, false);
                mGeoCallback = null;
                mGeoOrigin = null;
            }
        }

        // Start/refresh persistent notification bar after permissions are granted
        BootReceiver.startPrayerService(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (mFilePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getData() != null) {
                    results = new Uri[]{ data.getData() };
                } else if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                    results = new Uri[]{ data.getClipData().getItemAt(0).getUri() };
                } else {
                    results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                }
            }
            mFilePathCallback.onReceiveValue(results);
            mFilePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public static class WebAppInterface {
        MainActivity mActivity;

        WebAppInterface(MainActivity a) {
            mActivity = a;
        }

        @JavascriptInterface
        public boolean isNative() {
            return true;
        }

        @JavascriptInterface
        public String getAppVersion() {
            return "2.7.0";
        }

        @JavascriptInterface
        public float getCompassHeading() {
            return mActivity.mCurrentCompassHeading;
        }

        @JavascriptInterface
        public int getCompassAccuracy() {
            if (mActivity.mMagneticFieldStrength < 20.0f || mActivity.mMagneticFieldStrength > 75.0f) {
                return SensorManager.SENSOR_STATUS_ACCURACY_LOW;
            }
            return mActivity.mCompassAccuracy;
        }

        @JavascriptInterface
        public float getMagneticFieldMicroTesla() {
            return mActivity.mMagneticFieldStrength;
        }

        @JavascriptInterface
        public String getNativeLocation() {
            try {
                if (mActivity.mCachedLat != 0.0 || mActivity.mCachedLng != 0.0) {
                    return "{\"lat\":" + mActivity.mCachedLat + ",\"lng\":" + mActivity.mCachedLng + "}";
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (mActivity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                        mActivity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        return "";
                    }
                }
                LocationManager lm = (LocationManager) mActivity.getSystemService(Context.LOCATION_SERVICE);
                if (lm == null) return "";
                Location best = null;
                String[] providers = new String[]{
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                };
                for (String p : providers) {
                    try {
                        if (lm.isProviderEnabled(p)) {
                            Location loc = lm.getLastKnownLocation(p);
                            if (loc != null) {
                                if (best == null || loc.getTime() > best.getTime()) {
                                    best = loc;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
                if (best != null) {
                    mActivity.mCachedLat = best.getLatitude();
                    mActivity.mCachedLng = best.getLongitude();
                    return "{\"lat\":" + best.getLatitude() + ",\"lng\":" + best.getLongitude() + "}";
                }
            } catch (Exception ignored) {}
            return "";
        }

        @JavascriptInterface
        public void requestFreshNativeLocation() {
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (mActivity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                                mActivity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                mActivity.requestPermissions(new String[]{
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                }, LOCATION_PERMISSION_REQUEST);
                                return;
                            }
                        }
                        final LocationManager lm = (LocationManager) mActivity.getSystemService(Context.LOCATION_SERVICE);
                        if (lm == null) return;
                        final boolean[] delivered = new boolean[]{false};
                        final LocationListener listener = new LocationListener() {
                            @Override
                            public void onLocationChanged(final Location location) {
                                if (location != null && !delivered[0]) {
                                    delivered[0] = true;
                                    mActivity.mCachedLat = location.getLatitude();
                                    mActivity.mCachedLng = location.getLongitude();
                                }
                                try {
                                    lm.removeUpdates(this);
                                } catch (Exception ignored) {}
                            }
                            @Override public void onStatusChanged(String p, int s, Bundle b) {}
                            @Override public void onProviderEnabled(String p) {}
                            @Override public void onProviderDisabled(String p) {}
                        };
                        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, null);
                        } else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null);
                        }
                        // Force-stop location listener after 2.2 seconds so phone GPS never keeps searching
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    lm.removeUpdates(listener);
                                } catch (Exception ignored) {}
                            }
                        }, 2200);
                    } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void updatePrayerNotification(
                String city,
                String hijri,
                String imsak,
                String gunes,
                String ogle,
                String ikindi,
                String aksam,
                String yatsi
        ) {
            try {
                SharedPreferences prefs = mActivity.getSharedPreferences(PrayerForegroundService.PREFS_NAME, MODE_PRIVATE);
                prefs.edit()
                        .putString("city", city != null ? city : "İstanbul")
                        .putString("hijri", hijri != null ? hijri : "")
                        .putString("imsak", imsak != null ? imsak : "05:24")
                        .putString("gunes", gunes != null ? gunes : "07:15")
                        .putString("ogle", ogle != null ? ogle : "13:12")
                        .putString("ikindi", ikindi != null ? ikindi : "16:11")
                        .putString("aksam", aksam != null ? aksam : "18:58")
                        .putString("yatsi", yatsi != null ? yatsi : "20:35")
                        .apply();
                BootReceiver.startPrayerService(mActivity);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void requestAllPermissions() {
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mActivity.requestAllAppPermissions();
                    mActivity.promptBatteryOptimizationIfNeeded();
                }
            });
        }

        @JavascriptInterface
        public void requestLocationPermission() {
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (mActivity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                            mActivity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            mActivity.requestPermissions(new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            }, LOCATION_PERMISSION_REQUEST);
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void requestIgnoreBatteryOptimizations() {
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mActivity.promptBatteryOptimizationIfNeeded();
                }
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + mActivity.getPackageName()));
                mActivity.startActivity(intent);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void requestCameraPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (mActivity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    mActivity.requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
                }
            }
        }

        @JavascriptInterface
        public void vibrate(long ms) {
            try {
                Vibrator v = (Vibrator) mActivity.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    v.vibrate(ms);
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean keepOn) {
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (keepOn) {
                        mActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } else {
                        mActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                }
            });
        }

        @JavascriptInterface
        public void setStatusBarSymbolStyle(String symbolStyle) {
            try {
                SharedPreferences prefs = mActivity.getSharedPreferences(PrayerForegroundService.PREFS_NAME, MODE_PRIVATE);
                prefs.edit()
                        .putString("symbolStyle", symbolStyle != null ? symbolStyle : "sade_hilal")
                        .apply();
                BootReceiver.startPrayerService(mActivity);
            } catch (Exception ignored) {}
        }

        private String callGeminiNative(JSONObject payloadObj) {
            String apiKey = NativeBuildConfig.GEMINI_KEY;
            if (apiKey == null || apiKey.isEmpty()) return "";
            String[] models = new String[]{
                "gemini-3.1-flash-lite-preview",
                "gemini-flash-latest",
                "gemini-flash-lite-latest",
                "gemini-3-flash-preview"
            };
            for (String model : models) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(12000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("User-Agent", "aistudio-build");
                    conn.setDoOutput(true);

                    byte[] input = payloadObj.toString().getBytes("UTF-8");
                    OutputStream os = conn.getOutputStream();
                    os.write(input, 0, input.length);
                    os.close();

                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        br.close();
                        JSONObject respJson = new JSONObject(sb.toString());
                        JSONArray candidates = respJson.optJSONArray("candidates");
                        if (candidates != null && candidates.length() > 0) {
                            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                            if (content != null) {
                                JSONArray parts = content.optJSONArray("parts");
                                if (parts != null && parts.length() > 0) {
                                    String rawText = parts.getJSONObject(0).optString("text", "");
                                    if (!rawText.isEmpty()) {
                                        int firstBrace = rawText.indexOf('{');
                                        int lastBrace = rawText.lastIndexOf('}');
                                        if (firstBrace >= 0 && lastBrace > firstBrace) {
                                            return rawText.substring(firstBrace, lastBrace + 1);
                                        }
                                        return rawText;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Native Gemini model " + model + " error: " + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            return "";
        }

        @JavascriptInterface
        public String analyzePhotoNative(String base64Image, String mimeType, String language) {
            try {
                if (base64Image == null || base64Image.isEmpty()) return "";
                String cleanB64 = base64Image.contains(",")
                        ? base64Image.substring(base64Image.indexOf(",") + 1).replaceAll("\\s+", "")
                        : base64Image.replaceAll("\\s+", "");
                String safeMime = (mimeType != null && !mimeType.isEmpty()) ? mimeType : "image/jpeg";
                String langInstruction = "ar".equals(language) ? "Arabic" : "en".equals(language) ? "English" : "Turkish";

                String prompt = "You are HalalLife Pro's expert Multimodal Vision, Product Classification (Cleaning Products, Personal Care/Cosmetics, Food & Beverages), Chemistry & Islamic Fiqh analyst. "
                        + "Carefully examine this uploaded photo (it may show a CLEANING PRODUCT like detergent, bleach, dish soap, degreaser, limescale remover, fabric softener; OR a PERSONAL CARE / COSMETIC item like shampoo, soap, toothpaste, cream, deodorant, wet wipes; OR a FOOD/BEVERAGE item). "
                        + "CRITICAL RULE 1: If the photo ONLY shows a close-up of black-and-white barcode lines/stripes and NO brand logo, product name, bottle shape, or ingredients list is identifiable in the image, DO NOT GUESS a random product! Instead return {\"found\":false,\"onlyBarcodeVisible\":true}. "
                        + "CRITICAL RULE 2: NEVER confuse a cleaning product bottle (even if it has yellow/orange/red/green liquid or lemon/floral graphics, e.g. Bingo, Domestos, Cif, Omo, Yumoş, Ariel, Fairy, Persil, Pril, Vernel, Finish, Vanish, Asperox, Peros, Boron, Viking, ABC, Porçöz) with Fruit Juice ('Meyve Suyu') or a beverage! "
                        + "Target output language: " + langInstruction + ". "
                        + "Identify the exact Product Name, Brand Name, Category ('Temizlik & Deterjan' / 'Kişisel Bakım & Kozmetik' / specific food category), and full Ingredients/Composition list (start with 'TEMİZLİK ÜRÜNÜDÜR: ' for cleaning products or 'KİŞİSEL BAKIM ÜRÜNÜDÜR: ' for personal care). "
                        + "Determine Halal Status (\"halal\" | \"mushbooh\" | \"haram\"), Boycott Status, and provide Hanafi, Diyanet, and Yusuf al-Qaradawi rulings. "
                        + "Return valid JSON ONLY: "
                        + "{\"found\":true,\"productName\":\"string\",\"brandName\":\"string\",\"category\":\"string\",\"originCountry\":\"string\","
                        + "\"certifier\":\"TSE Helal / GİMDES / Doğal Kaynak / Bitkisel Bazlı / None\",\"ingredientsList\":\"complete ingredients in " + langInstruction + "\","
                        + "\"halalStatus\":\"halal\",\"fiqhVerdict\":\"detailed verdict\",\"hanafiRuling\":\"Hanafi ruling\","
                        + "\"diyanetRuling\":\"Diyanet ruling\",\"qaradawiRuling\":\"Qaradawi ruling\","
                        + "\"boycottStatus\":\"safe\",\"boycottReason\":\"\",\"alternatives\":[]}";

                JSONObject inlineData = new JSONObject();
                inlineData.put("mime_type", safeMime);
                inlineData.put("data", cleanB64);

                JSONObject imgPart = new JSONObject();
                imgPart.put("inline_data", inlineData);

                JSONObject txtPart = new JSONObject();
                txtPart.put("text", prompt);

                JSONArray parts = new JSONArray();
                parts.put(imgPart);
                parts.put(txtPart);

                JSONObject contentObj = new JSONObject();
                contentObj.put("parts", parts);

                JSONArray contentsArr = new JSONArray();
                contentsArr.put(contentObj);

                JSONObject genConfig = new JSONObject();
                genConfig.put("responseMimeType", "application/json");

                JSONObject payload = new JSONObject();
                payload.put("contents", contentsArr);
                payload.put("generationConfig", genConfig);

                return callGeminiNative(payload);
            } catch (Exception e) {
                Log.e(TAG, "analyzePhotoNative error: " + e.getMessage());
                return "";
            }
        }

        @JavascriptInterface
        public String lookupBarcodeNative(String barcode, String productNameHint, String language) {
            try {
                if ((productNameHint == null || productNameHint.trim().isEmpty()) && (barcode == null || barcode.trim().isEmpty())) {
                    return "{\"found\":false}";
                }
                String langInstruction = "ar".equals(language) ? "Arabic" : "en".equals(language) ? "English" : "Turkish";
                String targetDesc = (barcode != null && !barcode.trim().isEmpty())
                        ? "barcode \"" + barcode + "\" (Known Product/Brand/Category Hint: \"" + (productNameHint != null ? productNameHint : "") + "\")"
                        : "product \"" + productNameHint + "\"";
                String prompt = "You are HalalLife Pro's product classification (Cleaning Products, Personal Care/Cosmetics, Food & Beverages), chemistry, and Islamic Fiqh (Fatwa) research engine. "
                        + "Identify the exact commercial product for " + targetDesc + " in " + langInstruction + ". "
                        + "CRITICAL ANTI-HALLUCINATION RULE: NEVER guess or invent a random food or fruit juice ('Meyve Suyu') from a barcode number! If the hint indicates a Cleaning Product (Temizlik Ürünü) or Personal Care Product (Kişisel Bakım), keep that exact category and brand! If you do not know the exact product for a bare barcode and have no hint, return {\"found\":false}. "
                        + "Provide exact productName, brand, category, originCountry, certifier, FULL official ingredients/composition list, halalStatus (\"halal\"|\"mushbooh\"|\"haram\"), "
                        + "detailed halalReason, hanafiRuling, diyanetRuling, qaradawiRuling, boycottStatus (\"safe\"|\"boycotted\"), boycottReason. "
                        + "Return valid JSON ONLY: "
                        + "{\"found\":true,\"productName\":\"string\",\"brand\":\"string\",\"category\":\"string\",\"originCountry\":\"string\","
                        + "\"certifier\":\"TSE Helal / Doğal Kaynak / Bitkisel Bazlı / None\",\"ingredients\":\"complete ingredients list in " + langInstruction + "\",\"halalStatus\":\"halal\","
                        + "\"halalReason\":\"string\",\"hanafiRuling\":\"string\",\"diyanetRuling\":\"string\",\"qaradawiRuling\":\"string\","
                        + "\"boycottStatus\":\"safe\",\"boycottReason\":\"\",\"alternatives\":[]}";

                JSONObject txtPart = new JSONObject();
                txtPart.put("text", prompt);
                JSONArray parts = new JSONArray();
                parts.put(txtPart);
                JSONObject contentObj = new JSONObject();
                contentObj.put("parts", parts);
                JSONArray contentsArr = new JSONArray();
                contentsArr.put(contentObj);
                JSONObject genConfig = new JSONObject();
                genConfig.put("responseMimeType", "application/json");
                JSONObject payload = new JSONObject();
                payload.put("contents", contentsArr);
                payload.put("generationConfig", genConfig);

                return callGeminiNative(payload);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void lookupBarcodeNativeAsync(final String barcode, final String productNameHint, final String language, final String requestId) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String res = lookupBarcodeNative(barcode, productNameHint, language);
                    if (mActivity.mWebView != null) {
                        mActivity.mWebView.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    String safeJson = JSONObject.quote(res != null ? res : "");
                                    String safeId = JSONObject.quote(requestId != null ? requestId : "");
                                    mActivity.mWebView.evaluateJavascript(
                                        "window.dispatchEvent(new CustomEvent('helalguard-native-ai-done', { detail: { requestId: " + safeId + ", result: " + safeJson + " } }));",
                                        null
                                    );
                                } catch (Exception ignored) {}
                            }
                        });
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void updatePrayerAlertsConfig(String json) {
            try {
                SharedPreferences prefs = mActivity.getSharedPreferences(PrayerForegroundService.PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putString("prayer_alerts_json", json != null ? json : "").apply();
                BootReceiver.startPrayerService(mActivity);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void playNativeEzanAlert(String title, String soundType) {
            try {
                PrayerForegroundService.playSynthesizedEzanAudio(mActivity, title, soundType);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void stopNativeEzanAlert() {
            try {
                PrayerForegroundService.stopSynthesizedEzanAudio();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void snoozePrayerAlert(int minutes, String title, String soundType) {
            try {
                PrayerForegroundService.scheduleSnooze(mActivity, minutes, title, soundType);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void showToast(String toast) {
            Toast.makeText(mActivity, toast, Toast.LENGTH_SHORT).show();
        }
    }
}
