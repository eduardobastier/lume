package io.freemytv;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Settings → Updates → Send diagnostics: for a TV that isn't in front of you. Gathers what decides whether the
 * screensaver starts and the clicks are heard (system settings, dream and power state, audio volumes and routing) plus
 * the recent log, and sends it as a text file to the ntfy.sh topic in config.properties (diag_topic), read in the ntfy
 * app. No keys or passwords: the TMDB/YouTube keys never go in, and the log never has them (see AppLog).
 */
final class Diagnostics {
    private Diagnostics() {}

    static boolean enabled() {
        return !Config.DIAG_TOPIC.isEmpty();
    }

    /** Collects and sends (on a background thread). Throws with a short reason if it couldn't be sent. */
    static void send(Context c) throws IOException {
        byte[] body = collect(c).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection http = (HttpURLConnection) new URL("https://ntfy.sh/" + Config.DIAG_TOPIC).openConnection();
        try {
            http.setRequestMethod("PUT");
            http.setDoOutput(true);
            http.setConnectTimeout(10_000);
            http.setReadTimeout(30_000);
            http.setFixedLengthStreamingMode(body.length);
            http.setRequestProperty("Title", "Free My TV: " + Build.MANUFACTURER + " " + Build.MODEL);
            http.setRequestProperty("Filename", "freemytv-" + Build.MODEL.replaceAll("[^\\w-]", "_") + ".txt");
            try (OutputStream out = http.getOutputStream()) {
                out.write(body);
            }
            int code = http.getResponseCode();
            if (code / 100 != 2) throw new IOException("ntfy answered " + code);
        } finally {
            http.disconnect();
        }
    }

    private static String collect(Context c) {
        StringBuilder b = new StringBuilder();
        b.append("Free My TV ").append(Updater.currentVersion(c)).append('\n');
        b.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append("), Android ")
                .append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        b.append(Build.FINGERPRINT).append("\n\n");

        b.append("== App\n");
        b.append("navigation sounds: ").append(Prefs.sounds(c)).append('\n');
        b.append("mode state: ").append(HomeMode.state(c)).append('\n');
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            for (int[] s : new int[][] {{AudioManager.STREAM_MUSIC, 0}, {AudioManager.STREAM_SYSTEM, 1},
                    {AudioManager.STREAM_NOTIFICATION, 2}}) {
                b.append(new String[] {"music", "system", "notification"}[s[1]]).append(" volume: ")
                        .append(am.getStreamVolume(s[0])).append('/').append(am.getStreamMaxVolume(s[0]))
                        .append(am.isStreamMute(s[0]) ? " (muted)" : "").append('\n');
            }
            b.append("fixed volume: ").append(am.isVolumeFixed()).append('\n');
        }
        b.append('\n');

        try (Adb adb = Adb.connect(c, 10_000, null)) {
            section(b, adb, "Settings", "for k in screensaver_enabled screensaver_components screensaver_default_component"
                    + " screensaver_activate_on_sleep screensaver_activate_on_dock sleep_timeout attentive_timeout; do"
                    + " echo \"secure $k = $(settings get secure $k)\"; done;"
                    + " for k in screen_off_timeout sound_effects_enabled; do echo \"system $k = $(settings get system $k)\"; done;"
                    + " for k in stay_on_while_plugged_in; do echo \"global $k = $(settings get global $k)\"; done");
            section(b, adb, "Dreams", "dumpsys dreams | head -60");
            section(b, adb, "Power", "dumpsys power | grep -iE 'dream|timeout|wakefulness|stayon|userActivity|wake lock|PARTIAL|FULL|SCREEN' | head -80");
            section(b, adb, "Dream services", "cmd package query-services --brief -a android.service.dreams.DreamService");
            section(b, adb, "Audio", "dumpsys audio | grep -iE 'stream_(music|system)|alias|devices|fixed|hdmi|output|mute' | head -80");
            section(b, adb, "Log", "logcat -d -v time -t 1500 | grep -iE 'FreeMyTV|freemytv|dream|AndroidRuntime|SoundPool|AudioTrack|AudioFlinger' | tail -300");
        } catch (IOException e) {
            b.append("== ADB unavailable: ").append(e.getMessage()).append('\n');
        }
        return b.toString();
    }

    private static void section(StringBuilder b, Adb adb, String title, String command) {
        b.append("== ").append(title).append('\n');
        try {
            b.append(adb.shell(command).trim());
        } catch (IOException e) {
            b.append("(failed: ").append(e.getMessage()).append(')');
        }
        b.append("\n\n");
    }
}
