# Lot 2 — Profils, GPS Traccar, messagerie et assistant IA

**20 octobre 2026.** Ce document décrit ce qui a été construit, comment c'est vérifié, et ce qui reste à faire.
Aucune autorisation CAMTEL, aucun avis DSIR/DS et aucune mise en production ne sont revendiqués.

## 1. Les six profils (matrice des habilitations, cahier V4 chap. 03)

Le cahier définit **six** profils, pas cinq. Les six sont réalisés :

| Profil | Espace d'accueil | Ce qu'il peut faire | Ce qui lui est refusé |
|---|---|---|---|
| Gestionnaire flotte | Vue d'ensemble | Parc, conducteurs, missions et trajets, messagerie, assistant, coûts | Comptes et journal d'audit |
| Agent ou conducteur | Mes missions | Démarrer / terminer ses missions (localisation), messages, consulter le parc | Missions des autres, coûts, permis/CNI des collègues |
| Maintenance | Atelier | Véhicules à l'atelier, entretiens, contrôles techniques, messages | Coûts, trajets, conducteurs |
| Contrôleur | Vue d'ensemble | Trajets, anomalies, validation de bons, assistant, export | Demander un bon (distinct du demandeur) |
| Administrateur technique | Comptes et rôles | Comptes, rôles, matrice, activation de l'assistant | **Toute donnée métier** (parc, trajets, messages) |
| Auditeur | Vue d'ensemble | Lecture, journal d'audit chaîné, export | Toute écriture, messagerie, assistant |

**Une seule source de vérité.** La matrice est définie dans les migrations V1 et V5 (`role_permission`). La copie Java (`Role`, `Permission`) est vérifiée par un test qui relit les migrations : `RoleMatrixTest`.

Le masquage dans l'interface n'est pas une sécurité. Les refus sont appliqués à trois niveaux :
- la passerelle (démo ou API) ;
- les déclencheurs PostgreSQL (`nf_has_permission`) ;
- le noyau métier.

**Écrans :**
- connexion avec les six comptes de démonstration ;
- navigation propre à chaque profil, avec une rubrique « À venir » pour les modules non livrés ;
- Réglages → « Vos droits ».

## 2. GPS via Traccar

```
Téléphone (Android, lot 3) ──lot + jeton de mission──▶ Passerelle GPS NexFleet ──OsmAnd (points acceptés)──▶ Traccar
Boîtier véhicule (phase 2) ──protocole natif──▶ Traccar ──forward.type=json──▶ Passerelle GPS NexFleet
                                                          Passerelle ──▶ PostgreSQL : location_point (acceptés)
                                                                                     gps_ingest_rejection (motif, sans position)
```

- **Règle cardinale.** Un point n'est conservé que s'il remplit trois conditions :
  - il a été relevé pendant la période active d'une mission ;
  - il vient d'un émetteur autorisé pour cette mission ;
  - cette autorisation n'est pas révoquée.

  La règle est appliquée deux fois : par `MissionTrackingGate` (noyau), puis par le déclencheur `nf_guard_location_point` (base). Un point refusé n'est **jamais stocké**. Seuls le motif, l'émetteur et l'heure sont journalisés.
- **Format Traccar vérifié dans son code source** (commit `0745a8c` du 19/10/2026) :
  - le corps envoyé est `{position, device}` ;
  - les dates sont au format ISO avec décalage horaire ;
  - la **vitesse est en nœuds** (conversion en m/s) ;
  - l'identifiant métier est `device.uniqueId`.
- **Réponse 200 même en cas de refus.** Traccar réessaie toute réponse qui n'est pas 2xx ; un refus métier ne doit pas être rejoué. La passerelle répond 503 si la base est indisponible, pour que Traccar réessaie.
- **Jeton de mission** (HMAC-SHA256, lié à la mission et à l'émetteur, avec expiration). Un jeton d'une autre mission ne peut pas alimenter la mission en cours.
- **Configuration :**
  - `infra/traccar/traccar.xml` : `forward.type=json`, `forward.url`, `forward.header`, reprises, `osmand.port`, géocodage désactivé. Toutes les clés ont été vérifiées dans `org.traccar.config.Keys`.
  - `infra/compose.local.yaml` : profils `gps` et `chat`.
- **Écrans :**
  - **Missions** (gestionnaire, contrôleur) : liste par état, tracé, position en direct qui respire, arrêts, échelle, statistiques qualifiées par `TrackAnalyzer`, informations de collecte.
  - **Mes missions** (conducteur) : démarrage après une alerte d'information explicite (notice `NOTICE-GPS-v1`), suivi, clôture qui arrête la collecte.
  - **Fond de carte :** aucun n'est chargé. Le choix du serveur de tuiles revient à la DSIR, et la carte l'indique.

## 3. Messagerie

- **Base :**
  - participant actif et `chat:use` vérifiés à chaque envoi ;
  - séquence attribuée sous verrou ;
  - **correctif V5** : une réémission ne consomme plus de numéro (la séquence reste sans trou) ;
  - curseurs « distribué » / « lu » monotones.
- **Noyau :**
  - `Chat` : modèle, contrat de stockage, calcul de l'état d'un message ;
  - `InMemoryChatStore` : stockage de référence ;
  - `Outbox` : file d'envoi côté client qui réutilise le même `clientMessageId`.
- **Passerelle WebSocket pilote** (`services/chat-realtime`). Le premier message envoyé est `{"type":"auth","token":…}` : le jeton de session est stocké haché, il ne passe jamais dans l'URL. Les autres trames :
  - `send` → `ack` (après validation en base), puis `message` diffusé aux seuls participants autorisés ;
  - `sync` (`afterSeq`) → `history` ;
  - `delivered` / `read` → `receipt` ;
  - `error` (`UNAUTHENTICATED`, `FORBIDDEN`, `INVALID`, `UNAVAILABLE`).

  La cible du cahier reste Spring WebSocket dans l'API ; le stockage et les règles seront les mêmes.
- **Écran Messages (façon iMessage) :**
  - non lus en pastille bleue ;
  - bulles regroupées, séparateurs horaires ;
  - état sous la dernière bulle envoyée (Envoyé, Distribué, Lu) ;
  - « Non envoyé · Toucher pour réessayer » en cas de coupure.

  L'auditeur et l'administrateur n'y ont pas accès. Le journal enregistre qu'un message a été envoyé, **jamais son contenu**.

## 4. Assistant IA (lecture seule)

- **Désactivé par défaut.** L'activation suit quatre règles :
  - il faut une référence de validation de sécurité (DS / RSSI) ;
  - un signataire distinct de l'administrateur ;
  - seul le profil `admin:settings` décide ;
  - les décisions sont conservées en ajout seul (`assistant_activation`).
- **Moteur `RULES_FR_V1`.** Il comprend les questions en français par règles explicites, déterministes et testées : coûts, échéances, missions en cours, consommation, disponibilité. Toute demande d'action (supprimer, valider, modifier…) est refusée.
- **Données.** Seul l'accès `FleetFacts` est autorisé : des lectures déjà restreintes au périmètre de l'utilisateur. Chaque réponse donne les **chiffres, la période, les filtres et les références des pièces**. Un véhicule hors périmètre est signalé « aucun véhicule correspondant », sans révéler s'il existe ailleurs.
- **Journal.** Chaque interrogation est enregistrée (`assistant_run`). La base refuse toute réponse servie si l'assistant est désactivé ou si la permission manque.
- **Modèle de langage.** Aucun n'est intégré. LangChain4j avec un modèle local approuvé pourra remplacer la compréhension des questions après validation de sécurité, en restant limité aux mêmes outils en lecture seule.

## 5. Preuves exécutées

| Vérification | Commande | Résultat |
|---|---|---|
| Base (V1–V5) | `infra/db/tests/run_db_tests.sh` | **62 tests** + concurrence CAR-01 |
| Noyau métier | `tools/build-offline.sh` | **42 tests** (profils, passerelle de mission, messagerie, assistant dont le scénario E) |
| Passerelle GPS (unitaires) | idem | 4 tests (format Traccar, OsmAnd, jeton) |
| Bureau | idem | **17 tests** (navigation par profil, refus côté passerelle, cycle de mission, messagerie, assistant, journal chaîné) |
| GPS de bout en bout | `tools/run-integration-tests.sh` | **5 tests sur PostgreSQL** : forward Traccar accepté / doublon / refus, lot Android filtré puis relayé à un faux Traccar, jeton d'une autre mission refusé, plus aucune position après la clôture |
| Messagerie de bout en bout | idem | **4 tests sur PostgreSQL** avec de vrais clients WebSocket : temps réel, coupure + `afterSeq`, réémission sans doublon ni trou, refus de l'intrus, de l'auditeur, d'une session invalide ou suspendue |
| Contrats | `openapi-spec-validator` | `openapi.yaml` et `openapi-lot2.yaml` valides |
| Captures | `NF_TOUR=lot2 tools/screenshots.sh docs/design-reference/captures-lot2` | 21 captures, 2 GIF |

## 6. Limites connues (non masquées)

- **Traccar réel non exécuté.** Le binaire n'est pas téléchargeable ici. Le format a été lu dans son code source, et un faux serveur OsmAnd vérifie le relais.
- **Pas de téléphone.** L'application Android et son collecteur sont prévus au lot 3. En démonstration, le poste simule le téléphone et les tracés sont fictifs (itinéraires approximatifs).
- **Mise à jour 0.3.0** : l'API Spring Boot est compilée et testée en CI ; le client bureau utilise ses routes en mode serveur (voir ETAT_AVANCEMENT.md).
- **Bibliothèque WebSocket.** La passerelle pilote utilise Java-WebSocket ; ici, le fichier jar vient d'un paquet Ubuntu. La version Maven (1.5.7) est à confirmer dans la nomenclature DSIR.
- **Fond de carte, seuils GPS et notice d'information.** Ce sont des décisions DSIR / DS / métier.
