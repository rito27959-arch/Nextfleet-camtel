# Registre des licences des modèles 3D

Règle du cahier (V5) : **aucun modèle dont la licence interdit la redistribution** n'est embarqué. Chaque modèle
embarqué figure ici avec sa licence, son auteur et sa source ; `catalogue.json` reprend ces informations et les
applications les affichent sous la vue 3D.

| Fichier | Licence | Auteur | Source | Embarqué |
|---|---|---|---|---|
| `models/nexfleet-pickup.glb`, `-suv`, `-4x4`, `-berline`, `-minibus` | Création originale du projet | Projet NexFleet CAMTEL | `tools/gen_fleet_models.py` (régénérables) | Oui |
| `models/khronos-toycar.glb` | CC0 1.0 (domaine public) | Guido Odendahl ; Eric Chadwick | [Khronos glTF-Sample-Assets — ToyCar](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/ToyCar) | Oui |

## Sources examinées et non embarquées

| Source | Licence | Décision |
|---|---|---|
| [Kenney — Car Kit](https://kenney-assets.itch.io/car-kit) (40+ véhicules, glTF) | CC0 1.0 | **Recommandée** ; non téléchargeable depuis l'environnement de réalisation. À ajouter avec `tools/import-3d-model.md` |
| [Quaternius — Low Poly Cars](https://quaternius.itch.io/lowpoly-cars) | CC0 1.0 | Recommandée ; formats FBX/OBJ/Blend, conversion glTF à prévoir |
| [Khronos — CarConcept](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/CarConcept) | CC-BY 4.0 (logos Khronos exclus de la licence) | **Écartée** : ses textures portent des marques Khronos |
| [Sketchfab — Download API](https://sketchfab.com/developers/download-api/guidelines) | CC (souvent CC-BY), par modèle | Possible au cas par cas : connexion OAuth de l'utilisateur, affichage obligatoire auteur + licence + mention Sketchfab ; vérifier les conditions développeur avant tout embarquement |
| Modèles de constructeurs (Toyota, Peugeot…) | Propriétaires | **Exclus** : marques et dessins protégés ; les modèles NexFleet sont des silhouettes génériques par catégorie |

Les modèles de marques réelles ne sont jamais imités : un véhicule du parc est représenté par la silhouette de sa
catégorie (Pick-up, SUV, 4x4, Berline, Minibus).
