package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
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
 * It reads nothing on the screen and no other key. HomeMode turns it on and off with the mode.
 */
public class HomeButton extends AccessibilityService {
    static final String AMAZON_HOME = "com.amazon.tv.launcher";
    static final String COMPONENT = HomeMode.PKG + "/" + HomeMode.PKG + ".HomeButton";

    @Override protected boolean onKeyEvent(KeyEvent e) {
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

    @Override public void onInterrupt() {}
}
