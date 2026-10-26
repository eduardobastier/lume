package io.freemytv;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What shows up on the home screen and in the "All Apps" sheet, and how to open each thing.
 *
 * On the home shelf, each app is its colored logo on a dark card: the official logo in assets/logos/<key>.png
 * (downloaded from Wikimedia Commons by tools/logos.py) or, without one, the logo lifted from the app's TV art (the
 * Android TV "banner"), saved to disk after the first time. In "All Apps", 16:9 cards with the whole TV art (or the
 * icon on a dark card). Shortcuts (live TV, inputs, All Apps, settings) are icon buttons.
 */
final class HomeApps {
    private HomeApps() {}

    static final int APP = 0, LIVE_TV = 1, INPUT = 2, ALL_APPS = 3, SETTINGS = 4, ANDROID_SETTINGS = 5;
    /** The "HDMI Inputs" button: opens the bottom drawer on the inputs (the home screen handles it). */
    static final int INPUTS = 7;
    /** The network button (Wi‑Fi or cable): opens the TV's network settings. */
    static final int NETWORK = 8;
    /** Image size of each card (16:9; on screen it shows up to ~330 px wide). */
    static final int TILE_W = 384, TILE_H = 216;

    static final class Item {
        int kind;
        String key, label, pkg, inputId, number;
        String uri;         // apps: open this link inside the app (a YouTube video) instead of the app's start screen
        boolean fresh;      // with uri: start the app over, for apps that ignore a link while they're already open
        String symbol;      // icon for the shortcuts and special cards
        Bitmap tile;        // 16:9 card (apps and specials)
        Bitmap logo;        // colored logo with no background (shelf); null = use the card
        Bitmap logoDark;    // version for a light background (YouTube's white icon), if any
        float logoInk;      // fraction of the logo with ink (thin logos are drawn larger, with the same "weight")
        float logoScale = 1; // size tweak by eye (compact logos need more)
        boolean connected = true;
        boolean inDock;
        int fill, fillBottom; // icon-only apps: the card continues the icon's background, top to bottom (0 = Lume's card)     // All Apps: this app is on the home shelf (shown ticked)
    }

    /**
     * Apps on the home shelf, in this order, when installed (up to MAX_SHELF): key, name, package, brand color (for
     * its logo on glass) and how to lift the logo from the app's TV art: "tint" = a white logo takes the art's
     * background color (Prime Video, Globoplay); "play" = the white triangle inside the red button stays white
     * (YouTube); empty = the art's colors as they are. Last, a visual size tweak for the logo. Edit freely.
     */
    private static final String[][] SHELF = {
        {"netflix", "Netflix", "com.netflix.ninja", "E50914", "", "1"},
        {"youtube", "YouTube", "com.google.android.youtube.tv", "FF0033", "play", "1"},
        {"prime", "Prime Video", "com.amazon.amazonvideo.livingroom", "1A98FF", "tint", "0.96"},
        {"disney", "Disney+", "com.disney.disneyplus", "1838C8", "", "1"},
        {"max", "Max", "com.wbd.stream", "6C3BE0", "", "1.32"},
        {"appletv", "Apple TV", "com.apple.atve.androidtv.appletv", "B8BCC6", "", "1.1"},
        {"hulu", "Hulu", "com.hulu.livingroomplus", "1CE783", "", "1"},
        {"paramount", "Paramount+", "com.cbs.ott", "0064FF", "", "1"},
        {"peacock", "Peacock", "com.peacocktv.peacockandroid", "6E55DC", "", "1"},
        {"globoplay", "Globoplay", "com.globo.globotv", "FF5A1F", "tint", "1"},
        {"spotify", "Spotify", "com.spotify.tv.android", "1DB954", "", "1"},
    };
    private static final int MAX_SHELF = 8;
    /** Live TV apps of some brands (the antenna's channels open through the standard Android request first). */
    static final String[] LIVE_TV_APPS = {"com.tcl.tv", "com.google.android.tv", "com.mediatek.wwtv.tvcenter", "com.sony.dtv.tvx"};
    /** Kept out of "All Apps" (only hidden, still installed). Add the ones your TV shows that you never use. */
    private static final Set<String> HIDDEN = new HashSet<>(java.util.Arrays.asList(
            "com.google.android.play.games",        // Google Play Games
            "com.tcl.t_solo",                       // TCL: T-Solo
            "com.tcl.waterfall.overseas",           // TCL Channel
            "com.tcl.dashboard",                    // TCL Home
            "com.tcl.ocean.instructions",           // TCL: User Guide
            "com.tcl.guard",                        // TCL: Safety Guard
            "com.tcl.exhibit"));                    // TCL: T-Exhibition

    private static final Map<String, Bitmap> TILES = new HashMap<>();   // key → ready card
    private static final Map<String, Bitmap> LOGOS = new HashMap<>();   // key → colored logo with no background

    /**
     * Home screen apps. `extract`: also lifts the logos that are not on disk yet (slow: background only); without it,
     * those apps keep their TV art until the next load.
     */
    static List<Item> shelf(Context c, boolean extract) {
        List<Item> items = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String pkg : shelfPackages(c)) {
            String[] d = known(pkg);
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            if (iconOnly(pkg, label(pm, pkg))) {
                // Apps whose TV art has the name beside the icon: just the icon, as it is.
                it.key = pkg;
                it.label = d != null ? d[1] : label(pm, pkg);
                it.tile = appTile(c, null, pkg, 0);
                boolean art = Prefs.shelfArt(c);
                // "App logo": the whole icon (rounded) on Lume's card, which follows the theme. "App art": the icon
                // without its rim, its own background (and gradient) filling the whole card.
                it.logo = art ? iconLogo(c, pkg, true) : iconGlyph(c, pkg);
                it.logoDark = art ? it.logo : null;     // logo mode: made below for the light tile, like the others
                if (it.logo != null) {
                    if (art) {
                        it.fill = edgeColor(it.logo, true);
                        it.fillBottom = edgeColor(it.logo, false);
                        if (it.fill == 0 || it.fillBottom == 0) it.fill = it.fillBottom = 0;
                    }
                    // Art: the icon as big as the tile allows; logo: sized like the other logos (same "ink").
                    it.logoInk = art ? 0.12f : ink(it.logo);
                    it.logoScale = art ? 1.5f : 1.1f;
                    if (!art) it.logoDark = onLight("glyph:" + pkg, it.logo);
                    items.add(it);
                    continue;
                }
            }
            if (Prefs.shelfArt(c)) {
                // "App art" (the icon-only apps above keep their icon): the app's own TV art as it is (its colors and background), nothing cut out.
                it.key = d != null ? d[0] : pkg;
                it.label = d != null ? d[1] : label(pm, pkg);
                it.tile = artTile(c, pkg);
                items.add(it);
                continue;
            }
            if (d != null) {
                it.key = d[0];
                it.label = d[1];
                it.tile = appTile(c, d[0], pkg, 0xFF000000 | Integer.parseInt(d[3], 16));
                it.logo = shelfLogo(c, d[0], pkg, d[4], extract);
                it.logoDark = ownLogoCached(c, d[0] + "-dark");
                it.logoScale = Float.parseFloat(d[5]);
            } else {
                // An app you added yourself: its logo comes from its TV art, like the others.
                it.key = pkg;
                it.label = label(pm, pkg);
                it.tile = appTile(c, null, pkg, 0);
                it.logo = shelfLogo(c, pkg, pkg, "", extract);
            }
            if (it.logo != null) it.logoInk = ink(it.logo);
            if (it.logo != null && it.logoDark == null) it.logoDark = onLight(it.key, it.logo);
            items.add(it);
        }
        return items;
    }

    /**
     * Apps shown by their icon alone (their TV art puts the name beside a small icon): any package whose name has one
     * of these words, so other builds of the same app (UniTV, YouCine, Velo Play) look the same.
     */
    private static final String[] ICON_ONLY_WORDS = {"unitv", "youcine", "veloplay"};

    private static boolean iconOnly(String pkg, String label) {
        // The package or the app's name ("UniTV", "Velo Play": spaces and case ignored).
        String p = pkg.toLowerCase(Locale.ROOT), n = label == null ? "" : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String w : ICON_ONLY_WORDS) if (p.contains(w) || n.contains(w)) return true;
        return false;
    }

    /** The app's icon at full size (adaptive icons in a rounded square), for the shelf; null if it has none. */
    private static Bitmap iconLogo(Context c, String pkg, boolean cutRim) {
        String key = (cutRim ? "iconcut:" : "icon:") + pkg;
        synchronized (LOGOS) {
            if (LOGOS.containsKey(key)) return LOGOS.get(key);
        }
        Bitmap b = null;
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            Drawable icon = null;
            if (ai.icon != 0) {
                try {
                    icon = pm.getResourcesForApplication(ai).getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
                } catch (RuntimeException ignored) {
                }
            }
            if (icon == null) icon = pm.getApplicationIcon(ai);
            int size = 384;
            b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(b);
            if (icon instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
                Path clip = new Path();
                clip.addRoundRect(new RectF(0, 0, size, size), size * 0.2237f, size * 0.2237f, Path.Direction.CW);
                canvas.clipPath(clip);
                int pad = size / 4;
                for (Drawable layer : new Drawable[] {a.getBackground(), a.getForeground()}) {
                    if (layer == null) continue;
                    layer.setBounds(-pad, -pad, size + pad, size + pad);
                    layer.draw(canvas);
                }
            } else {
                icon.setBounds(0, 0, size, size);
                icon.draw(canvas);
            }
            // Cut off the icon's own rim and rounded corners: the card takes its background color around it.
            if (cutRim) {
                int cut = Math.round(size * 0.1f);
                b = Bitmap.createBitmap(b, cut, cut, size - 2 * cut, size - 2 * cut);
            }
            b.setHasMipMap(true);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            b = null;
        }
        synchronized (LOGOS) {
            LOGOS.put(key, b);
        }
        return b;
    }

    /** The icon's background along its top (or bottom) edge: the average of points there (0 if they're transparent). */
    private static int edgeColor(Bitmap b, boolean top) {
        int w = b.getWidth(), h = b.getHeight(), r = 0, g = 0, bl = 0, n = 0;
        int e = 2, y = top ? e : h - 1 - e;     // the icon is already cut inside its rim: its very edge is its background
        int[][] pts = {{e, y}, {w / 4, y}, {w / 2, y}, {3 * w / 4, y}, {w - 1 - e, y}};
        for (int[] p : pts) {
            int px = b.getPixel(p[0], p[1]);
            if ((px >>> 24) < 200) return 0;
            r += (px >> 16) & 255;
            g += (px >> 8) & 255;
            bl += px & 255;
            n++;
        }
        return 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (bl / n);
    }

    /** Icon-only apps in All Apps: the icon on a card that continues its own background (as on the shelf). */
    private static synchronized Bitmap iconCard(Context c, String pkg) {
        String key = "iconcard:" + pkg;
        if (TILES.containsKey(key)) return TILES.get(key);
        Bitmap icon = iconLogo(c, pkg, true);
        if (icon == null) return null;
        int top = edgeColor(icon, true), bottom = edgeColor(icon, false);
        if (top == 0 || bottom == 0) return null;
        Bitmap b = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        float ih = TILE_H * 0.62f, iw = ih * icon.getWidth() / icon.getHeight();
        float y0 = (TILE_H - ih) / 2, x0 = (TILE_W - iw) / 2;
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, y0, 0, y0 + ih, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, TILE_W, TILE_H, bg);
        canvas.drawBitmap(icon, null, new RectF(x0, y0, x0 + iw, y0 + ih), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        b.setHasMipMap(true);
        TILES.put(key, b);
        return b;
    }

    /**
     * "App logo" for icon-only apps: the icon without its own square, only what's drawn on it, so it sits on Lume's
     * card like the other logos. Its background (top-to-bottom, gradients too) is removed and the edges cleaned.
     */
    private static Bitmap iconGlyph(Context c, String pkg) {
        synchronized (LOGOS) {
            if (LOGOS.containsKey("glyph:" + pkg)) return LOGOS.get("glyph:" + pkg);
        }
        Bitmap out = null;
        Bitmap icon = iconLogo(c, pkg, true);
        if (icon != null) {
            int top = edgeColor(icon, true), bottom = edgeColor(icon, false);
            if (top != 0 && bottom != 0) {
                int w = icon.getWidth(), h = icon.getHeight();
                int[] px = new int[w * h];
                icon.getPixels(px, 0, w, 0, 0, w, h);
                float[] bg = new float[3], col = new float[3];
                for (int y = 0; y < h; y++) {
                    float v = (float) y / (h - 1);
                    for (int i = 0; i < 3; i++) {
                        int sh = 16 - 8 * i;
                        bg[i] = (((top >> sh) & 255) * (1 - v) + ((bottom >> sh) & 255) * v) / 255f;
                    }
                    for (int x = 0; x < w; x++) {
                        int p = px[y * w + x];
                        for (int i = 0; i < 3; i++) col[i] = ((p >> (16 - 8 * i)) & 255) / 255f;
                        float d = (float) Math.sqrt(((col[0] - bg[0]) * (col[0] - bg[0]) + (col[1] - bg[1]) * (col[1] - bg[1])
                                + (col[2] - bg[2]) * (col[2] - bg[2])) / 3);
                        float a = smoothstep(0.1f, 0.3f, d) * ((p >>> 24) / 255f);
                        if (a <= 0.004f) {
                            px[y * w + x] = 0;
                            continue;
                        }
                        for (int i = 0; i < 3; i++) col[i] = clamp((col[i] - (1 - a) * bg[i]) / a);
                        px[y * w + x] = (Math.round(a * 255) << 24) | (Math.round(col[0] * 255) << 16)
                                | (Math.round(col[1] * 255) << 8) | Math.round(col[2] * 255);
                    }
                }
                // Choke the edge by a pixel: no dark rim left from the icon's own outline around the drawing.
                int[] alpha = new int[w * h];
                for (int i = 0; i < px.length; i++) alpha[i] = px[i] >>> 24;
                for (int y = 1; y < h - 1; y++) {
                    for (int x = 1; x < w - 1; x++) {
                        int i = y * w + x, m = Math.min(Math.min(alpha[i - 1], alpha[i + 1]), Math.min(alpha[i - w], alpha[i + w]));
                        if (m < alpha[i]) px[i] = (m << 24) | (px[i] & 0xFFFFFF);
                    }
                }
                out = trimLogo(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888));
                if (out != null) out.setHasMipMap(true);
            }
        }
        if (out == null) out = iconLogo(c, pkg, false);
        synchronized (LOGOS) {
            LOGOS.put("glyph:" + pkg, out);
        }
        return out;
    }

    /** The app's TV art as the card (or its icon on a dark card without art), cached. */
    private static synchronized Bitmap artTile(Context c, String pkg) {
        String key = "art:" + pkg;
        if (TILES.containsKey(key)) return TILES.get(key);
        Bitmap t = bannerTile(c, pkg);
        if (t == null) t = iconTile(c, pkg);
        t.setHasMipMap(true);
        TILES.put(key, t);
        return t;
    }

    /** The logo for a light tile: its white and light gray parts turn dark; its colors stay. Made once per logo. */
    private static Bitmap onLight(String key, Bitmap logo) {
        synchronized (LOGOS) {
            if (LOGOS.containsKey("light:" + key)) return LOGOS.get("light:" + key);
        }
        Bitmap out = null;
        try {
            Bitmap b = logo.copy(Bitmap.Config.ARGB_8888, true);
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                int p = px[i], a = p >>> 24;
                if (a == 0) continue;
                int r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
                int max = Math.max(r, Math.max(g, bl)), min = Math.min(r, Math.min(g, bl));
                float sat = max == 0 ? 0 : (max - min) / (float) max;
                if (sat < 0.22f && max > 150) {
                    px[i] = (a << 24) | 0x1D1D1F;
                } else {
                    // Bright colors (ashtv's light lime, YouCine's yellow) fade on the light tile: the same hue,
                    // deeper, so they stand out like the other logos.
                    float lum = (0.2126f * r + 0.7152f * g + 0.0722f * bl) / 255f;
                    float f = min > 110 ? 0.42f : lum > 0.6f ? 0.42f / lum : 1f;
                    // Pale ones darken evenly; bright yellows keep more red, so they turn amber, not olive.
                    float fr = min > 110 ? f : Math.min(1f, f * 1.45f);
                    if (f < 1f) px[i] = (a << 24) | (Math.round(r * fr) << 16) | (Math.round(g * f) << 8) | Math.round(bl * f);
                }
            }
            b.setPixels(px, 0, w, 0, 0, w, h);
            b.setHasMipMap(true);
            out = b;
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("light logo: " + e.getMessage());
        }
        synchronized (LOGOS) {
            LOGOS.put("light:" + key, out);
        }
        return out;
    }

    // MARK: - Your dock: the SHELF defaults minus the ones you removed, then the ones you added (hold OK to change)

    private static SharedPreferences dock(Context c) {
        return c.getSharedPreferences("dock", Context.MODE_PRIVATE);
    }

    private static List<String> prefList(Context c, String name) {
        List<String> out = new ArrayList<>();
        for (String p : dock(c).getString(name, "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private static String[] known(String pkg) {
        for (String[] d : SHELF) if (d[2].equals(pkg)) return d;
        return null;
    }

    /** The packages on the home shelf, in order: installed ones only, up to MAX_SHELF. */
    static synchronized List<String> shelfPackages(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> removed = prefList(c, "removed"), out = new ArrayList<>();
        List<String> wanted = new ArrayList<>();
        for (String[] d : SHELF) if (!removed.contains(d[2])) wanted.add(d[2]);
        for (String p : prefList(c, "added")) if (!wanted.contains(p)) wanted.add(p);
        // The order you set by holding OK on the shelf (apps it doesn't know keep their place at the end).
        final List<String> order = prefList(c, "order");
        if (!order.isEmpty()) {
            final List<String> before = new ArrayList<>(wanted);
            List<String> sorted = new ArrayList<>(wanted);
            java.util.Collections.sort(sorted, (a, b) -> {
                int ia = order.indexOf(a), ib = order.indexOf(b);
                return Integer.compare(ia < 0 ? 1000 + before.indexOf(a) : ia, ib < 0 ? 1000 + before.indexOf(b) : ib);
            });
            wanted = sorted;
        }
        for (String p : wanted) {
            if (out.size() >= MAX_SHELF) break;
            if (launchIntent(pm, p) != null) out.add(p);
        }
        return out;
    }

    /** Adds an app to the end of the shelf. False when the shelf is already full. */
    static synchronized boolean addToDock(Context c, String pkg) {
        if (shelfPackages(c).contains(pkg)) return true;
        if (shelfPackages(c).size() >= MAX_SHELF) return false;
        List<String> removed = prefList(c, "removed"), added = prefList(c, "added");
        removed.remove(pkg);
        if (known(pkg) == null && !added.contains(pkg)) added.add(pkg);
        dock(c).edit().putString("removed", String.join(",", removed)).putString("added", String.join(",", added)).commit();
        return true;
    }

    static synchronized void removeFromDock(Context c, String pkg) {
        List<String> removed = prefList(c, "removed"), added = prefList(c, "added");
        added.remove(pkg);
        if (known(pkg) != null && !removed.contains(pkg)) removed.add(pkg);
        dock(c).edit().putString("removed", String.join(",", removed)).putString("added", String.join(",", added)).commit();
    }

    /** Saves the shelf's order (its packages, left to right). */
    static synchronized void setDockOrder(Context c, List<String> pkgs) {
        dock(c).edit().putString("order", String.join(",", pkgs)).commit();
    }

    /** Every app that can open on the TV ({package, name}), by name, for Home screen → Apps on the shelf. */
    static List<String[]> launchableApps(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || pkg.equals("com.android.tv.settings") || !seen.add(pkg)) continue;
                out.add(new String[] {pkg, String.valueOf(r.loadLabel(pm))});
            }
        }
        java.util.Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
        return out;
    }

    static boolean dockFull(Context c) {
        return shelfPackages(c).size() >= MAX_SHELF;
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Live TV, the HDMI inputs with a device sending a signal, and All Apps. */
    static List<Item> shortcuts(Context c) {
        List<Item> items = new ArrayList<>();
        if (liveTv(c) != null) {
            items.add(shortcut(LIVE_TV, "live", Ui.tr("TV ao Vivo", "Live TV"), "tv"));
        }
        items.addAll(inputs(c));
        items.add(shortcut(ALL_APPS, "all", Ui.tr("Todos os Apps", "All Apps"), "apps"));
        return items;
    }

    private static Item shortcut(int kind, String key, String label, String symbol) {
        Item it = new Item();
        it.kind = kind;
        it.key = key;
        it.label = label;
        it.symbol = symbol;
        return it;
    }

    /** Icon button (the ones in the corner of the home screen). */
    static Item action(int kind, String key, String label, String symbol) {
        return shortcut(kind, key, label, symbol);
    }

    /** Lume settings, in the corner of the home screen. */
    static Item settings() {
        return shortcut(SETTINGS, "settings", Ui.tr("Ajustes", "Settings"), "settings");
    }

    /** Shortcuts in the bottom drawer. */
    static List<Item> drawerShortcuts() {
        List<Item> items = new ArrayList<>();
        items.add(shortcut(SETTINGS, "settings", Ui.tr("Ajustes do Início", "Home Settings"), "settings"));
        items.add(shortcut(ANDROID_SETTINGS, "tv-settings", Ui.tr("Ajustes da TV", "TV Settings"), "tv_settings"));
        return items;
    }

    /** A streaming service's app (the "Watch" button of the featured pick). */
    static Item app(String pkg, String label) {
        Item it = new Item();
        it.kind = APP;
        it.key = pkg;
        it.pkg = pkg;
        it.label = label;
        return it;
    }

    /** For the bottom drawer: live TV and every HDMI input (the ones with no device are shown dimmed). */
    static List<Item> allInputs(Context c) {
        List<Item> items = new ArrayList<>();
        if (liveTv(c) != null) items.add(shortcut(LIVE_TV, "live", Ui.tr("TV ao Vivo", "Live TV"), "tv"));
        items.addAll(inputs(c, true));
        return items;
    }

    private static List<Item> inputs(Context c) {
        return inputs(c, false);
    }

    /**
     * HDMI inputs: only the ones with a device sending a signal (or all of them, with `all`). A name you gave the input
     * in the TV's settings is used as the label.
     */
    static List<Item> inputs(Context c, boolean all) {
        List<Item> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> list = new ArrayList<>();
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() == TvInputInfo.TYPE_HDMI && info.getParentId() == null) list.add(info);
            }
        } catch (RuntimeException e) {
            return out;
        }
        Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
        Pattern digit = Pattern.compile("(\\d)");
        for (int i = 0; i < list.size(); i++) {
            TvInputInfo info = list.get(i);
            int number = i + 1;
            Matcher m = digit.matcher(String.valueOf(info.loadLabel(c)));
            if (m.find()) number = Integer.parseInt(m.group(1));
            // TCL TVs report "connected standby" for every port, with or without a device; only truly "connected"
            // (a device that is on and sending a signal) makes the input show up.
            int state = TvInputManager.INPUT_STATE_DISCONNECTED;
            try {
                state = tim.getInputState(info.getId());
            } catch (RuntimeException ignored) {
            }
            if (!all && state != TvInputManager.INPUT_STATE_CONNECTED) continue;
            CharSequence custom = null;
            try {
                custom = info.loadCustomLabel(c);
            } catch (RuntimeException ignored) {
            }
            String label = custom != null && custom.length() > 0 ? custom.toString() : "HDMI " + number;
            Item it = shortcut(INPUT, "hdmi" + number, label, "hdmi");
            it.inputId = info.getId();
            it.number = String.valueOf(number);
            it.connected = state == TvInputManager.INPUT_STATE_CONNECTED;
            out.add(it);
        }
        Collections.sort(out, (a, b) -> a.number.compareTo(b.number));
        return out;
    }

    /** Installed, enabled TV apps, the shelf's ticked (the TV's Settings is among them), plus our own. */
    static List<Item> others(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new HashSet<>();
        List<String> dock = shelfPackages(c);
        skip.addAll(java.util.Arrays.asList(LIVE_TV_APPS));
        skip.add(c.getPackageName());
        skip.addAll(HIDDEN);
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (skip.contains(pkg) || !seen.add(pkg)) continue;
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            it.key = pkg;
            it.label = String.valueOf(ri.loadLabel(pm));
            it.tile = iconOnly(pkg, it.label) ? iconCard(c, pkg) : appTile(c, null, pkg, 0);
            if (it.tile == null) it.tile = appTile(c, null, pkg, 0);
            it.inDock = dock.contains(pkg);
            items.add(it);
        }
        Collections.sort(items, (a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        items.add(special(c, SETTINGS, "freemytv-settings", "Lume", "lume"));
        return items;
    }

    /** Every TV app (the dock's too), by name, for choosing what Live TV opens. Not ours, not the TV's Settings. */
    static List<Item> allApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || pkg.equals("com.android.tv.settings") || !seen.add(pkg)) continue;
            items.add(app(pkg, String.valueOf(ri.loadLabel(pm))));
        }
        Collections.sort(items, (a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        return items;
    }

    private static Item special(Context c, int kind, String key, String label, String symbol) {
        Item it = shortcut(kind, key, label, symbol);
        it.tile = symbolTile(c, key, symbol);
        return it;
    }

    /** Opens the item with the system zoom transition growing out of the card (the app starts opening right away). */
    static boolean open(Activity a, Item it, View from, int x, int y, int w, int h) {
        Intent intent;
        switch (it.kind) {
            case APP:
                if (it.uri != null) {
                    intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it.uri)).setPackage(it.pkg);
                    if (it.fresh) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    break;
                }
                intent = launchIntent(a.getPackageManager(), it.pkg);
                if (intent != null) intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                break;
            case LIVE_TV:
                intent = liveTv(a);
                break;
            case INPUT:
                intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.inputId));
                break;
            case SETTINGS:
                intent = new Intent().setComponent(new ComponentName(a, SettingsActivity.class));
                break;
            case ANDROID_SETTINGS:
                intent = new Intent(Settings.ACTION_SETTINGS);
                break;
            case NETWORK:
                intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                if (intent.resolveActivity(a.getPackageManager()) == null) intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                if (intent.resolveActivity(a.getPackageManager()) == null) intent = new Intent(Settings.ACTION_SETTINGS);
                break;
            default:
                return false;
        }
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(intent, ActivityOptions.makeScaleUpAnimation(from, x, y, Math.max(w, 1), Math.max(h, 1)).toBundle());
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + it.label + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Live TV: the antenna input's channels through the standard Android request (opening the TV app by itself may go
     * back to the last input used, like an HDMI); otherwise a known live TV app. Null = this TV has none.
     */
    static Intent liveTv(Context c) {
        PackageManager pm = c.getPackageManager();
        String choice = Prefs.liveTv(c);
        if (choice.startsWith("app:")) {
            Intent app = launchIntent(pm, choice.substring(4));
            if (app != null) return app;
        } else if (choice.startsWith("input:")) {
            return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(choice.substring(6)));
        } else if (choice.equals("auto")) {
            // A cable TV box (or any device) sending a signal on an HDMI input comes first.
            List<Item> on = inputs(c, false);
            if (!on.isEmpty()) return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(on.get(0).inputId));
        }
        return tunerTv(c);
    }

    /** The TV's own tuner (antenna or coaxial cable): its channels, or the brand's live TV app. */
    private static Intent tunerTv(Context c) {
        PackageManager pm = c.getPackageManager();
        String tuner = tunerInput(c);
        if (tuner != null) {
            Intent channels = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tuner));
            if (channels.resolveActivity(pm) != null) return channels;
        }
        for (String pkg : LIVE_TV_APPS) {
            Intent app = launchIntent(pm, pkg);
            if (app != null) return app;
        }
        return null;
    }

    /** The TV's antenna (tuner) input, or null. The TV maker's own tuner comes first. */
    static String tunerInput(Context c) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        String fallback = null;
        try {
            // The TV's real tuner is backed by hardware (".../HW2", whatever type the brand gives it); apps like Play
            // Movies also register system "tuner" inputs for previews, and opening one of those showed nothing.
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() && info.getId().contains("/HW")) return info.getId();
            }
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_TUNER || info.isPassthroughInput()) continue;
                if (info.getServiceInfo() != null && info.getServiceInfo().applicationInfo != null
                        && (info.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                    return info.getId();
                }
                if (fallback == null) fallback = info.getId();
            }
        } catch (RuntimeException ignored) {
        }
        return fallback;
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    // MARK: - Colored logos (shelf)

    /** Bump when the way logos are lifted changes (the ones saved on disk are redone). */
    private static final int LOGO_VERSION = 2;

    private static Bitmap shelfLogo(Context c, String key, String pkg, String mode, boolean extract) {
        synchronized (LOGOS) {
            if (LOGOS.containsKey(key)) return LOGOS.get(key);
        }
        Bitmap logo = ownLogo(c, key);
        File file = logoFile(c, key, pkg), none = new File(file.getPath() + ".none");
        if (logo == null && file.exists()) logo = BitmapFactory.decodeFile(file.getPath());
        if (logo == null) {
            if (none.exists()) {
                logo = null;
            } else if (!extract) {
                return null;
            } else {
                logo = bannerLogo(c, pkg, mode.equals("tint"), mode.equals("play"));
                saveLogo(logo, file, none);
            }
        }
        if (logo != null) logo.setHasMipMap(true);
        synchronized (LOGOS) {
            LOGOS.put(key, logo);
        }
        return logo;
    }

    /** File for the logo lifted from the TV art: it changes when the app is updated (the art may have changed). */
    private static File logoFile(Context c, String key, String pkg) {
        long updated = 0;
        try {
            updated = c.getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return new File(new File(c.getCacheDir(), "logos"), key + "-" + updated + "-v" + LOGO_VERSION + ".png");
    }

    private static void saveLogo(Bitmap logo, File file, File none) {
        File dir = file.getParentFile();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(logo != null ? file : none)) {
            if (logo != null) logo.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            AppLog.w("logo not saved: " + e.getMessage());
        }
    }

    /** Fraction (0 to 1) of the logo's pixels that have ink, by sampling. */
    private static float ink(Bitmap logo) {
        int w = logo.getWidth(), h = logo.getHeight(), hits = 0, total = 0;
        for (int y = 0; y < h; y += Math.max(1, h / 48)) {
            for (int x = 0; x < w; x += Math.max(1, w / 96)) {
                if ((logo.getPixel(x, y) >>> 24) > 128) hits++;
                total++;
            }
        }
        return total == 0 ? 0.4f : Math.max(0.08f, (float) hits / total);
    }

    /**
     * Logo lifted from the app's TV art: the background is removed (estimated from the edges, gradients are fine), the
     * brand colors stay and black turns white (the card is dark). With `tint`, a white logo on a colored background
     * takes the background's color; with `holes`, holes inside colored shapes (the play triangle) turn white. The
     * edges are "decontaminated" (no halo from the background).
     */
    private static Bitmap bannerLogo(Context c, String pkg, boolean tint, boolean holes) {
        Drawable d = bannerDrawable(c, pkg);
        if (d == null) return null;
        final int w = 768, h = 432;
        Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(src);
        canvas.drawColor(0xFF15161C);
        d.setBounds(0, 0, w, h);
        d.draw(canvas);
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        src.recycle();
        // Edge profiles (a 3% band), smoothed: the background at any point is the "patch" spanning the four sides.
        int band = Math.round(h * 0.03f);
        float[][] left = new float[h][3], right = new float[h][3], top = new float[w][3], bottom = new float[w][3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < band; x++) {
                add(left[y], px[y * w + x], 1f / band);
                add(right[y], px[y * w + w - 1 - x], 1f / band);
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < band; y++) {
                add(top[x], px[y * w + x], 1f / band);
                add(bottom[x], px[(h - 1 - y) * w + x], 1f / band);
            }
        }
        smooth(left, 25);
        smooth(right, 25);
        smooth(top, 41);
        smooth(bottom, 41);
        float[] p00 = top[0], p10 = top[w - 1], p01 = bottom[0], p11 = bottom[w - 1];
        float[] mean = new float[3];
        for (int i = 0; i < 3; i++) mean[i] = (p00[i] + p10[i] + p01[i] + p11[i] + top[w / 2][i] + bottom[w / 2][i] + left[h / 2][i] + right[h / 2][i]) / 8;
        float bgLum = lum(mean), bgSat = sat(mean);
        int[] out = new int[w * h];
        float[] bg = new float[3], col = new float[3];
        int opaque = 0;
        for (int y = 0; y < h; y++) {
            float v = (float) y / (h - 1);
            for (int x = 0; x < w; x++) {
                float u = (float) x / (w - 1);
                for (int i = 0; i < 3; i++) {
                    bg[i] = (1 - v) * top[x][i] + v * bottom[x][i] + (1 - u) * left[y][i] + u * right[y][i]
                            - ((1 - u) * (1 - v) * p00[i] + u * (1 - v) * p10[i] + (1 - u) * v * p01[i] + u * v * p11[i]);
                }
                int p = px[y * w + x];
                col[0] = ((p >> 16) & 255) / 255f;
                col[1] = ((p >> 8) & 255) / 255f;
                col[2] = (p & 255) / 255f;
                float dist = (float) Math.sqrt(((col[0] - bg[0]) * (col[0] - bg[0]) + (col[1] - bg[1]) * (col[1] - bg[1])
                        + (col[2] - bg[2]) * (col[2] - bg[2])) / 3);
                float a = smoothstep(0.07f, 0.22f, dist);
                if (a <= 0.004f) continue;
                for (int i = 0; i < 3; i++) col[i] = clamp((col[i] - (1 - a) * bg[i]) / a);   // no background halo
                float l = lum(col), s = sat(col);
                if (bgLum > 0.6f && l < 0.42f && s < 0.3f) {
                    col[0] = col[1] = col[2] = 1f;                  // black text on a light background → white
                } else if (tint && bgLum <= 0.6f && bgLum > 0.1f && bgSat > 0.35f && l > 0.7f && s < 0.25f) {
                    vivid(bg, col);                                 // white logo on brand color → that color, vivid
                }
                out[y * w + x] = (Math.round(a * 255) << 24) | (Math.round(col[0] * 255) << 16) | (Math.round(col[1] * 255) << 8) | Math.round(col[2] * 255);
                if (a > 0.5f) opaque++;
            }
        }
        float area = (float) opaque / (w * h);
        if (area < 0.01f || area > 0.6f) return null;    // couldn't split the logo from the background: keep the card
        if (holes) fillHoles(out, w, h);
        Bitmap logo = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
        return trimLogo(logo);
    }

    private static Drawable bannerDrawable(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            Intent launch = launchIntent(pm, pkg);
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                int res = ai.getBannerResource();
                if (res != 0) {
                    android.content.res.Resources r = pm.getResourcesForApplication(ai.applicationInfo);
                    android.util.TypedValue value = new android.util.TypedValue();
                    r.getValue(res, value, true);
                    Drawable d = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                    if (d != null) AppLog.w("TV banner for " + pkg + ": " + value.string + " (" + d.getIntrinsicWidth() + "×" + d.getIntrinsicHeight() + ")");
                    return d;
                }
            }
            return pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** Closed holes inside colored shapes (the play triangle) turn white; the ones in letters don't. */
    private static void fillHoles(int[] px, int w, int h) {
        byte[] mark = new byte[w * h];   // 1 = outside (reached from the border), 2 = hole already visited
        int[] queue = new int[w * h];
        int head = 0, tail = 0;
        for (int x = 0; x < w; x++) {
            for (int y : new int[] {0, h - 1}) {
                int i = y * w + x;
                if (mark[i] == 0 && (px[i] >>> 24) < 128) { mark[i] = 1; queue[tail++] = i; }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x : new int[] {0, w - 1}) {
                int i = y * w + x;
                if (mark[i] == 0 && (px[i] >>> 24) < 128) { mark[i] = 1; queue[tail++] = i; }
            }
        }
        while (head < tail) {
            int i = queue[head++], x = i % w, y = i / w;
            int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
            for (int n : next) {
                if (n >= 0 && mark[n] == 0 && (px[n] >>> 24) < 128) { mark[n] = 1; queue[tail++] = n; }
            }
        }
        for (int start = 0; start < w * h; start++) {
            if (mark[start] != 0 || (px[start] >>> 24) >= 128) continue;
            head = 0;
            tail = 0;
            queue[tail++] = start;
            mark[start] = 2;
            float satSum = 0;
            int border = 0;
            while (head < tail) {
                int i = queue[head++], x = i % w, y = i / w;
                int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
                for (int n : next) {
                    if (n < 0) continue;
                    if ((px[n] >>> 24) >= 128) {
                        float[] c = {((px[n] >> 16) & 255) / 255f, ((px[n] >> 8) & 255) / 255f, (px[n] & 255) / 255f};
                        satSum += sat(c);
                        border++;
                    } else if (mark[n] == 0) {
                        mark[n] = 2;
                        queue[tail++] = n;
                    }
                }
            }
            if (border > 0 && satSum / border > 0.45f && tail < w * h / 20) {
                for (int k = 0; k < tail; k++) px[queue[k]] = 0xFFFFFFFF;
            }
        }
    }

    private static Bitmap trimLogo(Bitmap b) {
        Bitmap t = trim(b);
        int pad = Math.max(2, Math.round(Math.max(t.getWidth(), t.getHeight()) * 0.02f));
        Bitmap out = Bitmap.createBitmap(t.getWidth() + 2 * pad, t.getHeight() + 2 * pad, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(t, pad, pad, null);
        return out;
    }

    private static void add(float[] acc, int p, float k) {
        acc[0] += ((p >> 16) & 255) / 255f * k;
        acc[1] += ((p >> 8) & 255) / 255f * k;
        acc[2] += (p & 255) / 255f * k;
    }

    /** Moving average (odd window) on each channel of the profile. */
    private static void smooth(float[][] profile, int window) {
        int n = profile.length, r = window / 2;
        float[][] copy = new float[n][];
        for (int i = 0; i < n; i++) copy[i] = profile[i].clone();
        for (int i = 0; i < n; i++) {
            float[] sum = new float[3];
            int count = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(n - 1, i + r); j++) {
                for (int k = 0; k < 3; k++) sum[k] += copy[j][k];
                count++;
            }
            for (int k = 0; k < 3; k++) profile[i][k] = sum[k] / count;
        }
    }

    private static float lum(float[] c) {
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    }

    private static float sat(float[] c) {
        float max = Math.max(c[0], Math.max(c[1], c[2])), min = Math.min(c[0], Math.min(c[1], c[2]));
        return max <= 0 ? 0 : (max - min) / max;
    }

    /** The local background color (gradients included), vivid and bright: the logo stands out on the dark card. */
    private static void vivid(float[] bg, float[] out) {
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV(Math.round(bg[0] * 255), Math.round(bg[1] * 255), Math.round(bg[2] * 255), hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = 1f;
        int c = android.graphics.Color.HSVToColor(hsv);
        out[0] = ((c >> 16) & 255) / 255f;
        out[1] = ((c >> 8) & 255) / 255f;
        out[2] = (c & 255) / 255f;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    // MARK: - Cards

    private static synchronized Bitmap appTile(Context c, String key, String pkg, int brand) {
        String cacheKey = key != null ? key : pkg;
        if (TILES.containsKey(cacheKey)) return TILES.get(cacheKey);
        Bitmap logo = key == null ? null : ownLogo(c, key);
        Bitmap tile = logo != null ? logoTile(logo, brand) : bannerTile(c, pkg);
        if (tile == null) tile = iconTile(c, pkg);
        tile.setHasMipMap(true);
        TILES.put(cacheKey, tile);
        return tile;
    }

    /** The app's official TV art (the Android TV banner), at the highest density the app has. */
    private static Bitmap bannerTile(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Drawable d = null;
        try {
            Intent launch = launchIntent(pm, pkg);
            if (launch != null && launch.getComponent() != null) {
                ActivityInfo ai = pm.getActivityInfo(launch.getComponent(), 0);
                int res = ai.getBannerResource();
                if (res != 0) d = pm.getResourcesForApplication(ai.applicationInfo).getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
            }
            if (d == null) d = pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        canvas.drawColor(0xFF15161C);   // banners with transparency sit on a dark background
        d.setBounds(0, 0, TILE_W, TILE_H);
        d.draw(canvas);
        return b;
    }

    /** No banner: the app's icon (in its proper shape, with some margin) on a dark card. */
    private static Bitmap iconTile(Context c, String pkg) {
        Bitmap b = darkTile();
        Canvas canvas = new Canvas(b);
        Drawable icon = null;
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.icon != 0) {
                try {
                    icon = pm.getResourcesForApplication(ai).getDrawableForDensity(ai.icon, DisplayMetrics.DENSITY_XXXHIGH, null);
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
            }
            if (icon == null) icon = pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if (icon == null) return b;
        int size = Math.round(TILE_H * 0.56f);
        float left = (TILE_W - size) / 2f, top = (TILE_H - size) / 2f;
        if (icon instanceof AdaptiveIconDrawable) {
            // Adaptive icon: the layers clipped to a squircle, like an Apple icon.
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            Path clip = new Path();
            clip.addRoundRect(new RectF(left, top, left + size, top + size), size * 0.2237f, size * 0.2237f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            int pad = size / 4;
            for (Drawable layer : new Drawable[] {a.getBackground(), a.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(Math.round(left) - pad, Math.round(top) - pad, Math.round(left) + size + pad, Math.round(top) + size + pad);
                layer.draw(canvas);
            }
            canvas.restore();
        } else {
            // Legacy icon (a free-form drawing with transparency): smaller, no frame.
            int s = Math.round(TILE_H * 0.46f);
            icon.setBounds((TILE_W - s) / 2, (TILE_H - s) / 2, (TILE_W + s) / 2, (TILE_H + s) / 2);
            icon.draw(canvas);
        }
        return b;
    }

    /** Settings and the like: the icon in white on a dark card. */
    private static synchronized Bitmap symbolTile(Context c, String key, String symbol) {
        if (TILES.containsKey(key)) return TILES.get(key);
        Bitmap b = darkTile();
        Symbols.draw(c, new Canvas(b), symbol, TILE_W / 2f, TILE_H / 2f, TILE_H * 0.36f, 0xF2FFFFFF);
        b.setHasMipMap(true);
        TILES.put(key, b);
        return b;
    }

    /** The official logo (no background) on dark glass with a faint brand tint coming from below. */
    private static Bitmap logoTile(Bitmap logo, int brand) {
        Bitmap b = darkTile();
        Canvas canvas = new Canvas(b);
        Paint glow = new Paint();
        glow.setShader(new RadialGradient(TILE_W / 2f, TILE_H * 1.15f, TILE_W * 0.75f, Ui.alpha(brand, 0.38f), 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, TILE_W, TILE_H, glow);
        boolean wide = logo.getWidth() > logo.getHeight() * 1.35f;
        float maxW = TILE_W * (wide ? 0.62f : 0.4f), maxH = TILE_H * (wide ? 0.42f : 0.56f);
        float scale = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
        float w = logo.getWidth() * scale, h = logo.getHeight() * scale;
        canvas.drawBitmap(logo, null, new RectF((TILE_W - w) / 2, (TILE_H - h) / 2, (TILE_W + w) / 2, (TILE_H + h) / 2),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return b;
    }

    private static Bitmap darkTile() {
        Bitmap b = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, 0, TILE_H, 0xFF2B2E3A, 0xFF17191F, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, TILE_W, TILE_H, bg);
        return b;
    }

    /** Official logo (tools/logos.py): trims the transparent margins and scales it down to at most 512 px. */
    /** The bundled logo, read once per process: reading it again on every load made a new image and filled caches. */
    private static Bitmap ownLogoCached(Context c, String key) {
        synchronized (LOGOS) {
            if (LOGOS.containsKey("own:" + key)) return LOGOS.get("own:" + key);
        }
        Bitmap b = ownLogo(c, key);
        synchronized (LOGOS) {
            LOGOS.put("own:" + key, b);
        }
        return b;
    }

    private static Bitmap ownLogo(Context c, String key) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getAssets().open("logos/" + key + ".png")) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / (opts.inSampleSize * 2) >= 640) opts.inSampleSize *= 2;
            Bitmap b;
            try (InputStream in = c.getAssets().open("logos/" + key + ".png")) {
                b = BitmapFactory.decodeStream(in, null, opts);
            }
            if (b == null) return null;
            b = trim(b);
            float scale = Math.min(1f, 512f / Math.max(b.getWidth(), b.getHeight()));
            if (scale < 1f) b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)), Math.max(1, Math.round(b.getHeight() * scale)), true);
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        int top = h, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > 10) {
                    if (y < top) top = y;
                    bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        if (bottom < 0) return b;
        return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
    }
}
