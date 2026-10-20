package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Ops.Mission;
import cm.camtel.nextfleet.desktop.data.Ops.MissionStatus;
import cm.camtel.nextfleet.domain.gps.GpsFix;
import cm.camtel.nextfleet.domain.gps.TrackAnalyzer;
import cm.camtel.nextfleet.domain.gps.TrackPolicy;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Éléments partagés des écrans de mission : statistiques qualifiées, informations de collecte, mention de confidentialité. */
final class MissionPanels {

    static final TrackAnalyzer ANALYZER = new TrackAnalyzer(TrackPolicy.demoDefaults());
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE).withZone(ZoneId.of("Africa/Douala"));
    static final DateTimeFormatter DAY_HM = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.FRANCE).withZone(ZoneId.of("Africa/Douala"));

    private MissionPanels() { }

    static Label statusPill(MissionStatus s) {
        Label l = Ui.label(s.label, "pill", "pill-" + s.tone);
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(3.5);
        dot.getStyleClass().add("dot-" + s.tone);
        l.setGraphic(dot);
        l.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return l;
    }

    /** Distance, durée, vitesse maximale observée, arrêts et fiabilité calculés par le noyau métier. */
    static HBox stats(List<GpsFix> fixes, Mission m, Instant now) {
        TrackAnalyzer.TripStatistics st = ANALYZER.statistics(fixes);
        Duration elapsed = m.startedAt() == null ? Duration.ZERO
                : Duration.between(m.startedAt(), m.finishedAt() != null ? m.finishedAt() : now);
        HBox row = new HBox(12,
                tile("Distance", Ui.decimal(java.math.BigDecimal.valueOf(st.distanceKm()), 1), "km"),
                tile("Durée", hm(elapsed), null),
                tile("Vitesse max. observée", st.maxObservedSpeedKmh() == null ? "—" : String.valueOf(Math.round(st.maxObservedSpeedKmh())), "km/h"),
                tile("Arrêts", String.valueOf(st.stops().size()), "≥ 5 min"),
                tile("Fiabilité", switch (st.reliability()) {
                    case FIABLE -> "Fiable";
                    case ESTIMATION -> "Estimation";
                    case INSUFFISANTE -> "Insuffisante";
                }, st.validPoints() + (st.validPoints() > 1 ? " pts" : " pt")));
        row.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
        return row;
    }

    private static VBox tile(String caption, String value, String unit) {
        Label v = Ui.label(value, "stat-value");
        HBox line = new HBox(5, v);
        line.setAlignment(Pos.BASELINE_LEFT);
        if (unit != null) line.getChildren().add(Ui.label(unit, "stat-unit"));
        VBox t = new VBox(2, Ui.label(caption, "footnote", "muted"), line);
        t.getStyleClass().add("stat-tile");
        t.setMaxWidth(Double.MAX_VALUE);
        t.setMinWidth(0);
        t.setAccessibleText(caption + " : " + value + (unit == null ? "" : " " + unit));
        return t;
    }

    static String hm(Duration d) {
        long m = Math.max(0, d.toMinutes());
        return m < 60 ? m + " min" : (m / 60) + " h " + String.format("%02d", m % 60);
    }

    /** Groupe « Collecte GPS » : émetteur, notice, période, points acceptés. */
    static Node collection(Mission m, int points) {
        String period = m.startedAt() == null ? "Pas encore démarrée"
                : HM.format(m.startedAt()) + " → " + (m.finishedAt() == null ? "en cours" : HM.format(m.finishedAt()));
        return Ui.insetGroup("Collecte GPS", List.of(
                Ui.listRow(Ui.symbolTile(Icons.PHONE, "tile-blue"), "Émetteur autorisé", m.deviceId() == null ? "—" : m.deviceId(), null, null),
                Ui.listRow(Ui.symbolTile(Icons.DOC, "tile-gray"), "Notice d'information", m.noticeVersion() == null ? "—" : m.noticeVersion(), null, null),
                Ui.listRow(Ui.symbolTile(Icons.CLOCK, "tile-mint"), "Période de collecte", period, null, null),
                Ui.listRow(Ui.symbolTile(Icons.LOCATION, "tile-indigo"), "Positions acceptées", String.valueOf(points), null, null)),
                "Positions reçues via Traccar puis contrôlées par NexFleet : seuls les points relevés pendant la mission, par "
                        + "l'émetteur autorisé, sont conservés. Un point refusé n'est jamais stocké.");
    }

    static HBox privacyNote(String text) {
        Label l = Ui.label(text, "footnote");
        l.setWrapText(true);
        HBox.setHgrow(l, Priority.ALWAYS);
        HBox h = new HBox(10, Icons.stroke(Icons.SHIELD, 18, "-nf-blue-text"), l);
        h.getStyleClass().add("privacy-note");
        return h;
    }

    static String liveText(List<GpsFix> fixes, Mission m, Instant now) {
        if (m.status() != MissionStatus.ACTIVE) {
            return m.status() == MissionStatus.FINISHED ? "Trajet terminé · collecte arrêtée" : "Collecte inactive";
        }
        TrackAnalyzer.LiveStatus ls = ANALYZER.liveStatus(fixes, now);
        return switch (ls.state()) {
            case DIRECT -> "En direct · " + age(ls.age());
            case DERNIERE_POSITION -> "Dernière position · " + age(ls.age());
            case AUCUNE_POSITION -> "En attente de la première position";
        };
    }

    static boolean isLive(List<GpsFix> fixes, Mission m, Instant now) {
        return m.status() == MissionStatus.ACTIVE && ANALYZER.liveStatus(fixes, now).state() == TrackAnalyzer.LiveState.DIRECT;
    }

    private static String age(Duration d) {
        long s = d.getSeconds();
        return s < 60 ? "il y a " + Math.max(1, s) + " s" : "il y a " + (s / 60) + " min";
    }
}
