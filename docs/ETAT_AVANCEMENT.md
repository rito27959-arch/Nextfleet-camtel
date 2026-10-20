# État réel d'avancement — Lot 2

**20 octobre 2026.** Ce document distingue ce qui a été exécuté et vérifié de ce qui est seulement écrit.
Aucune autorisation CAMTEL, aucun avis DSIR ou DS et aucune mise en production ne sont revendiqués.

## Ce qui fonctionne réellement (preuves exécutées)

| Élément | Commande | Résultat obtenu |
|---|---|---|
| Migrations SQL V1–V5 sur PostgreSQL 16 + PostGIS 3.4 | `infra/db/tests/run_db_tests.sh` | 5 migrations appliquées sur base vierge (V5 = profils, GPS Traccar, chat, assistant) |
| Tests d'intégrité de la base | idem | **62 tests réussis**. Lot 1 : 12 points, maintenance, bons, sessions, journal chaîné, licence 3D… Lot 2 : six profils, admin sans pouvoir métier, auditeur sans écriture ni chat, chat sans doublon ni trou, curseurs monotones, rejets GPS sans position, activation de l'assistant sous conditions, interrogations journalisées |
| Concurrence sur un bon (CAR-01) | idem | Un seul plein accepté sur deux simultanés ; solde jamais dépassé |
| Noyau métier Java 21 | `tools/build-offline.sh` | `-Xlint:all -Werror` ; **42 tests JUnit 5 réussis** |
| Passerelle GPS Traccar | idem + `tools/run-integration-tests.sh` | **9 tests** dont 5 de bout en bout sur PostgreSQL |
| Messagerie temps réel (WebSocket pilote) | `tools/run-integration-tests.sh` | **4 tests de bout en bout** sur PostgreSQL avec de vrais clients WebSocket |
| Client JavaFX | `tools/build-offline.sh` | **17 tests réussis** (lot 1 + profils, missions, messagerie, assistant, journal chaîné) |
| Parcours visuels | `tools/screenshots.sh` (lot 1) ; `NF_TOUR=lot2 tools/screenshots.sh …` | Lot 1 : 23 captures, 6 GIF. Lot 2 : **21 captures, 2 GIF** dans `docs/design-reference/captures-lot2/` |
| Contrats OpenAPI | `openapi-spec-validator` | `openapi.yaml` (lot 1) et `openapi-lot2.yaml` (12 chemins) valides |
| Contrastes | `python3 tools/check_contrast.py` | 16 couples conformes |
| Ancien dépôt `rito27959-arch/nexfleet` | `pytest` | 78 tests réussis ; registre de parité : `docs/REGISTRE_PARITE.md` |

Détail du lot 2 : **`docs/LOT2_GPS_CHAT_IA_PROFILS.md`**.

## Ce qui est écrit mais pas encore prouvé

| Élément | Statut | Condition pour le déclarer livré |
|---|---|---|
| API Spring Boot (lot 1 + contrat lot 2) | **Non compilée** : Maven Central bloqué | `mvn verify` vert, y compris Testcontainers |
| Traccar Server réel | Configuration `infra/traccar/traccar.xml` écrite et clés vérifiées dans le code source ; **binaire non exécuté** | Traccar démarré, position d'un téléphone de test reçue par la passerelle |
| Messagerie dans l'API (Spring WebSocket) | Protocole et stockage prouvés par la passerelle pilote | Portage dans l'API, WSS derrière le frontal DSIR |
| `infra/compose.local.yaml`, `infra/ci/pipeline.yml` | Non exécutés (Docker et CI indisponibles) | Exécution sur le poste DSIR |
| Paquets DEB / MSI / EXE | Procédure documentée, aucun paquet produit | `docs/BUILD.md` §3 sur chaque OS |

## Ce qui n'est pas commencé

Application Android et collecteur GPS (bloqués par l'accès réseau), pièces jointes du chat, écrans carburant / maintenance /
budget / rapports, catalogue et vues 3D, photo / détourage / reconnaissance, modèle de langage local pour l'assistant,
intégrations OIDC / RH. Répartition : `docs/AUDIT_ET_MATRICE.md`.

## Décisions attendues

| Décision | Qui | Impact |
|---|---|---|
| Serveur de tuiles (interne ou OpenStreetMap selon licence) | DSIR | Fond de carte des écrans Missions |
| Seuils GPS (précision, saut, arrêt, « en direct ») | Métier + DS | Remplacent `TrackPolicy.demoDefaults()` |
| Texte de la notice d'information GPS (`NOTICE-GPS-v1`) | DS + juridique | Affichée avant chaque démarrage de mission |
| Validation de sécurité de l'assistant | DS / RSSI | Référence exigée pour l'activer |
| Version figée de Traccar et de Java-WebSocket | DSIR (nomenclature) | Compose et pom |
| Marque « NexFleet » / « Nextfleet » | BUT | Libellés |

L'arbitrage de contraste du lot 1 est **résolu** (lot 1.2 : boutons pleins sur la menthe accessible #008575, 4,55:1).

## Prochain lot proposé (lot 3), dans l'ordre

1. Ouvrir les domaines Maven, Google et Gradle, puis lancer `mvn verify` et corriger l'API jusqu'à ce que tout passe.
2. Porter missions, GPS, messagerie et assistant dans l'API (même stockage, mêmes règles).
3. Application Android Java : connexion, Mes missions, collecteur GPS à tampon chiffré, messagerie.
4. Démarrer Traccar réel avec un téléphone de test (scénario B complet).
5. Écrans carburant et maintenance (tables déjà prêtes en base).
