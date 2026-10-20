# Registre de parité — ancien dépôt `rito27959-arch/nexfleet` → NexFleet Java

**Audit du 20 octobre 2026, commit `fb6b4a2`** (« App mobile : cible API = serveur Render »).
Exigence cahier V4 chap. 08 / 19 : aucune fonction historique n'est retirée sans équivalent prouvé.

## 1. Ce que contient l'ancien dépôt

| Composant | Technologie | Volume | État constaté |
|---|---|---|---|
| `backend/` | Python Flask, SQLite, JWT HS256, PBKDF2, ReportLab | `app.py` 2 475 lignes, 84 routes | **78 tests pytest exécutés : 78 réussis** (20/10/2026) |
| `frontend/` | SPA HTML/CSS/JS (+ source TypeScript) | 12 vues | Non exécuté ici (navigateur) |
| `nexfleet_app/` | Flutter (Android) | 29 fichiers Dart, 8 écrans | Non compilé ici (SDK Flutter absent) |
| `console/` | Flask, console d'administration de la base | 22 routes | Analysé, non exécuté |
| `data/parc.db` | SQLite **versionné** | 86 véhicules, 5 comptes, 130 lignes budget, 623 audits, 42 messages | ⚠️ voir §5 |
| `render.yaml` | Déploiement Render (offre gratuite) | — | Hébergement hors CAMTEL |

## 2. Correspondance fonctionnelle

Légende statut nouveau : ✅ équivalent livré et testé · 🟢 base de données prête (testée) · 🟡 écrit non compilé · ⬜ à faire (lot).

| Fonction de l'ancien dépôt | Routes / écrans d'origine | Exigence V4 | Nouveau NexFleet | Lot |
|---|---|---|---|---|
| Connexion, profil, mot de passe | `/api/auth/login`, `/me`, `/password` ; écran login web + Flutter | NF-01 | 🟡 API · ✅ écran bureau | 1–2 |
| Inscription publique, **1er inscrit = admin** | `/api/auth/register` | NF-02 interdit | **Non reprise** (amorçage administrateur contrôlé) | — |
| Utilisateurs et rôles (admin/user) | `/api/users*` | NF-02 | 🟢 rôles + périmètre · ⬜ écrans | 2 |
| Tableau de bord (KPI, donut carburant, marques, contrôles/mois, coûts) | `/api/stats` ; vue `dashboard` | NF-20 | ✅ bureau (démo) · ⬜ agrégat API | 2 |
| Véhicules CRUD, recherche, filtres statut/carburant | `/api/vehicles*` ; vue `vehicles` | NF-10 | ✅ bureau · 🟡 API | 1 |
| Suppression physique d'un véhicule | `DELETE /api/vehicles/<id>` | RG-04 interdit | **Remplacée** par l'archivage | — |
| Échéances vignette / assurance / CT / carte grise, compte à rebours | champs `*_exp` ; badges clignotants | NF-11 | 🟢 V4 · ✅ écran Échéances (démo) | 2 |
| Direction, affectation, châssis, moteur, CV, carte grise | colonnes `vehicles` | NF-10 | 🟢 V4 | 1 |
| Photo du véhicule + miniature | `/api/vehicles/<id>/photo` | NF-18, écrans 05/11 | 🟢 `vehicle_media` · ⬜ écrans | 4 |
| Relevés kilométriques | `/api/vehicles/<id>/odometer` | NF-17 | 🟢 + règle CAR-03 ✅ | 2 |
| Documents du véhicule | `/api/vehicles/<id>/documents` | NF-18 | 🟢 V4 `vehicle_file` | 3 |
| Pneumatiques par position | `/api/vehicles/<id>/tires`, `/api/tires/*` | NF-16 | 🟢 V4 (historique, pas d'écrasement) | 2 |
| Conducteurs CRUD, permis, CNI, échéances | `/api/drivers*` ; vue `drivers` ; écran Flutter | NF-12 | 🟢 V4 · ✅ écran bureau (démo) | 2 |
| Contrôles 12 points, 3 états, historique, fiche PDF | `/api/controls*`, `/api/vehicles/<id>/pdf` | NF-13 | 🟢 V4 (12 points en référentiel) · ⬜ PDF | 2 |
| Maintenance, coûts, garages, catégories budgétaires | `/api/maintenance*` | NF-14 | 🟢 V4 · ⬜ écrans | 2 |
| Vidanges (huile, filtres, fluides, intervalle, prochaine échéance), stats, PDF | `/api/vidanges*` | NF-15 | 🟢 V4 · ⬜ écrans | 2 |
| Calendrier des échéances | `/api/calendar/events` ; vue `calendar` | NF-11 | ⬜ | 2 |
| Maintenance prédictive (intervalle moyen constaté) | `/api/maintenance/forecast*` | NF-23 | ⬜ (méthode à exposer, pas de précision inventée) | 3 |
| Budget : import plan d'achat XLSX, prévu/réalisé par trimestre, export XLSX/PDF | `/api/budget/*` | NF-21 | 🟢 V4 `budget_plan_line` · ⬜ import | 3 |
| Analytique TCO, coût/km | `/api/tco` ; vue `analytics` | NF-22 | ⬜ — corriger le défaut D3 | 3 |
| Assistant (règles locales + OpenRouter/Claude) | `/api/assistant/*` | NF-27, NF-81/82 | ⬜ — fournisseur externe interdit par défaut | 4 |
| Digest e-mail quotidien SMTP | `/api/notify/*` | NF-24 | 🟢 outbox · ⬜ | 3 |
| Rapports PDF (parc, mensuel, fiche, vidanges, budget) | `/api/*/pdf` ; vue `reports` | NF-25 | ⬜ | 3 |
| Import/export Excel « intelligent », gabarit | `/api/import*`, `/api/export` | NF-26 | ⬜ (validation à blanc obligatoire) | 3 |
| Journal d'activité | `/api/audit` ; vue `activity` | NF-28 / NF-53 | 🟢 journal chaîné ✅ | 1 |
| Notifications internes | `/api/notifications` | NF-24 | ⬜ | 3 |
| Carte des véhicules (lat/lng saisies) | `/api/vehicles/locations` ; vue `maps` | NF-19 | ✅ « position déclarée » · ⬜ carte | 3 |
| Sauvegarde : téléchargement de la base entière | `/api/backup` | NF-28, SEC-06 | **Non reprise telle quelle** (sauvegarde serveur chiffrée) | 5 |
| Données de démonstration | `/api/demo` | NF-04 | ✅ séparation stricte | 1 |
| Paramètres (SMTP, clé IA, seuil d'alerte, prévisions) | `/api/settings` | NF-03 | ⬜ — secrets jamais renvoyés au client | 2 |
| Console d'administration (tables, **SQL libre en écriture**, utilisateurs, sauvegardes, rotation de secret) | `console/` | SEC-02, SEC-10 | **Non reprise** : pas d'exécution SQL arbitraire via le web | — |
| App mobile Flutter : tableau de bord, véhicules + fiche, conducteurs, maintenance, vidanges, profil | `nexfleet_app/` | NF-29 | Maintenue jusqu'au remplacement Android Java | 2+ |

## 3. Défauts relevés dans l'ancien code (à ne pas reproduire)

| # | Défaut | Emplacement | Correction dans la refonte |
|---|---|---|---|
| D1 | Le premier compte inscrit devient administrateur ; inscription publique | `register()` | Amorçage administrateur hors ligne ; pas d'inscription publique (NF-02) |
| D2 | Suppressions physiques (véhicules, contrôles, maintenance, conducteurs) avec `ON DELETE CASCADE` | routes `DELETE`, schéma | Archivage ; historique conservé (RG-04) |
| D3 | « Coût/km » = coût total ÷ **kilométrage du compteur**, pas ÷ distance parcourue sur la période | `stats()` | Coût/km seulement si distance fiable (NF-22) |
| D4 | Mois du graphe calculés par pas de 31 jours : un mois peut être sauté ou doublé | `stats()` `last6` | Calcul par `YearMonth` (fait dans le client bureau) |
| D5 | Montants en `REAL` (virgule flottante) | schéma | `numeric` / `BigDecimal` XAF (RG-06) |
| D6 | CORS `*` sur toute l'API | `app.py` l.32 | Pas de CORS pour clients natifs |
| D7 | Jeton JWT valable 7 jours, non révocable | `auth.py` | Session serveur révocable (NF-01) |
| D8 | Clé IA stockée en clair dans la base et envoyée à un fournisseur externe | `assistant.py`, `settings` | Coffre de secrets ; modèle local approuvé (NF-82) |
| D9 | Console web exécutant du SQL en écriture | `console/app.py` | Supprimée du périmètre |
| D10 | Sauvegarde = téléchargement de toute la base par l'administrateur | `/api/backup` | Sauvegarde côté serveur chiffrée, accès journalisé |
| D11 | Dates stockées en texte local, sans fuseau | schéma | `timestamptz` UTC, affichage Africa/Douala (RG-05) |

## 4. Éléments de design repris de l'ancien produit

Compteurs animés des KPI, graphiques qui se dessinent, badges d'échéance qui attirent l'œil, toasts, modales « ressort »,
préchargeur, retour haptique (Flutter). Ils sont repris dans le client JavaFX **dans la charte claire NexFleet du cahier V4**
(le thème sombre « aurora / glassmorphism » de l'ancien produit n'est pas la référence p. 17).

## 5. Sécurité : données et secrets publiés (action requise)

Le dépôt est **public** et versionne `data/parc.db` (avec une clé API OpenRouter renseignée, un champ de clé Google Maps,
les empreintes de mot de passe de 5 comptes, 86 véhicules, l'historique d'assistant) et `console/.console-secret`.
Le `.gitignore` exclut `data/*` **sauf** `parc.db`. Actions : révoquer les clés, changer les mots de passe, rendre le dépôt privé,
purger l'historique Git (`git filter-repo`) puis faire tourner tous les secrets (cahier V4 chap. 14 « assainissement préalable »).
Aucune de ces données n'est recopiée dans `Nextfleet-camtel`.
