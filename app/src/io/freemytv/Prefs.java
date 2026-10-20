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
    /** SmartTube (an unofficial YouTube app): the stable build, then the beta. */
    static final String[] SMARTTUBE_PKGS = {"com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta",
            "com.liskovsoft.smarttubetv"};

    private static SharedPreferences ui(Context c) {
        return c.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
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

    /** The TMDB key typed on the TV (Home Settings → Recommendations); without one, the key built into the app. */
    static String tmdbKey(Context c) {
        String typed = ui(c).getString("tmdb_key", "").trim();
        return typed.isEmpty() ? Config.TMDB_KEY.trim() : typed;
    }

    static boolean typedTmdbKey(Context c) {
        return !ui(c).getString("tmdb_key", "").trim().isEmpty();
    }

    static void setTmdbKey(Context c, String key) {
        ui(c).edit().putString("tmdb_key", key == null ? "" : key.trim()).apply();
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
