package cm.camtel.nextfleet.mobile.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import java.util.function.Consumer;

/**
 * Tirer pour actualiser (iOS) autour d'une liste défilante : résistance croissante, déclic haptique au franchissement
 * du seuil, indicateur qui tourne pendant l'actualisation, retour en ressort. N'intercepte le geste que si la liste
 * est déjà tout en haut ; le défilement normal n'est jamais gêné.
 */
@SuppressLint("ClickableViewAccessibility")
final class PullToRefresh extends FrameLayout {

    private final View content;
    private final ProgressBar spinner;
    private final int slop;
    private final float threshold;
    private final float hold;
    private Consumer<Runnable> onRefresh;
    private float startY;
    private float lastPull;
    private boolean dragging;
    private boolean armed;
    private boolean refreshing;

    PullToRefresh(Context c, View content) {
        super(c);
        this.content = content;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        threshold = Ui.dp(c, 76);
        hold = Ui.dp(c, 58);
        spinner = new ProgressBar(c);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        spinner.setAlpha(0f);
        spinner.setContentDescription("Actualisation");
        addView(spinner, new LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    /** Le rappel reçoit « fini » : à appeler quand les données sont arrivées (ou en échec). */
    void setOnRefresh(Consumer<Runnable> onRefresh) {
        this.onRefresh = onRefresh;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (refreshing || onRefresh == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startY = e.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!content.canScrollVertically(-1) && e.getY() - startY > slop) {
                    dragging = true;
                    startY = e.getY();
                }
                break;
            default:
                break;
        }
        return dragging;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float raw = Math.max(0, e.getY() - startY);
                // résistance : plus on tire, moins le contenu suit (sensation de ressort tendu)
                float pull = threshold * 1.6f * (1 - (float) Math.exp(-raw / (threshold * 1.6f)));
                lastPull = pull;
                apply(pull);
                boolean nowArmed = pull >= threshold * 0.92f;
                if (nowArmed && !armed) Motion.threshold(this);
                armed = nowArmed;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (armed) startRefresh();
                else settle(0);
                armed = false;
                return true;
            default:
                return true;
        }
    }

    private void apply(float pull) {
        content.setTranslationY(pull);
        float p = Math.min(1f, pull / threshold);
        spinner.setAlpha(p);
        spinner.setTranslationY(topInset() + pull / 2 - spinner.getHeight() / 2f);
        spinner.setRotation(pull * 2.2f);
        spinner.setScaleX(0.6f + 0.4f * p);
        spinner.setScaleY(0.6f + 0.4f * p);
    }

    private float topInset() {
        return content.getPaddingTop();
    }

    private void startRefresh() {
        refreshing = true;
        settle(hold);
        onRefresh.accept(() -> post(() -> {
            refreshing = false;
            settle(0);
        }));
    }

    private void settle(float to) {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(lastPull, to);
        a.setDuration(to == 0 ? 420 : 300);
        a.setInterpolator(to == 0 ? Motion.SPRING : Motion.EASE_OUT);
        a.addUpdateListener(v -> {
            lastPull = (float) v.getAnimatedValue();
            apply(lastPull);
        });
        a.start();
    }
}
