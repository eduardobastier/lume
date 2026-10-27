package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /** Wallpaper slots: the home screen in the light theme, in the dark theme, and the screensaver. */
    static final int WP_LIGHT = 0, WP_DARK = 1, WP_SAVER = 2;
    private static final String[] WP_KEYS = {"wp_home", "wp_home_dark", "wp_saver"};

    /**
     * Wallpaper choice: "every:<minutes>" (rotates through the images) or "fixed:<image name>"; for the dark theme and
     * the screensaver also "same" (the dark theme: whatever the light one shows; the screensaver: the dark theme's).
     */
    static String wallpaper(Context c, int slot) {
        return ui(c).getString(WP_KEYS[slot], slot == WP_LIGHT ? "every:60" : "same");
    }

    /**
     * Where the wallpapers come from: "wallhaven:<search>" (the default: Wallhaven's most popular, or a search),
     * "bing" (Bing's images of the day) or "github" (the repository in config.properties).
     */
    static String wallpaperSource(Context c) {
        String s = ui(c).getString("wp_source", "wallhaven:");
        // The GitHub folder only counts once it's been seen with images (until then it isn't offered either).
        return s.equals("github") && !Wallpapers.githubHasImages(c) ? "wallhaven:" : s;
    }

    static void setWallpaperSource(Context c, String value) {
        ui(c).edit().putString("wp_source", value).apply();
    }

    /** Wallpapers left out of the rotation (by name); new images join it on their own. */
    static Set<String> wallpaperSkip(Context c) {
        return new HashSet<>(ui(c).getStringSet("wp_skip", new HashSet<>()));
    }

    static void toggleWallpaperSkip(Context c, String name) {
        Set<String> skip = wallpaperSkip(c);
        if (!skip.remove(name)) skip.add(name);
        ui(c).edit().putStringSet("wp_skip", skip).apply();
    }

    static void setWallpaper(Context c, int slot, String value) {
        ui(c).edit().putString(WP_KEYS[slot], value).apply();
    }

    /** The weather pill at the top: true = right, next to the clock; false = in the middle. */
    static boolean weatherRight(Context c) {
        return ui(c).getBoolean("weather_right", true);
    }

    /** Shelf icons: false = each app's logo on Lume's tile (default); true = the app's own TV art, untouched. */
    static boolean shelfArt(Context c) {
        return ui(c).getBoolean("shelf_art", false);
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

    /** Dock size: 0 large (default), 1 medium, 2 small. */
    static int dockSize(Context c) {
        return ui(c).getInt("dock_size", 0);
    }

    static void setDockSize(Context c, int size) {
        ui(c).edit().putInt("dock_size", size).apply();
    }

    /** How much the dock shrinks for its size. */
    static float dockScale(Context c) {
        int size = dockSize(c);
        return size == 2 ? 0.68f : size == 1 ? 0.84f : 1f;
    }

    /** Outlines and backgrounds, each on its own: the top's pills and buttons, the dock, the panel below. */
    static boolean topBorders(Context c) {
        return ui(c).getBoolean("border_top", ui(c).getBoolean("borders", false));
    }

    static boolean topBackground(Context c) {
        return ui(c).getBoolean("bg_top", true);
    }

    static boolean dockBorders(Context c) {
        return ui(c).getBoolean("border_dock", ui(c).getBoolean("borders", false));
    }

    /** The glass bar behind the dock's apps (off: just the apps). */
    static boolean dockBackground(Context c) {
        return ui(c).getBoolean("dock_bg", true);
    }

    static boolean panelBorders(Context c) {
        return ui(c).getBoolean("border_panel", ui(c).getBoolean("borders", false));
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
