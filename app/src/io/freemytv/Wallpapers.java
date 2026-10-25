package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wallpapers for when the recommendations are off: the images in a folder of a public GitHub repository
 * (config.properties wallpaper_repo / wallpaper_path), kept in sync on the TV. Images added there are downloaded, the
 * ones deleted there are deleted here (checked every 3 h and when asked). Which one shows comes from Prefs.wallpaper:
 * a fixed image, or one per period of N minutes (the same on every screen at the same time, without saved state).
 */
final class Wallpapers {
    private Wallpapers() {}

    private static final long SYNC_EVERY = 3 * 3600_000L;
    private static final Object LOCK = new Object();
    private static volatile boolean syncing;

    static boolean enabled() {
        return !Config.WALLPAPER_REPO.isEmpty();
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "wallpapers");
    }

    /** The images on the TV, by name. */
    static List<String> names(Context c) {
        String[] files = dir(c).list();
        List<String> out = new ArrayList<>();
        if (files != null) for (String f : files) if (image(f)) out.add(f);
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** "beach.jpg" → "beach". */
    static String label(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').replace('-', ' ');
    }

    /** The image to show now on the home screen or the screensaver, or null without any. */
    static File current(Context c, boolean saver) {
        List<String> names = names(c);
        if (names.isEmpty()) return null;
        String choice = Prefs.wallpaper(c, saver);
        if (choice.equals("same")) choice = Prefs.wallpaper(c, false);
        if (choice.startsWith("fixed:")) {
            String name = choice.substring(6);
            return new File(dir(c), names.contains(name) ? name : names.get(0));
        }
        int minutes = 60;
        try {
            minutes = Math.max(1, Integer.parseInt(choice.substring(choice.indexOf(':') + 1)));
        } catch (RuntimeException ignored) {}
        long slot = System.currentTimeMillis() / (minutes * 60_000L);
        return new File(dir(c), names.get((int) (slot % names.size())));
    }

    /** In the background: syncs with the repository if it's been a while (or `force`). Calls `done` after, if any. */
    static void syncIfStale(Context c, boolean force, Runnable done) {
        if (!enabled() || syncing) return;
        final Context app = c.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences("wallpapers", Context.MODE_MULTI_PROCESS);
        long last = sp.getLong("synced", 0), now = System.currentTimeMillis();
        if (!force && now - last < SYNC_EVERY && now >= last && !names(app).isEmpty()) return;
        syncing = true;
        new Thread(() -> {
            try {
                synchronized (LOCK) {
                    sync(app);
                }
                sp.edit().putLong("synced", System.currentTimeMillis()).apply();
            } catch (IOException | RuntimeException e) {
                AppLog.w("wallpapers: " + e.getMessage());
            } finally {
                syncing = false;
                if (done != null) done.run();
            }
        }, "wallpapers").start();
    }

    private static void sync(Context c) throws IOException {
        String url = "https://api.github.com/repos/" + Config.WALLPAPER_REPO + "/contents/" + Config.WALLPAPER_PATH;
        JSONArray list;
        try {
            list = new JSONArray(new String(get(url, 2_000_000), "UTF-8"));
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer from GitHub");
        }
        File dir = dir(c);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        SharedPreferences shas = c.getSharedPreferences("wallpaper-shas", Context.MODE_MULTI_PROCESS);
        Set<String> wanted = new HashSet<>();
        int added = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null || !"file".equals(o.optString("type"))) continue;
            String name = o.optString("name"), sha = o.optString("sha"), download = o.optString("download_url");
            if (!image(name) || download.isEmpty() || name.contains("/")) continue;
            wanted.add(name);
            File f = new File(dir, name);
            if (f.exists() && sha.equals(shas.getString(name, ""))) continue;
            File part = new File(dir, name + ".part");
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(get(download, 30_000_000));
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save " + name);
            shas.edit().putString(name, sha).apply();
            added++;
        }
        int removed = 0;
        for (String name : names(c)) {
            if (wanted.contains(name)) continue;
            //noinspection ResultOfMethodCallIgnored
            new File(dir, name).delete();
            shas.edit().remove(name).apply();
            removed++;
        }
        AppLog.w("wallpapers: " + wanted.size() + " in " + Config.WALLPAPER_REPO + ", " + added + " new, " + removed + " removed");
    }

    private static byte[] get(String url, int max) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(10_000);
        http.setReadTimeout(30_000);
        http.setRequestProperty("Accept", "application/vnd.github+json");
        http.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            int code = http.getResponseCode();
            if (code == 404) throw new IOException("repository or folder not found (" + Config.WALLPAPER_REPO + "/" + Config.WALLPAPER_PATH + ")");
            if (code != 200) throw new IOException("GitHub answered " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            try (InputStream in = http.getInputStream()) {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > max) throw new IOException("file too big");
                }
            }
            return out.toByteArray();
        } finally {
            http.disconnect();
        }
    }

    private static boolean image(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return Arrays.asList(".jpg", ".jpeg", ".png", ".webp").contains(n.substring(Math.max(0, n.lastIndexOf('.'))));
    }
}
