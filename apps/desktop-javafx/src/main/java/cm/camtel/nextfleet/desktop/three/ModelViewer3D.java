package cm.camtel.nextfleet.desktop.three;

import javafx.animation.AnimationTimer;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Cylinder;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

/**
 * Visionneuse 3D (JavaFX 3D) : orbite à la souris, zoom à la molette, rotation lente automatique, socle circulaire.
 * Le modèle est recentré et mis à l'échelle (plus grande dimension = 4 unités). Nécessite le pipeline matériel
 * (ConditionalFeature.SCENE3D) ; sinon {@link #supported()} est faux et l'écran l'indique.
 */
public final class ModelViewer3D extends Pane {

    private final Group world = new Group();
    private final Group holder = new Group();
    private final Rotate yaw = new Rotate(-35, Rotate.Y_AXIS);
    private final Rotate pitch = new Rotate(18, Rotate.X_AXIS);
    private final Translate zoom = new Translate(0, 0, -11);
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final SubScene sub;
    private double lastX, lastY;
    private boolean autoRotate = true;
    private long lastFrame;
    private final AnimationTimer spin = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (lastFrame != 0 && autoRotate) yaw.setAngle(yaw.getAngle() + (now - lastFrame) / 1e9 * 12);
            lastFrame = now;
        }
    };

    public static boolean supported() {
        return javafx.application.Platform.isSupported(javafx.application.ConditionalFeature.SCENE3D);
    }

    public ModelViewer3D() {
        Cylinder plinth = new Cylinder(3.1, 0.06, 96);
        PhongMaterial pm = new PhongMaterial(Color.web("#E6EAED"));
        pm.setSpecularColor(Color.gray(0.15));
        plinth.setMaterial(pm);
        plinth.setTranslateY(0.03);
        AmbientLight ambient = new AmbientLight(Color.gray(0.42));
        PointLight key = new PointLight(Color.gray(0.85));
        key.getTransforms().add(new Translate(-6, -9, -7));
        PointLight fill = new PointLight(Color.gray(0.38));
        fill.getTransforms().add(new Translate(7, -4, 6));
        world.getChildren().addAll(plinth, holder, ambient, key, fill);
        world.getTransforms().addAll(pitch, yaw);

        camera.setNearClip(0.05);
        camera.setFarClip(200);
        camera.setFieldOfView(32);
        camera.getTransforms().addAll(new Translate(0, -0.9, 0), zoom);
        sub = new SubScene(world, 400, 300, true, SceneAntialiasing.BALANCED);
        sub.setCamera(camera);
        sub.setFill(Color.TRANSPARENT);
        sub.widthProperty().bind(widthProperty());
        sub.heightProperty().bind(heightProperty());
        getChildren().add(sub);

        setOnMousePressed(e -> {
            lastX = e.getSceneX();
            lastY = e.getSceneY();
            autoRotate = false;
        });
        setOnMouseDragged(e -> {
            yaw.setAngle(yaw.getAngle() + (e.getSceneX() - lastX) * 0.4);
            pitch.setAngle(Math.max(-5, Math.min(70, pitch.getAngle() + (e.getSceneY() - lastY) * 0.3)));
            lastX = e.getSceneX();
            lastY = e.getSceneY();
        });
        setOnScroll(e -> zoom.setZ(Math.max(-30, Math.min(-5, zoom.getZ() + e.getDeltaY() * 0.01))));
        setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) reset();
        });
        sceneProperty().addListener((o, a, s) -> {
            if (s == null) spin.stop();
            else spin.start();
        });
        setAccessibleText("Vue 3D du véhicule : glisser pour tourner, molette pour zoomer, double-clic pour recentrer");
    }

    /** Affiche un modèle chargé : recentré sur le socle, plus grande dimension ramenée à 4 unités. */
    public void show(GlbLoader.Model model) {
        double sx = model.max()[0] - model.min()[0], sy = model.max()[1] - model.min()[1], sz = model.max()[2] - model.min()[2];
        double size = Math.max(sx, Math.max(sy, sz));
        double k = size <= 0 ? 1 : 4.0 / size;
        double cx = (model.min()[0] + model.max()[0]) / 2, cz = (model.min()[2] + model.max()[2]) / 2;
        Group g = new Group(model.root());
        // après la rotation de 180° autour de X : y → -y, z → -z ; le bas du modèle est posé sur le socle (y = 0)
        g.getTransforms().addAll(new javafx.scene.transform.Scale(k, k, k), new Translate(-cx, model.min()[1], cz));
        holder.getChildren().setAll(g);
    }

    public void reset() {
        yaw.setAngle(-35);
        pitch.setAngle(18);
        zoom.setZ(-11);
        autoRotate = true;
    }

    public void setAutoRotate(boolean on) {
        autoRotate = on;
    }

    public void setAngle(double degrees) {
        yaw.setAngle(degrees);
    }
}
