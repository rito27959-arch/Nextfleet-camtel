package cm.camtel.nextfleet.mobile.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.filament.Engine;
import com.google.android.filament.IndirectLight;
import com.google.android.filament.Renderer;
import com.google.android.filament.android.UiHelper;
import com.google.android.filament.utils.Float3;
import com.google.android.filament.utils.Manipulator;
import com.google.android.filament.utils.ModelViewer;
import com.google.android.filament.utils.Utils;

import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.data.Catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Visionneuse 3D Google Filament (rendu PBR temps réel) : modèle GLB du catalogue embarqué, orbite au doigt,
 * pincement pour zoomer. La licence et l'auteur du modèle sont affichés en permanence (CC0, CC-BY, création originale).
 */
public final class ModelViewerActivity extends Activity implements Choreographer.FrameCallback {

    static {
        Utils.init();   // bibliothèques natives Filament (filament, gltfio, utils)
    }

    private ModelViewer viewer;
    private Choreographer choreographer;
    private Engine engine;

    public static void open(Context c, Catalogue.Entry e) {
        c.startActivity(new Intent(c, ModelViewerActivity.class).putExtra("id", e.id));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String id = getIntent().getStringExtra("id");
        Catalogue.Entry entry = null;
        for (Catalogue.Entry e : NexFleetApp.get().catalogue().entries) if (e.id.equals(id)) entry = e;
        if (entry == null) {
            finish();
            return;
        }
        SurfaceView surface = new SurfaceView(this);
        engine = Engine.create();
        Manipulator manipulator = new Manipulator.Builder()
                .targetPosition(0f, 0f, -4f)
                .orbitHomePosition(2.6f, 1.4f, 0.8f)
                .viewport(1, 1)
                .build(Manipulator.Mode.ORBIT);
        viewer = new ModelViewer(surface, engine, new UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK), manipulator);
        surface.setOnTouchListener((v, event) -> {
            viewer.onTouchEvent(event);
            return true;
        });

        Renderer.ClearOptions clear = new Renderer.ClearOptions();
        clear.clearColor = new double[]{0.949, 0.957, 0.969, 1.0};
        clear.clear = true;
        viewer.getRenderer().setClearOptions(clear);
        IndirectLight ibl = new IndirectLight.Builder()
                .irradiance(1, new float[]{1f, 1f, 1f})
                .intensity(32_000f)
                .build(engine);
        viewer.getScene().setIndirectLight(ibl);

        try {
            viewer.loadModelGlb(readAsset(entry.file));
            viewer.transformToUnitCube(new Float3(0f, 0f, -4f));
        } catch (IOException e) {
            Ui.alert(this, "Modèle illisible", "Le fichier 3D n'a pas pu être chargé.", null, "OK", this::finish);
        }

        LinearLayout panel = Ui.column(this, 4);
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
        panel.setBackground(Ui.glass(this, 28));
        panel.setElevation(Ui.dp(this, 8));
        panel.addView(Ui.headline(this, entry.label));
        panel.addView(Ui.muted(this, entry.category + " · " + entry.licence, 13));
        panel.addView(Ui.muted(this, "Auteur : " + entry.author, 12));
        panel.addView(Ui.muted(this, "Glisser pour tourner · pincer pour zoomer", 12));
        // bouton fermer en capsule de verre (iOS 26), action principale séparée du contenu
        TextView close = Ui.text(this, "Terminé", 16, Ui.ACCENT_TEXT, NexFleetApp.get().semibold());
        close.setPadding(Ui.dp(this, 18), Ui.dp(this, 11), Ui.dp(this, 18), Ui.dp(this, 11));
        close.setBackground(Ui.glass(this, 999));
        close.setElevation(Ui.dp(this, 6));
        close.setOnClickListener(v -> finish());

        FrameLayout root = new FrameLayout(this);
        root.addView(surface);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM);
        pp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 14));
        root.addView(panel, pp);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.END);
        cp.setMargins(0, Ui.dp(this, 10), Ui.dp(this, 14), 0);
        root.addView(close, cp);
        Ui.applyInsets(root, true, true);
        setContentView(root);
        choreographer = Choreographer.getInstance();
    }

    private ByteBuffer readAsset(String path) throws IOException {
        try (InputStream in = getAssets().open(path); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
            byte[] all = out.toByteArray();
            ByteBuffer b = ByteBuffer.allocateDirect(all.length).order(ByteOrder.nativeOrder());
            b.put(all);
            b.flip();
            return b;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (choreographer != null) choreographer.postFrameCallback(this);
    }

    @Override
    protected void onPause() {
        if (choreographer != null) choreographer.removeFrameCallback(this);
        super.onPause();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        choreographer.postFrameCallback(this);
        viewer.render(frameTimeNanos);
    }

    @Override
    protected void onDestroy() {
        if (choreographer != null) choreographer.removeFrameCallback(this);
        if (viewer != null) viewer.destroy();
        if (engine != null) engine.destroy();
        super.onDestroy();
    }
}
