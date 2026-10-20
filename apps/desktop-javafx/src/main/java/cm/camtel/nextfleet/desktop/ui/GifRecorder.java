package cm.camtel.nextfleet.desktop.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;
import javafx.util.Duration;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Enregistre une séquence animée de l'interface réelle (preuve de recette des animations, REC-UX-01).
 * Capture des images à intervalle fixe pendant qu'une action s'exécute, puis écrit un GIF en boucle.
 */
final class GifRecorder {

    private final Scene scene;
    private final List<BufferedImage> frames = new ArrayList<>();
    private final double scale;

    GifRecorder(Scene scene, double scale) {
        this.scene = scene;
        this.scale = scale;
    }

    /** Démarre l'enregistrement, exécute l'action, capture {@code count} images espacées de {@code everyMs}. */
    void record(Runnable action, int count, int everyMs, Runnable done) {
        Timeline tl = new Timeline();
        for (int i = 0; i < count; i++) {
            tl.getKeyFrames().add(new KeyFrame(Duration.millis(10 + (double) i * everyMs), e -> grab()));
        }
        tl.setOnFinished(e -> done.run());
        action.run();
        tl.play();
    }

    private void grab() {
        SnapshotParameters p = new SnapshotParameters();
        p.setTransform(Transform.scale(scale, scale));
        WritableImage img = scene.getRoot().snapshot(p, null);
        BufferedImage src = SwingFXUtils.fromFXImage(img, null);
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(src, 0, 0, null);
        frames.add(rgb);
    }

    int frameCount() {
        return frames.size();
    }

    void write(Path file, int delayMs) throws IOException {
        if (frames.isEmpty()) return;
        ImageWriter w = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
            w.setOutput(out);
            w.prepareWriteSequence(null);
            for (int i = 0; i < frames.size(); i++) {
                BufferedImage f = frames.get(i);
                IIOMetadata meta = w.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(f), null);
                String fmt = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);
                IIOMetadataNode gce = child(root, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                // Dernière image tenue 1,5 s avant de reboucler
                gce.setAttribute("delayTime", String.valueOf((i == frames.size() - 1 ? 1500 : delayMs) / 10));
                gce.setAttribute("transparentColorIndex", "0");
                IIOMetadataNode apps = child(root, "ApplicationExtensions");
                IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
                app.setAttribute("applicationID", "NETSCAPE");
                app.setAttribute("authenticationCode", "2.0");
                app.setUserObject(new byte[]{1, 0, 0});
                apps.appendChild(app);
                meta.setFromTree(fmt, root);
                w.writeToSequence(new IIOImage(f, null, meta), null);
            }
            w.endWriteSequence();
        } finally {
            w.dispose();
        }
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        }
        IIOMetadataNode n = new IIOMetadataNode(name);
        root.appendChild(n);
        return n;
    }
}
