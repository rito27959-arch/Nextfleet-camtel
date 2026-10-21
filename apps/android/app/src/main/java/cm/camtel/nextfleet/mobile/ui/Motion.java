package cm.camtel.nextfleet.mobile.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import cm.camtel.nextfleet.mobile.R;

/**
 * Mouvement et retours tactiles NexFleet (même langage que le bureau) : ressort légèrement amorti, décélération
 * « ease-out », appui qui enfonce, retour haptique aux moments qui comptent. Si l'utilisateur a désactivé les
 * animations dans Android (Accessibilité → Supprimer les animations), tout s'affiche directement dans son état final.
 */
final class Motion {

    private Motion() { }

    /** Décélération proche de cubic-bezier(0.2, 0.8, 0.2, 1). */
    static final Interpolator EASE_OUT = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);

    /** Ressort amorti (ζ ≈ 0,72) : léger dépassement puis stabilisation, sans oscillation visible. */
    static final Interpolator SPRING = t -> {
        if (t >= 1f) return 1f;
        double zeta = 0.72, omega = 11.0, wd = omega * Math.sqrt(1 - zeta * zeta);
        return (float) (1 - Math.exp(-zeta * omega * t) * (Math.cos(wd * t) + (zeta * omega / wd) * Math.sin(wd * t)));
    };

    static boolean enabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    // ------------------------------------------------------------------ apparition

    /** Entrée : fondu + montée de 14 dp, avec un délai (apparition échelonnée). */
    static void enter(View v, long delayMs) {
        if (!enabled()) return;
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(v.getContext(), 14));
        v.animate().alpha(1f).translationY(0).setStartDelay(delayMs).setDuration(380).setInterpolator(EASE_OUT).start();
    }

    /** Apparition échelonnée des enfants (35 ms d'écart, plafonnée à ~0,3 s pour ne jamais faire attendre). */
    static void stagger(ViewGroup g) {
        if (!enabled()) return;
        for (int i = 0; i < g.getChildCount(); i++) enter(g.getChildAt(i), Math.min(i, 8) * 35L);
    }

    // ------------------------------------------------------------------ appui

    /**
     * Appui qui enfonce : l'élément se réduit à 96 % sous le doigt puis revient en ressort au relâchement.
     * Le clic reste géré normalement (l'écouteur ne consomme pas l'événement).
     */
    @SuppressLint("ClickableViewAccessibility")
    static void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            if (!enabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setStartDelay(0).setDuration(120).setInterpolator(EASE_OUT).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(420).setInterpolator(SPRING).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Rebond d'une icône (onglet choisi, action réussie). */
    static void pop(View v) {
        if (!enabled()) return;
        v.setScaleX(0.82f);
        v.setScaleY(0.82f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(SPRING).start();
    }

    /** Secousse horizontale (saisie refusée), comme un mot de passe erroné sur iOS. */
    static void shake(View v) {
        if (!enabled()) return;
        float d = Ui.dp(v.getContext(), 9);
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0, d, -d, d * 0.6f, -d * 0.6f, d * 0.25f, 0);
        a.setDuration(420);
        a.start();
    }

    // ------------------------------------------------------------------ retours haptiques

    /** Petit déclic : sélection d'un onglet, d'une option. */
    static void tick(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /** Confirmation : action acceptée par le serveur (plein enregistré, mission démarrée, message parti). */
    static void success(View v) {
        v.performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /** Refus : saisie invalide ou action refusée. */
    static void reject(View v) {
        v.performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS);
    }

    /** Seuil franchi (tirer pour actualiser). */
    static void threshold(View v) {
        v.performHapticFeedback(Build.VERSION.SDK_INT >= 34 ? HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE : HapticFeedbackConstants.KEYBOARD_TAP);
    }

    // ------------------------------------------------------------------ transitions entre écrans

    /**
     * Écran de détail (à appeler dans son onCreate) : arrive depuis la droite pendant que l'écran précédent recule
     * légèrement, et repart vers la droite à la fermeture (iOS « push »). Android gère lui-même la suppression des
     * animations quand l'utilisateur l'a demandée.
     */
    static void detailScreen(Activity a) {
        declare(a, R.anim.nf_push_in, R.anim.nf_push_out, R.anim.nf_pop_in, R.anim.nf_pop_out);
    }

    /** Formulaire modal (à appeler dans son onCreate) : monte depuis le bas, redescend à la fermeture. */
    static void sheetScreen(Activity a) {
        declare(a, R.anim.nf_sheet_up, R.anim.nf_hold, R.anim.nf_hold, R.anim.nf_sheet_down);
    }

    /** Fermeture d'un écran de détail (à appeler après super.finish()) — Android 13 et antérieurs. */
    static void detailClosing(Activity a) {
        closing(a, R.anim.nf_pop_in, R.anim.nf_pop_out);
    }

    static void sheetClosing(Activity a) {
        closing(a, R.anim.nf_hold, R.anim.nf_sheet_down);
    }

    @SuppressWarnings("deprecation")
    private static void declare(Activity a, int openEnter, int openExit, int closeEnter, int closeExit) {
        if (Build.VERSION.SDK_INT >= 34) {
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, openEnter, openExit);
            a.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, closeEnter, closeExit);
        } else {
            a.overridePendingTransition(openEnter, openExit);
        }
    }

    @SuppressWarnings("deprecation")
    private static void closing(Activity a, int enter, int exit) {
        if (Build.VERSION.SDK_INT < 34) a.overridePendingTransition(enter, exit);
    }
}
