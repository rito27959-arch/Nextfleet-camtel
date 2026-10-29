package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Models.DueItem;
import cm.camtel.nextfleet.desktop.data.Models.DueLevel;
import cm.camtel.nextfleet.desktop.data.Models.Vehicle;
import cm.camtel.nextfleet.desktop.data.Models.VehicleStatus;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Score de santé d'un véhicule (0–100), toujours EXPLIQUÉ : chaque point retiré a un motif lisible.
 * Barème : document expiré −30 ; échéance sous 7 jours −15 ; sous 30 jours −5 ; en entretien −25 ; hors service −40 ;
 * demande d'intervention ouverte −10. Calculé sur les données NexFleet, sans télématique.
 */
final class Health {

    record Score(int value, List<String> reasons) {
        String tone() {
            return value >= 80 ? "good" : value >= 50 ? "warn" : "bad";
        }

        String label() {
            return value >= 80 ? "Bon état" : value >= 50 ? "À surveiller" : "Critique";
        }
    }

    private Health() { }

    static Score of(Vehicle v, List<DueItem> dues, long openRequests) {
        int s = 100;
        List<String> why = new ArrayList<>();
        for (DueItem d : dues) {
            if (!d.vehicle() || !same(d.subject(), v.registration())) continue;
            if (d.level() == DueLevel.EXPIRE) {
                s -= 30;
                why.add(d.kind().label + " expiré(e) depuis " + (-d.daysLeft()) + " j");
            } else if (d.daysLeft() <= 7) {
                s -= 15;
                why.add(d.kind().label + " : échéance dans " + d.daysLeft() + " j");
            } else if (d.daysLeft() <= 30) {
                s -= 5;
                why.add(d.kind().label + " : échéance dans " + d.daysLeft() + " j");
            }
        }
        if (v.status() == VehicleStatus.MAINTENANCE) {
            s -= 25;
            why.add("En entretien");
        } else if (v.status() == VehicleStatus.OUT_OF_SERVICE) {
            s -= 40;
            why.add("Hors service");
        }
        if (openRequests > 0) {
            s -= 10 * (int) Math.min(openRequests, 3);
            why.add(openRequests + " demande(s) d'intervention ouverte(s)");
        }
        return new Score(Math.max(0, Math.min(100, s)), why);
    }

    /** Moyenne de la flotte (véhicules archivés exclus). */
    static int fleet(List<Score> scores) {
        return scores.isEmpty() ? 100 : (int) Math.round(scores.stream().mapToInt(Score::value).average().orElse(100));
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.replaceAll("[^A-Za-z0-9]", "").equalsIgnoreCase(b.replaceAll("[^A-Za-z0-9]", ""));
    }

    /** Anneau de santé : arc coloré selon le niveau (doublé du chiffre, jamais la couleur seule), remplissage animé. */
    static StackPane ring(Score score, double radius, double stroke, double fontSize) {
        Arc track = new Arc(0, 0, radius, radius, 90, -360);
        track.setType(ArcType.OPEN);
        track.setStrokeWidth(stroke);
        track.getStyleClass().add("health-track");
        Arc arc = new Arc(0, 0, radius, radius, 90, 0);
        arc.setType(ArcType.OPEN);
        arc.setStrokeWidth(stroke);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        arc.getStyleClass().add("health-" + score.tone());
        DoubleProperty p = new SimpleDoubleProperty(0);
        p.addListener((o, a, b) -> arc.setLength(-360 * b.doubleValue()));
        Motion.animate(p, score.value() / 100.0, Duration.millis(900), Duration.millis(120));
        Label value = Ui.label(String.valueOf(score.value()), "health-value");
        value.setStyle("-fx-font-size: " + fontSize + "px;");
        StackPane ringPane = new StackPane(track, arc, value);
        ringPane.setMinSize(2 * radius + stroke, 2 * radius + stroke);
        ringPane.setMaxSize(2 * radius + stroke, 2 * radius + stroke);
        ringPane.setAccessibleText("Santé " + score.value() + " sur 100, " + score.label());
        return ringPane;
    }
}
