package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String trailer;         // YouTube id of the trailer, dubbed in Portuguese when there is one (OK on a card)
        String overview = "";   // the synopsis (OK on a card shows it)
        String poster;          // TMDB poster path (the cover, in Portuguese when there is one): the tall cards
        String where;           // streaming service in your country (recs_all: even one the TV doesn't have)
        String video;           // YouTube video id (YouTube picks only): "Watch" opens it
        boolean detailed;
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 10;
    /** Anime and cartoons never show up (TMDB's Animation genre, the same id for movies and shows). */
    private static final String NO_GENRES = "16";
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("youtube", "YouTube");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    /** The screensaver (main process) starts from the list the home screen (:home) saved last. */
    static synchronized void forget() {
        instance = null;
    }

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp;
    private volatile boolean listEnglish;   // the saved list came in English (titles, genres and texts)

    private Recs(Context c) {
        context = c;
        app = c;
        //noinspection deprecation
        cache = c.getSharedPreferences("recs", Context.MODE_MULTI_PROCESS);   // the screensaver reads it from another process
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listEnglish = cache.getBoolean("english", false);
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Checks a TMDB key (the settings screen, before keeping one typed on the TV). Network: not on the UI thread. */
    static boolean validKey(String key) {
        try {
            boolean bearer = key.startsWith("eyJ");
            HttpURLConnection c = (HttpURLConnection) new URL(API + "/configuration"
                    + (bearer ? "" : "?api_key=" + URLEncoder.encode(key, "UTF-8"))).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
            try {
                return c.getResponseCode() == 200;
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return false;
        }
    }

    static boolean hasKey(Context c) {
        return youtube() || !Prefs.tmdbKey(c).isEmpty();
    }

    /** With a YouTube key (config.properties "youtube_key"), the picks are what's trending on YouTube instead. */
    /** recs_all=true: Brazil's top movies and shows on any streaming service, not only the TV's apps. */
    /** The app's context, for the choices in Home Settings (Prefs). */
    private static Context app;

    /** A pick worth showing: on one of the TV's apps or, with recs_all, on any service in your country. */
    /** recs_genres (thriller, action, comedy, ...) → TMDB genre ids, for movies and for shows (TMDB numbers them apart). */
    private static final String[][] GENRE_IDS = {
        {"thriller", "53|9648", "9648|80"},     // suspense: thriller and mystery (shows: mystery and crime)
        {"action", "28", "10759"},
        {"comedy", "35", "35"},
        {"horror", "27", "9648"},
        {"drama", "18", "18"},
        {"scifi", "878", "10765"},
        {"animation", "16", "16"},
        {"romance", "10749", "10766"},
        {"crime", "80", "80"},
        {"adventure", "12", "10759"},
        {"fantasy", "14", "10765"},
        {"family", "10751", "10751"},
        {"documentary", "99", "99"},
    };

    /** Anime and cartoons only come up when chosen among the genres. */
    private static boolean animation() {
        return Prefs.genres(app).contains("animation");
    }

    /** The genre ids to keep for "movie" or "tv", joined with "|" (TMDB's "any of"), or "" for every genre. */
    private static String genres(String kind) {
        StringBuilder out = new StringBuilder();
        for (String g : Prefs.genres(app)) {
            for (String[] row : GENRE_IDS) {
                if (row[0].equals(g)) out.append(out.length() == 0 ? "" : "|").append(kind.equals("movie") ? row[1] : row[2]);
            }
        }
        return out.toString();
    }

    /** recs_recent=true: only new releases (movies from the past 4 months, shows that premiered in the past 6). */

    private static final int MOVIE_DAYS = 120, SHOW_DAYS = 180;

    private static boolean usable(Pick p) {
        return p.pkg != null || (Prefs.anyService(app) && p.where != null);
    }

    static boolean youtube() {
        return !Config.YOUTUBE_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        return (Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL)[period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        for (Pick p : picks) {
            boolean tab = kind == null ? !youtube() || "video".equals(p.kind) : kind.equals(p.kind);
            if (tab && (!p.detailed || usable(p))) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Three per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < 3; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** Every pick of the tab (not just this hour's 3), never two from the same franchise: the cards rotate through it. */
    List<Pick> pool(String kind) {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : picks) {
            boolean tab = kind == null ? !youtube() || "video".equals(p.kind) : kind.equals(p.kind);
            if (tab && p.detailed && usable(p) && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replaceAll("[^A-Za-z0-9._-]", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = (Prefs.recent(app) ? 13 : 0) + Prefs.signature(app) * 131L + (Prefs.anyService(app) ? 7 : 0) + (Prefs.tmdbKey(app).hashCode() * 31L + REGION.hashCode()) * 31L + Config.YOUTUBE_KEY.hashCode();   // a new key or country refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || listEnglish != Ui.en;
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale && youtube()) fetchYoutube(p);
                else if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (usable(pick)) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        // The same titles as the home screen's cards, in the same order (Explore first, then Movies and Shows).
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : pool(kind)) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : pool(null)) if (!hasImage(p)) todo.add(p);
        for (Pick p : picks) if (!p.detailed || (usable(p) && !hasImage(p) && !todo.contains(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (usable(p)) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - YouTube (Data API v3: the most popular videos in your country right now)

    private static final String YOUTUBE_API = "https://www.googleapis.com/youtube/v3/videos";
    private static final String YOUTUBE_PKG = "com.google.android.youtube.tv";

    /** Explore: trending overall; the Movies and Shows tabs: trending in Film & Animation and in Entertainment. */
    private void fetchYoutube(int p) throws Exception {
        if (!installed(YOUTUBE_PKG)) throw new IOException("the YouTube app isn't on the TV");
        List<Pick> all = youtubeChart(null, "video"), movies = new ArrayList<>(), shows = new ArrayList<>();
        try { movies = youtubeChart("1", "movie"); } catch (IOException ignored) {}
        try { shows = youtubeChart("24", "tv"); } catch (IOException ignored) {}
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (List<Pick> part : java.util.Arrays.asList(all, movies, shows)) {
            for (Pick pick : part) if (seen.add(pick.kind + pick.video)) list.add(pick);
        }
        if (list.isEmpty()) throw new IOException("YouTube returned no videos");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listEnglish = Ui.en;
    }

    private List<Pick> youtubeChart(String category, String kind) throws Exception {
        String url = YOUTUBE_API + "?part=snippet,statistics,contentDetails&chart=mostPopular&maxResults=24"
                + "&regionCode=" + REGION + "&hl=" + lang() + (category == null ? "" : "&videoCategoryId=" + category)
                + "&key=" + URLEncoder.encode(Config.YOUTUBE_KEY.trim(), "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        JSONArray items;
        try {
            int code = c.getResponseCode();
            if (code == 400 || code == 403) throw new IOException("YouTube rejected the key (HTTP " + code + ")");
            if (code != 200) throw new IOException("YouTube returned HTTP " + code);
            items = new JSONObject(read(c.getInputStream())).optJSONArray("items");
        } finally {
            c.disconnect();
        }
        List<Pick> out = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject v = items.getJSONObject(i), sn = v.optJSONObject("snippet");
            if (sn == null) continue;
            String image = thumbnail(sn.optJSONObject("thumbnails"));
            if (image == null) continue;
            Pick pick = new Pick();
            pick.video = v.optString("id");
            pick.id = pick.video.hashCode();
            pick.kind = kind;
            JSONObject loc = sn.optJSONObject("localized");
            pick.title = loc != null ? loc.optString("title", sn.optString("title")) : sn.optString("title");
            pick.backdrop = image;
            List<String> parts = new ArrayList<>();
            parts.add(sn.optString("channelTitle"));
            JSONObject st = v.optJSONObject("statistics");
            if (st != null && st.has("viewCount")) parts.add(views(st.optLong("viewCount")));
            JSONObject cd = v.optJSONObject("contentDetails");
            if (cd != null) {
                String d = duration(cd.optString("duration"));
                if (!d.isEmpty()) parts.add(d);
            }
            parts.add("YouTube");
            pick.meta = String.join(" · ", parts);
            pick.app = "youtube";
            pick.pkg = YOUTUBE_PKG;
            pick.detailed = true;
            out.add(pick);
        }
        return out;
    }

    /** The largest thumbnail (maxres is 1280×720; not every video has it). */
    private static String thumbnail(JSONObject t) {
        if (t == null) return null;
        for (String size : new String[] {"maxres", "standard", "high"}) {
            JSONObject o = t.optJSONObject(size);
            if (o != null && !o.optString("url").isEmpty()) return o.optString("url");
        }
        return null;
    }

    /** 1234567 → "1,2 mi de visualizações" / "1.2M views". */
    private static String views(long n) {
        String num;
        if (n >= 1_000_000) num = String.format(Ui.en ? Locale.US : new Locale("pt", "BR"), "%.1f", n / 1e6) + (Ui.en ? "M" : " mi");
        else if (n >= 1_000) num = (n / 1000) + (Ui.en ? "K" : " mil");
        else num = String.valueOf(n);
        return num + Ui.tr(" de visualizações", " views");
    }

    /** ISO 8601 "PT1H2M3S" → "1 h 2 min" / "1h 2m". */
    private static String duration(String iso) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matcher(iso);
        if (!m.matches()) return "";
        int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1)), min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (h == 0 && min == 0) return "";
        if (Ui.en) return h > 0 ? h + "h " + min + "m" : min + "m";
        return h > 0 ? h + " h " + min + " min" : min + " min";
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty() && !Prefs.anyService(app)) throw new IOException("none of the TV's streaming services are on TMDB");
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc" + (animation() ? "" : "&without_genres=" + NO_GENRES)
                + (Prefs.anyService(app) ? "" : "&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8"));
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < (Prefs.anyService(app) ? 30 : 20); i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (usable(pick)) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        String mg = genres("movie"), tg = genres("tv");
        JSONArray movies = get("/discover/movie?vote_count.gte=" + (Prefs.recent(app) ? 10 : 30) + "&primary_release_date.gte=" + day(Prefs.recent(app) ? -MOVIE_DAYS : -365)
                + "&primary_release_date.lte=" + day(0) + common
                + (mg.isEmpty() ? "" : "&with_genres=" + URLEncoder.encode(mg, "UTF-8"))).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=" + (Prefs.recent(app) ? 5 : 20)
                + (Prefs.recent(app) ? "&first_air_date.gte=" + day(-SHOW_DAYS) + "&first_air_date.lte=" + day(0)
                          : "&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0))
                + common + (tg.isEmpty() ? "" : "&with_genres=" + URLEncoder.encode(tg, "UTF-8"))).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 24);
        alternate(list, seen, newMovies, newShows, 24);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listEnglish = Ui.en;
    }

    /** Language of titles and genres; the services come from the region. */
    private static String lang() {
        return Ui.en ? "en-US" : "pt-BR";
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        JSONArray gids = o.optJSONArray("genre_ids");
        for (int i = 0; gids != null && !animation() && i < gids.length(); i++) if (String.valueOf(gids.optInt(i)).equals(NO_GENRES)) return;
        // Horror only when chosen: "Suspense" takes mystery too, and many horror films are also mysteries.
        boolean horror = Prefs.genres(app).contains("horror");
        for (int i = 0; gids != null && !horror && i < gids.length(); i++) if (gids.optInt(i) == 27) return;
        if (Prefs.recent(app)) {
            String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date", "");
            if (date.length() < 10 || date.compareTo(day(kind.equals("movie") ? -MOVIE_DAYS : -SHOW_DAYS)) < 0) return;
        }
        String wanted = genres(kind);
        if (!wanted.isEmpty()) {
            java.util.Set<String> keep = new java.util.HashSet<>(Arrays.asList(wanted.split("\\|")));
            JSONArray ids = o.optJSONArray("genre_ids");
            boolean match = false;
            for (int i = 0; ids != null && i < ids.length(); i++) match |= keep.contains(String.valueOf(ids.optInt(i)));
            if (!match) return;
        }
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        list.add(p);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images,videos&include_image_language=null,pt,en"
                + "&include_video_language=pt,en");
        p.trailer = trailer(o.optJSONObject("videos"));
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        p.overview = o.optString("overview", "").trim();
        String poster = o.optString("poster_path", "");
        if (!poster.isEmpty() && !"null".equals(poster)) p.poster = poster;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(Ui.en ? name : genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0 && Ui.en) parts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
            else if (minutes > 0) parts.add(minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0 && Ui.en) parts.add(seasons == 1 ? "1 season" : seasons + " seasons");
            else if (seasons > 0) parts.add(seasons == 1 ? "1 temporada" : seasons + " temporadas");
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) p.where = SERVICE_NAME.get(p.app);
        else if (flat != null && flat.length() > 0) p.where = flat.getJSONObject(0).optString("provider_name");
        if (p.where != null) parts.add(p.where);
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /** The best YouTube trailer: dubbed ("Dublado") first, then subtitled in Portuguese, then the original. */
    private static String trailer(JSONObject videos) {
        JSONArray list = videos == null ? null : videos.optJSONArray("results");
        String best = null;
        int bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null || !"YouTube".equals(v.optString("site")) || v.optString("key").isEmpty()) continue;
            String type = v.optString("type"), name = v.optString("name").toLowerCase(Locale.ROOT);
            if (!type.equals("Trailer") && !type.equals("Teaser")) continue;
            int score = (name.contains("dublad") ? 100 : 0) + ("pt".equals(v.optString("iso_639_1")) ? 40 : 0)
                    + (type.equals("Trailer") ? 10 : 0) + (v.optBoolean("official") ? 2 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = v.optString("key");
            }
        }
        return best;
    }

    /** TV genres that TMDB returns in English even in pt-BR. */
    private static final String[][] GENRES_PT = {
        {"Action & Adventure", "Ação e Aventura"}, {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"},
        {"War & Politics", "Guerra e Política"}, {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"},
        {"Talk", "Entrevistas"},
    };

    private static String genre(String name) {
        for (String[] g : GENRES_PT) if (g[0].equalsIgnoreCase(name)) return g[1];
        return name;
    }

    /** The same for lists saved earlier ("Série · Action & Adventure · …" in Portuguese). */
    private static String fixGenres(String meta) {
        for (String[] g : GENRES_PT) {
            meta = meta.replace(" · " + g[0] + " ·", " · " + g[1] + " ·");
            if (meta.endsWith(" · " + g[0])) meta = meta.substring(0, meta.length() - g[0].length()) + g[1];
        }
        return meta;
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), at the highest resolution
     * (up to 3840, TMDB's 4K) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 3840) * 100 + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    private static String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] s : SERVICES) {
            if (s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0])) return s;
        }
        return null;
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Prefs.tmdbKey(app);
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(p.backdrop.startsWith("http") ? p.backdrop : IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the original (≥ 1920 wide) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight);
            boolean forLight = light;
            Bitmap cover = forLight && p.poster != null ? poster(p) : null;
            Backdrop backdrop = Backdrop.make(b, cover, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > (forLight ? 10 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    /** The cover (poster), downloaded once next to the backdrops; null if it can't be had. */
    private Bitmap poster(Pick p) {
        File f = new File(new File(context.getCacheDir(), "posters"), p.poster.replaceAll("[^A-Za-z0-9._-]", ""));
        try {
            if (!f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.getParentFile().mkdirs();
                File part = new File(f.getPath() + ".part");
                HttpURLConnection c = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/w780" + p.poster).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(20000);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                } finally {
                    c.disconnect();
                }
                if (!part.renameTo(f)) return null;
                prune(f.getParentFile());
            }
            return BitmapFactory.decodeFile(f.getPath());
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            AppLog.w("poster: " + e.getMessage());
            return null;
        }
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 24) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 24; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                        .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed).put("video", p.video == null ? "" : p.video)
                        .put("where", p.where == null ? "" : p.where).put("poster", p.poster == null ? "" : p.poster)
                        .put("overview", p.overview == null ? "" : p.overview).put("trailer", p.trailer == null ? "" : p.trailer));
            }
            cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putBoolean("english", listEnglish)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION).apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.meta = fixGenres(o.optString("meta"));
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            p.video = o.optString("video").isEmpty() ? null : o.optString("video");
            p.where = o.optString("where").isEmpty() ? null : o.optString("where");
            p.poster = o.optString("poster").isEmpty() ? null : o.optString("poster");
            p.overview = o.optString("overview");
            p.trailer = o.optString("trailer").isEmpty() ? null : o.optString("trailer");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
