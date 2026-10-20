# Design et mouvement — client bureau NexFleet

Référence : cahier V4 chap. 15, 16 et 40 (maquette p. 17), jetons `shared/ui-tokens/tokens.json`.
Objectif : la précision perçue des grandes applications iOS, **sans copier leurs éléments** et en restant dans la charte NexFleet.

## Principes

1. **Le contenu d'abord.** Une seule action primaire par écran ; hiérarchie surtitre → titre → contexte → indicateurs → détail.
2. **Le mouvement explique, il ne décore pas.** Chaque animation indique une origine ou un changement d'état.
3. **Jamais d'attente imposée.** Durées 180–280 ms ; réduction de mouvement = état final immédiat (bouton dans la barre supérieure, option `--reduce-motion`).
4. **Honnêteté de l'information.** Une animation ne masque jamais une erreur, un droit refusé ou une donnée absente.

## Catalogue des mouvements (classe `Motion`)

| Mouvement | Durée / courbe | Usage |
|---|---|---|
| Entrée d'écran | 240 ms fondu + 280 ms montée de 8 px, décélération (0.2, 0.8, 0.2, 1) | Chaque changement de section |
| Apparition échelonnée | +35 ms par élément, plafonnée à 8 éléments | Cartes du tableau de bord, échéances, conducteurs |
| Compteurs | 720 ms, décélération ; texte final exact | KPI, synthèse des échéances (repris de l'ancien NexFleet) |
| Anneau et jauges | 700–900 ms | Taux de disponibilité, niveaux de carburant |
| Courbe qui se dessine | 720 ms de gauche à droite, axes fixes | Graphique de consommation, micro-courbes |
| Indicateur de navigation | 360 ms, ressort amorti (ζ ≈ 0,72) | Pastille verte qui glisse sous l'entrée active |
| Feuille latérale | 420 ms ressort à l'ouverture, 180 ms à la fermeture, voile en fondu | Fiche véhicule, ajout de véhicule ; Échap ou clic sur le voile pour fermer |
| Pression | 0,97 puis retour en ressort | Navigation, boutons de la barre supérieure, connexion |
| Survol | soulèvement de 2 px + ombre renforcée | Cartes |
| Thème clair/sombre | fondu enchaîné 240 ms | Bouton lune |
| Toast | entrée en ressort, 3,2 s, sortie en fondu | Confirmation d'enregistrement |
| Refus de connexion | secousse horizontale 330 ms | Identifiants invalides (convention iOS) |
| Échéance expirée | respiration lente 1,8 s | Puce « Expiré depuis n j » (badges clignotants de l'ancien produit, adoucis) |
| Chargement | squelettes qui respirent | Toutes les listes et cartes |

## Preuves

`tools/screenshots.sh` exécute le parcours réel et produit dans `docs/design-reference/captures-lot1/` :
16 captures 1440×900 et 4 GIF enregistrés image par image depuis l'application (entrée du tableau de bord,
bascule de thème, ouverture en ressort de la fiche, entrée de l'écran Échéances).
Le rendu logiciel sous Xvfb est plus lent qu'un poste réel : la cadence des GIF est indicative.

## Limites connues

- JavaFX n'expose ni flou d'arrière-plan performant ni espacement des lettres en CSS : la barre supérieure est translucide sans flou.
- `:focus-visible` n'existe qu'à partir de JavaFX 19 ; sous JavaFX 11 (recette hors ligne) l'anneau de focus clavier des boutons n'apparaît pas — à vérifier sous JavaFX 21.
- Contraste du texte blanc sur le vert #009B87 : 3,48:1 (arbitrage BUT en attente, voir `ETAT_AVANCEMENT.md`).
