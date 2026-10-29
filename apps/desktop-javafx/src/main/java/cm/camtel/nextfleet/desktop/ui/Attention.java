package cm.camtel.nextfleet.desktop.ui;

import cm.camtel.nextfleet.desktop.data.Models.DueItem;
import cm.camtel.nextfleet.desktop.data.Models.DueLevel;
import cm.camtel.nextfleet.desktop.data.Ops.Mission;
import cm.camtel.nextfleet.desktop.data.Ops.MissionStatus;
import cm.camtel.nextfleet.desktop.data.Prod.FuelOrder;
import cm.camtel.nextfleet.desktop.data.Prod.FuelOrderStatus;
import cm.camtel.nextfleet.desktop.data.Prod.WorkOrder;
import cm.camtel.nextfleet.desktop.data.Prod.WorkStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * File « À traiter » : ce qui demande une décision, classé par urgence, chaque élément disant pourquoi il est là et quelle
 * action le résout (flux d'exceptions plutôt que mur d'indicateurs). Logique pure, sans interface : testable.
 */
final class Attention {

    enum Rank { CRITICAL, HIGH, NORMAL }

    enum Target { DUE, MAINTENANCE, FUEL, MISSION, VEHICLE }

    /** Un élément : rang, symbole, titre, motif, échéance lisible, libellé d'action et cible (écran, objet). */
    record Item(Rank rank, String icon, String title, String why, String when, String action, Target target, String ref, long sortKey) { }

    private Attention() { }

    static List<Item> build(List<DueItem> dues, List<WorkOrder> works, List<FuelOrder> fuels, List<Mission> missions,
                            boolean canApproveFuel, boolean canAuthorizeMissions, Instant now) {
        List<Item> out = new ArrayList<>();
        for (DueItem d : dues) {
            if (d.level() == DueLevel.A_JOUR) continue;
            boolean expired = d.level() == DueLevel.EXPIRE;
            Rank r = expired ? Rank.CRITICAL : d.daysLeft() <= 7 ? Rank.HIGH : Rank.NORMAL;
            String what = switch (d.kind()) {
                case VIDANGE -> expired ? "Vidange dépassée" : "Vidange à prévoir";
                case CONTROLE_TECHNIQUE, PERMIS -> d.kind().label + (expired ? " expiré" : " à renouveler");
                default -> d.kind().label + (expired ? " expirée" : " à renouveler");
            };
            String detail = d.subjectDetail() == null || d.subjectDetail().isBlank() ? "" : d.subjectDetail() + " · ";
            String why = detail + (expired
                    ? (d.vehicle() ? "ne devrait pas circuler sans ce document" : "ne devrait pas conduire sans ce document")
                    : "anticipez le renouvellement");
            out.add(new Item(r, d.vehicle() ? Icons.DOC : Icons.ID_CARD, what + " — " + d.subject(), why, when(d.daysLeft()),
                    d.vehicle() ? "Ouvrir le véhicule" : "Voir les échéances", d.vehicle() ? Target.VEHICLE : Target.DUE, d.subject(), d.daysLeft()));
        }
        for (WorkOrder w : works) {
            if (w.status() == WorkStatus.DEMANDE) {
                long age = days(w.createdAt(), now);
                out.add(new Item(age >= 3 ? Rank.HIGH : Rank.NORMAL, Icons.WRENCH, "Demande d'intervention — " + w.registration(),
                        w.categoryLabel() + " · " + w.title() + (w.requestedBy() == null ? "" : " · par " + w.requestedBy()),
                        age == 0 ? "Aujourd'hui" : "Depuis " + age + " j", "Qualifier", Target.MAINTENANCE, w.id().toString(), -age));
            } else if (w.status() == WorkStatus.EN_COURS) {
                long age = days(w.immobilisedFrom() != null ? w.immobilisedFrom() : w.createdAt(), now);
                out.add(new Item(age >= 7 ? Rank.HIGH : Rank.NORMAL, Icons.WRENCH, "Véhicule immobilisé — " + w.registration(),
                        w.title() + (w.garageLabel() == null ? "" : " · " + w.garageLabel()),
                        age == 0 ? "Depuis aujourd'hui" : "Depuis " + age + " j", "Suivre", Target.MAINTENANCE, w.id().toString(), 100 - age));
            }
        }
        if (canApproveFuel) {
            for (FuelOrder f : fuels) {
                if (f.status() != FuelOrderStatus.SUBMITTED || f.mine()) continue;
                out.add(new Item(Rank.HIGH, Icons.FUEL, "Bon de carburant à valider — " + f.registration(),
                        f.orderNumber() + " · " + f.supplierName() + " · demandé par " + f.requestedBy(),
                        "Demande en attente", "Valider", Target.FUEL, f.id().toString(), 10));
            }
        }
        if (canAuthorizeMissions) {
            for (Mission m : missions) {
                if (m.status() != MissionStatus.DRAFT) continue;
                long hours = m.plannedStart() == null ? 999 : Duration.between(now, m.plannedStart()).toHours();
                Rank r = hours < 24 ? Rank.HIGH : Rank.NORMAL;
                out.add(new Item(r, Icons.ROUTE, "Mission à autoriser — " + m.reference(),
                        m.purpose() + " · " + m.registration() + (m.driverName() == null ? "" : " · " + m.driverName()),
                        hours < 0 ? "Départ dépassé" : hours < 24 ? "Départ dans " + Math.max(hours, 1) + " h" : "Départ " + Views.dayTime(m.plannedStart()),
                        "Autoriser", Target.MISSION, m.id().toString(), hours));
            }
        }
        out.sort(Comparator.comparing(Item::rank).thenComparingLong(Item::sortKey));
        return out;
    }

    static String when(long daysLeft) {
        if (daysLeft < -1) return "Expiré depuis " + (-daysLeft) + " j";
        if (daysLeft == -1) return "Expiré hier";
        if (daysLeft == 0) return "Aujourd'hui";
        if (daysLeft == 1) return "Demain";
        return "Dans " + daysLeft + " j";
    }

    private static long days(Instant from, Instant now) {
        return from == null ? 0 : Math.max(0, Duration.between(from, now).toDays());
    }
}
