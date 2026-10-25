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
 * (the only way apps get around that), in Free My TV mode:
 * - takes the Home key and opens ours. Letting Home through and opening ours after Amazon's showed it only 4 to 6 s
 *   later: Android holds back apps opening for 5 s after Home. (So holding Home no longer opens Fire OS's quick menu;
 *   its settings are still in our home screen's settings button.)
 * - as a fallback (boot, Amazon's screen opened some other way), opens ours when Amazon's shows up (packageNames in
 *   res/xml/home_button.xml).
 * - on Fire TV, where only Amazon's screensaver may be the system's, opens ours (IdleSaver) after IDLE_MS without a
 *   key and with nothing playing; 4 min, so it comes before Amazon's (5 min by default).
 * It reads nothing on the screen; other keys are only timed, never kept. HomeMode turns it on and off with the mode
 * (through ADB); on a Fire TV without ADB you turn it on yourself in Accessibility.
 */
public class HomeButton extends AccessibilityService {
    static final String AMAZON_HOME = "com.amazon.tv.launcher";
    static final String COMPONENT = HomeMode.PKG + "/" + HomeMode.PKG + ".HomeButton";
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

    static boolean fireTv() {
        return "Amazon".equalsIgnoreCase(Build.MANUFACTURER);
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
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
        if (e.getPackageName() == null || !AMAZON_HOME.contentEquals(e.getPackageName())) return;
        if (free()) openHome();
    }

    private boolean free() {
        return HomeMode.FREE.equals(HomeMode.state(this).optString("mode"));
    }

    private void openHome() {
        try {
            startActivity(new Intent(this, HomeActivity.class).addCategory(Intent.CATEGORY_HOME)
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
