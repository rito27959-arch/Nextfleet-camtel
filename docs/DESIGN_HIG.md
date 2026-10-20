# NexFleet × Apple Human Interface Guidelines

Refonte du lot 1.2 (20 octobre 2026). Chaque choix ci-dessous renvoie à une règle des HIG consultées le jour même.
NexFleet tourne sous Windows et Linux : on applique les **principes** d'Apple, sans copier ses éléments propriétaires
(exigence du cahier V4, chap. 15 et 40).

## 1. Typographie

| Règle HIG | Application NexFleet |
|---|---|
| Échelle Dynamic Type iOS ; catégorie « Small » : Large Title 32, Title 1 26, Title 2 20, Title 3 18, Headline 15 semi-gras, Body 15, Callout 14, Subhead 13, Footnote 12, Caption 11 | Classes `.large-title` … `.caption2` du CSS ; tous les écrans les utilisent |
| Taille par défaut iOS 17 pt, minimum 11 pt | Corps 15 px (bureau à 1×), jamais sous 11 px |
| Police système SF Pro, avec variantes optiques Text / Display | **SF Pro ne peut pas être livrée** : sa licence la réserve aux maquettes pour plateformes Apple et interdit de l'embarquer. Remplacée par **Inter** (OFL) avec la même logique : *Inter Display* pour les titres ≥ 20 px, *Inter* pour le texte |
| Éviter les graisses fines | Regular, Medium, Semibold, Bold uniquement |

## 2. Couleurs

| Règle HIG | Application |
|---|---|
| Couleurs système avec variantes clair / sombre / contraste renforcé | Rouge, orange, vert, bleu, indigo, gris 1–6 d'Apple (valeurs de juin 2025) ; pour le **texte**, les valeurs « contraste renforcé » (ex. rouge #E9152D, vert #008932, orange #C55300, bleu #1E6EF4) |
| Couleur d'accent pour les boutons proéminents | Accent = menthe accessible d'Apple **#008575** : blanc à 4,55:1 — résout l'écart de contraste du vert #009B87 signalé au lot 1 ; #009B87 reste la couleur de marque (indicateurs, graphiques) |
| Libellés primaire / secondaire / tertiaire | `-nf-text` #102434, `-nf-muted` #616E78 (≥ 4,5:1 sur fond et carte), `-nf-tertiary` décoratif uniquement |
| Fonds « grouped » primaire / secondaire | Fond #F2F4F7, cartes blanches ; sombre #0D1114 / #1A1F24 |
| Ne pas compter sur la couleur seule | Chaque statut a un libellé et un point ; chaque échéance un texte (« J-6 », « Expiré depuis 6 j ») |

`python3 tools/check_contrast.py` vérifie 16 couples (clair et sombre) : tous conformes.

## 3. Boutons

| Règle HIG | Application |
|---|---|
| Style + contenu + rôle | Styles `btn-filled`, `btn-tinted`, `btn-gray`, `btn-plain`, `btn-destructive` ; tailles 28 / 36 / 44 px |
| Un ou deux boutons proéminents par vue au plus | Un seul bouton plein par écran (Nouveau véhicule, Se connecter, Ajouter le véhicule) |
| Toujours un état pressé | Couleur pressée + réduction à 0,97 puis retour en ressort |
| Rôle principal jamais destructif | « Archiver » est une action délibérée : style normal, pas rouge |
| Indicateur d'activité dans le bouton + libellé alternatif | « Se connecter » → indicateur + « Connexion… » ; « Ajouter le véhicule » → « Ajout… » |
| Ellipse quand le bouton ouvre une autre vue (macOS) | « Archiver le véhicule… » |

## 4. Alertes

| Règle HIG | Application (`AlertDialog`) |
|---|---|
| Uniquement pour une information critique avec action utile ; pas pour une simple information | Utilisée pour : confirmer la déconnexion, confirmer l'archivage, échec d'archivage, mot de passe oublié. Les erreurs réseau restent **en ligne** (bandeau), comme l'indicateur de connexion de Mail |
| Titre précis, au plus deux lignes ; message bref qui n'explique pas les boutons | « Archiver BK 7280 LT ? » / « Le véhicule disparaîtra du parc actif. Son historique et ses pièces restent consultables. » |
| Au plus trois boutons ; Annuler à gauche d'une rangée, en bas d'une pile ; action probable à droite / en haut | Ordonnancement automatique selon le rôle ; deux boutons côte à côte, trois empilés |
| « Annuler » pour annuler, « OK » seulement pour une alerte informative ; jamais « Oui / Non » | Respecté |
| Échap annule | Échap = Annuler, Entrée = action préférée ; le focus revient à l'élément d'origine |

## 5. Notifications

| Règle HIG | Application |
|---|---|
| Titre court, casse de titre, sans ponctuation finale ; corps en phrases complètes | Vérifié par un test automatisé (`DesktopLogicTest.notifications`) |
| Pas de données sensibles | Échéances et statuts uniquement ; aucune pièce d'identité affichée |
| Application au premier plan : pas de bannière intrusive, incrémenter un badge | Les nouvelles échéances incrémentent le **badge de la cloche** ; les bannières ne confirment que les actions de l'utilisateur (véhicule ajouté, archivé) |
| Badge = nombre de notifications non lues uniquement | Badge de la cloche = non lues ; le compteur de la barre latérale indique les échéances à traiter (information distincte, libellée) |
| Centre de notifications groupé | Panneau « Notifications » : Aujourd'hui / Hier / Plus tôt, filtre Toutes / Non lues, « Tout marquer comme lu », point bleu « non lu », heure relative |
| Réglages par type | Réglages → Notifications : Échéances, Entretiens, Carburant |

## 6. Autres composants

- **Contrôle segmenté** à pastille blanche glissante (période du graphique, thème, filtre des notifications).
- **Interrupteur** 46×28, vert quand activé, pastille en ressort, accessible au clavier et aux lecteurs d'écran.
- **Listes groupées inset** (Réglages) : tuiles d'icônes colorées, filets inset alignés sur le texte, en-têtes en capitales et pieds explicatifs.
- **Feuilles** : barre « Annuler · titre · Ajouter » du formulaire, bouton de fermeture rond gris.
- **États vides** à la manière de *ContentUnavailableView* : grand symbole gris, titre, explication.
- **Connexion** : icône d'application, grand titre, champs regroupés séparés par un filet, bouton de 44 px.

## 7. Preuves

`tools/screenshots.sh` produit `docs/design-reference/captures-lot1/` : 23 captures (clair et sombre) et 6 GIF
(entrée du tableau de bord, thème, fiche en ressort, échéances, centre de notifications, apparition d'une alerte).

## Sources

- [Apple HIG — Typography](https://developer.apple.com/design/human-interface-guidelines/typography)
- [Apple HIG — Color](https://developer.apple.com/design/human-interface-guidelines/color)
- [Apple HIG — Buttons](https://developer.apple.com/design/human-interface-guidelines/buttons)
- [Apple HIG — Alerts](https://developer.apple.com/design/human-interface-guidelines/alerts)
- [Apple HIG — Notifications](https://developer.apple.com/design/human-interface-guidelines/notifications)
- [Apple — Fonts for Apple platforms (licence San Francisco)](https://developer.apple.com/fonts/)
