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
    private static final android.graphics.Path LUME_PATH = new android.graphics.Path(), LUME_CUT = new android.graphics.Path();

    /**
     * The Lume mark, drawn as shapes so it stays sharp at any size: exactly tools/make_art.py's glyph() (a rounded TV
     * with a little house and its door on the screen), `width` wide (the glyph's bounding box) and centered on (cx, cy).
     */
    private static void lume(Canvas canvas, float cx, float cy, float width, int color) {
        synchronized (LUME) {
            float s = width / 0.62f, h = 0.585f * s;
            float ox = cx - width / 2 - 0.19f * s, oy = cy - h / 2 - 0.23f * s;   // the glyph's s × s box
            LUME.setColor(color);
            // Screen: the outline grows inward from the box (as Pillow draws it).
            float lw = 0.055f * s;
            LUME.setStyle(Paint.Style.STROKE);
            LUME.setStrokeWidth(lw);
            RECT.set(ox + 0.19f * s + lw / 2, oy + 0.23f * s + lw / 2, ox + 0.81f * s - lw / 2, oy + 0.67f * s - lw / 2);
            canvas.drawRoundRect(RECT, 0.12f * s - lw / 2, 0.12f * s - lw / 2, LUME);
            // Stand.
            LUME.setStyle(Paint.Style.FILL);
            RECT.set(ox + 0.4f * s, oy + 0.76f * s, ox + 0.6f * s, oy + 0.815f * s);
            canvas.drawRoundRect(RECT, 0.0275f * s, 0.0275f * s, LUME);
            // House: roof and walls (rounded at the bottom), the roof's edge softened, the door cut out.
            float hx = ox + 0.5f * s, top = oy + 0.335f * s, half = 0.115f * s, eave = oy + 0.445f * s, bottom = oy + 0.575f * s, r = 0.025f * s;
            LUME_PATH.rewind();
            LUME_PATH.moveTo(hx - half, eave);
            LUME_PATH.lineTo(hx, top);
            LUME_PATH.lineTo(hx + half, eave);
            LUME_PATH.lineTo(hx + half, bottom - r);
            LUME_PATH.lineTo(hx - half, bottom - r);
            LUME_PATH.close();
            LUME_CUT.rewind();
            RECT.set(hx - half, eave, hx + half, bottom);
            LUME_CUT.addRoundRect(RECT, r, r, android.graphics.Path.Direction.CW);
            LUME_PATH.op(LUME_CUT, android.graphics.Path.Op.UNION);
            LUME_CUT.rewind();
            RECT.set(hx - 0.026f * s, oy + 0.49f * s, hx + 0.026f * s, bottom + 0.01f * s);
            LUME_CUT.addRoundRect(RECT, 0.012f * s, 0.012f * s, android.graphics.Path.Direction.CW);
            LUME_PATH.op(LUME_CUT, android.graphics.Path.Op.DIFFERENCE);
            canvas.drawPath(LUME_PATH, LUME);
            LUME.setStyle(Paint.Style.STROKE);
            LUME.setStrokeWidth(0.03f * s);
            LUME.setStrokeJoin(Paint.Join.ROUND);
            LUME.setStrokeCap(Paint.Cap.ROUND);
            LUME_PATH.rewind();
            LUME_PATH.moveTo(hx - half, eave);
            LUME_PATH.lineTo(hx, top);
            LUME_PATH.lineTo(hx + half, eave);
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
