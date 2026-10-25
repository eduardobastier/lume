package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * The screensaver as a plain screen, for TVs that don't let it be the system's (Fire TV: only Amazon's own is
 * allowed). HomeButton opens it when the remote has been idle and nothing is playing; any key closes it.
 */
public class IdleSaver extends Activity {
    private DreamView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new DreamView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.start();
    }

    @Override protected void onPause() {
        view.stop();
        super.onPause();
        finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
