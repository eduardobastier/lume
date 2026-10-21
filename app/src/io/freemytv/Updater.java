package io.freemytv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates over the air from the GitHub releases of config.properties "update_repo" (owner/name): the newest release's
 * APK is downloaded and handed to Android's installer, which asks the person to confirm. The APK must be signed with
 * the same key (freemytv.keystore), as every release is.
 */
final class Updater {
    private Updater() {}

    static final class Release {
        String tag, name, notes, apk;
        long size;
    }

    interface Progress { void update(int percent); }

    private static final String REPO = Config.UPDATE_REPO.trim();

    static boolean enabled() {
        return REPO.contains("/");
    }

    static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** The latest release with an APK, or null. Network: not on the UI thread. */
    static Release latest() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("GitHub returned HTTP " + c.getResponseCode());
            JSONObject o = new JSONObject(read(c.getInputStream()));
            JSONArray assets = o.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (!a.optString("name").endsWith(".apk")) continue;
                Release r = new Release();
                r.tag = o.optString("tag_name");
                r.name = o.optString("name", r.tag);
                r.notes = o.optString("body", "");
                r.apk = a.optString("browser_download_url");
                r.size = a.optLong("size");
                return r;
            }
            return null;
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        } finally {
            c.disconnect();
        }
    }

    /** "v1.2" is newer than "1.1.1"? Compares the numbers part by part. */
    static boolean newer(String tag, String current) {
        String[] a = tag.replaceAll("[^0-9.]", "").split("\\."), b = current.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** Downloads the release's APK into the cache. Network: not on the UI thread. */
    static File download(Context c, Release r, Progress progress) throws IOException {
        File f = new File(c.getCacheDir(), "update.apk");
        HttpURLConnection conn = (HttpURLConnection) new URL(r.apk).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "FreeMyTV");
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            long total = r.size > 0 ? r.size : conn.getContentLengthLong(), done = 0;
            byte[] buffer = new byte[32768];
            int n, last = -1;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                done += n;
                int p = total > 0 ? (int) (done * 100 / total) : 0;
                if (p != last) progress.update(last = p);
            }
        } finally {
            conn.disconnect();
        }
        return f;
    }

    /** Hands the APK to Android's installer; it shows its own "Update this app?" screen. */
    static void install(Context c, File apk) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                session.fsync(out);
            }
            int flags = Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE: the installer fills in the status */ : 0;
            PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(c, Done.class), flags | PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(done.getIntentSender());
        }
    }

    /** The installer's answer: it needs the person's OK (we open its screen), or it failed. */
    public static class Done extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                AppLog.w("update: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                android.widget.Toast.makeText(c, Ui.tr("A atualização não foi instalada: ", "The update wasn't installed: ")
                        + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }
}
