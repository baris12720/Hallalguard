package com.helalguard.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.text.SpannableString;
import android.text.style.UnderlineSpan;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Calendar;

public class PrayerForegroundService extends Service {
    public static final String CHANNEL_ID = "helalguard_prayer_bar_v2";
    public static final String EZAN_CHANNEL_ID = "helalguard_ezan_alert_v1";
    public static final int NOTIF_ID = 1001;
    public static final int EZAN_NOTIF_ID = 1002;
    public static final String PREFS_NAME = "HelalGuardPrefs";
    public static final String ACTION_SNOOZE_5 = "com.helalguard.app.ACTION_SNOOZE_5";
    public static final String ACTION_SNOOZE_10 = "com.helalguard.app.ACTION_SNOOZE_10";
    public static final String ACTION_STOP_EZAN = "com.helalguard.app.ACTION_STOP_EZAN";

    private static volatile AudioTrack sActiveAudioTrack = null;
    private static volatile MediaPlayer sActiveMediaPlayer = null;
    private static volatile boolean sStopRequested = false;
    private static final Handler sSnoozeHandler = new Handler(Looper.getMainLooper());
    private static Runnable sActiveSnoozeRunnable = null;

    private Handler mHandler;
    private Runnable mTicker;
    private BroadcastReceiver mTimeTickReceiver;
    private String mLastTriggeredHHMM = "";

    public static void scheduleSnooze(final Context context, final int minutes, final String title, final String soundType) {
        stopSynthesizedEzanAudio();
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(EZAN_NOTIF_ID);
        } catch (Exception ignored) {}

        if (sActiveSnoozeRunnable != null) {
            sSnoozeHandler.removeCallbacks(sActiveSnoozeRunnable);
            sActiveSnoozeRunnable = null;
        }

        final Context appCtx = context.getApplicationContext();
        final String safeTitle = (title != null && !title.isEmpty()) ? title : "🕌 Namaz Vakti Uyarısı";
        final String safeSound = (soundType != null && !soundType.isEmpty()) ? soundType : "ezan";
        sActiveSnoozeRunnable = new Runnable() {
            @Override
            public void run() {
                sActiveSnoozeRunnable = null;
                playSynthesizedEzanAudio(appCtx, safeTitle + " (⏰ " + minutes + " Dk Erteleme)", safeSound);
            }
        };
        sSnoozeHandler.postDelayed(sActiveSnoozeRunnable, Math.max(1, minutes) * 60L * 1000L);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannelIfNeeded();

        mHandler = new Handler(Looper.getMainLooper());
        mTicker = new Runnable() {
            @Override
            public void run() {
                updateNotification();
                mHandler.postDelayed(this, 10000); // Update countdown every 10 seconds
            }
        };

        mTimeTickReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateNotification();
            }
        };

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            registerReceiver(mTimeTickReceiver, filter);
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String act = intent.getAction();
            String alertTitle = intent.getStringExtra("alert_title");
            String alertSound = intent.getStringExtra("alert_sound");
            if (ACTION_STOP_EZAN.equals(act)) {
                stopSynthesizedEzanAudio();
                try {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancel(EZAN_NOTIF_ID);
                } catch (Exception ignored) {}
                return START_STICKY;
            } else if (ACTION_SNOOZE_5.equals(act)) {
                scheduleSnooze(this, 5, alertTitle, alertSound);
                return START_STICKY;
            } else if (ACTION_SNOOZE_10.equals(act)) {
                scheduleSnooze(this, 10, alertTitle, alertSound);
                return START_STICKY;
            }
        }

        Notification notification = buildPrayerNotification();
        try {
            // On Android 14+ (API 34), startForeground can take foregroundServiceType via reflection if needed
            startForeground(NOTIF_ID, notification);
        } catch (Exception e) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(NOTIF_ID, notification);
            } catch (Exception ignored) {}
        }

        mHandler.removeCallbacks(mTicker);
        mHandler.post(mTicker);

        return START_STICKY;
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) return;
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                // IMPORTANCE_LOW = 2 (Silent ongoing notification bar in 'Sessiz' section like Ezan Vakti Pro)
                Object channel = channelClass.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL_ID, "Namaz Vakitleri Bildirim Çubuğu", 2);
                try {
                    Method setShowBadge = channelClass.getMethod("setShowBadge", boolean.class);
                    setShowBadge.invoke(channel, false);
                } catch (Exception ignored) {}
                Method createMethod = NotificationManager.class.getMethod("createNotificationChannel", channelClass);
                createMethod.invoke(nm, channel);

                // Also create High-Importance Ezan Alert Channel (IMPORTANCE_HIGH = 4)
                Object ezanChannel = channelClass.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(EZAN_CHANNEL_ID, "Ezan ve Namaz Vakti Uyarıları", 4);
                createMethod.invoke(nm, ezanChannel);
            } catch (Exception ignored) {}
        }
    }

    public static void stopSynthesizedEzanAudio() {
        sStopRequested = true;
        try {
            if (sActiveMediaPlayer != null) {
                if (sActiveMediaPlayer.isPlaying()) {
                    sActiveMediaPlayer.stop();
                }
                sActiveMediaPlayer.release();
                sActiveMediaPlayer = null;
            }
        } catch (Exception ignored) {
            sActiveMediaPlayer = null;
        }
        try {
            if (sActiveAudioTrack != null) {
                sActiveAudioTrack.pause();
                sActiveAudioTrack.flush();
                sActiveAudioTrack.release();
                sActiveAudioTrack = null;
            }
        } catch (Exception ignored) {
            sActiveAudioTrack = null;
        }
    }

    public static void playSynthesizedEzanAudio(final Context context, final String title, final String soundType) {
        stopSynthesizedEzanAudio();
        sStopRequested = false;

        try {
            Vibrator vib = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null) {
                vib.vibrate(new long[]{0, 250, 120, 250, 120, 400}, -1);
            }
        } catch (Exception ignored) {}

        // Post High-Importance Heads-Up Notification for Ezan / Custom Reminder with Smart Snooze (5m / 10m)
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                Intent openIntent = new Intent(context, MainActivity.class);
                openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) piFlags |= 67108864;
                PendingIntent pi = PendingIntent.getActivity(context, 102, openIntent, piFlags);

                Intent snooze5Intent = new Intent(context, PrayerForegroundService.class);
                snooze5Intent.setAction(ACTION_SNOOZE_5);
                snooze5Intent.putExtra("alert_title", title != null ? title : "🕌 Namaz Vakti Girdi");
                snooze5Intent.putExtra("alert_sound", soundType != null ? soundType : "ezan");
                PendingIntent snooze5Pi = PendingIntent.getService(context, 205, snooze5Intent, piFlags);

                Intent snooze10Intent = new Intent(context, PrayerForegroundService.class);
                snooze10Intent.setAction(ACTION_SNOOZE_10);
                snooze10Intent.putExtra("alert_title", title != null ? title : "🕌 Namaz Vakti Girdi");
                snooze10Intent.putExtra("alert_sound", soundType != null ? soundType : "ezan");
                PendingIntent snooze10Pi = PendingIntent.getService(context, 210, snooze10Intent, piFlags);

                Intent stopIntent = new Intent(context, PrayerForegroundService.class);
                stopIntent.setAction(ACTION_STOP_EZAN);
                PendingIntent stopPi = PendingIntent.getService(context, 299, stopIntent, piFlags);

                Notification.Builder nb = new Notification.Builder(context)
                        .setSmallIcon(R.drawable.ic_stat_mosque)
                        .setContentTitle(title != null ? title : "🕌 Namaz Vakti Girdi")
                        .setContentText("Allâhu Ekber • Bildirimden +5 Dk veya +10 Dk Akıllı Erteleme yapabilirsiniz")
                        .setContentIntent(pi)
                        .setAutoCancel(true);
                if (Build.VERSION.SDK_INT >= 16) {
                    nb.addAction(0, "⏰ +5 Dk Ertele", snooze5Pi);
                    nb.addAction(0, "⏰ +10 Dk Ertele", snooze10Pi);
                    nb.addAction(0, "🔇 Durdur", stopPi);
                }
                if (Build.VERSION.SDK_INT >= 21) {
                    nb.setVisibility(Notification.VISIBILITY_PUBLIC);
                    nb.setPriority(Notification.PRIORITY_HIGH);
                    nb.setColor(Color.parseColor("#F59E0B"));
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        Method setChannel = Notification.Builder.class.getMethod("setChannelId", String.class);
                        setChannel.invoke(nb, EZAN_CHANNEL_ID);
                    } catch (Exception ignored) {}
                }
                nm.notify(EZAN_NOTIF_ID, nb.build());
            }
        } catch (Exception ignored) {}

        if ("silent".equalsIgnoreCase(soundType)) {
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Short notification chime ("beep")
                    if ("beep".equalsIgnoreCase(soundType)) {
                        final int sampleRate = 22050;
                        double[] freqs = new double[]{880.0, 1320.0, 0, 880.0, 1320.0};
                        double[] durs  = new double[]{0.18,  0.25,   0.1, 0.18, 0.30};
                        int totalSamples = 0;
                        for (double d : durs) totalSamples += (int) (d * sampleRate);
                        short[] pcm = new short[totalSamples];
                        int idx = 0;
                        for (int n = 0; n < freqs.length; n++) {
                            double f = freqs[n];
                            int len = (int) (durs[n] * sampleRate);
                            for (int i = 0; i < len && idx < totalSamples; i++, idx++) {
                                if (f <= 1.0) {
                                    pcm[idx] = 0;
                                } else {
                                    double t = (double) i / sampleRate;
                                    double env = (i > len - sampleRate * 0.05) ? (double) (len - i) / (sampleRate * 0.05) : 1.0;
                                    pcm[idx] = (short) (Math.sin(2.0 * Math.PI * f * t) * env * 18000);
                                }
                            }
                        }
                        AudioTrack track = new AudioTrack(
                                AudioManager.STREAM_MUSIC,
                                sampleRate,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                pcm.length * 2,
                                AudioTrack.MODE_STATIC
                        );
                        track.write(pcm, 0, pcm.length);
                        sActiveAudioTrack = track;
                        if (!sStopRequested) {
                            track.play();
                        }
                        return;
                    }

                    // Real Human Voice MP3 Playback for Ezan or Tekbir
                    String assetFile = "audio/ezan_makkah.mp3";
                    if ("tekbir".equalsIgnoreCase(soundType)) {
                        assetFile = "audio/tekbir_real.mp3";
                    } else {
                        String muezzinId = "makkah";
                        if (soundType != null && soundType.contains(":")) {
                            String[] sp = soundType.split(":");
                            if (sp.length > 1 && !sp[1].trim().isEmpty()) {
                                muezzinId = sp[1].trim().toLowerCase();
                            }
                        } else {
                            try {
                                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                                String alertsJson = prefs.getString("prayer_alerts_json", "");
                                if (alertsJson != null && alertsJson.length() > 2) {
                                    JSONObject obj = new JSONObject(alertsJson);
                                    String savedM = obj.optString("selectedMuezzin", "makkah");
                                    if (savedM != null && !savedM.isEmpty()) {
                                        muezzinId = savedM.trim().toLowerCase();
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                        if ("dawsari".equals(muezzinId) || "erabaci".equals(muezzinId) || "alafasy".equals(muezzinId)
                                || "abdulbasit".equals(muezzinId) || "madinah".equals(muezzinId) || "makkah".equals(muezzinId)) {
                            assetFile = "audio/ezan_" + muezzinId + ".mp3";
                        }
                    }

                    if (sStopRequested) return;

                    MediaPlayer mp = new MediaPlayer();
                    mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    boolean loaded = false;
                    try {
                        AssetFileDescriptor afd = context.getAssets().openFd(assetFile);
                        mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                        afd.close();
                        loaded = true;
                    } catch (Exception fdErr) {
                        // Fallback: extract asset stream to cache file if compressed
                        try {
                            InputStream is = context.getAssets().open(assetFile);
                            File outFile = new File(context.getCacheDir(), "active_ezan_stream.mp3");
                            FileOutputStream fos = new FileOutputStream(outFile);
                            byte[] buf = new byte[8192];
                            int read;
                            while ((read = is.read(buf)) != -1) {
                                fos.write(buf, 0, read);
                            }
                            fos.flush();
                            fos.close();
                            is.close();
                            mp.setDataSource(outFile.getAbsolutePath());
                            loaded = true;
                        } catch (Exception ignored) {}
                    }

                    if (loaded && !sStopRequested) {
                        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                            @Override
                            public void onCompletion(MediaPlayer player) {
                                try {
                                    player.release();
                                } catch (Exception ignored) {}
                                if (sActiveMediaPlayer == player) {
                                    sActiveMediaPlayer = null;
                                }
                            }
                        });
                        mp.prepare();
                        sActiveMediaPlayer = mp;
                        if (!sStopRequested) {
                            mp.start();
                        } else {
                            mp.release();
                            sActiveMediaPlayer = null;
                        }
                    } else {
                        mp.release();
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private int parseMinutes(String hhmm) {
        try {
            String[] parts = hhmm.trim().split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildPrayerNotification());
            }
            checkAndTriggerScheduledEzan();
        } catch (Exception ignored) {}
    }

    private void checkAndTriggerScheduledEzan() {
        try {
            Calendar now = Calendar.getInstance();
            String currentHHMM = String.format("%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
            if (currentHHMM.equals(mLastTriggeredHHMM)) {
                return;
            }

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String city = prefs.getString("city", "İstanbul");
            String[] keys = new String[]{"imsak", "gunes", "ogle", "ikindi", "aksam", "yatsi"};
            String[] labels = new String[]{"İmsak (Sabah)", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı"};
            String[] times = new String[]{
                prefs.getString("imsak", "05:24"),
                prefs.getString("gunes", "07:15"),
                prefs.getString("ogle", "13:12"),
                prefs.getString("ikindi", "16:11"),
                prefs.getString("aksam", "18:58"),
                prefs.getString("yatsi", "20:35")
            };

            String alertsJsonStr = prefs.getString("prayer_alerts_json", "");
            JSONObject alertsObj = alertsJsonStr != null && alertsJsonStr.length() > 2
                    ? new JSONObject(alertsJsonStr)
                    : new JSONObject();
            String defaultSound = alertsObj.optString("alertSound", "ezan");
            JSONObject perSound = alertsObj.optJSONObject("perPrayerSound");

            for (int i = 0; i < 6; i++) {
                boolean enabled = alertsObj.optBoolean(keys[i], true);
                if (enabled && currentHHMM.equals(times[i])) {
                    mLastTriggeredHHMM = currentHHMM;
                    String sType = (perSound != null && perSound.has(keys[i]))
                            ? perSound.optString(keys[i], defaultSound)
                            : (i == 1 ? "beep" : defaultSound);
                    playSynthesizedEzanAudio(this, "🕌 " + city + " • " + labels[i] + " Vakti Girdi (" + currentHHMM + ")", sType);
                    return;
                }
            }

            // Check user's custom reminders
            JSONArray customArr = alertsObj.optJSONArray("customReminders");
            if (customArr != null) {
                for (int c = 0; c < customArr.length(); c++) {
                    JSONObject rem = customArr.optJSONObject(c);
                    if (rem != null && rem.optBoolean("enabled", true)) {
                        String remTime = rem.optString("time", "");
                        if (currentHHMM.equals(remTime)) {
                            mLastTriggeredHHMM = currentHHMM;
                            String remTitle = rem.optString("title", "Özel Namaz Uyarısı");
                            String remSound = rem.optString("soundType", "ezan");
                            playSynthesizedEzanAudio(this, "🔔 " + remTitle + " (" + remTime + ")", remSound);
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void drawFivePointStar(Canvas canvas, float cx, float cy, float outerRadius, float innerRadius, Paint paint) {
        Path starPath = new Path();
        double angleStep = Math.PI / 5.0;
        double startAngle = -Math.PI / 2.0 - 0.22; // Slight tilt toward crescent
        for (int i = 0; i < 10; i++) {
            double r = (i % 2 == 0) ? outerRadius : innerRadius;
            double theta = startAngle + i * angleStep;
            float x = (float) (cx + Math.cos(theta) * r);
            float y = (float) (cy + Math.sin(theta) * r);
            if (i == 0) {
                starPath.moveTo(x, y);
            } else {
                starPath.lineTo(x, y);
            }
        }
        starPath.close();
        canvas.drawPath(starPath, paint);
    }

    /**
     * Builds a 128x128 high-resolution transparent-background status bar icon next to the phone clock:
     * - Default (> 60 minutes left): Graceful Islamic Crescent Moon & 5-Pointed Star (Hilal ve Yıldız)
     *   or Ottoman Dome + Hilal if selected in widget customizer.
     * - When <= 60 minutes (1 hour or less) remain for ANY prayer (or in 'canli_dakika' mode):
     *   Renders a sweeping Crescent Moon (Hilal) cradling the bold live remaining minutes (e.g. "59", "42", "15")!
     */
    private Bitmap createStatusBarMosqueCountdownBitmap(int remainingSec, String symbolStyle) {
        int size = 128;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        Paint symbolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        symbolPaint.setStyle(Paint.Style.FILL);
        symbolPaint.setShader(new LinearGradient(
                0f, 0f, size, size,
                Color.parseColor("#FBBF24"), // Warm Islamic Gold
                Color.parseColor("#10B981"), // Emerald Green
                Shader.TileMode.CLAMP
        ));

        Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        boolean showCountdownInHilal = (remainingSec <= 3600 && remainingSec > 0) || "canli_dakika".equals(symbolStyle);

        if (showCountdownInHilal) {
            int minsLeft = Math.max(1, (int) Math.ceil(remainingSec / 60.0));
            String mainText = minsLeft <= 99 ? String.valueOf(minsLeft) : String.valueOf(minsLeft / 60) + "s";
            String subUnit = minsLeft <= 99 ? "dk" : "sa";

            // 1. Left-side symmetric Crescent Moon (Hilal) cradling the countdown
            canvas.drawCircle(54f, 64f, 54f, symbolPaint);
            canvas.drawCircle(78f, 64f, 48f, clearPaint);
            // Guarantee a 100% clear right bay so crescent horns never touch the digits
            canvas.drawCircle(86f, 64f, 45f, clearPaint);

            // 2. Bold remaining minutes inside the open right bay of the Hilal
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.parseColor("#FBBF24"));
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(mainText.length() >= 3 ? 42f : 54f);
            canvas.drawText(mainText, 85f, 68f, textPaint);

            // 3. "dk" unit cleanly below the minute number inside the Hilal bay
            Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            subPaint.setColor(Color.parseColor("#34D399"));
            subPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            subPaint.setTextAlign(Paint.Align.CENTER);
            subPaint.setTextSize(24f);
            canvas.drawText(subUnit, 85f, 96f, subPaint);
        } else if ("ince_hilal".equals(symbolStyle)) {
            // Ultra-Slender Starless Crescent Moon (Zarif İnce Hilal - Yıldızsız)
            canvas.drawCircle(64f, 64f, 56f, symbolPaint);
            canvas.drawCircle(80f, 52f, 49f, clearPaint);
        } else if ("hilal_yildiz".equals(symbolStyle)) {
            // Classic Crescent & 5-Pointed Star (Hilal ve Yıldız - Optional)
            canvas.drawCircle(58f, 66f, 52f, symbolPaint);
            canvas.drawCircle(80f, 52f, 42f, clearPaint);
            drawFivePointStar(canvas, 92f, 45f, 18.5f, 7.5f, symbolPaint);
        } else if ("kubbe_hilal".equals(symbolStyle)) {
            // Ottoman Dome crowned with a Starless Crescent Moon (Cami Kubbesi)
            canvas.drawCircle(64f, 32f, 20f, symbolPaint);
            canvas.drawCircle(71f, 26f, 16f, clearPaint);

            // Spire
            canvas.drawRect(61f, 50f, 67f, 62f, symbolPaint);

            // Graceful Rounded Dome
            RectF domeRect = new RectF(20f, 60f, 108f, 124f);
            canvas.drawArc(domeRect, 180f, 180f, true, symbolPaint);

            // Arched mihrab cutout inside dome
            RectF archCut = new RectF(52f, 82f, 76f, 116f);
            canvas.drawArc(archCut, 180f, 180f, true, clearPaint);
            canvas.drawRect(52f, 96f, 76f, 116f, clearPaint);

            // Sleek base bar
            RectF baseBar = new RectF(12f, 112f, 116f, 120f);
            canvas.drawRoundRect(baseBar, 4f, 4f, symbolPaint);
        } else if ("kabe".equals(symbolStyle)) {
            // Minimalist Kaaba Silhouette with Hizam Band & Door (Kâbe-i Muazzama)
            RectF cubeRect = new RectF(22f, 18f, 106f, 114f);
            canvas.drawRoundRect(cubeRect, 10f, 10f, symbolPaint);

            // Horizontal Hizam belt cutout across upper Kaaba
            canvas.drawRect(22f, 38f, 106f, 50f, clearPaint);

            // Decorative dashed gold stones along Hizam belt
            canvas.drawRect(28f, 41f, 46f, 47f, symbolPaint);
            canvas.drawRect(55f, 41f, 73f, 47f, symbolPaint);
            canvas.drawRect(82f, 41f, 100f, 47f, symbolPaint);

            // Kaaba Door (Mültezem) cutout on right side
            RectF doorRect = new RectF(70f, 66f, 90f, 106f);
            canvas.drawRoundRect(doorRect, 4f, 4f, clearPaint);
        } else {
            // Default ('sade_hilal' or 'hilal'): Pure, Starless Islamic Crescent Moon (Yıldızsız Sade Hilal)
            canvas.drawCircle(64f, 64f, 54f, symbolPaint);
            canvas.drawCircle(86f, 50f, 43f, clearPaint);
        }

        return bmp;
    }

    private Notification buildPrayerNotification() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String city = prefs.getString("city", "Hamburg");
        String hijri = prefs.getString("hijri", "22 Rebi-ül Ahir");
        String imsak = prefs.getString("imsak", "05:24");
        String gunes = prefs.getString("gunes", "07:15");
        String ogle = prefs.getString("ogle", "13:12");
        String ikindi = prefs.getString("ikindi", "16:11");
        String aksam = prefs.getString("aksam", "18:58");
        String yatsi = prefs.getString("yatsi", "20:35");
        String symbolStyle = prefs.getString("symbolStyle", "hilal");

        String[] times = new String[]{imsak, gunes, ogle, ikindi, aksam, yatsi};
        String[] labels = new String[]{"İmsak", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı"};
        String[] datLabels = new String[]{"İmsaka", "Güneşe", "Öğleye", "İkindiye", "Akşama", "Yatsıya"};

        Calendar now = Calendar.getInstance();
        int nowSec = now.get(Calendar.HOUR_OF_DAY) * 3600 + now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND);

        int nextIdx = 0;
        int remainingSec = 0;
        boolean found = false;
        for (int i = 0; i < 6; i++) {
            int slotSec = parseMinutes(times[i]) * 60;
            if (slotSec > nowSec) {
                nextIdx = i;
                remainingSec = slotSec - nowSec;
                found = true;
                break;
            }
        }
        if (!found) {
            nextIdx = 0;
            int imsakSec = parseMinutes(times[0]) * 60;
            remainingSec = (86400 - nowSec) + imsakSec;
        }

        int activeIdx = (nextIdx == 0) ? 5 : (nextIdx - 1);

        int remH = remainingSec / 3600;
        int remM = (remainingSec % 3600) / 60;
        int remS = remainingSec % 60;
        String countdownStr = String.format("%d:%02d:%02d", remH, remM, remS);
        String remainingFull = datLabels[nextIdx] + " " + countdownStr;
        String headerSubText = labels[nextIdx] + ":" + times[nextIdx] + " • " + countdownStr;

        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.notification_prayer_bar);

        SpannableString underlinedCity = new SpannableString(city);
        underlinedCity.setSpan(new UnderlineSpan(), 0, city.length(), 0);
        rv.setTextViewText(R.id.tv_notif_city, underlinedCity);

        String shortHijri = hijri.replaceAll("\\s+\\d{4}$", "");
        rv.setTextViewText(R.id.tv_notif_hijri, shortHijri);
        rv.setTextViewText(R.id.tv_notif_remaining, remainingFull);

        int[] lblIds = new int[]{
            R.id.tv_lbl_imsak, R.id.tv_lbl_gunes, R.id.tv_lbl_ogle,
            R.id.tv_lbl_ikindi, R.id.tv_lbl_aksam, R.id.tv_lbl_yatsi
        };
        int[] valIds = new int[]{
            R.id.tv_val_imsak, R.id.tv_val_gunes, R.id.tv_val_ogle,
            R.id.tv_val_ikindi, R.id.tv_val_aksam, R.id.tv_val_yatsi
        };

        int activeColor = Color.parseColor("#F97316"); // Orange highlight like Ezan Vakti Pro
        int normalLblColor = Color.parseColor("#E2E8F0");
        int normalValColor = Color.parseColor("#FFFFFF");

        for (int i = 0; i < 6; i++) {
            rv.setTextViewText(valIds[i], times[i]);
            if (i == activeIdx) {
                rv.setTextColor(lblIds[i], activeColor);
                rv.setTextColor(valIds[i], activeColor);
            } else {
                rv.setTextColor(lblIds[i], normalLblColor);
                rv.setTextColor(valIds[i], normalValColor);
            }
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= 67108864; // PendingIntent.FLAG_IMMUTABLE
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_mosque)
                .setContentTitle(city + " • " + remainingFull)
                .setContentText("İmsak " + imsak + " | Güneş " + gunes + " | Öğle " + ogle + " | İkindi " + ikindi + " | Akşam " + aksam + " | Yatsı " + yatsi)
                .setSubText(headerSubText)
                .setContentIntent(pi)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true);

        try {
            Bitmap fullColorLogo = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
            if (fullColorLogo != null) {
                builder.setLargeIcon(fullColorLogo);
            }
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Bitmap statusBmp = createStatusBarMosqueCountdownBitmap(remainingSec, symbolStyle);
                builder.setSmallIcon(Icon.createWithBitmap(statusBmp));
            } catch (Exception ignored) {}
        }

        if (Build.VERSION.SDK_INT >= 21) {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
            builder.setColor(Color.parseColor("#10B981"));
        }

        if (Build.VERSION.SDK_INT >= 24) {
            try {
                Method setCustomContent = Notification.Builder.class.getMethod("setCustomContentView", RemoteViews.class);
                Method setCustomBig = Notification.Builder.class.getMethod("setCustomBigContentView", RemoteViews.class);
                setCustomContent.invoke(builder, rv);
                setCustomBig.invoke(builder, rv);
            } catch (Exception ignored) {}
        }

        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Method setChannel = Notification.Builder.class.getMethod("setChannelId", String.class);
                setChannel.invoke(builder, CHANNEL_ID);
                Method setColorized = Notification.Builder.class.getMethod("setColorized", boolean.class);
                setColorized.invoke(builder, true);
            } catch (Exception ignored) {}
        }

        Notification n = builder.build();
        n.contentView = rv;
        if (Build.VERSION.SDK_INT >= 16) {
            n.bigContentView = rv;
        }
        n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        return n;
    }

    @Override
    public void onDestroy() {
        if (mHandler != null && mTicker != null) {
            mHandler.removeCallbacks(mTicker);
        }
        if (mTimeTickReceiver != null) {
            try {
                unregisterReceiver(mTimeTickReceiver);
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
