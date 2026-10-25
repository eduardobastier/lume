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
 * the TV's full log (all buffers, crashes included), and sends it as a file to the Telegram chat in config.properties
 * (telegram_bot_token, telegram_chat_id). The TMDB/YouTube keys never go in, and the app's log never has them.
 */
final class Diagnostics {
    private Diagnostics() {}

    static boolean enabled() {
        return !Config.TELEGRAM_TOKEN.isEmpty() && !Config.TELEGRAM_CHAT.isEmpty();
    }

    /** Collects and sends (on a background thread). Throws with a short reason if it couldn't be sent. */
    static void send(Context c) throws IOException {
        byte[] file = collect(c).getBytes(StandardCharsets.UTF_8);
        String boundary = "freemytv" + System.nanoTime();
        String name = "freemytv-" + Build.MODEL.replaceAll("[^\\w-]", "_") + "-" + System.currentTimeMillis() / 1000 + ".txt";
        String caption = "Free My TV " + Updater.currentVersion(c) + " · " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE;
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n" + Config.TELEGRAM_CHAT
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n" + caption
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\"" + name
                + "\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n";
        byte[] start = head.getBytes(StandardCharsets.UTF_8);
        byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection http = (HttpURLConnection) new URL("https://api.telegram.org/bot" + Config.TELEGRAM_TOKEN
                + "/sendDocument").openConnection();
        try {
            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setConnectTimeout(10_000);
            http.setReadTimeout(60_000);
            http.setFixedLengthStreamingMode(start.length + file.length + end.length);
            http.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = http.getOutputStream()) {
                out.write(start);
                out.write(file);
                out.write(end);
            }
            int code = http.getResponseCode();
            if (code / 100 != 2) throw new IOException("Telegram answered " + code);
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
        b.append("accessibility services: ").append(android.provider.Settings.Secure.getString(c.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)).append('\n');
        b.append("home button service on: ").append(HomeButton.enabled(c)).append('\n');
        for (android.content.pm.ResolveInfo r : c.getPackageManager().queryIntentActivities(new android.content.Intent(
                android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)) {
            b.append("home screen: ").append(r.activityInfo.packageName).append('/').append(r.activityInfo.name).append('\n');
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
            section(b, adb, "Crashes", "logcat -d -b crash -v time");
            section(b, adb, "Log (everything since boot or as far back as the TV keeps)", "logcat -d -b main,system,events -v time");
        } catch (IOException e) {
            b.append("== ADB unavailable: ").append(e).append("\n\n");
            // Without ADB, the app may still read its own log (its processes only).
            b.append("== This app's log\n");
            try {
                Process p = Runtime.getRuntime().exec(new String[] {"logcat", "-d", "-v", "time"});
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                try (java.io.InputStream in = p.getInputStream()) {
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                b.append(out.toString("UTF-8"));
            } catch (IOException ex) {
                b.append("(failed: ").append(ex).append(")\n");
            }
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
