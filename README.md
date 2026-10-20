# NexFleet CAMTEL

Plateforme de gestion du parc automobile CAMTEL : API Java 21 / Spring Boot, PostgreSQL + PostGIS, client bureau JavaFX
(Windows, Linux) et application Android Java (à venir). Référence : cahier des charges V4.0 et document d'architecture V5.0.

> **Statut : lot 1 — fondations.** Voir [`docs/ETAT_AVANCEMENT.md`](docs/ETAT_AVANCEMENT.md) pour ce qui est réellement prouvé.
> Document de conception et code en cours de réalisation : ni homologation, ni avis DSIR/DS, ni mise en production.

## Arborescence

```
apps/desktop-javafx/       Client bureau JavaFX (connexion, tableau de bord, véhicules, échéances, conducteurs) — compilé et testé
services/nextfleet-domain/ Noyau métier sans dépendance (plein-à-plein, bons, règles CAR, GPS, accès) — compilé et testé
services/nextfleet-api/    API Spring Boot (identité, parc, carburant, audit) — écrite, NON compilée
shared/api-contract/       Contrat OpenAPI 3 du lot 1 — validé
shared/ui-tokens/          Jetons du design system (p. 17 / p. 18)
infra/db/migrations/       Migrations Flyway V1–V4 — testées sur PostgreSQL 16 + PostGIS
infra/db/tests/            Campagne de tests de la base (43 tests + concurrence)
infra/compose.local.yaml   PostGIS, API, Traccar en local (non exécuté)
docs/                      Audit, registre de parité avec l'ancien NexFleet, design et mouvement, état d'avancement, captures et GIF
tools/                     Construction hors ligne, captures, contrôle des contrastes
```

## Démarrage rapide (sans Maven)

```bash
infra/db/tests/run_db_tests.sh      # base : migrations + tests
tools/build-offline.sh              # noyau + bureau : compilation + tests
tools/screenshots.sh                # parcours visuel et captures
```

Détails et paquets DEB/MSI/EXE : [`docs/BUILD.md`](docs/BUILD.md).

## Règles non négociables appliquées dans le code

- Le serveur est la seule source de vérité ; les clients n'accèdent jamais à la base.
- Un demandeur ne valide pas son propre bon ; le solde d'un bon est consommé de façon atomique.
- Consommation uniquement plein-à-plein, entrées exposées ; « données insuffisantes » plutôt qu'une valeur inventée.
- Aucune position GPS acceptée hors mission active autorisée ; aucune position périmée affichée « en direct ».
- Une anomalie est un indice à instruire : aucun statut « fraude », aucune sanction automatique.
- Aucun actif 3D publié sans licence revue autorisant la redistribution.
- Données de démonstration refusées en production ; aucun secret ni donnée réelle dans le dépôt.

## Licences tierces embarquées

Police Inter (SIL OFL 1.1) : `apps/desktop-javafx/src/main/resources/cm/camtel/nextfleet/desktop/fonts/OFL-1.1-Inter.txt`.
