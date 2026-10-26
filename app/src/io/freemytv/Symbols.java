package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Material Symbols Rounded icons (Apache 2.0), rendered by tools/make_symbols.py as white PNGs in assets/symbols. Drawn
 * like text: `pointSize` is the size of the font next to the icon, and the icon is centered on that font's cap height.
 */
final class Symbols {
    private Symbols() {}

    /**
     * PNG pixels per em of `pointSize` (a 96 pt × 1.5 px/pt reference scale). tools/make_symbols.py renders a 172 px
     * em, so an icon's em is drawn at ~1.2 × `pointSize`.
     */
    private static final float PX_PER_POINT = 96 * 1.5f;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Map<Integer, PorterDuffColorFilter> FILTERS = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();

    static Bitmap get(Context c, String name) {
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Bitmap b = null;
            try (InputStream in = c.getAssets().open("symbols/" + name + ".png")) {
                b = BitmapFactory.decodeStream(in);
                if (b != null) b.setHasMipMap(true);
            } catch (IOException ignored) {
            }
            CACHE.put(name, b);
            return b;
        }
    }

    static float width(Context c, String name, float pointSize) {
        Bitmap b = get(c, name);
        return b == null ? 0 : b.getWidth() * pointSize / PX_PER_POINT;
    }

    /** Draws centered at (cx, cy) in the given color (lighter, translucent parts of the symbol stay lighter). */
    static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {
        if (name.equals("lume")) {     // the app's own mark: drawn as shapes, sharp at any size
            lume(canvas, cx, cy, pointSize * 154 / PX_PER_POINT, color);
            return;
        }
        Bitmap b = get(c, name);
        if (b == null) return;
        synchronized (PAINT) {   // card loading (another thread) also draws symbols
            drawLocked(canvas, b, cx, cy, pointSize, color);
        }
    }

    private static void drawLocked(Canvas canvas, Bitmap b, float cx, float cy, float pointSize, int color) {
        float w = b.getWidth() * pointSize / PX_PER_POINT, h = b.getHeight() * pointSize / PX_PER_POINT;
        PorterDuffColorFilter filter = FILTERS.get(color);
        if (filter == null) {
            // Animated colors (focus, night) produce a new color every frame: the cache must not grow forever.
            if (FILTERS.size() > 256) FILTERS.clear();
            filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            FILTERS.put(color, filter);
        }
        PAINT.setColorFilter(filter);
        RECT.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        canvas.drawBitmap(b, null, RECT, PAINT);
    }

    private static final Paint LUME = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final android.graphics.Path LUME_PATH = new android.graphics.Path();

    /**
     * The Lume mark (a TV with a house on it), `width` wide and centered on (cx, cy): the screen's outline, the house
     * with its door cut out, and the stand. Same proportions as the icon and the banner.
     */
    private static void lume(Canvas canvas, float cx, float cy, float width, int color) {
        synchronized (LUME) {
            float s = width / 0.92f;
            float x = cx - s / 2, top = cy - s * 0.42f - s * 0.08f;     // unit box: x 0..1, y 0..1 from the top
            LUME.setColor(color);
            LUME.setStyle(Paint.Style.STROKE);
            float lw = 0.075f * s;
            LUME.setStrokeWidth(lw);
            RECT.set(x + 0.04f * s + lw / 2, top + 0.08f * s + lw / 2, x + 0.96f * s - lw / 2, top + 0.70f * s - lw / 2);
            canvas.drawRoundRect(RECT, 0.13f * s - lw / 2, 0.13f * s - lw / 2, LUME);
            LUME.setStyle(Paint.Style.FILL);
            RECT.set(x + 0.34f * s, top + 0.845f * s, x + 0.66f * s, top + 0.92f * s);
            canvas.drawRoundRect(RECT, 0.0375f * s, 0.0375f * s, LUME);
            float hx = x + 0.5f * s, base = top + 0.55f * s, eave = top + 0.365f * s, roof = top + 0.20f * s;
            LUME_PATH.rewind();
            LUME_PATH.setFillType(android.graphics.Path.FillType.EVEN_ODD);
            LUME_PATH.moveTo(hx, roof);
            LUME_PATH.lineTo(hx + 0.15f * s, eave);
            LUME_PATH.lineTo(hx + 0.105f * s, eave);
            LUME_PATH.lineTo(hx + 0.105f * s, base);
            LUME_PATH.lineTo(hx + 0.032f * s, base);
            LUME_PATH.lineTo(hx + 0.032f * s, base - 0.09f * s);
            LUME_PATH.lineTo(hx - 0.032f * s, base - 0.09f * s);
            LUME_PATH.lineTo(hx - 0.032f * s, base);
            LUME_PATH.lineTo(hx - 0.105f * s, base);
            LUME_PATH.lineTo(hx - 0.105f * s, eave);
            LUME_PATH.lineTo(hx - 0.15f * s, eave);
            LUME_PATH.close();
            LUME.setStrokeJoin(Paint.Join.ROUND);
            LUME.setStyle(Paint.Style.FILL_AND_STROKE);
            LUME.setStrokeWidth(0.03f * s);
            canvas.drawPath(LUME_PATH, LUME);
        }
    }

    /** Weather symbol for an Open-Meteo WMO weather code. */
    static String weather(int code, boolean day) {
        if (code <= 1) return day ? "sunny" : "clear_night";
        if (code == 2) return day ? "partly_cloudy_day" : "partly_cloudy_night";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "rainy";
        if (code == 65 || code == 67 || code == 82) return "rainy";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snowflake";
        if (code >= 95) return "thunderstorm";
        return "rainy";
    }
}
