package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Picture;
import android.graphics.RenderNode;
import android.os.Build;

/**
 * A recorded drawing replayed every frame: a RenderNode on Android 10+, a Picture on Android 9 (Mi Stick, older
 * Fire TV), where RenderNode isn't public. Position, alpha and translation work on both; the compositing layer only on
 * Android 10+.
 */
final class Node {
    private static final boolean RENDER_NODE = Build.VERSION.SDK_INT >= 29;
    private final RenderNode node;
    private final Picture picture;
    private int left, top, width, height;
    private float alpha = 1, tx, ty;

    Node(String name) {
        node = RENDER_NODE ? new RenderNode(name) : null;
        picture = RENDER_NODE ? null : new Picture();
    }

    void setPosition(int l, int t, int r, int b) {
        left = l;
        top = t;
        width = r - l;
        height = b - t;
        if (node != null) node.setPosition(l, t, r, b);
    }

    int getWidth() {
        return width;
    }

    void setAlpha(float a) {
        alpha = a;
        if (node != null) node.setAlpha(a);
    }

    void setTranslationX(float x) {
        tx = x;
        if (node != null) node.setTranslationX(x);
    }

    void setTranslationY(float y) {
        ty = y;
        if (node != null) node.setTranslationY(y);
    }

    void setUseCompositingLayer(boolean use) {
        if (node != null) node.setUseCompositingLayer(use, null);
    }

    Canvas beginRecording(int w, int h) {
        return node != null ? node.beginRecording(w, h) : picture.beginRecording(w, h);
    }

    void endRecording() {
        if (node != null) node.endRecording(); else picture.endRecording();
    }

    void draw(Canvas c) {
        if (node != null) {
            c.drawRenderNode(node);
            return;
        }
        if (alpha <= 0.002f) return;
        int save = alpha < 0.998f
                ? c.saveLayerAlpha(left + tx, top + ty, left + tx + width, top + ty + height, Math.round(255 * alpha))
                : c.save();
        c.translate(left + tx, top + ty);
        c.drawPicture(picture);
        c.restoreToCount(save);
    }
}
