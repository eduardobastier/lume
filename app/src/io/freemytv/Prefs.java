package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Choices made on the TV in Home Settings (the theme, the recommendations and what OK on a card opens). They start from
 * config.properties and are kept on the TV. The settings screen, the home screen and the screensaver share a process
 * (:home), so they all read the same values.
 */
final class Prefs {
    private Prefs() {}

    /** Genres for the recommendations: key, Portuguese name, English name. */
    static final String[][] GENRES = {
        {"thriller", "Suspense", "Thriller"}, {"action", "Ação", "Action"}, {"comedy", "Comédia", "Comedy"},
        {"horror", "Terror", "Horror"}, {"drama", "Drama", "Drama"}, {"crime", "Crime", "Crime"},
        {"scifi", "Ficção científica", "Sci-Fi"}, {"adventure", "Aventura", "Adventure"},
        {"fantasy", "Fantasia", "Fantasy"}, {"romance", "Romance", "Romance"}, {"family", "Família", "Family"},
        {"documentary", "Documentário", "Documentary"}, {"animation", "Anime e desenhos", "Animation"},
    };

    /** What OK on a recommendation card opens. */
    static final String YOUTUBE = "youtube", SMARTTUBE = "smarttube", NUVIO = "nuvio";
    static final String YOUTUBE_PKG = "com.google.android.youtube.tv", NUVIO_PKG = "com.nuvio.tv";
    /** SmartTube (an unofficial YouTube app): the stable builds (newest package name first), then the betas. */
    static final String[] SMARTTUBE_PKGS = {"org.smarttube.stable", "com.teamsmart.videomanager.tv", "org.smarttube.beta",
            "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv"};

    private static SharedPreferences ui(Context c) {
        // The screensaver runs in the main process (on Android 12 its window must live with the service), the settings
        // in :home: reread the file when the other process changed it.
        //noinspection deprecation
        return c.getSharedPreferences("home-ui", Context.MODE_MULTI_PROCESS);
    }

    static List<String> genres(Context c) {
        String raw = ui(c).getString("recs_genres", Config.RECS_GENRES);
        List<String> out = new ArrayList<>();
        for (String g : raw.toLowerCase(Locale.ROOT).split("[,\\s]+")) if (!g.isEmpty()) out.add(g);
        return out;
    }

    static void toggleGenre(Context c, String key) {
        List<String> list = genres(c);
        if (!list.remove(key)) list.add(key);
        ui(c).edit().putString("recs_genres", String.join(",", list)).apply();
    }

    /**
     * The TMDB key typed on the TV (Home Settings → Recommendations); without one, the key built into the app; without
     * that, the last key a build of this app brought. A personal build's key is kept, so an over-the-air update to the
     * public APK (which has none) doesn't take the picks away.
     */
    static String tmdbKey(Context c) {
        String typed = ui(c).getString("tmdb_key", "").trim();
        if (!typed.isEmpty()) return typed;
        String built = Config.TMDB_KEY.trim();
        String kept = ui(c).getString("built_tmdb_key", "");
        if (built.isEmpty()) return kept;
        if (!built.equals(kept)) ui(c).edit().putString("built_tmdb_key", built).apply();
        return built;
    }

    static boolean typedTmdbKey(Context c) {
        return !ui(c).getString("tmdb_key", "").trim().isEmpty();
    }

    static void setTmdbKey(Context c, String key) {
        ui(c).edit().putString("tmdb_key", key == null ? "" : key.trim()).apply();
    }

    /** Weather on the home screen, in the drawer and on the screensaver (the sunset still sets the theme). */
    /** The recommendations (cards and their photos). Off: the home screen and the screensaver use the wallpapers. */
    static boolean recsOn(Context c) {
        return ui(c).getBoolean("recs_on", true);
    }

    /**
     * Wallpaper choice: "every:<minutes>" (rotates through the images) or "fixed:<image name>"; for the screensaver also
     * "same" (whatever the home screen shows).
     */
    static String wallpaper(Context c, boolean saver) {
        return ui(c).getString(saver ? "wp_saver" : "wp_home", saver ? "same" : "every:60");
    }

    static void setWallpaper(Context c, boolean saver, String value) {
        ui(c).edit().putString(saver ? "wp_saver" : "wp_home", value).apply();
    }

    static boolean showWeather(Context c) {
        return ui(c).getBoolean("weather", true);
    }

    /** A city typed in Home Settings ({name, latitude, longitude}), or null: then the internet connection's location. */
    static String[] weatherCity(Context c) {
        SharedPreferences p = ui(c);
        if (!p.getBoolean("weather_manual", false) || !p.contains("weather_lat")) return null;
        return new String[] {p.getString("weather_city", ""), p.getString("weather_lat", ""), p.getString("weather_lon", "")};
    }

    static boolean manualWeather(Context c) {
        return ui(c).getBoolean("weather_manual", false);
    }

    static void setWeatherCity(Context c, String name, String lat, String lon) {
        ui(c).edit().putBoolean("weather_manual", true).putString("weather_city", name).putString("weather_lat", lat)
                .putString("weather_lon", lon).apply();
    }

    static String weatherCityName(Context c) {
        return ui(c).getString("weather_city", "");
    }

    /** Navigation sounds (the soft clicks on the home screen and in Settings). */
    static boolean sounds(Context c) {
        return ui(c).getBoolean("sounds", true);
    }

    /**
     * What "Live TV" opens: "auto" (the HDMI input with a device on, like a cable TV box; else the TV's tuner),
     * "tuner", "input:<TV input id>" or "app:<package>".
     */
    static String liveTv(Context c) {
        return ui(c).getString("live_tv", "auto");
    }

    static void setLiveTv(Context c, String value) {
        ui(c).edit().putString("live_tv", value).apply();
    }

    static boolean recent(Context c) {
        return ui(c).getBoolean("recs_recent", Config.RECS_RECENT);
    }

    static boolean anyService(Context c) {
        return ui(c).getBoolean("recs_all", Config.RECS_ALL);
    }

    static void set(Context c, String key, boolean value) {
        ui(c).edit().putBoolean(key, value).apply();
    }

    static String cardAction(Context c) {
        return ui(c).getString("card_action", YOUTUBE);
    }

    static void setCardAction(Context c, String action) {
        ui(c).edit().putString("card_action", action).apply();
    }

    /** The installed SmartTube package, or null. */
    static String smartTube(Context c) {
        for (String p : SMARTTUBE_PKGS) if (HomeApps.launchIntent(c.getPackageManager(), p) != null) return p;
        return null;
    }

    /** Changes when a choice that decides which titles come up changes (the list is fetched again). */
    static int signature(Context c) {
        return Arrays.asList(genres(c).toString(), recent(c), anyService(c)).hashCode();
    }

    static String genreNames(Context c) {
        List<String> names = new ArrayList<>();
        for (String[] g : GENRES) if (genres(c).contains(g[0])) names.add(Ui.en ? g[2] : g[1]);
        return names.isEmpty() ? Ui.tr("Todas", "All") : String.join(", ", names);
    }
}
