# NexFleet CAMTEL

Plateforme de gestion du parc automobile CAMTEL : API Java 21 / Spring Boot, PostgreSQL + PostGIS, client bureau JavaFX
(Windows, Linux) et application Android Java avec collecte GPS de mission et vues 3D Filament. Référence : cahier des charges V4.0 et document d'architecture V5.0.

> **Version 0.7.2 — création et autorisation des missions par le gestionnaire de flotte.**
> Le même gestionnaire peut créer puis autoriser une mission ; le conducteur affecté la démarre ensuite sur son téléphone.
> Un gestionnaire peut lier une fiche à un compte Conducteur actif ou sélectionner ce compte directement dans Nouvelle mission.
> L'import privé de `parc.db` et `console.db` conserve les historiques et intègre les dépenses aux coûts.
> Voir [`docs/MIGRATION_SQLITE.md`](docs/MIGRATION_SQLITE.md) pour l'import, les pièces manquantes et l'activation des anciens comptes.
> Les clients utilisent par défaut `https://api-production-a1d8.up.railway.app` ; la publication des paquets ne vaut pas déploiement serveur.
> Voir [`docs/ETAT_AVANCEMENT.md`](docs/ETAT_AVANCEMENT.md) pour ce qui est prouvé et ce qui reste à essayer sur le terrain.
> Aucune homologation ni avis DSIR/DS n'est revendiqué.

## Arborescence

```
apps/desktop-javafx/       Client bureau JavaFX : 6 profils, parc, échéances, missions, messagerie, assistant, comptes, audit, catalogue 3D — paquets DEB et EXE
apps/android/              Application Android Java : missions, collecteur GPS chiffré, messagerie, catalogue 3D (Filament) — APK
services/nextfleet-domain/ Noyau métier sans dépendance (plein-à-plein, bons, règles CAR, GPS, accès) — compilé et testé
services/nextfleet-api/    API Spring Boot : identité, parc, carburant, missions + jeton GPS, messagerie, assistant, administration, audit — testée (Testcontainers)
services/gps-traccar-adapter/ Passerelle GPS : forward JSON de Traccar, lots Android à jeton de mission, relais OsmAnd — testée sur PostgreSQL
services/chat-realtime/    Messagerie temps réel pilote (WebSocket) — testée sur PostgreSQL
shared/api-contract/       Contrats OpenAPI 3 des lots 1 et 2 — validés
shared/assets3d/           Modèles 3D GLB redistribuables et leurs licences
shared/ui-tokens/          Jetons du design system (p. 17 / p. 18)
infra/db/migrations/       Migrations Flyway V1–V10 — testées sur PostgreSQL 16 + PostGIS
infra/db/tests/            Campagne de tests de la base (63 tests + concurrence) et jeu fictif d'intégration
infra/deploy/camtel/       Kit d'hébergement CAMTEL : Docker Compose, HTTPS (Caddy), sauvegardes — éprouvé en CI
infra/docker/              Images API, passerelle GPS, Traccar (publiées sur ghcr.io)
infra/traccar/             Configuration Traccar (forward JSON vers NexFleet)
docs/                      Audit, registre de parité avec l'ancien NexFleet, design et mouvement, état d'avancement, captures et GIF
tools/                     Construction hors ligne, captures, contrôle des contrastes
```

## Démarrage rapide (sans Maven)

```bash
infra/db/tests/run_db_tests.sh      # base : migrations + tests
tools/build-offline.sh              # noyau, passerelles, bureau : compilation + tests
tools/run-integration-tests.sh      # GPS et messagerie de bout en bout sur PostgreSQL
tools/screenshots.sh                # parcours visuel et captures
```

Construction : [`docs/BUILD.md`](docs/BUILD.md) · installation des paquets DEB / EXE / APK : [`docs/INSTALLATION.md`](docs/INSTALLATION.md) · mobile : [`docs/MOBILE.md`](docs/MOBILE.md).

## Règles non négociables appliquées dans le code

- Le serveur est la seule source de vérité ; les clients n'accèdent jamais à la base.
- Un demandeur ne valide pas son propre bon ; le solde d'un bon est consommé de façon atomique.
- Consommation uniquement plein-à-plein, entrées exposées ; « données insuffisantes » plutôt qu'une valeur inventée.
- Aucune position GPS acceptée hors mission active autorisée ; aucune position périmée affichée « en direct ».
- Une anomalie est un indice à instruire : aucun statut « fraude », aucune sanction automatique.
- Aucun actif 3D publié sans licence revue autorisant la redistribution.
- Données de démonstration refusées en production ; aucun secret ni donnée réelle dans le dépôt.

## Licences tierces embarquées

Modèles 3D : `shared/assets3d/LICENCES.md` (créations originales et Khronos Toy Car CC0). Police Inter (SIL OFL 1.1) : `apps/desktop-javafx/src/main/resources/cm/camtel/nextfleet/desktop/fonts/OFL-1.1-Inter.txt`.

## Mode démonstration : six comptes

Le client bureau se connecte par défaut au serveur de production. `--demo` ouvre la démonstration hors ligne (données fictives, badge DÉMO, mot de passe commun : `demo`).

| Identifiant | Profil | Espace d'accueil |
|---|---|---|
| `gestionnaire` | Gestionnaire flotte | Vue d'ensemble, missions, messages, assistant |
| `conducteur` | Agent ou conducteur | Mes missions (démarrage avec information sur la localisation) |
| `maintenance` | Maintenance | Atelier |
| `controleur` | Contrôleur | Vue d'ensemble, trajets, assistant |
| `admin` | Administrateur technique | Comptes et rôles (aucune donnée métier) |
| `auditeur` | Auditeur | Journal d'audit chaîné (lecture seule) |
