package cm.camtel.nextfleet.domain.rules;

import cm.camtel.nextfleet.domain.rules.Finding.DataQuality;
import cm.camtel.nextfleet.domain.rules.Finding.Severity;
import cm.camtel.nextfleet.domain.rules.FuelControlContext.FilledReceipt;
import cm.camtel.nextfleet.domain.rules.FuelControlContext.Immobilisation;
import cm.camtel.nextfleet.domain.rules.FuelControlContext.InvoiceLineMatch;
import cm.camtel.nextfleet.domain.rules.FuelControlContext.OdometerReading;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Règles CAR du cahier V4 (chap. 10) implémentées de façon déterministe.
 * CAR-01 (bon dépassé) est appliquée en BLOCAGE transactionnel par la base et l'API ; elle n'apparaît pas ici.
 * CAR-05 (surconsommation) et CAR-08 (maintenance) nécessitent des profils approuvés par le métier : lot ultérieur.
 */
public final class StandardFuelRules {

    private StandardFuelRules() { }

    public static List<FuelRule> all() {
        return List.of(new Car02DuplicateReceipt(), new Car03OdometerRegression(),
                new Car04TankCapacity(), new Car06FillDuringImmobilisation(), new Car07InvoiceGap());
    }

    private static Map<String, Object> evidence(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1] == null ? "non renseigné" : kv[i + 1]);
        }
        return m;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** CAR-02 : même fournisseur et même référence de reçu saisis plusieurs fois. */
    public static final class Car02DuplicateReceipt implements FuelRule {
        public String id() { return "CAR-02"; }
        public int version() { return 1; }
        public String label() { return "Justificatif répété"; }

        public List<Finding> evaluate(FuelControlContext ctx) {
            Map<String, List<FilledReceipt>> byKey = new HashMap<>();
            for (FilledReceipt r : ctx.fills()) {
                if (r.normalizedReceipt().isEmpty()) continue;
                byKey.computeIfAbsent(r.supplierId() + "|" + r.normalizedReceipt(), k -> new ArrayList<>()).add(r);
            }
            List<Finding> out = new ArrayList<>();
            byKey.forEach((key, list) -> {
                if (list.size() < 2) return;
                List<String> ids = list.stream().map(r -> r.fill().id()).sorted().toList();
                out.add(new Finding(id(), version(), Severity.MEDIUM, DataQuality.FIABLE, ctx.vehicleId(),
                        "Reçu « " + list.get(0).normalizedReceipt() + " » saisi " + list.size()
                                + " fois chez le même fournisseur : comparer les pièces.",
                        evidence("fournisseur", list.get(0).supplierId(), "reference_recu", list.get(0).normalizedReceipt(),
                                "pleins", ids),
                        id() + ":" + key));
            });
            return out;
        }
    }

    /** CAR-03 : compteur décroissant sans changement de compteur documenté. */
    public static final class Car03OdometerRegression implements FuelRule {
        public String id() { return "CAR-03"; }
        public int version() { return 1; }
        public String label() { return "Compteur incohérent"; }

        public List<Finding> evaluate(FuelControlContext ctx) {
            List<OdometerReading> sorted = new ArrayList<>(ctx.odometerReadings());
            sorted.sort(Comparator.comparing(OdometerReading::readAt).thenComparing(OdometerReading::id));
            List<Finding> out = new ArrayList<>();
            for (int i = 1; i < sorted.size(); i++) {
                OdometerReading prev = sorted.get(i - 1);
                OdometerReading cur = sorted.get(i);
                if (cur.counterEpoch() != prev.counterEpoch()) continue;      // changement de compteur documenté
                if (cur.valueKm().compareTo(prev.valueKm()) < 0) {
                    out.add(new Finding(id(), version(), Severity.MEDIUM, DataQuality.FIABLE, ctx.vehicleId(),
                            "Recul du compteur de " + plain(prev.valueKm()) + " à " + plain(cur.valueKm())
                                    + " km : justification demandée.",
                            evidence("releve_precedent", prev.id(), "valeur_precedente_km", plain(prev.valueKm()),
                                    "releve", cur.id(), "valeur_km", plain(cur.valueKm()),
                                    "ecart_km", plain(cur.valueKm().subtract(prev.valueKm()))),
                            id() + ":" + prev.id() + ":" + cur.id()));
                }
            }
            return out;
        }
    }

    /** CAR-04 : volume d'un plein supérieur à la capacité documentée du réservoir. */
    public static final class Car04TankCapacity implements FuelRule {
        public String id() { return "CAR-04"; }
        public int version() { return 1; }
        public String label() { return "Capacité dépassée"; }

        public List<Finding> evaluate(FuelControlContext ctx) {
            if (ctx.tankCapacityLiters() == null) return List.of();      // pas de seuil : pas d'alerte inventée
            List<Finding> out = new ArrayList<>();
            for (FilledReceipt r : ctx.fills()) {
                if (r.fill().liters().compareTo(ctx.tankCapacityLiters()) > 0) {
                    DataQuality q = ctx.tankCapacityVerified() ? DataQuality.FIABLE : DataQuality.PARTIELLE;
                    out.add(new Finding(id(), version(), Severity.HIGH, q, ctx.vehicleId(),
                            plain(r.fill().liters()) + " L saisis pour une capacité documentée de "
                                    + plain(ctx.tankCapacityLiters()) + " L : vérifier réservoirs auxiliaires et pièce.",
                            evidence("plein", r.fill().id(), "litres", plain(r.fill().liters()),
                                    "capacite_l", plain(ctx.tankCapacityLiters()),
                                    "capacite_verifiee", ctx.tankCapacityVerified()),
                            id() + ":" + r.fill().id()));
                }
            }
            return out;
        }
    }

    /** CAR-06 : plein pendant une immobilisation enregistrée, sauf exception atelier. */
    public static final class Car06FillDuringImmobilisation implements FuelRule {
        public String id() { return "CAR-06"; }
        public int version() { return 1; }
        public String label() { return "Plein pendant immobilisation"; }

        public List<Finding> evaluate(FuelControlContext ctx) {
            List<Finding> out = new ArrayList<>();
            for (FilledReceipt r : ctx.fills()) {
                for (Immobilisation im : ctx.immobilisations()) {
                    if (im.contains(r.fill().filledAt()) && !im.workshopFuelAllowed()) {
                        out.add(new Finding(id(), version(), Severity.MEDIUM, DataQuality.FIABLE, ctx.vehicleId(),
                                "Plein enregistré pendant l'immobilisation " + im.maintenanceOrderId() + " : à instruire.",
                                evidence("plein", r.fill().id(), "date_plein", r.fill().filledAt().toString(),
                                        "ordre_maintenance", im.maintenanceOrderId(),
                                        "debut", im.from().toString(), "fin", im.to() == null ? null : im.to().toString()),
                                id() + ":" + r.fill().id() + ":" + im.maintenanceOrderId()));
                    }
                }
            }
            return out;
        }
    }

    /** CAR-07 : facture ligne à ligne contre le plein délivré (quantité et montant). */
    public static final class Car07InvoiceGap implements FuelRule {
        public String id() { return "CAR-07"; }
        public int version() { return 1; }
        public String label() { return "Écart de facturation"; }

        public List<Finding> evaluate(FuelControlContext ctx) {
            List<Finding> out = new ArrayList<>();
            for (InvoiceLineMatch l : ctx.invoiceLines()) {
                String key = id() + ":" + l.invoiceNumber() + ":" + l.lineNo();
                if (l.fillId() == null) {
                    out.add(new Finding(id(), version(), Severity.MEDIUM, DataQuality.PARTIELLE, ctx.vehicleId(),
                            "Ligne " + l.lineNo() + " de la facture " + l.invoiceNumber() + " sans plein rapproché.",
                            evidence("facture", l.invoiceNumber(), "ligne", l.lineNo(),
                                    "litres_factures", plain(l.invoicedLiters()), "montant_facture_xaf", plain(l.invoicedAmount())),
                            key));
                    continue;
                }
                BigDecimal dl = l.invoicedLiters().subtract(l.deliveredLiters());
                BigDecimal da = l.invoicedAmount().subtract(l.deliveredAmount());
                if (dl.signum() != 0 || da.signum() != 0) {
                    out.add(new Finding(id(), version(), dl.signum() > 0 || da.signum() > 0 ? Severity.HIGH : Severity.LOW,
                            DataQuality.FIABLE, ctx.vehicleId(),
                            "Facture " + l.invoiceNumber() + " ligne " + l.lineNo() + " : écart de " + plain(dl)
                                    + " L et " + plain(da) + " XAF par rapport au reçu.",
                            evidence("facture", l.invoiceNumber(), "ligne", l.lineNo(), "plein", l.fillId(),
                                    "litres_factures", plain(l.invoicedLiters()), "litres_recu", plain(l.deliveredLiters()),
                                    "ecart_litres", plain(dl), "montant_facture_xaf", plain(l.invoicedAmount()),
                                    "montant_recu_xaf", plain(l.deliveredAmount()), "ecart_xaf", plain(da)),
                            key));
                }
            }
            return out;
        }
    }
}
