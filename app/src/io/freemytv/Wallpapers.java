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
 * Wallpapers for when the recommendations are off, downloaded on the TV (none come with the app) from the source chosen
 * in Home Settings (Prefs.wallpaperSource): Bing's images of the day, Wallhaven's most popular (or a search), or a
 * folder of a public GitHub repository (config.properties wallpaper_repo / wallpaper_path). New images are downloaded
 * and the ones gone from the source are deleted here (checked every 3 h and when asked); changing the source starts
 * over. Which one shows comes from Prefs.wallpaper:
 * a fixed image, or one per period of N minutes (the same on every screen at the same time, without saved state).
 */
final class Wallpapers {
    private Wallpapers() {}

    private static final long SYNC_EVERY = 3 * 3600_000L;
    private static final Object LOCK = new Object();
    private static volatile boolean syncing;

    /** At most this many images on the TV (Wallhaven's are large). */
    private static final int MAX = 12;

    static boolean enabled() {
        return true;
    }

    /** The source in words, for Home Settings. */
    static String sourceName(Context c) {
        String s = Prefs.wallpaperSource(c);
        if (s.equals("bing")) return Ui.tr("Bing: imagens do dia", "Bing: images of the day");
        if (s.startsWith("wallhaven:")) {
            String q = s.substring(10);
            return q.isEmpty() ? Ui.tr("Wallhaven: mais populares", "Wallhaven: most popular") : "Wallhaven: " + q;
        }
        return "GitHub: " + Config.WALLPAPER_REPO;
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

    /** The image's title when the source gives one (Bing), else its name: "beach.jpg" → "beach". */
    static String label(Context c, String name) {
        String title = c.getSharedPreferences("wallpaper-shas", Context.MODE_MULTI_PROCESS).getString("t:" + name, "");
        return title.isEmpty() ? label(name) : title;
    }

    static String label(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').replace('-', ' ');
    }

    /** The image to show now in a slot (Prefs.WP_LIGHT, WP_DARK or WP_SAVER), or null without any. */
    static File current(Context c, int slot) {
        List<String> names = names(c);
        if (names.isEmpty()) return null;
        String choice = Prefs.wallpaper(c, slot);
        // The screensaver (always dark) follows the dark theme, which follows the light one.
        if (choice.equals("same") && slot == Prefs.WP_SAVER) choice = Prefs.wallpaper(c, Prefs.WP_DARK);
        if (choice.equals("same")) choice = Prefs.wallpaper(c, Prefs.WP_LIGHT);
        if (choice.startsWith("fixed:")) {
            String name = choice.substring(6);
            return new File(dir(c), names.contains(name) ? name : names.get(0));
        }
        int minutes = 60;
        try {
            minutes = Math.max(1, Integer.parseInt(choice.substring(choice.indexOf(':') + 1)));
        } catch (RuntimeException ignored) {}
        long period = System.currentTimeMillis() / (minutes * 60_000L);
        return new File(dir(c), names.get((int) (period % names.size())));
    }

    /** In the background: syncs with the repository if it's been a while (or `force`). Calls `done` after, if any. */
    static void syncIfStale(Context c, boolean force, Runnable done) {
        if (syncing) return;
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

    /** One image at the source: its file name here, where to download it, and what changes when it's replaced. */
    private static final class Entry {
        final String name, url, version;
        String title = "";

        Entry(String name, String url, String version) {
            this.name = name;
            this.url = url;
            this.version = version;
        }
    }

    private static List<Entry> list(Context c, String source) throws IOException {
        List<Entry> out = new ArrayList<>();
        try {
            if (source.equals("bing")) {
                String mkt = Ui.en ? "en-US" : "pt-BR";
                JSONArray images = new JSONObject(text("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=" + mkt))
                        .getJSONArray("images");
                for (int i = 0; i < images.length(); i++) {
                    JSONObject o = images.getJSONObject(i);
                    String date = o.optString("startdate");
                    Entry e = new Entry("bing-" + date + ".jpg", "https://www.bing.com" + o.optString("url"), date);
                    e.title = o.optString("title");
                    out.add(e);
                }
            } else if (source.startsWith("wallhaven:")) {
                String q = source.substring(10);
                String url = "https://wallhaven.cc/api/v1/search?categories=100&purity=100&atleast=1920x1080&ratios=16x9"
                        + (q.isEmpty() ? "&sorting=toplist&topRange=1M" : "&sorting=relevance&q=" + java.net.URLEncoder.encode(q, "UTF-8"));
                JSONArray data = new JSONObject(text(url)).getJSONArray("data");
                for (int i = 0; i < data.length() && out.size() < MAX; i++) {
                    JSONObject o = data.getJSONObject(i);
                    if (o.optLong("file_size") > 9_000_000) continue;     // a huge PNG would take long on a stick
                    String path = o.optString("path");
                    out.add(new Entry("wallhaven-" + path.substring(path.lastIndexOf('-') + 1), path, o.optString("id")));
                }
            } else {
                JSONArray items = new JSONArray(text("https://api.github.com/repos/" + Config.WALLPAPER_REPO + "/contents/" + Config.WALLPAPER_PATH));
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    String name = o.optString("name"), download = o.optString("download_url");
                    if (!"file".equals(o.optString("type")) || !image(name) || download.isEmpty() || name.contains("/")) continue;
                    out.add(new Entry(name, download, o.optString("sha")));
                }
            }
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer from " + source);
        }
        return out;
    }

    private static String text(String url) throws IOException {
        return new String(get(url, 2_000_000), "UTF-8");
    }

    private static void sync(Context c) throws IOException {
        String source = Prefs.wallpaperSource(c);
        SharedPreferences shas = c.getSharedPreferences("wallpaper-shas", Context.MODE_MULTI_PROCESS);
        if (!source.equals(shas.getString(" source", ""))) {
            // Another source: start over (the old images go).
            for (String name : names(c)) //noinspection ResultOfMethodCallIgnored
                new File(dir(c), name).delete();
            shas.edit().clear().putString(" source", source).apply();
        }
        List<Entry> entries = list(c, source);
        File dir = dir(c);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        Set<String> wanted = new HashSet<>();
        int added = 0;
        for (Entry e : entries) {
            if (!image(e.name)) continue;
            wanted.add(e.name);
            if (!e.title.isEmpty()) shas.edit().putString("t:" + e.name, e.title).apply();
            File f = new File(dir, e.name);
            if (f.exists() && e.version.equals(shas.getString(e.name, ""))) continue;
            File part = new File(dir, e.name + ".part");
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(get(e.url, 30_000_000));
            } catch (IOException ex) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                AppLog.w("wallpaper " + e.name + ": " + ex.getMessage());
                continue;
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save " + e.name);
            shas.edit().putString(e.name, e.version).apply();
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
        AppLog.w("wallpapers: " + wanted.size() + " from " + source + ", " + added + " new, " + removed + " removed");
    }

    private static byte[] get(String url, int max) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(10_000);
        http.setReadTimeout(30_000);
        http.setRequestProperty("Accept", "application/vnd.github+json");
        http.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            int code = http.getResponseCode();
            if (code == 404) throw new IOException("not found: " + url);
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
