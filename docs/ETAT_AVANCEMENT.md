# État réel d'avancement — Lot 1

**20 octobre 2026.** Ce document distingue ce qui a été exécuté et vérifié de ce qui est seulement écrit.
Aucune autorisation CAMTEL, aucun avis DSIR ou DS et aucune mise en production ne sont revendiqués.

## Ce qui fonctionne réellement (preuves exécutées)

| Élément | Commande | Résultat obtenu |
|---|---|---|
| Migrations SQL V1–V3 sur PostgreSQL 16 + PostGIS 3.4 | `infra/db/tests/run_db_tests.sh` | 3 migrations appliquées sur base vierge |
| Tests d'intégrité de la base | idem | **32 tests réussis** : unicité, démo interdite en production, révocation des sessions, auto-attribution refusée, chevauchements, bons (auto-validation, transitions, solde, reçu répété, montant), facture 55 L / reçu 50 L, GPS hors mission, rejeu GPS, chat (séquence, doublon, intrus), pas de statut « fraude », licence 3D, journal chaîné et immuable |
| Concurrence sur un bon (CAR-01) | idem | Deux sessions simultanées 40 L et 30 L sur un bon de 60 L : **un seul plein accepté**, solde jamais dépassé |
| Noyau métier Java 21 (sans dépendance) | `tools/build-offline.sh` | Compilation `-Xlint:all -Werror` ; **27 tests JUnit 5 réussis** |
| Client JavaFX | idem | Compilation ; **7 tests réussis** (passerelle démo, validation du formulaire, client HTTP contre faux serveur local) |
| Parcours visuel | `tools/screenshots.sh` | **11 captures** 1440×900 dans `docs/design-reference/captures-lot1/` |
| Contrastes | `python3 tools/check_contrast.py` | 13 couples conformes |
| Contrat OpenAPI lot 1 | `openapi-spec-validator` | Valide (11 chemins, 14 schémas) |

## Ce qui est écrit mais pas encore prouvé

| Élément | Statut | Condition pour le déclarer livré |
|---|---|---|
| API Spring Boot (auth, véhicules, carburant, audit, accès par entité) | **Non compilée** : Maven Central bloqué. Seule l'analyse syntaxique Java a été faite. | `mvn verify` vert, y compris `ApiIntegrationTest` (Testcontainers) |
| `HttpFleetGateway` contre la vraie API | Testé contre un faux serveur local seulement | Test de bout en bout bureau ↔ API |
| `infra/compose.local.yaml`, `infra/ci/pipeline.yml` | Non exécutés (Docker et CI indisponibles) | Exécution sur le poste DSIR |
| Paquets DEB / MSI / EXE | Procédure documentée, aucun paquet produit | `docs/BUILD.md` §3 sur chaque OS |

## Ce qui n'est pas commencé

Application Android (bloquée par l'accès réseau), chat WebSocket, collecte et carte GPS, module carburant côté écrans,
maintenance/vidanges/pneus/conducteurs, budget et rapports, catalogue et vues 3D, photo/détourage/reconnaissance, assistant IA,
intégrations OIDC/RH. Leur répartition est dans `docs/AUDIT_ET_MATRICE.md`.

## Différences avec la maquette p. 17 (choix assumés)

- Données fictives différentes de la maquette (12 véhicules) ; la consommation affichée est **calculée** plein-à-plein, pas recopiée.
- « Dernière position » devient « Dernière position (déclarée) » : sans module GPS, aucune position n'est présentée comme suivie (NF-19).
- « Exporter », « Tout voir » et « Voir les entretiens » sont visibles mais **inactifs**, avec infobulle indiquant le lot : aucun faux bouton.
- Niveau de carburant absent affiché « Non renseigné », jamais 0 % (RG-07).
- Couleur d'alerte assombrie à #B83232 pour atteindre 4,5:1 sur fond rosé.

## Point d'arbitrage design

Le texte blanc sur le vert imposé #009B87 atteint **3,48:1**. C'est conforme pour un grand texte, pas pour le texte courant (objectif 4,5:1 du cahier).
Options : conserver #009B87 (fidélité maquette) ou utiliser #007A6A pour le fond des boutons (5,3:1). **Décision à prendre par le BUT.**

## Prochain lot proposé (lot 2), dans l'ordre

1. Ouvrir les domaines Maven/Google/Gradle, puis `mvn verify` et correction de l'API jusqu'au vert.
2. Obtenir le dépôt existant : registre de parité réel (NF-01 à NF-29, Flutter NF-29).
3. Squelette Android Java (connexion, tableau de bord, véhicules) aux jetons p. 18.
4. Conducteurs, affectations, maintenance, vidanges (API + bureau).
5. Agrégat tableau de bord côté API (le client ne calcule plus rien en mode réel).
