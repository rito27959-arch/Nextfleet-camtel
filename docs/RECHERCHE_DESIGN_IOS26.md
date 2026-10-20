# Recherche : refonte « niveau iOS 26 » de NexFleet (bureau et Android)

**20 octobre 2026.** Synthèse des sources officielles Apple (WWDC25, Human Interface Guidelines), des retours
d'accessibilité publiés depuis la sortie d'iOS 26 et de l'observation d'apps de référence. Elle sert de base aux
décisions de la refonte (section 4). Les sources sont listées en fin de document.

## 1. Ce qu'Apple prescrit avec Liquid Glass (WWDC25, HIG)

**Un matériau réservé à la couche de navigation.** Liquid Glass est un matériau adaptatif pour les **contrôles et
éléments de navigation** qui « flottent au-dessus du contenu ». Il ne doit pas servir pour le contenu lui-même. Il reste
limité aux éléments les plus importants (sessions 284 et 323, guide Acorn de Mozilla).

**Pas de verre sur du verre.** Le verre ne peut pas échantillonner un autre verre. Plans masque ses boutons flottants
quand une feuille s'agrandit, précisément pour éviter la superposition.

**Teinte uniquement pour le sens.** Les icônes deviennent monochromes dans les barres. La couleur est réservée à
l'appel à l'action ou à l'étape suivante, jamais à la décoration. « Créer l'emphase par le regroupement et la mise en
page, pas par la couleur » (session 356).

**Formes.** Il en existe trois : fixe, capsule (rayon = demi-hauteur) et **concentrique** (rayon de l'enfant = rayon du
parent − marge). Les boutons à bordure sont des capsules par défaut. Un bouton en bas d'une feuille est concentrique avec
ses coins.

**Typographie.** Elle est plus grasse et alignée à gauche. Tailles iOS par défaut : grand titre 34/41 gras, titre 1
28/34, titre 2 22/28, titre 3 20/25, titre de section (« headline ») 17/22 semi-gras, corps 17/22, légende 13 à 11.
Minimum 11 pt sur iOS, 10 pt sur macOS, corps par défaut 13 pt sur macOS.

**Barres.**
- La barre d'onglets de l'iPhone flotte et peut se réduire au défilement. Un accessoire persistant peut s'y loger
  (lecture en cours, trajet en cours).
- Les actions propres à un écran n'y vont pas.
- Les boutons des barres sont regroupés par fonction : les icônes partagent une même capsule de verre, un texte a la
  sienne.
- L'action principale (Terminé) est séparée et teintée. Les actions secondaires vont dans un menu.
- La recherche descend en bas sur iPhone.

**Effet de bord de défilement.** Un fondu flou léger sous les barres flottantes, pour la lisibilité seulement. Il en
existe deux styles : « soft » (par défaut) et « hard », pour les en-têtes de tableau épinglés et les écrans denses. Un
seul style par vue.

**Feuilles.**
- À hauteur partielle, elles sont décollées des bords, en verre, avec les coins qui épousent l'écran.
- À pleine hauteur, elles deviennent opaques.
- Pas de voile sombre si la tâche est parallèle (carte + panneau), un voile si elle interrompt.
- Menus et feuilles naissent du bouton qui les ouvre.

**Interaction.** Retour par balayage n'importe où dans le contenu. Transitions interruptibles. Les contrôles « gonflent »
au toucher.

## 2. Ce que la première année d'iOS 26 a appris (retours publiés)

**Contraste.**
- Infinum a mesuré des contrastes de **1,5:1** sur certains écrans de la bêta, très loin des 4,5:1 exigés par WCAG.
- Le texte se perd sur des fonds chargés et les reflets ajoutent du bruit.
- Il devient flou de savoir ce qui est cliquable.

**Recul d'Apple.**
- iOS 26.1 ajoute un réglage **« Teinté »**, qui atténue la brillance et rapproche les panneaux de l'ancien design. Les
  plaintes venaient surtout des pays très ensoleillés et des administrateurs d'entreprise.
- D'après la presse, une version 26.2 aurait ajouté un mode opaque. Pour iOS 27, Apple préparerait des conteneurs plus
  opaques et plus contrastés, moins de travail graphique sur les écrans statiques et des bords plus nets.
- Ces deux derniers points viennent d'un article d'opinion, à prendre avec prudence.

**Réglages d'accessibilité.** « Réduire la transparence », « Augmenter le contraste » et « Réduire les animations »
transforment le verre système. Un composant maison doit fournir ses propres replis.

**Ce que cela implique pour NexFleet.** Le parc est utilisé en extérieur, au Cameroun, sous un fort soleil, sur des
téléphones de milieu de gamme. La version à suivre est la version **teintée**, pas la version la plus transparente :
- verre très opaque (85 à 92 %) ;
- arête claire et ombre portée pour détacher les contrôles ;
- contenu toujours sur fond opaque ;
- aucun texte sous 4,5:1.

## 3. Apps de référence observées et ce qu'on leur emprunte

| App | Motif retenu |
|---|---|
| **Plans / Localiser (Apple)** | Carte plein écran, contrôles en verre flottants, panneau inférieur à crans (aperçu / moitié / plein). La carte reste manipulable sous le panneau aux petits crans. Pas de verre sur verre. |
| **Wallet / Santé** | Grands titres gras alignés à gauche, cartes arrondies à grands rayons, une métrique héroïque par carte, couleur réservée au statut. |
| **Uber / Bolt (côté conducteur)** | Un seul gros bouton d'action en bas, concentrique au panneau. L'état de la course en capsule permanente. Information critique (statut, prochaine étape) lisible d'un coup d'œil. |
| **Samsara (gestionnaire de flotte)** | La carte par défaut. On glisse vers le haut pour la liste des véhicules (nom, conducteur, statut, dernière position). Fiche avec appel, message, partage de position. |
| **Linear / Things** | Densité maîtrisée sur grand écran, barre latérale calme. Une seule couleur d'accent, raccourcis et états vides soignés. |
| **Revolut** | Même langage visuel sur iOS et Android, ce qui confirme qu'une app d'entreprise peut garder une identité unique sur les deux plateformes. |

## 4. Décisions pour NexFleet (appliquées par la refonte)

1. **Couches.**
   - Le contenu reste sur des surfaces **opaques** : cartes blanches, listes groupées.
   - Le **verre teinté** est réservé à la navigation : barre latérale flottante sur le bureau, barre d'onglets flottante
     sur Android, barre d'outils et commandes de carte, panneaux posés sur la carte.
   - Jamais de verre sur verre.
2. **Verre teinté.** Fond blanc à 86 % (sombre : 78 %), arête intérieure claire de 1 px, ombre douce. Reflet discret en
   haut. Réglage **« Réduire la transparence »** qui passe tout en opaque.
   - JavaFX n'a pas de flou d'arrière-plan.
   - Le flou en temps réel coûte cher sur Android de milieu de gamme.
   - Le verre teinté est donc à la fois le choix accessible et le choix sobre.
3. **Formes.**
   - Capsules pour les boutons, puces, segments et champs de recherche.
   - Rayons concentriques : fenêtre → panneau 26 → élément 16 (26 − 10 de marge) ; carte 22 → ligne 14 ; feuille 28 →
     bouton capsule.
4. **Typographie.** La police est **Inter** (licence OFL). San Francisco est réservée aux plateformes Apple et ne peut pas
   être embarquée sur Android ni Windows.
   - Échelle iOS par défaut sur Android : 34, 28, 22, 20, 17, 15, 13, 12.
   - Échelle desktop entre iPadOS et macOS : 30, 24, 20, 17, 14, 13, 12.
   - Grands titres gras, alignés à gauche, tracking resserré.
5. **Couleur.**
   - Une seule couleur d'action, la menthe CAMTEL accessible (#008575, 4,55:1 sur blanc).
   - Statuts : vert, bleu, gris, orange, rouge, toujours doublés d'un libellé.
   - Icônes monochromes dans les barres.
6. **Carte.**
   - Plein écran, commandes en verre flottantes.
   - Sur Android : panneau à crans (aperçu, moitié, plein).
   - Sur le bureau : liste et fiche en panneaux flottants posés sur la carte, comme Plans sur Mac.
7. **Mouvement.** Ressort court (220 à 280 ms), transitions interruptibles. Le réglage « Réduire les animations » coupe
   les effets.
8. **Accessibilité vérifiée.**
   - Contrastes contrôlés par `tools/check_contrast.py`, avec les nouveaux couples verre.
   - Cibles de 44 px minimum.
   - Libellés d'accessibilité.
   - Aucune information portée par la couleur seule.

## Sources

- [WWDC25 — Build a SwiftUI app with the new design (323)](https://developer.apple.com/videos/play/wwdc2025/323/?time=0)
- [WWDC25 — Build a UIKit app with the new design (284)](https://developer.apple.com/videos/play/wwdc2025/284?time=115)
- [WWDC25 — Get to know the new design system (356), notes](https://wwdcnotes.com/documentation/wwdc25-356-get-to-know-the-new-design-system/)
- [Apple HIG — Typography](https://developer.apple.com/design/human-interface-guidelines/typography.md)
- [Mozilla Acorn — iOS 26 and Liquid Glass](https://acorn.firefox.com/latest/mobile/patterns/i-os-26-and-liquid-glass-w2oH48nk)
- [Infinum — Liquid Glass: sleek, shiny and questionably accessible](https://infinum.com/blog/apples-ios-26-liquid-glass-sleek-shiny-and-questionably-accessible/)
- [Gulf News — réglage « Teinté » d'iOS 26.1](https://gulfnews.com/technology/companies/apple-yields-tinted-control-in-ios-261-beta-4-tones-down-liquid-glass-after-backlash-1.500315176)
- [Web Designer Depot — How “Liquid Design” broke the iPhone (opinion, fév. 2026)](https://webdesignerdepot.com/how-liquid-design-broke-the-iphone-and-forced-apples-great-reset/)
- [Apple Developer Forums — navigation par feuilles au-dessus d'une carte](https://developer.apple.com/forums/thread/794346)
- [Samsara — application Fleet, carte et fiche d'actif](https://kb.samsara.com/hc/articles/360040058532)
