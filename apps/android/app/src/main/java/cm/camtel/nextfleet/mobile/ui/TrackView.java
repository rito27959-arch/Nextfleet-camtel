package cm.camtel.nextfleet.mobile.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import cm.camtel.nextfleet.mobile.tracking.FixCodec;

import java.util.List;

/**
 * Tracé du trajet sur fond quadrillé neutre (pas de fond cartographique : serveur de tuiles à désigner par la DSIR).
 * Départ = anneau vert, dernière position = point bleu, échelle en kilomètres.
 */
public final class TrackView extends View {

    private List<FixCodec.Fix> fixes = List.of();
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TrackView(Context c) {
        super(c);
        grid.setColor(0x0F102434);
        grid.setStrokeWidth(1);
        halo.setColor(0x38009B87);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(Ui.dp(c, 9));
        halo.setStrokeCap(Paint.Cap.ROUND);
        halo.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(Ui.BRAND);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 3.5f));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        text.setColor(Ui.MUTED);
        text.setTextSize(Ui.dp(c, 11));
        setContentDescription("Tracé du trajet de la mission");
    }

    public void setFixes(List<FixCodec.Fix> f) {
        fixes = List.copyOf(f);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFFEEF2F0);
        float step = Ui.dp(getContext(), 44);
        for (float x = 0; x < w; x += step) c.drawLine(x, 0, x, h, grid);
        for (float y = 0; y < h; y += step) c.drawLine(0, y, w, y, grid);
        if (fixes.isEmpty()) {
            String s = "Aucune position · la collecte commence au démarrage de la mission";
            c.drawText(s, (w - text.measureText(s)) / 2, h / 2f, text);
            return;
        }
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (FixCodec.Fix f : fixes) {
            minLat = Math.min(minLat, f.lat);
            maxLat = Math.max(maxLat, f.lat);
            minLon = Math.min(minLon, f.lon);
            maxLon = Math.max(maxLon, f.lon);
        }
        double k = Math.cos(Math.toRadians((minLat + maxLat) / 2));
        double spanX = Math.max(0.009, (maxLon - minLon) * k), spanY = Math.max(0.009, maxLat - minLat);
        double cLon = (minLon + maxLon) / 2, cLat = (minLat + maxLat) / 2;
        float pad = Ui.dp(getContext(), 36);
        double scale = Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY);
        Path p = new Path();
        float[] first = null, last = null;
        for (FixCodec.Fix f : fixes) {
            float x = (float) (w / 2.0 + (f.lon - cLon) * k * scale);
            float y = (float) (h / 2.0 - (f.lat - cLat) * scale);
            if (first == null) {
                p.moveTo(x, y);
                first = new float[]{x, y};
            } else {
                p.lineTo(x, y);
            }
            last = new float[]{x, y};
        }
        c.drawPath(p, halo);
        c.drawPath(p, line);
        float r = Ui.dp(getContext(), 7);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(first[0], first[1], r, fill);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(Ui.dp(getContext(), 3));
        fill.setColor(0xFF34C759);
        c.drawCircle(first[0], first[1], r, fill);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x380088FF);
        c.drawCircle(last[0], last[1], r * 2.4f, fill);
        fill.setColor(0xFF0088FF);
        c.drawCircle(last[0], last[1], r, fill);
        // échelle
        double metersPerPx = 111_320 / scale;
        double[] nice = {100, 200, 500, 1000, 2000, 5000, 10_000, 20_000, 50_000};
        double chosen = nice[0];
        for (double n : nice) if (n <= metersPerPx * Ui.dp(getContext(), 100)) chosen = n;
        float len = (float) (chosen / metersPerPx);
        float bx = Ui.dp(getContext(), 16), by = h - Ui.dp(getContext(), 18);
        fill.setColor(Ui.TEXT);
        c.drawRect(bx, by, bx + len, by + Ui.dp(getContext(), 3), fill);
        c.drawText(chosen >= 1000 ? (int) (chosen / 1000) + " km" : (int) chosen + " m", bx, by - Ui.dp(getContext(), 5), text);
    }
}
