package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Icons drawn as vectors, as in the home screen mockups: outlined gear, apps grid, sliders, TV, HDMI port, and the
 * weather in color (yellow sun, clouds, blue rain...). Each one fits a square of `size` centered on (cx, cy).
 */
final class Icons {
    private Icons() {}

    private static final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path path = new Path(), path2 = new Path();
    private static final RectF r = new RectF();

    static {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    /** Draws the icon for a shortcut or input symbol; false when there is no vector version (use Symbols). */
    static boolean draw(Canvas c, String symbol, float cx, float cy, float size, int color) {
        switch (symbol == null ? "" : symbol) {
            case "settings": gear(c, cx, cy, size, color); return true;
            case "apps": grid(c, cx, cy, size, color); return true;
            case "tv_settings": sliders(c, cx, cy, size, color); return true;
            case "tv": tv(c, cx, cy, size, color); return true;
            case "hdmi": hdmi(c, cx, cy, size, color); return true;
            default: return false;
        }
    }

    /** Outlined gear (eight teeth, a hole in the middle). */
    static void gear(Canvas c, float cx, float cy, float size, int color) {
        float u = size / 24f;
        path.rewind();
        path.addCircle(cx, cy, 7.2f * u, Path.Direction.CW);
        for (int i = 0; i < 8; i++) {
            path2.rewind();
            r.set(cx - 1.9f * u, cy - 10.6f * u, cx + 1.9f * u, cy - 5.5f * u);
            path2.addRoundRect(r, 1f * u, 1f * u, Path.Direction.CW);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setRotate(i * 45, cx, cy);
            path2.transform(m);
            path.op(path2, Path.Op.UNION);
        }
        stroke.setColor(color);
        stroke.setStrokeWidth(1.9f * u);
        c.drawPath(path, stroke);
        c.drawCircle(cx, cy, 3.2f * u, stroke);
    }

    /** Apps: a 3 × 3 grid of rounded squares. */
    static void grid(Canvas c, float cx, float cy, float size, int color) {
        float u = size / 24f, cell = 5.4f * u, gap = 1.9f * u, start = -(3 * cell + 2 * gap) / 2;
        fill.setColor(color);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                float l = cx + start + x * (cell + gap), t = cy + start + y * (cell + gap);
                r.set(l, t, l + cell, t + cell);
                c.drawRoundRect(r, 1.3f * u, 1.3f * u, fill);
            }
        }
    }

    /** TV settings: three sliders with round knobs. */
    static void sliders(Canvas c, float cx, float cy, float size, int color) {
        float u = size / 24f, l = cx - 10 * u, rt = cx + 10 * u, kr = 2.3f * u;
        float[] ys = {-6.5f, 0, 6.5f}, ks = {3.5f, -3.5f, 1.5f};
        stroke.setColor(color);
        stroke.setStrokeWidth(1.9f * u);
        for (int i = 0; i < 3; i++) {
            float y = cy + ys[i] * u, k = cx + ks[i] * u;
            c.drawLine(l, y, k - kr, y, stroke);
            c.drawLine(k + kr, y, rt, y, stroke);
            c.drawCircle(k, y, kr, stroke);
        }
    }

    /** A TV: the screen outlined and its stand. */
    static void tv(Canvas c, float cx, float cy, float size, int color) {
        float u = size / 24f;
        stroke.setColor(color);
        stroke.setStrokeWidth(2f * u);
        r.set(cx - 10.5f * u, cy - 8 * u, cx + 10.5f * u, cy + 5.5f * u);
        c.drawRoundRect(r, 2.2f * u, 2.2f * u, stroke);
        c.drawLine(cx - 4 * u, cy + 9.5f * u, cx + 4 * u, cy + 9.5f * u, stroke);
        c.drawLine(cx, cy + 5.5f * u, cx, cy + 9.5f * u, stroke);
    }

    /** An HDMI port: the wide plug outline with its row of pins. */
    static void hdmi(Canvas c, float cx, float cy, float size, int color) {
        float u = size / 24f;
        path.rewind();
        path.moveTo(cx - 11 * u, cy - 4.5f * u);
        path.lineTo(cx + 11 * u, cy - 4.5f * u);
        path.lineTo(cx + 11 * u, cy + 0.5f * u);
        path.lineTo(cx + 8 * u, cy + 4.5f * u);
        path.lineTo(cx - 8 * u, cy + 4.5f * u);
        path.lineTo(cx - 11 * u, cy + 0.5f * u);
        path.close();
        stroke.setColor(color);
        stroke.setStrokeWidth(1.9f * u);
        c.drawPath(path, stroke);
        fill.setColor(color);
        for (int i = 0; i < 6; i++) {
            float x = cx - 6.25f * u + i * 2.5f * u;
            r.set(x - 0.8f * u, cy - 1.4f * u, x + 0.8f * u, cy + 0.4f * u);
            c.drawRect(r, fill);
        }
    }

    // MARK: - Weather, in color

    private static int SUN = 0xFFFFC83A, MOON = 0xFFF3E3A1, RAIN = 0xFF4DA3FF, BOLT = 0xFFFFC83A;

    /**
     * The weather for an Open-Meteo code, in color: `cloud` is the cloud color (white on dark, gray on light).
     */
    static void weather(Canvas c, int code, boolean day, float cx, float cy, float size, int cloud) {
        float u = size / 24f;
        // On a light background (dark clouds), deeper colors so the sun, moon and rain still stand out.
        boolean onLight = android.graphics.Color.luminance(cloud) < 0.5f;
        SUN = onLight ? 0xFFF29D00 : 0xFFFFC83A;
        BOLT = SUN;
        MOON = onLight ? 0xFFD9A520 : 0xFFF3E3A1;
        RAIN = onLight ? 0xFF1E7BE6 : 0xFF4DA3FF;
        if (code <= 1) {
            if (day) sun(c, cx, cy, 6.2f * u, true);
            else moon(c, cx, cy, 7.5f * u);
            return;
        }
        if (code == 2) {
            if (day) sun(c, cx + 3.5f * u, cy - 3.5f * u, 4.6f * u, true);
            else moon(c, cx + 4f * u, cy - 4f * u, 5.2f * u);
            cloud(c, cx - 1.5f * u, cy + 2.5f * u, u * 0.85f, cloud);
            return;
        }
        if (code == 45 || code == 48) {
            cloud(c, cx, cy - 2.5f * u, u * 0.85f, cloud);
            stroke.setColor(cloud);
            stroke.setStrokeWidth(1.8f * u);
            c.drawLine(cx - 8 * u, cy + 6 * u, cx + 6 * u, cy + 6 * u, stroke);
            c.drawLine(cx - 5 * u, cy + 9.5f * u, cx + 9 * u, cy + 9.5f * u, stroke);
            return;
        }
        if (code == 3) {
            cloud(c, cx, cy, u, cloud);
            return;
        }
        cloud(c, cx, cy - 3 * u, u * 0.9f, cloud);
        if (code >= 71 && code <= 77 || code == 85 || code == 86) {
            fill.setColor(0xFFDCEBFF);
            for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * 5 * u, cy + 7.5f * u + (i == 0 ? 2 : 0) * u, 1.5f * u, fill);
            return;
        }
        if (code >= 95) {
            path.rewind();
            path.moveTo(cx + 1 * u, cy + 2 * u);
            path.lineTo(cx - 3 * u, cy + 8 * u);
            path.lineTo(cx, cy + 8 * u);
            path.lineTo(cx - 2 * u, cy + 12 * u);
            path.lineTo(cx + 4 * u, cy + 5.5f * u);
            path.lineTo(cx + 1 * u, cy + 5.5f * u);
            path.close();
            fill.setColor(BOLT);
            c.drawPath(path, fill);
            return;
        }
        stroke.setColor(RAIN);
        stroke.setStrokeWidth(2f * u);
        for (int i = -1; i <= 1; i++) {
            float x = cx + i * 5 * u;
            c.drawLine(x + 1 * u, cy + 5 * u, x - 1 * u, cy + 10 * u, stroke);
        }
    }

    private static void sun(Canvas c, float cx, float cy, float rad, boolean rays) {
        fill.setColor(SUN);
        c.drawCircle(cx, cy, rad, fill);
        if (!rays) return;
        stroke.setColor(SUN);
        stroke.setStrokeWidth(rad * 0.28f);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float a0 = rad * 1.45f, a1 = rad * 1.9f;
            c.drawLine(cx + (float) Math.cos(a) * a0, cy + (float) Math.sin(a) * a0,
                    cx + (float) Math.cos(a) * a1, cy + (float) Math.sin(a) * a1, stroke);
        }
    }

    private static void moon(Canvas c, float cx, float cy, float rad) {
        path.rewind();
        path.addCircle(cx, cy, rad, Path.Direction.CW);
        path2.rewind();
        path2.addCircle(cx + rad * 0.55f, cy - rad * 0.4f, rad * 0.85f, Path.Direction.CW);
        path.op(path2, Path.Op.DIFFERENCE);
        fill.setColor(MOON);
        c.drawPath(path, fill);
    }

    /** A cloud about 20 units wide (u = unit size), centered. */
    private static void cloud(Canvas c, float cx, float cy, float u, int color) {
        path.rewind();
        r.set(cx - 10 * u, cy - 1 * u, cx + 10 * u, cy + 6 * u);
        path.addRoundRect(r, 3.5f * u, 3.5f * u, Path.Direction.CW);
        path.addCircle(cx - 4 * u, cy, 4.6f * u, Path.Direction.CW);
        path.addCircle(cx + 2.2f * u, cy - 2.2f * u, 6.2f * u, Path.Direction.CW);
        path.addCircle(cx + 7 * u, cy + 1.5f * u, 4 * u, Path.Direction.CW);
        // A soft shadow at the bottom gives it a little volume (like the mockup's clouds).
        fill.setColor(color);
        c.drawPath(path, fill);
    }
}
