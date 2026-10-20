package cm.camtel.nextfleet.mobile.ui;

import android.app.Activity;
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
 * Boîte à outils d'interface NexFleet (Android, sans bibliothèque tierce), design v2 « Liquid Glass teinté » d'iOS 26
 * (docs/RECHERCHE_DESIGN_IOS26.md) : échelle typographique iOS par défaut (34/22/20/17/15/13), contenu sur surfaces
 * opaques à grands rayons, verre teinté réservé à la navigation (barre d'onglets flottante, panneaux sur la carte),
 * capsules pour les contrôles, rayons concentriques, alertes iOS 26. « Réduire la transparence » rend tout opaque.
 */
public final class Ui {

    public static final int NAVY = 0xFF102631, BRAND = 0xFF009B87, ACCENT = 0xFF008575, ACCENT_TEXT = 0xFF007A6A;
    public static final int BG = 0xFFF2F4F7, SURFACE = 0xFFFFFFFF, TEXT = 0xFF102434, MUTED = 0xFF616E78, TERTIARY = 0xFF9AA3AB;
    public static final int FILL = 0x1F767680, SEPARATOR = 0x1F102434, RED = 0xFFE9152D, ORANGE = 0xFFC55300, GREEN = 0xFF008932;
    public static final int BLUE = 0xFF1E6EF4;
    /** Sélection teintée (menthe à 13 %) et ombre des panneaux de verre. */
    public static final int SELECT = 0x21008575;
    /** Texte secondaire et accent sur verre : contraste ≥ 4,5:1 au pire cas (verre à 90 % sur fond noir). */
    public static final int GLASS_MUTED = 0xFF525D66, GLASS_ACCENT = 0xFF00695B;
    /** Espace réservé sous le contenu pour la barre d'onglets flottante. */
    public static final float TAB_CLEARANCE = 112;

    private static volatile boolean opaque;

    /** Réglage « Réduire la transparence » (équivalent du réglage d'accessibilité d'Apple). */
    public static boolean opaque() {
        return opaque;
    }

    public static void setOpaque(Context c, boolean value) {
        opaque = value;
        c.getSharedPreferences("nexfleet.ui", Context.MODE_PRIVATE).edit().putBoolean("opaque", value).apply();
    }

    public static void loadPreferences(Context c) {
        opaque = c.getSharedPreferences("nexfleet.ui", Context.MODE_PRIVATE).getBoolean("opaque", false);
    }

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
        TextView t = text(c, s, 34, TEXT, NexFleetApp.get().display());
        t.setLetterSpacing(-0.01f);
        return t;
    }

    public static TextView title(Context c, String s) {
        return text(c, s, 22, TEXT, NexFleetApp.get().display());
    }

    public static TextView title3(Context c, String s) {
        return text(c, s, 20, TEXT, NexFleetApp.get().semibold());
    }

    public static TextView headline(Context c, String s) {
        return text(c, s, 17, TEXT, NexFleetApp.get().semibold());
    }

    public static TextView body(Context c, String s) {
        return text(c, s, 16, TEXT, NexFleetApp.get().regular());
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
        TextView t = text(c, s, 13, fg, NexFleetApp.get().semibold());
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        t.setBackground(rounded(c, (fg & 0x00FFFFFF) | 0x1F000000, 999));
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
        GradientDrawable g = rounded(c, SURFACE, 22);
        g.setStroke(1, 0x12102434);
        l.setBackground(g);
        l.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16));
        l.setElevation(dp(c, 1.5f));
        return l;
    }

    /** Fond doux des écrans (dégradé très léger, comme les fonds d'iOS 26) : le verre se lit dessus. */
    public static GradientDrawable backdrop() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFE9F0F3, 0xFFF3F5F8, 0xFFEEF5F2});
        return g;
    }

    /**
     * Verre teinté : blanc à 95→90 % (lisible même sur fond noir, voir tools/check_contrast.py), arête de 1 px, ombre portée par l'élévation. Opaque si « Réduire la
     * transparence ». Réservé à la couche de navigation (barres, panneaux sur la carte), jamais au contenu.
     */
    public static GradientDrawable glass(Context c, float radiusDp) {
        GradientDrawable g = opaque ? new GradientDrawable() :
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF2FFFFFF, 0xE6FFFFFF});
        if (opaque) g.setColor(SURFACE);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(Math.max(1, dp(c, 0.7f)), 0x1A102434);
        return g;
    }

    /** Groupe de lignes iOS (« inset grouped ») : carte de rayon 22, séparateurs alignés sur le texte. */
    public static LinearLayout group(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = rounded(c, SURFACE, 22);
        g.setStroke(1, 0x12102434);
        l.setBackground(g);
        l.setClipToOutline(true);
        l.setElevation(dp(c, 1));
        return l;
    }

    /** Ligne de groupe : pastille d'icône, titre, valeur, chevron facultatif ; cible ≥ 52 dp. */
    public static LinearLayout groupRow(Context c, int iconRes, int tile, String title, String value, View.OnClickListener onClick) {
        LinearLayout r = row(c, 12);
        r.setMinimumHeight(dp(c, 52));
        r.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        if (iconRes != 0) {
            android.widget.ImageView ic = new android.widget.ImageView(c);
            ic.setImageResource(iconRes);
            ic.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            ic.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
            ic.setBackground(rounded(c, tile, 9));
            r.addView(ic, new LinearLayout.LayoutParams(dp(c, 30), dp(c, 30)));
        }
        TextView t = text(c, title, 16, TEXT, NexFleetApp.get().regular());
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null) r.addView(text(c, value, 16, MUTED, NexFleetApp.get().regular()));
        if (onClick != null) {
            r.addView(text(c, "›", 22, TERTIARY, NexFleetApp.get().regular()));
            r.setBackground(new RippleDrawable(ColorStateList.valueOf(0x14000000), null, rounded(c, Color.BLACK, 0)));
            r.setOnClickListener(onClick);
        }
        return r;
    }

    public static View separator(Context c, float insetDp) {
        View v = new View(c);
        v.setBackgroundColor(SEPARATOR);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
        lp.leftMargin = dp(c, insetDp);
        v.setLayoutParams(lp);
        return v;
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
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 52));
        b.setMinimumHeight(dp(c, 52));
        b.setPadding(dp(c, 22), 0, dp(c, 22), 0);
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
        // iOS 26 : boutons en capsules ; l'action principale est la seule pleine de l'écran
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), rounded(c, bg, 999), rounded(c, Color.BLACK, 999)));
        if (style == Style.FILLED) b.setElevation(dp(c, 2));
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
        alert(a, title, message, cancel, action, false, onAction);
    }

    /**
     * Alerte iOS 26 : carte de rayon 28, titre gras, message, boutons capsules concentriques (Annuler à gauche en gris,
     * action à droite, pleine ou rouge si destructive). Fermeture par le bouton retour = Annuler.
     */
    public static void alert(Activity a, String title, String message, String cancel, String action, boolean destructive, Runnable onAction) {
        android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout card = column(a, 8);
        card.setPadding(dp(a, 22), dp(a, 22), dp(a, 22), dp(a, 16));
        card.setBackground(rounded(a, 0xFAFAFBFC, 28));
        TextView t = text(a, title, 18, TEXT, NexFleetApp.get().semibold());
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(t, matchWrap());
        if (message != null) {
            TextView m = text(a, message, 15, MUTED, NexFleetApp.get().regular());
            m.setGravity(Gravity.CENTER_HORIZONTAL);
            m.setLineSpacing(0, 1.2f);
            card.addView(m, matchWrap());
        }
        LinearLayout buttons = row(a, 10);
        buttons.setPadding(0, dp(a, 10), 0, 0);
        if (cancel != null) {
            Button c = button(a, cancel, Style.GRAY, v -> d.dismiss());
            buttons.addView(c, new LinearLayout.LayoutParams(0, dp(a, 48), 1f));
        }
        Button ok = button(a, action, destructive ? Style.GRAY : Style.FILLED, v -> {
            d.dismiss();
            if (onAction != null) onAction.run();
        });
        if (destructive) ok.setTextColor(RED);
        buttons.addView(ok, new LinearLayout.LayoutParams(0, dp(a, 48), 1f));
        card.addView(buttons, matchWrap());
        d.setContentView(card);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setLayout(Math.min(dp(a, 340), a.getResources().getDisplayMetrics().widthPixels - dp(a, 48)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setDimAmount(0.32f);
        }
        d.show();
    }

    public static ViewGroup.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
