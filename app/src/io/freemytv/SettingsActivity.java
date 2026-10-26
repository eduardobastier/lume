package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Free My TV / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onDestroy() {
        view.releaseSounds();
        super.onDestroy();
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /**
     * Recommendations → TMDB key: typed with the TV's keyboard, checked with TMDB before it's kept. Empty = go back to
     * the key built into the app (if any).
     */
    interface TextDone { void done(String text); }

    void askTmdbKey() {
        askText(Ui.tr("Chave do TMDB", "TMDB key"),
                Ui.tr("Grátis em themoviedb.org → Ajustes → API. Deixe vazio para usar a chave do app.",
                        "Free at themoviedb.org → Settings → API. Leave empty to use the app's key."),
                Ui.tr("Chave da API (v3) ou token de leitura", "API key (v3) or read access token"),
                Prefs.typedTmdbKey(this) ? Prefs.tmdbKey(this) : "", true, this::saveTmdbKey);
    }

    /** Wallpaper → Source → Wallhaven search: what to look for (in English works best), typed with the TV's keyboard. */
    void askWallhaven() {
        String v = Prefs.wallpaperSource(this);
        askText(Ui.tr("Buscar no Wallhaven", "Search Wallhaven"),
                Ui.tr("Uma ou duas palavras, de preferência em inglês: nature, city night, mountains.",
                        "One or two words: nature, city night, mountains."),
                Ui.tr("O que buscar", "Search for"), v.startsWith("wallhaven:") ? v.substring(10) : "", false,
                text -> view.setWallpaperSource("wallhaven:" + text));
    }

    /** Weather → City: typed with the TV's keyboard and looked up (Open-Meteo) before it's kept. */
    void askCity() {
        askText(Ui.tr("Cidade da previsão", "Weather city"),
                Ui.tr("Digite o nome da cidade, por exemplo: Quissamã, RJ.", "Type the city's name, e.g. Lisbon."),
                Ui.tr("Nome da cidade", "City name"), Prefs.weatherCityName(this), false, this::saveCity);
    }

    private void saveCity(final String name) {
        if (name.isEmpty()) return;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String[] found = HomeData.findCity(name.replace(",", " "));
            if (found != null) {
                Prefs.setWeatherCity(app, found[0], found[1], found[2]);
                HomeData.get(app).relocate();
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                android.widget.Toast.makeText(app, found != null
                        ? Ui.tr("Previsão de ", "Weather for ") + found[3] + "."
                        : Ui.tr("Não achei essa cidade. Confira o nome e tente de novo.", "Couldn't find that city. Check the name and try again."),
                        android.widget.Toast.LENGTH_LONG).show();
                view.invalidate();
            });
        }).start();
    }

    /** A text field in our own panel (the same look as the popups), with the TV's keyboard. */
    private void askText(String titleText, String messageText, String hint, String initial, boolean secret, TextDone done) {
        final boolean light = view.isLight();
        final int ink = light ? 0xFF1D1D1F : 0xFFF5F5F7, dim = light ? 0x991D1D1F : 0x99F5F5F7;
        float dp = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(28 * dp);
        box.setPadding(pad, pad, pad, Math.round(8 * dp));
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText(titleText);
        title.setTextColor(ink);
        title.setTextSize(24);
        title.setTypeface(Ui.semibold);
        android.widget.TextView message = new android.widget.TextView(this);
        message.setText(messageText);
        message.setTextColor(dim);
        message.setTextSize(15);
        message.setTypeface(Ui.regular);
        message.setPadding(0, Math.round(8 * dp), 0, Math.round(16 * dp));
        final android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setTextColor(ink);
        field.setHintTextColor(dim);
        field.setTypeface(Ui.regular);
        field.setHint(hint);
        field.setText(initial);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (secret ? android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS));
        field.setTypeface(Ui.regular);   // after setInputType, which switches to monospace
        field.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF0A84FF));
        box.addView(title);
        box.addView(message);
        box.addView(field);
        // Our own colors (the system's dialog stays dark on this TV): the same panel as the genre popup.
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton(Ui.tr("Salvar", "Save"), (d, w) -> done.done(field.getText().toString().trim()))
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                .create();
        android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
        panel.setColor(light ? 0xFFF2F2F7 : 0xFF2C2C2E);
        panel.setCornerRadius(28 * dp);
        dialog.setOnShowListener(d -> {
            dialog.getWindow().setBackgroundDrawable(panel);
            for (int which : new int[] {android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE}) {
                android.widget.Button b = dialog.getButton(which);
                b.setTextColor(which == android.app.AlertDialog.BUTTON_POSITIVE ? 0xFF0A84FF : ink);
                b.setTypeface(Ui.medium);
            }
            field.requestFocus();
        });
        dialog.show();
    }

    private void saveTmdbKey(final String key) {
        if (key.isEmpty()) {
            Prefs.setTmdbKey(this, "");
            return;
        }
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            boolean ok = Recs.validKey(key);
            if (ok) Prefs.setTmdbKey(app, key);
            new Handler(Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(app, ok
                    ? Ui.tr("Chave salva. As recomendações vão aparecer no Início.", "Key saved. Recommendations will show on the home screen.")
                    : Ui.tr("O TMDB não aceitou essa chave. Confira e tente de novo.", "TMDB didn't accept that key. Check it and try again."),
                    android.widget.Toast.LENGTH_LONG).show());
        }).start();
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, RECS = 2, THEME = 3, SAVER = 4, WEATHER = 5, UPDATE = 6, WALL = 7, HOMESCR = 8, MAIN_ROWS = 4;
        /** How long the TV waits before the screensaver (Android's screen-off timeout), in minutes. */
        private static final int[] SAVER_MINUTES = {1, 2, 5, 10, 15, 30, 60};
        /** The popup over the list: none, the theme, the genres (several at once) or what OK on a card opens. */
        private static final int NO_POPUP = 0, THEME_POPUP = 1, GENRE_POPUP = 2, ACTION_POPUP = 3, LIVE_POPUP = 4, SAVER_POPUP = 5,
                YES_NO_POPUP = 6, PLACE_POPUP = 7, WP_MODE_POPUP = 8, WP_PICK_POPUP = 9, WP_SOURCE_POPUP = 10, WP_ROTATE_POPUP = 11, BG_POPUP = 12, WX_POS_POPUP = 13;
        /** The on/off choice the Yes/No popup is for: its Prefs key and title. */
        private String yesNoKey = "", yesNoTitle = "";
        /** Live TV choices: [value, name], built when the popup opens (the TV's inputs and installed apps). */
        private List<String[]> liveChoices = new ArrayList<>();
        private int popup = NO_POPUP, popupFocus;
        private final Spring[] popupPop = new Spring[40];
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_H = 74, ROW_GAP = 8, CHOICE_H = 104;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private Click click;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[10];
        private final RectF tmp = new RectF(), rowRect = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                if (!state.optString("busy").isEmpty()) pendingAt = 0;
                invalidate();
                handler.postDelayed(this, 500);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            for (int i = 0; i < popupPop.length; i++) popupPop[i] = new Spring(0.28f, 0.85f, 0);
            click = new Click(a);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
        }

        void resume() {
            if (running) return;
            if (w > 0 && wantLight() != light) {
                light = !light;
                initPaints();
            }
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            for (Spring s : popupPop) moving |= !s.settled();
            if (moving || SystemClock.uptimeMillis() - lastSky >= 100) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            light = wantLight();
            initPaints();
        }

        private void initPaints() {
            title = Ui.text(Ui.displaySemibold, 50 * k, fg(0xFFFFFFFF));
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, fg(0x99FFFFFF));
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, fg(0x66FFFFFF));
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, fg(0xF2FFFFFF));
            rowValue = Ui.text(Ui.regular, 28 * k, fg(0x8CFFFFFF));
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            header = Ui.text(Ui.semibold, 21 * k, fg(0x73FFFFFF));
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, fg(0xD9FFFFFF));
            cellValue = Ui.numbers(Ui.regular, 25 * k, fg(0xD9FFFFFF));
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, fg(0xB3FFFFFF)));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        }

        /** Light look (the Theme setting, or daytime on Automatic): white text turns into near-black. */
        private boolean light;

        private int fg(int color) {
            return light ? (color & 0xFF000000) | 0x1D1D1F : color;
        }

        void releaseSounds() {
            click.release();
        }

        boolean isLight() {
            return light;
        }

        private boolean wantLight() {
            int t = HomeView.theme(getContext());
            return t == HomeView.THEME_AUTO ? !HomeData.get(getContext()).isNight(System.currentTimeMillis()) : t == HomeView.THEME_LIGHT;
        }

        /** Background like Apple TV's Settings: a quiet gray gradient, dark or light. */
        private void drawBackdrop(Canvas c) {
            Paint bg = new Paint(Paint.DITHER_FLAG);
            bg.setShader(new android.graphics.LinearGradient(0, 0, 0, h, light ? 0xFFF5F5F7 : 0xFF2C2C30,
                    light ? 0xFFDCDCE2 : 0xFF141416, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, bg);
        }

        /** The app's icon, big, in a rounded square (Apple TV shows the section's icon on the left). */
        private void drawIcon(Canvas c, float cx, float cy, float size) {
            tmp.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2);
            float r = size * 0.22f;
            tmp.offset(0, 14 * k);
            shadow.setColor(light ? 0x33000000 : 0x66000000);
            c.drawRoundRect(tmp, r, r, shadow);
            tmp.offset(0, -14 * k);
            Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
            icon.setShader(new android.graphics.LinearGradient(0, tmp.top, 0, tmp.bottom, 0xFF5E5CE6, 0xFF0A84FF,
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(tmp, r, r, icon);
            Symbols.draw(getContext(), c, "sparkles", cx, cy, size * 0.52f, 0xFFFFFFFF);
        }

        private String screenTitle() {
            if (screen == RECS) return Ui.tr("Recomendações", "Recommendations");
            if (screen == THEME) return Ui.tr("Tema", "Theme");
            if (screen == HOME_CHOICE) return Ui.tr("Início", "Home Screen");
            if (screen == SAVER) return Ui.tr("Tela de Descanso", "Screensaver");
            if (screen == WEATHER) return Ui.tr("Previsão do Tempo", "Weather");
            if (screen == UPDATE) return Ui.tr("Sistema", "System");
            if (screen == HOMESCR) return Ui.tr("Tela Inicial", "Home Screen");
            if (screen == WALL) return Ui.tr("Papel de Parede", "Wallpaper");
            return Ui.tr("Ajustes do Início", "Home Settings");
        }

        private boolean busy() {
            return !state.optString("busy").isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            return screen == MAIN ? MAIN_ROWS : screen == RECS ? 5 : screen == THEME ? 3 : screen == WEATHER ? 4 : screen == WALL ? 4
                    : screen == UPDATE ? updateRows() : screen == HOMESCR ? 6 : screen == SAVER ? 3 : 2;
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            lastSky = up;
            drawBackdrop(c);

            // Apple TV's Settings: the title on top, the big icon on the left, the list on the right.
            c.drawText(screenTitle(), 960 * k, 118 * k, title);
            float cx = 500 * k;
            drawIcon(c, cx, 470 * k, 300 * k);
            c.drawText("Free My TV", cx, 690 * k, subtitle);
            c.drawText(versionName(getContext()), cx, 732 * k, version);

            if (screen == MAIN) drawMain(c, seconds);
            else if (screen == RECS) drawRecsScreen(c);
            else if (screen == THEME) drawThemeScreen(c);
            else if (screen == SAVER) drawSaverScreen(c);
            else if (screen == WEATHER) drawWeatherScreen(c);
            else if (screen == UPDATE) drawUpdateScreen(c);
            else if (screen == WALL) drawWallScreen(c);
            else if (screen == HOMESCR) drawHomeScreen(c);
            else drawChoice(c);
            if (popup != NO_POPUP) drawPopup(c, dt);
        }

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = state.optString("busy");
            String mode = freeActive() ? "Free My TV" : Ui.tr("Original", "Original");
            String[] labels = {Ui.tr("Início", "Home"), Ui.tr("Tela inicial", "Home screen"), Ui.tr("Tela de descanso", "Screensaver"),
                    Ui.tr("Sistema", "System")};
            String[] values = {working ? "" : mode, themes()[HomeView.theme(getContext())] + " · " + backgroundName(), saverName(),
                    updateName()};
            for (int i = 0; i < MAIN_ROWS; i++) {
                float y = (LIST_TOP + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                int ink = blend(fg(0xF2FFFFFF), INK, f);
                rowLabel.setColor(ink);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (!(i == 0 && working)) {
                    valueAndChevron(c, values[i], base, f);
                } else {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = busyText.isEmpty() ? Ui.tr("Começando…", "Starting…") : busyText;
                    rowValue.setColor(blend(fg(0xB3FFFFFF), 0xB31C1C1E, f));
                    String shown = TextUtils.ellipsize(text, new TextPaint(rowValue), 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(blend(fg(0xFFFFFFFF), INK, f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                }
            }

            float y = (LIST_TOP + MAIN_ROWS * (ROW_H + ROW_GAP) + 46) * k;
            String error = state.optString("error");
            if (working && (busyText.contains("Permitir") || busyText.contains("Allow"))) {
                c.drawText(Ui.tr("Na TV: marque “Sempre permitir” e aperte OK.", "On the TV: tick “Always allow” and press OK."),
                        LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            } else if (!working && !error.isEmpty()) {
                c.drawText(TextUtils.ellipsize(error, new TextPaint(warn), 760 * k, TextUtils.TruncateAt.END).toString(), LIST_X * k + 30 * k, y, warn);
                y += 46 * k;
            }
            // Like Apple TV's Settings: the list, and below it only the recommendations status and the notices.
            drawRecs(c, y);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!Prefs.recsOn(getContext())) line = Ui.tr("Desligadas · Papel de parede: ", "Off · Wallpaper: ") + Wallpapers.sourceName(getContext()) + ".";
            else if (!hasKey) line = Ui.tr("Sem chave do TMDB: coloque a sua em Recomendações.", "No TMDB key: add yours in Recommendations.");
            else if (!error.isEmpty()) line = Ui.tr("Erro: ", "Error: ") + error + ".";
            else if (count > 0) line = Ui.tr("Ligadas · " + count + " títulos em alta, trocando a cada 5 segundos.",
                    "On · " + count + " trending titles, changing every 5 seconds.");
            else line = Ui.tr("Buscando os títulos…", "Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
            return y + 78 * k;
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        /** Home screen choice: Free My TV or the original, with a check on the one in use. */
        private String[] themes() {
            return new String[] {Ui.tr("Automático", "Automatic"), Ui.tr("Claro", "Light"), Ui.tr("Escuro", "Dark")};
        }

        private String[] actions() {
            Context c = getContext();
            String missing = Ui.tr(" (não instalado)", " (not installed)");
            return new String[] {Ui.tr("Trailer no YouTube", "Trailer on YouTube"),
                    Ui.tr("Trailer no SmartTube", "Trailer on SmartTube") + (Prefs.smartTube(c) == null ? missing : ""),
                    Ui.tr("Abrir o título no Nuvio", "Open the title in Nuvio")
                            + (HomeApps.launchIntent(c.getPackageManager(), Prefs.NUVIO_PKG) == null ? missing : "")};
        }

        private static final String[] ACTION_KEYS = {Prefs.YOUTUBE, Prefs.SMARTTUBE, Prefs.NUVIO};

        private int actionIndex() {
            return Math.max(0, java.util.Arrays.asList(ACTION_KEYS).indexOf(Prefs.cardAction(getContext())));
        }

        /** Recommendations: genres, new releases only, any streaming service, and what OK on a card opens. */
        /**
         * The rows: "Show recommendations" first; on, the recommendation options; off, only Wallpaper (it shows instead).
         */
        private static final int R_SHOW = 0, R_KEY = 1, R_GENRES = 2, R_RECENT = 3, R_ANY = 4, R_ACTION = 5, R_WALL = 6;

        private int[] recsRows() {
            return new int[] {R_KEY, R_GENRES, R_RECENT, R_ANY, R_ACTION};
        }

        private void drawRecsScreen(Canvas c) {
            Context ctx = getContext();
            String yes = Ui.tr("Sim", "On"), no = Ui.tr("Não", "Off");
            String key = Prefs.tmdbKey(ctx);
            String keyValue = key.isEmpty() ? Ui.tr("Não configurada", "Not set")
                    : (Prefs.typedTmdbKey(ctx) ? "" : Ui.tr("Do app · ", "Built in · ")) + "••••" + key.substring(Math.max(0, key.length() - 4));
            String[] labels = {Ui.tr("Mostrar recomendações", "Show recommendations"), Ui.tr("Chave do TMDB", "TMDB key"),
                    Ui.tr("Categorias", "Genres"), Ui.tr("Só lançamentos", "New releases only"),
                    Ui.tr("Em qualquer streaming", "On any streaming service"), Ui.tr("Ao apertar OK no card", "OK on a card"),
                    Ui.tr("Papel de parede", "Wallpaper")};
            String[] values = {Prefs.recsOn(ctx) ? yes : no, keyValue, Prefs.genreNames(ctx), Prefs.recent(ctx) ? yes : no,
                    Prefs.anyService(ctx) ? yes : no, actions()[actionIndex()], Wallpapers.sourceName(ctx)};
            int[] rows = recsRows();
            for (int i = 0; i < rows.length; i++) {
                float y = (LIST_TOP + 20 + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[rows[i]], rowRect.left + 30 * k, base, rowLabel);
                valueAndChevron(c, values[rows[i]], base, f);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            c.drawText(Ui.tr("As mudanças aparecem quando você voltar ao Início.", "Changes show up when you go back home."),
                    LIST_X * k + 30 * k, (LIST_TOP + 20 + rows.length * (ROW_H + ROW_GAP) + 40) * k, hint);
        }

        // MARK: - Wallpaper (recommendations off): images from GitHub, fixed or rotating, home and screensaver

        private static final int[] WP_MINUTES = {1, 5, 15, 30, 60, 180, 1440};
        private int wpSlot;             // which one the popups are choosing for (Prefs.WP_LIGHT, WP_DARK, WP_SAVER)
        private boolean wpSyncing;
        private String wpStatus = "";

        private String every(int m) {
            return Ui.tr("A cada ", "Every ") + (m == 1440 ? Ui.tr("1 dia", "1 day") : minutes(m));
        }

        /** What a wallpaper choice means, in words. */
        private String wallName(int slot) {
            String v = Prefs.wallpaper(getContext(), slot);
            if (v.equals("same")) return slot == Prefs.WP_SAVER ? Ui.tr("Igual ao modo escuro", "Same as dark mode")
                    : Ui.tr("Igual ao modo claro", "Same as light mode");
            if (v.startsWith("fixed:")) return Ui.tr("Fixa: ", "Fixed: ") + Wallpapers.label(getContext(), v.substring(6));
            try {
                return every(Integer.parseInt(v.substring(v.indexOf(':') + 1)));
            } catch (RuntimeException e) {
                return v;
            }
        }

        private void drawWallScreen(Canvas c) {
            Context ctx = getContext();
            int n = Wallpapers.names(ctx).size();
            String[] labels = {Ui.tr("Fonte", "Source"), Ui.tr("Imagens na troca", "Images that rotate"),
                    Ui.tr("Modo claro", "Light mode"),
                    Ui.tr("Modo escuro", "Dark mode")};
            String count = wpSyncing ? Ui.tr("Sincronizando…", "Syncing…") : !wpStatus.isEmpty() ? wpStatus
                    : n == 0 ? Ui.tr("Nenhuma ainda", "None yet") : n + Ui.tr(n == 1 ? " imagem" : " imagens", n == 1 ? " image" : " images");
            List<String> all = Wallpapers.names(ctx);
            int rotating = Wallpapers.rotation(ctx, all).size();
            String inRotation = all.isEmpty() ? "—" : rotating == all.size() ? Ui.tr("Todas", "All")
                    : rotating + Ui.tr(" de ", " of ") + all.size();
            String[] values = {Wallpapers.sourceName(ctx), inRotation, wallName(Prefs.WP_LIGHT), wallName(Prefs.WP_DARK)};
            for (int i = 0; i < 4; i++) {
                float y = (LIST_TOP + 20 + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                valueAndChevron(c, values[i], base, f);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            String note = Ui.tr("Baixadas na TV (até 12), nada vem dentro do app. Atualiza a cada 3 h.",
                    "Downloaded on the TV (up to 12); none come with the app. Refreshed every 3 h.");
            c.drawText(note, LIST_X * k + 30 * k, (LIST_TOP + 20 + 4 * (ROW_H + ROW_GAP) + 40) * k, hint);
            String more = wpSyncing ? Ui.tr("Baixando as imagens da fonte…", "Downloading the source's images…")
                    : (n == 0 ? (wpStatus.isEmpty() ? Ui.tr("Nenhuma imagem ainda", "No images yet") : Ui.tr("Nada encontrado nessa fonte", "Nothing found at this source")) : n + Ui.tr(n == 1 ? " imagem na TV" : " imagens na TV", n == 1 ? " image on the TV" : " images on the TV"))
                    + Ui.tr(" · trocar a fonte baixa as novas.", " · changing the source downloads new ones.");
            c.drawText(more, LIST_X * k + 30 * k, (LIST_TOP + 20 + 4 * (ROW_H + ROW_GAP) + 80) * k, hint);
        }

        private void syncWallpapers() {
            if (wpSyncing) return;
            wpSyncing = true;
            wpStatus = "";
            final Context app = getContext().getApplicationContext();
            int before = Wallpapers.names(app).size();
            Wallpapers.syncIfStale(app, true, () -> handler.post(() -> {
                wpSyncing = false;
                int n = Wallpapers.names(app).size();
                wpStatus = n == 0 ? Ui.tr("Nada encontrado", "Nothing found") : n + Ui.tr(" imagens", " images")
                        + (n != before ? Ui.tr(" (atualizado)", " (updated)") : "");
                invalidate();
            }));
            invalidate();
        }

        /** Options of the wallpaper popups: for the screensaver "same" first, then fixed, then the intervals. */
        private String[] wpModeOptions() {
            List<String> out = new ArrayList<>();
            if (wpSlot == Prefs.WP_DARK) out.add(Ui.tr("Igual ao modo claro", "Same as light mode"));
            if (wpSlot == Prefs.WP_SAVER) out.add(Ui.tr("Igual ao modo escuro", "Same as dark mode"));
            out.add(Ui.tr("Imagem fixa…", "Fixed image…"));
            for (int m : WP_MINUTES) out.add(every(m));
            return out.toArray(new String[0]);
        }

        private String wpModeValue(int i) {
            if (wpSlot != Prefs.WP_LIGHT) {
                if (i == 0) return "same";
                i--;
            }
            return i == 0 ? "fixed:" : "every:" + WP_MINUTES[i - 1];
        }

        /** The wallpaper sources: Bing, Wallhaven (popular or a search) and, if this APK names one, the GitHub folder. */
        private String[] wpSources() {
            List<String> out = new ArrayList<>();
            out.add(Ui.tr("Bing: imagens do dia", "Bing: images of the day"));
            out.add(Ui.tr("Wallhaven: mais populares", "Wallhaven: most popular"));
            out.add(Ui.tr("Wallhaven: buscar…", "Wallhaven: search…"));
            if (Wallpapers.githubHasImages(getContext())) out.add("GitHub: " + Config.WALLPAPER_REPO);
            return out.toArray(new String[0]);
        }

        private int wpSourceIndex() {
            String v = Prefs.wallpaperSource(getContext());
            return v.equals("bing") ? 0 : v.equals("wallhaven:") ? 1 : v.startsWith("wallhaven:") ? 2 : 3;
        }

        void setWallpaperSource(String value) {
            Context c = getContext();
            Prefs.setWallpaperSource(c, value);
            // A fixed image from the old source is gone: back to rotating (home) and following the home screen (saver).
            if (Prefs.wallpaper(c, Prefs.WP_LIGHT).startsWith("fixed:")) Prefs.setWallpaper(c, Prefs.WP_LIGHT, "every:60");
            for (int slot : new int[] {Prefs.WP_DARK, Prefs.WP_SAVER}) {
                if (Prefs.wallpaper(c, slot).startsWith("fixed:")) Prefs.setWallpaper(c, slot, "same");
            }
            synchronized (wpThumbs) {
                wpThumbs.clear();
            }
            wpStatus = "";
            syncWallpapers();
        }

        // The fixed-image picker: thumbnails of the downloaded wallpapers, 4 per row, the focused one raised in white.
        private static final int WP_COLS = 4;
        private final java.util.Map<String, android.graphics.Bitmap> wpThumbs = new java.util.HashMap<>();
        private boolean wpThumbsLoading;

        private void loadWpThumbs() {
            if (wpThumbsLoading) return;
            wpThumbsLoading = true;
            final List<String> names = new ArrayList<>(wpNames());
            final java.io.File dir = Wallpapers.dir(getContext());
            new Thread(() -> {
                for (String name : names) {
                    synchronized (wpThumbs) {
                        if (wpThumbs.containsKey(name)) continue;
                    }
                    String path = new java.io.File(dir, name).getPath();
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(path, o);
                    o.inJustDecodeBounds = false;
                    o.inSampleSize = 1;
                    while (o.outWidth / (o.inSampleSize * 2) >= 360) o.inSampleSize *= 2;
                    android.graphics.Bitmap b = null;
                    try {
                        b = android.graphics.BitmapFactory.decodeFile(path, o);
                    } catch (OutOfMemoryError ignored) {}
                    synchronized (wpThumbs) {
                        wpThumbs.put(name, b);
                    }
                    handler.post(this::invalidate);
                }
                wpThumbsLoading = false;
            }, "wallpaper-thumbs").start();
        }

        private void drawWpPicker(Canvas c, float dt) {
            List<String> names = wpNames();
            int rows = Math.max(1, (names.size() + WP_COLS - 1) / WP_COLS);
            float tw = 330, th = tw * 9 / 16, gap = 26, pad = 44, head = 100, foot = 70;
            float pw = WP_COLS * tw + (WP_COLS - 1) * gap + 2 * pad, ph = head + rows * (th + gap) + foot;
            float left = (1920 - pw) / 2 * k, top = Math.max(20, (1080 - ph) / 2) * k;
            Paint dim = new Paint();
            dim.setColor(0x99000000);
            c.drawRect(0, 0, w, h, dim);
            tmp.set(left, top, left + pw * k, top + ph * k);
            Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
            panel.setColor(light ? 0xFAE6E6EB : 0xF22C2C2E);
            c.drawRoundRect(tmp, 32 * k, 32 * k, panel);
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, fg(0xFFFFFFFF));
            c.drawText(popupTitle(), left + pad * k, top + 64 * k, heading);
            Paint photo = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
            frame.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < names.size(); i++) {
                popupPop[i].target = i == popupFocus ? 1 : 0;
                popupPop[i].step(dt);
                float f = Ui.clamp(popupPop[i].value), s = 1 + 0.08f * f;
                int col = i % WP_COLS, row = i / WP_COLS;
                float cx = left + (pad + col * (tw + gap) + tw / 2) * k, cy = top + (head + row * (th + gap) + th / 2) * k;
                RectF r = new RectF(cx - tw * s / 2 * k, cy - th * s / 2 * k, cx + tw * s / 2 * k, cy + th * s / 2 * k);
                float rad = 14 * k;
                android.graphics.Bitmap b;
                synchronized (wpThumbs) {
                    b = wpThumbs.get(names.get(i));
                }
                c.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
                c.clipPath(clip);
                if (b != null) {
                    // Center-crop to 16:9.
                    float scale = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
                    float bw = b.getWidth() * scale, bh = b.getHeight() * scale;
                    c.drawBitmap(b, null, new RectF(r.centerX() - bw / 2, r.centerY() - bh / 2, r.centerX() + bw / 2, r.centerY() + bh / 2), photo);
                } else {
                    Paint ph2 = new Paint();
                    ph2.setColor(0x33FFFFFF);
                    c.drawRect(r, ph2);
                }
                c.restore();
                if (f > 0.01f) {
                    frame.setStrokeWidth(5 * k);
                    frame.setColor(Ui.alpha(0xFFFFFFFF, f));
                    c.drawRoundRect(r, rad, rad, frame);
                }
                if (popupChecked(i)) {
                    float cr = 20 * k, ccx = r.right - cr - 10 * k, ccy = r.top + cr + 10 * k;
                    Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                    dot.setColor(0xFF0A84FF);
                    c.drawCircle(ccx, ccy, cr, dot);
                    Symbols.draw(getContext(), c, "check", ccx, ccy, 22 * k, 0xFFFFFFFF);
                }
            }
            Paint hint = Ui.text(Ui.regular, 22 * k, fg(0x8CFFFFFF));
            String name = popupFocus < names.size() ? Wallpapers.label(getContext(), names.get(popupFocus)) : "";
            String keys = popup == WP_ROTATE_POPUP ? Ui.tr("  ·  OK marca ou desmarca · Voltar fecha", "  ·  OK ticks or unticks · Back closes")
                    : Ui.tr("  ·  OK escolhe · Voltar fecha", "  ·  OK picks · Back closes");
            c.drawText(name + keys, left + pad * k, top + (ph - 26) * k, hint);
        }

        private List<String> wpNames() {
            List<String> all = Wallpapers.names(getContext());
            return all.size() > popupPop.length ? all.subList(0, popupPop.length) : all;
        }

        private int saverMinutes() {
            int ms = android.provider.Settings.System.getInt(getContext().getContentResolver(),
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 600_000);
            return Math.max(1, Math.round(ms / 60_000f));
        }

        private String minutes(int m) {
            if (m >= 60 && m % 60 == 0) return m / 60 + " h";
            return m + " min";
        }

        private String saverName() {
            return Ui.tr("Após ", "After ") + minutes(saverMinutes());
        }

        /** Screensaver: preview it, and how long the TV waits before it starts. */
        private void drawSaverScreen(Canvas c) {
            String[] labels = {Ui.tr("Ver agora", "Preview"), Ui.tr("Começar após", "Start after"), Ui.tr("Imagem", "Image")};
            String[] values = {"", minutes(saverMinutes()) + Ui.tr(" sem usar o controle", " without using the remote"),
                    Prefs.recsOn(getContext()) ? Ui.tr("Fotos das recomendações", "The recommendations' photos") : wallName(Prefs.WP_SAVER)};
            for (int i = 0; i < 3; i++) {
                float y = (LIST_TOP + 20 + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                if (i >= 1) valueAndChevron(c, values[i], base, f);
            }
            int sleep = android.provider.Settings.Secure.getInt(getContext().getContentResolver(), "sleep_timeout", -1);
            Paint hint = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            String note = sleep > 0 && sleep <= saverMinutes() * 60_000
                    ? Ui.tr("A TV desliga antes (Ajustes da TV → Energia): a tela de descanso não chega a aparecer.",
                            "The TV turns off first (TV Settings → Power): the screensaver won't show.")
                    : Ui.tr("Vale para a TV toda, como nos Ajustes da TV.", "Applies to the whole TV, like TV Settings.");
            c.drawText(note, LIST_X * k + 30 * k, (LIST_TOP + 20 + 3 * (ROW_H + ROW_GAP) + 40) * k, hint);
        }

        // MARK: Home screen: theme, background (recommendations or wallpaper), weather, sounds, Live TV

        private String backgroundName() {
            return Prefs.recsOn(getContext()) ? Ui.tr("Recomendações", "Recommendations") : Ui.tr("Papel de parede", "Wallpaper");
        }

        private void drawHomeScreen(Canvas c) {
            Context ctx = getContext();
            boolean recsOn = Prefs.recsOn(ctx);
            String[] labels = {Ui.tr("Tema", "Theme"), Ui.tr("Fundo", "Background"),
                    recsOn ? Ui.tr("Recomendações", "Recommendations") : Ui.tr("Papel de parede", "Wallpaper"),
                    Ui.tr("Previsão do tempo", "Weather"), Ui.tr("Sons ao navegar", "Navigation sounds"), Ui.tr("TV ao Vivo", "Live TV")};
            String[] values = {themes()[HomeView.theme(ctx)], backgroundName(),
                    recsOn ? (hasKey ? Prefs.genreNames(ctx) : Ui.tr("Sem chave do TMDB", "No TMDB key")) : Wallpapers.sourceName(ctx),
                    weatherName(), Prefs.sounds(ctx) ? Ui.tr("Sim", "On") : Ui.tr("Não", "Off"), liveName()};
            for (int i = 0; i < 6; i++) {
                float y = (LIST_TOP + 20 + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                valueAndChevron(c, values[i], base, f);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            c.drawText(Ui.tr("TV ao Vivo: o app do atalho de TV ao Vivo, na gaveta de baixo.",
                    "Live TV: the app of the Live TV shortcut, in the bottom drawer."),
                    LIST_X * k + 30 * k, (LIST_TOP + 20 + 6 * (ROW_H + ROW_GAP) + 40) * k, hint);
        }

        // MARK: Updates (GitHub releases)

        private Updater.Release release;          // the latest release, once checked
        private String updateStatus = "";         // what's going on, shown on the second row
        private boolean updateBusy;

        private String updateName() {
            if (release != null && Updater.newer(release.tag, Updater.currentVersion(getContext()))) {
                return Ui.tr("Nova: ", "New: ") + release.tag;
            }
            return Updater.currentVersion(getContext());
        }

        /** Looks for a newer release (on opening the screen and when asked). */
        private void checkUpdate() {
            if (updateBusy || !Updater.enabled()) return;
            updateBusy = true;
            updateStatus = Ui.tr("Procurando…", "Checking…");
            new Thread(() -> {
                String status;
                Updater.Release r = null;
                try {
                    r = Updater.latest();
                    status = r == null ? Ui.tr("Nenhuma versão publicada", "No release yet")
                            : Updater.newer(r.tag, Updater.currentVersion(getContext())) ? Ui.tr("Instalar ", "Install ") + r.tag
                            : Ui.tr("Você já tem a mais nova", "You're up to date");
                } catch (java.io.IOException e) {
                    status = Ui.tr("Sem conexão com o GitHub", "Couldn't reach GitHub");
                }
                final Updater.Release found = r;
                final String text = status;
                handler.post(() -> {
                    release = found;
                    updateStatus = text;
                    updateBusy = false;
                    invalidate();
                });
            }).start();
        }

        /** Downloads the newer release and hands it to Android's installer (it asks before installing). */
        private void installUpdate() {
            final Context c = getContext().getApplicationContext();
            if (updateBusy || release == null || !Updater.newer(release.tag, Updater.currentVersion(c))) {
                checkUpdate();
                return;
            }
            updateBusy = true;
            final Updater.Release r = release;
            new Thread(() -> {
                // Android asks once to let this app install apps: done quietly with the TV's own ADB when it can.
                if (!c.getPackageManager().canRequestPackageInstalls()) {
                    try (Adb adb = Adb.connect(c, 10_000, null)) {
                        adb.shell("appops set " + c.getPackageName() + " REQUEST_INSTALL_PACKAGES allow");
                    } catch (Exception ignored) {
                    }
                }
                if (!c.getPackageManager().canRequestPackageInstalls()) {
                    handler.post(() -> {
                        updateBusy = false;
                        updateStatus = Ui.tr("Permita instalar apps e tente de novo", "Allow installing apps and try again");
                        invalidate();
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    android.net.Uri.parse("package:" + c.getPackageName())));
                        } catch (RuntimeException e) {
                            activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                        }
                    });
                    return;
                }
                String status;
                try {
                    java.io.File apk = Updater.download(c, r, p -> handler.post(() -> {
                        updateStatus = Ui.tr("Baixando… ", "Downloading… ") + p + "%";
                        invalidate();
                    }));
                    handler.post(() -> {
                        updateStatus = Ui.tr("Instalando…", "Installing…");
                        invalidate();
                    });
                    Updater.install(c, apk);
                    status = Ui.tr("Confirme na tela de instalação", "Confirm on the install screen");
                } catch (java.io.IOException | RuntimeException e) {
                    status = Ui.tr("Não deu para atualizar: ", "Couldn't update: ") + e.getMessage();
                }
                final String text = status;
                handler.post(() -> {
                    updateStatus = text;
                    updateBusy = false;
                    invalidate();
                });
            }).start();
        }

        /** Updates: version, check/install, and "Send diagnostics" when this APK has a topic for it. */
        private int updateRows() {
            return 4;
        }

        private void drawUpdateScreen(Canvas c) {
            String[] labels = {Ui.tr("Versão instalada", "Installed version"),
                    release != null && Updater.newer(release.tag, Updater.currentVersion(getContext()))
                            ? Ui.tr("Atualizar agora", "Update now") : Ui.tr("Procurar atualização", "Check for updates"),
                    Ui.tr("Enviar diagnóstico", "Send diagnostics"), Ui.tr("Ajustes da TV", "TV Settings")};
            String[] values = {Updater.currentVersion(getContext()), updateStatus, Diagnostics.enabled() ? diagStatus : Ui.tr("Desligado neste APK", "Off in this APK"), ""};
            for (int i = 0; i < updateRows(); i++) {
                float y = (LIST_TOP + 20 + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                if (i >= 1) {
                    valueAndChevron(c, values[i], base, f);
                } else {
                    rowValue.setColor(blend(fg(0x8CFFFFFF), 0x8C1C1C1E, f));
                    c.drawText(values[i], rowRect.right - 30 * k, base, rowValue);
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            String note = Updater.enabled()
                    ? Ui.tr("Vem das releases do GitHub (" + Config.UPDATE_REPO + "). O Android pede para confirmar a instalação.",
                            "From the GitHub releases (" + Config.UPDATE_REPO + "). Android asks you to confirm the install.")
                    : Ui.tr("Atualizações desligadas neste APK.", "Updates are off in this APK.");
            c.drawText(TextUtils.ellipsize(note, new TextPaint(hint), (LIST_R - LIST_X - 30) * k, TextUtils.TruncateAt.END).toString(),
                    LIST_X * k + 30 * k, (LIST_TOP + 20 + updateRows() * (ROW_H + ROW_GAP) + 40) * k, hint);
        }

        private String diagStatus = "";
        private boolean diagBusy;

        /** Sends the TV's screensaver/sound state and the full log to the developer's Telegram (see Diagnostics). */
        private void sendDiagnostics() {
            if (diagBusy) return;
            diagBusy = true;
            diagStatus = Ui.tr("Enviando…", "Sending…");
            final Context c = getContext().getApplicationContext();
            new Thread(() -> {
                String status;
                try {
                    Diagnostics.send(c);
                    status = Ui.tr("Enviado", "Sent");
                } catch (java.io.IOException | RuntimeException e) {
                    status = Ui.tr("Não deu para enviar: ", "Couldn't send: ") + e.getMessage();
                }
                final String text = status;
                handler.post(() -> {
                    diagStatus = text;
                    diagBusy = false;
                    invalidate();
                });
            }).start();
        }

        private String weatherName() {
            Context c = getContext();
            if (!Prefs.showWeather(c)) return Ui.tr("Desligada", "Off");
            String city = HomeData.get(c).city;
            return city.isEmpty() ? Ui.tr("Ligada", "On") : city;
        }

        /** Weather: show it or not, where it's for (the internet connection's location or a typed city). */
        private void drawWeatherScreen(Canvas c) {
            Context ctx = getContext();
            String[] labels = {Ui.tr("Mostrar previsão", "Show weather"), Ui.tr("Localização", "Location"),
                    Ui.tr("Cidade", "City"), Ui.tr("Posição no topo", "Place at the top")};
            String city = Prefs.weatherCityName(ctx);
            String[] values = {Prefs.showWeather(ctx) ? Ui.tr("Sim", "On") : Ui.tr("Não", "Off"),
                    Prefs.manualWeather(ctx) ? Ui.tr("Cidade escolhida", "Chosen city") : Ui.tr("Automática (internet)", "Automatic (internet)"),
                    city.isEmpty() ? Ui.tr("Nenhuma", "None") : city, wxPosNames()[Prefs.weatherRight(ctx) ? 1 : 0]};
            for (int i = 0; i < 4; i++) {
                float y = (LIST_TOP + 20 + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                valueAndChevron(c, values[i], base, f);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            String now = HomeData.get(ctx).city;
            c.drawText(Ui.tr("Agora: ", "Now: ") + (now.isEmpty() ? "—" : now) + Ui.tr(" · o pôr do sol daqui troca o tema", " · its sunset switches the theme"),
                    LIST_X * k + 30 * k, (LIST_TOP + 20 + 4 * (ROW_H + ROW_GAP) + 40) * k, hint);
        }

        private String[] wxPosNames() {
            return new String[] {Ui.tr("No centro", "In the middle"), Ui.tr("Ao lado do relógio", "Next to the clock")};
        }

        /** Saves the wait with the TV's own ADB (this app may not write system settings itself). */
        private void setSaverMinutes(int m) {
            final Context app = getContext().getApplicationContext();
            new Thread(() -> {
                try (Adb adb = Adb.connect(app, 10_000, null)) {
                    adb.shell("settings put system screen_off_timeout " + m * 60_000);
                } catch (Exception e) {
                    AppLog.w("screensaver wait: " + e.getMessage());
                }
                handler.post(this::invalidate);
            }).start();
        }

        /** Theme: a list like Recommendations, with a check on the current one. */
        private void drawThemeScreen(Canvas c) {
            String[] names = themes();
            String[] notes = {Ui.tr("Segue o pôr do sol", "Follows the sunset"),
                    Ui.tr("Sempre claro", "Always light"), Ui.tr("Sempre escuro", "Always dark")};
            int current = HomeView.theme(getContext());
            for (int i = 0; i < 3; i++) {
                float y = (LIST_TOP + 20 + i * (ROW_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(names[i], rowRect.left + 30 * k, base, rowLabel);
                rowValue.setColor(blend(fg(0x8CFFFFFF), 0x8C1C1C1E, f));
                float cw = Symbols.width(getContext(), "check", 24 * k);
                c.drawText(notes[i], rowRect.right - 30 * k - cw - 24 * k, base, rowValue);
                if (i == current) {
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 24 * k, blend(fg(0xFFFFFFFF), INK, f));
                }
            }
        }

        /** "Live TV" choices: automatic, the TV's tuner, each HDMI input, then the installed apps. */
        private List<String[]> liveChoices() {
            Context c = getContext();
            List<String[]> out = new ArrayList<>();
            out.add(new String[] {"auto", Ui.tr("Automático (TV a cabo)", "Automatic (cable box)")});
            out.add(new String[] {"tuner", Ui.tr("Antena / cabo coaxial", "Antenna / coax cable")});
            for (HomeApps.Item in : HomeApps.inputs(c, true)) out.add(new String[] {"input:" + in.inputId, in.label});
            for (HomeApps.Item app : HomeApps.allApps(c)) out.add(new String[] {"app:" + app.pkg, app.label});
            return out;
        }

        private String liveName() {
            String v = Prefs.liveTv(getContext());
            if (v.equals("auto")) return Ui.tr("Automático", "Automatic");
            if (v.equals("tuner")) return Ui.tr("Antena / cabo", "Antenna / cable");
            for (String[] ch : liveChoices) if (ch[0].equals(v)) return ch[1];
            if (v.startsWith("app:")) {
                try {
                    android.content.pm.PackageManager pm = getContext().getPackageManager();
                    return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(v.substring(4), 0)));
                } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                }
            }
            return v.startsWith("input:") ? "HDMI" : v;
        }

        /** The value on the right of a row, then the chevron that says it opens more (Apple TV's ">"). */
        private void valueAndChevron(Canvas c, String value, float base, float f) {
            float right = rowRect.right - 30 * k;
            float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
            Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, blend(fg(0x66FFFFFF), 0x991C1C1E, f));
            if (value.isEmpty()) return;
            rowValue.setColor(blend(fg(0x8CFFFFFF), 0x8C1C1C1E, f));
            float room = rowRect.width() * 0.55f;
            c.drawText(TextUtils.ellipsize(value, new TextPaint(rowValue), room, TextUtils.TruncateAt.END).toString(),
                    right - cw - 14 * k, base, rowValue);
        }

        private String popupNote() {
            if (popup != YES_NO_POPUP) return "";
            if (yesNoKey.equals("recs_all")) {
                List<String> mine = new ArrayList<>();
                for (String name : Recs.knownServices(getContext(), true)) mine.add(name);
                return Ui.tr("Sim: o que está em alta em qualquer streaming do Brasil, mesmo sem o app na TV.\n"
                        + "Não: só títulos dos streamings instalados nesta TV ("
                        + (mine.isEmpty() ? "nenhum agora" : String.join(", ", mine)) + "). O app reconhece "
                        + String.join(", ", Recs.knownServices(getContext(), false)) + ".",
                        "On: what's trending on any streaming service, even without its app on the TV.\n"
                        + "Off: only titles on the streaming apps installed on this TV ("
                        + (mine.isEmpty() ? "none now" : String.join(", ", mine)) + "). Known apps: "
                        + String.join(", ", Recs.knownServices(getContext(), false)) + ".");
            }
            if (yesNoKey.equals("weather")) {
                return Ui.tr("Sim: o tempo no topo do Início, na gaveta e na tela de descanso.\n"
                        + "Não: some de todos esses lugares.",
                        "On: the weather on top of the home screen, in the drawer and on the screensaver.\n"
                        + "Off: it's gone from all of them.");
            }
            if (yesNoKey.equals("recs_on")) {
                return Ui.tr("Sim: os cards com o que está em alta, e as fotos deles no fundo e na tela de descanso.\n"
                        + "Não: sem cards; o fundo e a tela de descanso mostram os papéis de parede.",
                        "On: the cards with what's trending, and their photos behind them and on the screensaver.\n"
                        + "Off: no cards; the background and the screensaver show the wallpapers.");
            }
            if (yesNoKey.equals("recs_recent")) {
                return Ui.tr("Sim: só filmes dos últimos 4 meses e séries que estrearam nos últimos 6.\n"
                        + "Não: tudo o que está em alta, incluindo títulos antigos e temporadas novas.",
                        "On: only movies from the last 4 months and shows that premiered in the last 6.\n"
                        + "Off: everything trending, older titles and new seasons too.");
            }
            return Ui.tr("Os cliques suaves ao mover o foco e ao abrir, no Início e nos Ajustes.",
                    "The soft clicks when moving the focus and opening things, on the home screen and in Settings.");
        }

        private String popupTitle() {
            if (popup == LIVE_POPUP) return Ui.tr("TV ao Vivo abre", "Live TV opens");
            if (popup == SAVER_POPUP) return Ui.tr("Começar após", "Start after");
            if (popup == YES_NO_POPUP) return yesNoTitle;
            if (popup == PLACE_POPUP) return Ui.tr("Localização", "Location");
            if (popup == WP_MODE_POPUP) return wpSlot == Prefs.WP_SAVER ? Ui.tr("Tela de descanso", "Screensaver")
                    : wpSlot == Prefs.WP_DARK ? Ui.tr("Início no modo escuro", "Home in dark mode") : Ui.tr("Início no modo claro", "Home in light mode");
            if (popup == WP_PICK_POPUP) return Ui.tr("Imagem fixa", "Fixed image");
            if (popup == WP_ROTATE_POPUP) return Ui.tr("Imagens na troca", "Images that rotate");
            if (popup == WP_SOURCE_POPUP) return Ui.tr("Fonte das imagens", "Image source");
            if (popup == BG_POPUP) return Ui.tr("Fundo", "Background");
            if (popup == WX_POS_POPUP) return Ui.tr("Posição no topo", "Place at the top");
            return popup == THEME_POPUP ? Ui.tr("Tema", "Theme") : popup == GENRE_POPUP ? Ui.tr("Categorias", "Genres")
                    : Ui.tr("Ao apertar OK no card", "OK on a card");
        }

        private String[] popupOptions() {
            if (popup == THEME_POPUP) return themes();
            if (popup == ACTION_POPUP) return actions();
            if (popup == YES_NO_POPUP) return new String[] {Ui.tr("Sim", "On"), Ui.tr("Não", "Off")};
            if (popup == PLACE_POPUP) return new String[] {Ui.tr("Automática (pela internet)", "Automatic (internet)"),
                    Ui.tr("Cidade escolhida", "Chosen city")};
            if (popup == WP_MODE_POPUP) return wpModeOptions();
            if (popup == WP_SOURCE_POPUP) return wpSources();
            if (popup == BG_POPUP) return new String[] {Ui.tr("Recomendações", "Recommendations"), Ui.tr("Papel de parede", "Wallpaper")};
            if (popup == WX_POS_POPUP) return wxPosNames();
            if (popup == WP_PICK_POPUP || popup == WP_ROTATE_POPUP) {
                List<String> names = wpNames();
                String[] out = new String[names.size()];
                for (int i = 0; i < out.length; i++) out[i] = Wallpapers.label(getContext(), names.get(i));
                return out;
            }
            if (popup == SAVER_POPUP) {
                String[] out = new String[SAVER_MINUTES.length];
                for (int i = 0; i < out.length; i++) out[i] = minutes(SAVER_MINUTES[i]);
                return out;
            }
            if (popup == LIVE_POPUP) {
                String[] names = new String[liveChoices.size()];
                for (int i = 0; i < names.length; i++) names[i] = liveChoices.get(i)[1];
                return names;
            }
            String[] out = new String[Prefs.GENRES.length];
            for (int i = 0; i < out.length; i++) out[i] = Ui.en ? Prefs.GENRES[i][2] : Prefs.GENRES[i][1];
            return out;
        }

        private boolean popupChecked(int i) {
            if (popup == THEME_POPUP) return HomeView.theme(getContext()) == i;
            if (popup == ACTION_POPUP) return actionIndex() == i;
            if (popup == SAVER_POPUP) return SAVER_MINUTES[i] == saverMinutes();
            if (popup == YES_NO_POPUP) return yesNo() == (i == 0);
            if (popup == PLACE_POPUP) return Prefs.manualWeather(getContext()) == (i == 1);
            if (popup == LIVE_POPUP) return liveChoices.get(i)[0].equals(Prefs.liveTv(getContext()));
            if (popup == WP_MODE_POPUP) {
                String v = Prefs.wallpaper(getContext(), wpSlot), mine = wpModeValue(i);
                return mine.equals("fixed:") ? v.startsWith("fixed:") : v.equals(mine);
            }
            if (popup == WP_PICK_POPUP) return Prefs.wallpaper(getContext(), wpSlot).equals("fixed:" + wpNames().get(i));
            if (popup == WP_SOURCE_POPUP) return wpSourceIndex() == i;
            if (popup == BG_POPUP) return Prefs.recsOn(getContext()) == (i == 0);
            if (popup == WX_POS_POPUP) return Prefs.weatherRight(getContext()) == (i == 1);
            if (popup == WP_ROTATE_POPUP) return !Prefs.wallpaperSkip(getContext()).contains(wpNames().get(i));
            return Prefs.genres(getContext()).contains(Prefs.GENRES[i][0]);
        }

        /** A panel in the middle of the screen over a darkened list; the focused option in white. */
        private void drawPopup(Canvas c, float dt) {
            if (popup == WP_PICK_POPUP || popup == WP_ROTATE_POPUP) {
                drawWpPicker(c, dt);
                return;
            }
            String[] options = popupOptions();
            boolean grid = popup == GENRE_POPUP || popup == LIVE_POPUP || popup == WP_PICK_POPUP;   // long lists in two columns
            int cols = grid ? 2 : 1, rows = (options.length + cols - 1) / cols;
            float rowH = rows > 9 ? 58 : 70, gap = 8, colW = grid ? (popup == LIVE_POPUP ? 470 : 400) : 640, pad = 36, head = 96,
                    foot = popup == GENRE_POPUP ? 56 : 24;
            // An explanation under the options (the Yes/No choices say what each one does).
            String note = popupNote();
            StaticLayout noteLayout = null;
            if (!note.isEmpty()) {
                TextPaint np = new TextPaint(Ui.text(Ui.regular, 23 * k, fg(0xA6FFFFFF)));
                int width = Math.round((cols * colW + (cols - 1) * 16 - 20) * k);
                noteLayout = StaticLayout.Builder.obtain(note, 0, note.length(), np, width).setLineSpacing(0, 1.12f).build();
                foot += noteLayout.getHeight() / k + 20;
            }
            float pw = cols * colW + (cols - 1) * 16 + 2 * pad, ph = head + rows * (rowH + gap) + foot;
            float left = (1920 - pw) / 2 * k, top = (1080 - ph) / 2 * k;
            Paint dim = new Paint();
            dim.setColor(0x99000000);
            c.drawRect(0, 0, w, h, dim);
            tmp.set(left, top, left + pw * k, top + ph * k);
            shadow.setColor(0x80000000);
            c.drawRoundRect(tmp, 32 * k, 32 * k, shadow);
            Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
            panel.setColor(light ? 0xFAE6E6EB : 0xF22C2C2E);
            c.drawRoundRect(tmp, 32 * k, 32 * k, panel);
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, fg(0xFFFFFFFF));
            c.drawText(popupTitle(), left + pad * k + 10 * k, top + 62 * k, heading);
            for (int i = 0; i < options.length; i++) {
                popupPop[i].target = i == popupFocus ? 1 : 0;
                popupPop[i].step(dt);
                int col = i % cols, row = i / cols;
                float x = left + (pad + col * (colW + 16)) * k, y = top + (head + row * (rowH + gap)) * k;
                rowRect.set(x, y, x + colW * k, y + rowH * k);
                float f = Ui.clamp(popupPop[i].value);
                if (f > 0.01f) {
                    white.setAlpha(Math.round(255 * f));
                    c.drawRoundRect(rowRect, 16 * k, 16 * k, white);
                }
                rowLabel.setColor(blend(fg(0xF2FFFFFF), INK, f));
                c.drawText(options[i], rowRect.left + 26 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (popupChecked(i)) {
                    float cw = Symbols.width(getContext(), "check", 24 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 26 * k - cw / 2, rowRect.centerY(), 24 * k, blend(fg(0xFFFFFFFF), INK, f));
                }
            }
            if (noteLayout != null) {
                c.save();
                c.translate(left + pad * k + 10 * k, top + (head + rows * (rowH + gap) + 12) * k);
                noteLayout.draw(c);
                c.restore();
            }
            if (popup == GENRE_POPUP) {
                Paint hint = Ui.text(Ui.regular, 22 * k, fg(0x8CFFFFFF));
                c.drawText(Ui.tr("OK marca ou desmarca · Voltar fecha", "OK ticks or unticks · Back closes"),
                        left + pad * k + 10 * k, top + (ph - 22) * k, hint);
            }
        }

        private boolean yesNo() {
            Context c = getContext();
            if (yesNoKey.equals("sounds")) return Prefs.sounds(c);
            if (yesNoKey.equals("weather")) return Prefs.showWeather(c);
            if (yesNoKey.equals("recs_recent")) return Prefs.recent(c);
            if (yesNoKey.equals("recs_on")) return Prefs.recsOn(c);
            return Prefs.anyService(c);
        }

        private void openYesNo(String key, String title) {
            yesNoKey = key;
            yesNoTitle = title;
            openPopup(YES_NO_POPUP);
        }

        private void openPopup(int kind) {
            popup = kind;
            popupFocus = kind == THEME_POPUP ? HomeView.theme(getContext()) : kind == ACTION_POPUP ? actionIndex() : 0;
            if (kind == LIVE_POPUP || kind == SAVER_POPUP || kind == YES_NO_POPUP || kind == PLACE_POPUP || kind == WP_MODE_POPUP
                    || kind == WP_PICK_POPUP || kind == WP_SOURCE_POPUP || kind == BG_POPUP || kind == WX_POS_POPUP) {
                for (int i = 0; i < popupOptions().length; i++) if (popupChecked(i)) popupFocus = i;
            }
            for (Spring s : popupPop) s.snap(0);
        }

        private void drawChoice(Canvas c) {
            String[][] options = {{"Free My TV", Ui.tr("Sem anúncios, com a tela de descanso nova", "No ads, with the new screensaver")},
                    {"Original", Ui.tr("O Início de fábrica da TV", "The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(fg(0xF2FFFFFF), INK, f));
                rowSub.setColor(blend(fg(0x8CFFFFFF), 0x991C1C1E, f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(fg(0xFFFFFFFF), INK, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, fg(0x8CFFFFFF));
            c.drawText(Ui.tr("A troca leva uns segundos e a tela pisca nas medições.",
                    "Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String versionName(Context c) {
            try {
                return "Free My TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Free My TV";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            if (popup != NO_POPUP) return popupKey(e, down);
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) {
                        focus--;
                        click.move();
                    }
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) {
                        focus++;
                        click.move();
                    }
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) {
                            click.open();
                            activate();
                        }
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            goBack();
                        }
                        backDown = false;
                    }
                    invalidate();
                    return true;
                default:
                    return false;
            }
        }

        private boolean popupKey(KeyEvent e, boolean down) {
            int n = popupOptions().length, cols = popup == WP_PICK_POPUP || popup == WP_ROTATE_POPUP ? WP_COLS : popup == GENRE_POPUP || popup == LIVE_POPUP ? 2 : 1;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && popupFocus - cols >= 0) {
                        popupFocus -= cols;
                        click.move();
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && popupFocus + cols < n) {
                        popupFocus += cols;
                        click.move();
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down && cols > 1 && popupFocus % cols > 0) popupFocus--;
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && cols > 1 && popupFocus % cols < cols - 1 && popupFocus + 1 < n) popupFocus++;
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else if (okDown && !e.isCanceled()) {
                        okDown = false;
                        click.open();
                        Context c = getContext();
                        if (popup == THEME_POPUP) {
                            HomeView.setTheme(c, popupFocus);
                            popup = NO_POPUP;
                        } else if (popup == ACTION_POPUP) {
                            Prefs.setCardAction(c, ACTION_KEYS[popupFocus]);
                            popup = NO_POPUP;
                        } else if (popup == YES_NO_POPUP) {
                            Prefs.set(c, yesNoKey, popupFocus == 0);
                            popup = NO_POPUP;
                        } else if (popup == PLACE_POPUP) {
                            popup = NO_POPUP;
                            if (popupFocus == 0) {
                                Prefs.set(c, "weather_manual", false);
                                HomeData.get(c).relocate();
                            } else if (Prefs.weatherCity(c) == null) {
                                activity.askCity();   // no city yet: ask for one
                            } else {
                                Prefs.set(c, "weather_manual", true);
                                HomeData.get(c).relocate();
                            }
                        } else if (popup == SAVER_POPUP) {
                            setSaverMinutes(SAVER_MINUTES[popupFocus]);
                            popup = NO_POPUP;
                        } else if (popup == LIVE_POPUP) {
                            Prefs.setLiveTv(c, liveChoices.get(popupFocus)[0]);
                            popup = NO_POPUP;
                        } else if (popup == WP_MODE_POPUP) {
                            String v = wpModeValue(popupFocus);
                            if (v.equals("fixed:")) {
                                if (wpNames().isEmpty()) {
                                    popup = NO_POPUP;
                                    syncWallpapers();
                                } else {
                                    openPopup(WP_PICK_POPUP);
                                    loadWpThumbs();
                                }
                            } else {
                                Prefs.setWallpaper(c, wpSlot, v);
                                popup = NO_POPUP;
                            }
                        } else if (popup == WP_SOURCE_POPUP) {
                            popup = NO_POPUP;
                            if (popupFocus == 0) setWallpaperSource("bing");
                            else if (popupFocus == 1) setWallpaperSource("wallhaven:");
                            else if (popupFocus == 2) activity.askWallhaven();
                            else setWallpaperSource("github");
                        } else if (popup == BG_POPUP) {
                            Prefs.set(c, "recs_on", popupFocus == 0);
                            popup = NO_POPUP;
                            if (popupFocus == 1 && Wallpapers.names(c).isEmpty()) syncWallpapers();
                        } else if (popup == WX_POS_POPUP) {
                            Prefs.set(c, "weather_right", popupFocus == 1);
                            popup = NO_POPUP;
                        } else if (popup == WP_ROTATE_POPUP) {
                            Prefs.toggleWallpaperSkip(c, wpNames().get(popupFocus));     // stays open: tick several
                        } else if (popup == WP_PICK_POPUP) {
                            Prefs.setWallpaper(c, wpSlot, "fixed:" + wpNames().get(popupFocus));
                            popup = NO_POPUP;
                        } else {
                            Prefs.toggleGenre(c, Prefs.GENRES[popupFocus][0]);
                        }
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) popup = NO_POPUP;
                        backDown = false;
                    }
                    break;
                default:
                    return false;
            }
            invalidate();
            return true;
        }

        /** One level up: Home screen's submenus go back to it, the rest to the main list (on the row they came from). */
        private void goBack() {
            if (screen == THEME || screen == RECS || screen == WALL || screen == WEATHER) {
                focus = screen == THEME ? 0 : screen == WEATHER ? 3 : 2;
                screen = HOMESCR;
            } else {
                focus = screen == HOMESCR ? 1 : screen == SAVER ? 2 : screen == UPDATE ? 3 : 0;
                screen = MAIN;
            }
            for (Spring s : pop) s.snap(0);
        }

        private void open(int to, int at) {
            screen = to;
            focus = at;
            for (Spring s : pop) s.snap(0);
        }

        private void activate() {
            Context c = getContext();
            if (screen == THEME) {
                HomeView.setTheme(c, focus);   // the home screen follows when it shows again
                if (wantLight() != light) {      // this screen follows right away
                    light = !light;
                    initPaints();
                }
                goBack();
            } else if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                if (wantFree != freeActive() && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                goBack();
            } else if (screen == UPDATE) {
                if (focus == 1) installUpdate();
                else if (focus == 2 && Diagnostics.enabled()) sendDiagnostics();
                else if (focus == 3) activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (screen == WEATHER) {
                if (focus == 0) openYesNo("weather", Ui.tr("Mostrar previsão", "Show weather"));
                else if (focus == 1) openPopup(PLACE_POPUP);
                else if (focus == 2) activity.askCity();
                else openPopup(WX_POS_POPUP);
            } else if (screen == SAVER) {
                if (focus == 0) {
                    pause();
                    activity.showPreview();
                } else if (focus == 1) {
                    openPopup(SAVER_POPUP);
                } else {
                    wpSlot = Prefs.WP_SAVER;
                    if (Wallpapers.names(c).isEmpty()) syncWallpapers();
                    openPopup(WP_MODE_POPUP);
                }
            } else if (screen == WALL) {
                if (focus == 0) {
                    openPopup(WP_SOURCE_POPUP);
                    Wallpapers.checkGithub(c, false, () -> handler.post(this::invalidate));
                } else if (focus == 1) {
                    if (Wallpapers.names(c).isEmpty()) {
                        syncWallpapers();
                    } else {
                        openPopup(WP_ROTATE_POPUP);
                        loadWpThumbs();
                    }
                } else {
                    wpSlot = focus == 2 ? Prefs.WP_LIGHT : Prefs.WP_DARK;
                    openPopup(WP_MODE_POPUP);
                }
            } else if (screen == RECS) {
                int row = recsRows()[Math.min(focus, recsRows().length - 1)];
                if (row == R_KEY) activity.askTmdbKey();
                else if (row == R_GENRES) openPopup(GENRE_POPUP);
                else if (row == R_RECENT) openYesNo("recs_recent", Ui.tr("Só lançamentos", "New releases only"));
                else if (row == R_ANY) openYesNo("recs_all", Ui.tr("Em qualquer streaming", "On any streaming service"));
                else openPopup(ACTION_POPUP);
            } else if (screen == HOMESCR) {
                if (focus == 0) open(THEME, HomeView.theme(c));
                else if (focus == 1) openPopup(BG_POPUP);
                else if (focus == 2) {
                    if (Prefs.recsOn(c)) {
                        open(RECS, 0);
                    } else {
                        open(WALL, 0);
                        Wallpapers.checkGithub(c, false, () -> handler.post(this::invalidate));
                        if (Wallpapers.names(c).isEmpty()) syncWallpapers();
                    }
                } else if (focus == 3) open(WEATHER, 0);
                else if (focus == 4) openYesNo("sounds", Ui.tr("Sons ao navegar", "Navigation sounds"));
                else {
                    liveChoices = liveChoices();
                    openPopup(LIVE_POPUP);
                }
            } else if (focus == 0) {
                if (busy()) return;
                open(HOME_CHOICE, freeActive() ? 0 : 1);
            } else if (focus == 1) {
                open(HOMESCR, 0);
            } else if (focus == 2) {
                open(SAVER, 0);
            } else {
                open(UPDATE, 1);
                checkUpdate();
            }
            invalidate();
        }
    }
}
