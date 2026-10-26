package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire TV: Fire OS ignores `set-home-activity` and Home always opens Amazon's home screen. This accessibility service
 * (the only way apps get around that), in Lume mode:
 * - takes the Home key and opens ours. Letting Home through and opening ours after Amazon's showed it only 4 to 6 s
 *   later: Android holds back apps opening for 5 s after Home. (So holding Home no longer opens Fire OS's quick menu;
 *   its settings are still in our home screen's settings button.)
 * - as a fallback (boot, Amazon's screen opened some other way), opens ours when Amazon's shows up (the
 *   known package, or whatever Home resolves to on this model).
 * - on Fire TV, where only Amazon's screensaver may be the system's, opens ours (IdleSaver) after IDLE_MS without a
 *   key and with nothing playing; 4 min, so it comes before Amazon's (5 min by default).
 * It reads nothing on the screen; other keys are only timed, never kept. Fire OS has no switch for it: after
 * ENABLE_COMMAND (once) the app turns it on itself (selfEnable) and a job turns it back on; the mode decides if it acts.
 */
public class HomeButton extends AccessibilityService {
    static final String AMAZON_HOME = "com.amazon.tv.launcher";
    static final String COMPONENT = HomeMode.PKG + "/" + HomeMode.CODE + ".HomeButton";
    private static final long IDLE_MS = 4 * 60_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastKey = SystemClock.uptimeMillis();

    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            long idle = SystemClock.uptimeMillis() - lastKey;
            if (idle >= IDLE_MS) {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (free() && (am == null || !am.isMusicActive())) openSaver();
                lastKey = SystemClock.uptimeMillis();
                idle = 0;
            }
            handler.postDelayed(this, IDLE_MS - idle + 1000);
        }
    };

    /** Turns this service on (Fire OS has no switch for it in Accessibility): run from ATV Tools or adb shell. */
    static final String ENABLE_COMMAND = "pm grant " + HomeMode.PKG + " android.permission.WRITE_SECURE_SETTINGS";

    /** Whether the one-time permission (ENABLE_COMMAND) was given. */
    static boolean canWriteSettings(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Fire TV with the permission: turns this service on (keeping other services, minus other Home takers that fight
     * over the same key) and accessibility itself. True if it's on afterwards.
     */
    static boolean selfEnable(Context c) {
        if (!fireTv() || !canWriteSettings(c)) return enabled(c);
        if (enabled(c) && "1".equals(android.provider.Settings.Secure.getString(c.getContentResolver(), "accessibility_enabled"))) return true;
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            String list = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            java.util.List<String> kept = new java.util.ArrayList<>();
            if (list != null) for (String one : list.split(":")) {
                if (one.isEmpty() || one.equals(COMPONENT) || one.startsWith("io.github.toolicious.homeonfire/")) continue;
                kept.add(one);
            }
            kept.add(COMPONENT);
            android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", kept));
            android.provider.Settings.Secure.putString(cr, "accessibility_enabled", "1");
            AppLog.w("home button: turned on by the app");
        } catch (RuntimeException e) {
            AppLog.w("home button: couldn't turn it on: " + e.getMessage());
        }
        return enabled(c);
    }

    /** Every 15 min (and at boot): turns the service back on if Fire OS or another app turned it off. */
    static void scheduleWatch(Context c) {
        if (!fireTv()) return;
        android.app.job.JobScheduler js = c.getSystemService(android.app.job.JobScheduler.class);
        if (js == null) return;
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putString("action", ModeJob.WATCH);
        extras.putString("mode", "");
        js.schedule(new android.app.job.JobInfo.Builder(1999, new android.content.ComponentName(c, ModeJob.class))
                .setExtras(extras).setPeriodic(15 * 60_000L).setPersisted(true).build());
    }

    /** Whether this service is on (apps may read the list). */
    static boolean enabled(Context c) {
        String list = android.provider.Settings.Secure.getString(c.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return list != null && java.util.Arrays.asList(list.split(":")).contains(COMPONENT);
    }

    static boolean fireTv() {
        return "Amazon".equalsIgnoreCase(Build.MANUFACTURER);
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        scheduleWatch(this);
        // Ask for the remote's keys again at run time (some Fire OS builds only honor the flag set this way).
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
            AppLog.w("home button: service connected, flags=" + getServiceInfo().flags
                    + " caps=" + getServiceInfo().getCapabilities());
        } catch (RuntimeException ex) {
            AppLog.w("home button: service connected, no key flag: " + ex.getMessage());
        }
        if (fireTv()) handler.postDelayed(idleCheck, IDLE_MS);
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(idleCheck);
        super.onDestroy();
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        lastKey = SystemClock.uptimeMillis();
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME || !free()) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) openHome();
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (e.getPackageName() != null && e.getPackageName().toString().equals(getPackageName())) {
            hideCover();     // Lume's home screen is up: the cover can go
            return;
        }
        if (e.getPackageName() == null || !amazonHome(e.getPackageName().toString())) return;
        // Amazon's launcher also hosts Fire OS's settings (MainSettingsActivity...): only its home screen counts.
        CharSequence cls = e.getClassName();
        if (cls == null || !cls.toString().contains("HomeActivity")) return;
        AppLog.w("home button: Amazon's home screen showed up (" + e.getClassName() + "), free=" + free());
        if (free()) openHome();
    }

    /** Amazon's home screen: the known package, or whatever the system opens for Home on this model. */
    private String resolvedHome;

    private boolean amazonHome(String pkg) {
        if (pkg.equals(AMAZON_HOME)) return true;
        if (resolvedHome == null) {
            android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            resolvedHome = r != null && r.activityInfo != null ? r.activityInfo.packageName : "";
        }
        return pkg.equals(resolvedHome) && !pkg.equals(getPackageName()) && !pkg.equals("android");
    }

    private boolean free() {
        return HomeMode.FREE.equals(HomeMode.state(this).optString("mode"));
    }

    // MARK: - Cover: after Home, Fire OS (Android 9) holds back any app opening for ~5 s, so Amazon's home screen would
    // show meanwhile. An accessibility overlay (allowed right away) covers it until Lume's screen is up.

    private android.view.View cover;

    private void showCover() {
        if (cover != null) return;
        try {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.OPAQUE);
            android.widget.FrameLayout v = new android.widget.FrameLayout(this);
            v.setBackgroundColor(0xFF0B0B0D);
            android.widget.ProgressBar spin = new android.widget.ProgressBar(this);
            v.addView(spin, new android.widget.FrameLayout.LayoutParams(96, 96, android.view.Gravity.CENTER));
            wm.addView(v, lp);
            cover = v;
            handler.postDelayed(this::hideCover, 9000);     // never stuck if Lume doesn't come up
        } catch (RuntimeException ex) {
            AppLog.w("home button: no cover: " + ex.getMessage());
        }
    }

    private void hideCover() {
        if (cover == null) return;
        try {
            ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        } catch (RuntimeException ignored) {
        }
        cover = null;
    }

    private void openHome() {
        AppLog.w("home button: opening Lume");
        showCover();
        try {
            // No CATEGORY_HOME: Fire OS sends home intents to Amazon's launcher whatever their target.
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException ex) {
            AppLog.w("could not open the home screen over Amazon's: " + ex.getMessage());
        }
    }

    private void openSaver() {
        try {
            startActivity(new Intent(this, IdleSaver.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ex) {
            AppLog.w("could not open the screensaver: " + ex.getMessage());
        }
    }

    @Override public void onInterrupt() {}
}
