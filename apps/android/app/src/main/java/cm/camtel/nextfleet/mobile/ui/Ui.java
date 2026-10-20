package cm.camtel.nextfleet.mobile.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import cm.camtel.nextfleet.mobile.NexFleetApp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Boîte à outils d'interface NexFleet (Android, sans bibliothèque tierce) : jetons de couleur et typographie
 * Apple HIG (échelle « Small »), cartes, boutons plein / teinté / gris de 50 dp, pastilles, marges des barres système
 * (affichage bord à bord), exécution en arrière-plan.
 */
public final class Ui {

    public static final int NAVY = 0xFF102631, BRAND = 0xFF009B87, ACCENT = 0xFF008575, ACCENT_TEXT = 0xFF007A6A;
    public static final int BG = 0xFFF2F4F7, SURFACE = 0xFFFFFFFF, TEXT = 0xFF102434, MUTED = 0xFF616E78, TERTIARY = 0xFF9AA3AB;
    public static final int FILL = 0x1F767680, SEPARATOR = 0x1F102434, RED = 0xFFE9152D, ORANGE = 0xFFC55300, GREEN = 0xFF008932;
    public static final int BLUE = 0xFF1E6EF4;

    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() { }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** Travail bloquant hors du fil d'interface, résultat ou erreur rendus sur le fil d'interface. */
    public static <T> void async(Supplier<T> work, Consumer<T> ok, Consumer<RuntimeException> err) {
        IO.execute(() -> {
            try {
                T v = work.get();
                MAIN.post(() -> ok.accept(v));
            } catch (RuntimeException e) {
                MAIN.post(() -> err.accept(e));
            }
        });
    }

    // ------------------------------------------------------------------ texte (échelle Dynamic Type « Small »)

    public static TextView text(Context c, String s, float sizeSp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    public static TextView largeTitle(Context c, String s) {
        return text(c, s, 32, TEXT, NexFleetApp.get().display());
    }

    public static TextView title(Context c, String s) {
        return text(c, s, 22, TEXT, NexFleetApp.get().display());
    }

    public static TextView headline(Context c, String s) {
        return text(c, s, 16, TEXT, NexFleetApp.get().semibold());
    }

    public static TextView body(Context c, String s) {
        return text(c, s, 15, TEXT, NexFleetApp.get().regular());
    }

    public static TextView muted(Context c, String s, float size) {
        return text(c, s, size, MUTED, NexFleetApp.get().regular());
    }

    public static TextView overline(Context c, String s, int color) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.FRANCE), 12, color, NexFleetApp.get().semibold());
        t.setLetterSpacing(0.04f);
        return t;
    }

    public static TextView pill(Context c, String s, int fg) {
        TextView t = text(c, s, 12, fg, NexFleetApp.get().semibold());
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        t.setBackground(rounded(c, (fg & 0x00FFFFFF) | 0x1F000000, 6));
        return t;
    }

    // ------------------------------------------------------------------ surfaces

    public static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = column(c, 8);
        GradientDrawable g = rounded(c, SURFACE, 16);
        g.setStroke(1, 0x14102434);
        l.setBackground(g);
        l.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16));
        l.setElevation(dp(c, 1));
        return l;
    }

    public static LinearLayout column(Context c, float gapDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        GradientDrawable spacer = new GradientDrawable();
        spacer.setSize(1, dp(c, gapDp));
        spacer.setColor(Color.TRANSPARENT);
        l.setDividerDrawable(spacer);
        return l;
    }

    public static LinearLayout row(Context c, float gapDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        GradientDrawable spacer = new GradientDrawable();
        spacer.setSize(dp(c, gapDp), 1);
        spacer.setColor(Color.TRANSPARENT);
        l.setDividerDrawable(spacer);
        return l;
    }

    public static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    // ------------------------------------------------------------------ boutons (HIG : un seul bouton plein par écran)

    public enum Style { FILLED, TINTED, GRAY, PLAIN, ON_DARK, GLASS }

    public static Button button(Context c, String label, Style style, View.OnClickListener onClick) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(NexFleetApp.get().semibold());
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 50));
        b.setMinimumHeight(dp(c, 50));
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        int bg, fg;
        switch (style) {
            case FILLED: bg = ACCENT; fg = Color.WHITE; break;
            case TINTED: bg = 0x1F009B87; fg = ACCENT_TEXT; break;
            case GRAY: bg = FILL; fg = TEXT; break;
            case ON_DARK: bg = Color.WHITE; fg = NAVY; break;
            case GLASS: bg = 0x2EFFFFFF; fg = Color.WHITE; break;
            default: bg = Color.TRANSPARENT; fg = ACCENT_TEXT;
        }
        b.setTextColor(fg);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), rounded(c, bg, 14), rounded(c, Color.BLACK, 14)));
        b.setOnClickListener(onClick);
        return b;
    }

    // ------------------------------------------------------------------ bord à bord (Android 15 et plus)

    /** Ajoute aux marges intérieures de la vue la taille des barres système et du clavier. */
    public static void applyInsets(View root, boolean top, boolean bottom) {
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int it, ib, il, ir;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets s = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()
                        | WindowInsets.Type.displayCutout());
                it = s.top; ib = s.bottom; il = s.left; ir = s.right;
            } else {
                it = legacyTop(insets); ib = legacyBottom(insets); il = legacyLeft(insets); ir = legacyRight(insets);
            }
            v.setPadding(l + il, t + (top ? it : 0), r + ir, b + (bottom ? ib : 0));
            return insets;
        });
        root.requestApplyInsets();
    }

    @SuppressWarnings("deprecation")
    private static int legacyTop(WindowInsets i) {
        return i.getSystemWindowInsetTop();
    }

    @SuppressWarnings("deprecation")
    private static int legacyBottom(WindowInsets i) {
        return i.getSystemWindowInsetBottom();
    }

    @SuppressWarnings("deprecation")
    private static int legacyLeft(WindowInsets i) {
        return i.getSystemWindowInsetLeft();
    }

    @SuppressWarnings("deprecation")
    private static int legacyRight(WindowInsets i) {
        return i.getSystemWindowInsetRight();
    }

    // ------------------------------------------------------------------ alertes (HIG : Annuler à gauche, action à droite)

    public static void alert(Activity a, String title, String message, String cancel, String action, Runnable onAction) {
        AlertDialog.Builder b = new AlertDialog.Builder(a, cm.camtel.nextfleet.mobile.R.style.Theme_NexFleet_Dialog)
                .setTitle(title).setMessage(message);
        if (cancel != null) b.setNegativeButton(cancel, null);
        b.setPositiveButton(action, (d, w) -> {
            if (onAction != null) onAction.run();
        });
        b.show();
    }

    public static ViewGroup.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
