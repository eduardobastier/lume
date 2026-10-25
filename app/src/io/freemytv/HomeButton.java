package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire TV: Fire OS ignores `set-home-activity` and Home always opens Amazon's home screen. This accessibility service
 * (the only way apps get around that) watches for that screen alone (packageNames in res/xml/home_button.xml) and,
 * in Free My TV mode, opens ours over it. It reads nothing on the screen and doesn't touch keys, so a long press on
 * Home still opens Fire OS's quick menu. HomeMode turns it on and off with the mode.
 */
public class HomeButton extends AccessibilityService {
    static final String AMAZON_HOME = "com.amazon.tv.launcher";
    static final String COMPONENT = HomeMode.PKG + "/" + HomeMode.PKG + ".HomeButton";

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (e.getPackageName() == null || !AMAZON_HOME.contentEquals(e.getPackageName())) return;
        if (!HomeMode.FREE.equals(HomeMode.state(this).optString("mode"))) return;
        try {
            startActivity(new Intent(this, HomeActivity.class).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException ex) {
            AppLog.w("could not open the home screen over Amazon's: " + ex.getMessage());
        }
    }

    @Override public void onInterrupt() {}
}
