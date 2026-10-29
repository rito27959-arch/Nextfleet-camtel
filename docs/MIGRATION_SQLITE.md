# Import de parc.db et console.db — version 0.7.1

`tools/migrate_legacy.py` lit les deux sources SQLite en lecture seule et produit un SQL transactionnel et un rapport privé. Le dépôt et les images publiques ne doivent contenir ni ces bases, ni le SQL généré, ni les hashes de mots de passe.

```sh
python3 tools/migrate_legacy.py --parc /chemin/prive/parc.db --console /chemin/prive/console.db --output /chemin/prive/import
```

## Conversion

- Chaque ligne et le schéma SQLite sont conservés dans `legacy_import_record` / `legacy_import_batch`, y compris audit, chat, paramètres, champs sans équivalent et compteurs SQLite. L'archive est immuable et n'est pas exposée par l'API.
- Véhicules, conducteurs, échéances des documents, kilométrages, permis, contrôles, vidanges et budgets deviennent des données métier. Les informations d'organisation absentes de la source utilisent l'entité choisie à l'exécution ; direction et affectation libres restent conservées.
- Les catégories de permis absentes restent vides. Les chemins de photos ne remplacent pas les fichiers binaires, à transférer séparément si disponibles.
- Les pleins, maintenances et dépenses historiques sans justificatifs modernes sont conservés dans `legacy_operation`, visibles dans **Coûts → Historique importé** et comptabilisés par `v_cost_entry`. Ils ne créent ni reçu fictif ni visa technique et ne contournent pas les contraintes des nouvelles opérations.
- Les budgets conservent leurs montants trimestriels. Si l'annuel historique diffère de leur somme, le rapport le signale ; l'annuel source reste dans l'archive.
- Les anciens comptes sont importés **suspendus, sans rôle et sans mot de passe actif**, avec les identifiants `legacy.parc.<identifiant>` et `legacy.console.<identifiant>`. Un administrateur doit confirmer l'identité, attribuer le rôle, réinitialiser le mot de passe et activer le compte. Les hashes historiques restent privés dans l'archive. Aucun compte de production n'est remplacé, aucun rôle `user` n'est assimilé automatiquement à `DRIVER`.
- L'auteur des opérations importées est l'opérateur désigné ; les auteurs et dates historiques restent dans l'archive. L'import ajoute un événement au journal actuel sans réécrire sa chaîne d'intégrité.

## Exécution sur PostgreSQL / Railway

1. Déployer l'API 0.7.1 : Flyway applique V10 avant l'import.
2. Sauvegarder PostgreSQL avec `pg_dump --format=custom` et vérifier la restauration sur une base de contrôle. Conserver cette sauvegarde hors dépôt.
3. Tester le SQL sur cette copie avec l'UUID de l'entité CAMTEL et celui d'un opérateur actif. Vérifier les comptes, les totaux et les anomalies du rapport.
4. Exécuter sur la cible par une connexion PostgreSQL authentifiée (DSN dans une variable d'environnement, jamais dans le dépôt) :

```sh
psql "$DATABASE_URL" -X -v ON_ERROR_STOP=1 -v org_id="$NF_IMPORT_ORG" -v actor_id="$NF_IMPORT_ACTOR" -f /chemin/prive/import/import.sql
```

Toute erreur annule la transaction. Les identifiants déterministes empêchent le double import. Une source modifiée après un premier import est refusée et exige une réconciliation ; une collision de matricule, VIN ou immatriculation avec la cible n'écrase rien. Recontrôler les droits, rattachements, comptes d'origine et pièces manquantes avant l'usage opérationnel.

## Comptes conducteurs

Un gestionnaire peut choisir **Conducteurs → Depuis un compte**, ou **Lier un compte** sur une fiche existante. Dans **Nouvelle mission**, les comptes Conducteur non encore liés sont également proposés ; leur fiche est créée à partir de leur identité réelle. Le matricule RH est utilisé s'il existe, sinon l'identifiant du compte. Aucun permis ni numéro de CNI n'est obligatoire pour ce rattachement.

Le serveur vérifie le rôle Conducteur actif, son périmètre, le statut du compte, l'archivage et l'unicité du lien. Il ne permet pas au gestionnaire d'attribuer un rôle. Les deux identités restent intactes lors du rattachement d'une fiche existante.

## Tests

`python3 -m unittest discover -s tools/tests -v` vérifie les sources intactes et l'archive complète. Avec `NF_LEGACY_TEST_DSN` sur une base de test migrée, la suite vérifie l'import PostgreSQL, les totaux, le réimport et l'annulation sur source modifiée. La CI l'exécute avec des données synthétiques uniquement. `ProductionFlowIntegrationTest` vérifie le parcours gestionnaire → compte existant → mission visible par le conducteur ainsi que les refus d'accès.

## Paquets et publication

La CI construit l'APK, le MSI, le ZIP portable Windows et le DEB. Après une hausse de version, un commit sur `main` portant le marqueur `[release]` publie ces paquets uniquement si les tests et constructions requis réussissent. Le lancement manuel avec `publier` et les tags `v*` restent disponibles. Les images serveur sont publiées séparément ; Railway doit utiliser la nouvelle image et passer ses contrôles de santé avant l'import.
