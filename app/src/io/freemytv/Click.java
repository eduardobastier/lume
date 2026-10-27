package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.IOException;

/**
 * Sounds for the home screen (moving focus, OK and Back), from assets/sounds, played through SoundPool. It only plays on the home
 * screen, never inside apps. It goes out like game audio (the TV's volume, through HDMI): TV boxes route "system
 * sounds" to a built-in speaker they don't have, or keep that volume at zero, and the click was silent there.
 */
final class Click {
    private SoundPool pool;
    private int move, open, back;

    private final Context context;

    Click(Context c) {
        context = c.getApplicationContext();
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            // The sounds you chose (assets/sounds): moving the focus, OK, and Back.
            move = pool.load(c.getAssets().openFd("sounds/navigate.wav"), 1);
            open = pool.load(c.getAssets().openFd("sounds/click.wav"), 1);
            back = pool.load(c.getAssets().openFd("sounds/back.wav"), 1);
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    void move() {
        play(move, 0.5f);
    }

    void open() {
        play(open, 0.5f);
    }

    void back() {
        play(back, 0.4f);
    }

    private int playing;

    /** A new key cuts the sound still playing (Back's tail no longer adds up with the next one). */
    private void play(int sound, float volume) {
        if (pool == null || !Prefs.sounds(context)) return;
        if (playing != 0) pool.stop(playing);
        playing = pool.play(sound, volume, volume, 1, 0, 1f);
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }
}
