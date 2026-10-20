# Ajouter un modèle 3D au catalogue

1. Vérifier la licence : CC0, CC-BY ou licence écrite autorisant la **redistribution** dans une application. Refuser
   « usage personnel », « éditorial », « pas de redistribution », et tout modèle reproduisant une marque protégée.
2. Convertir en **GLB autonome** (textures intégrées, PNG/JPEG, sans Draco ni KTX2) — par ex. Blender ≥ 3.6 :
   Fichier → Exporter → glTF 2.0 (.glb), « Compression » décochée.
3. Valider : `npx gltf-validator modele.glb` (0 erreur exigée).
4. Copier dans `shared/assets3d/models/`, ajouter l'entrée dans `catalogue.json` (licence, auteur, source, SPDX) et
   une ligne dans `LICENCES.md`.
5. `tools/build-offline.sh` (test du catalogue) puis CI : le modèle est embarqué dans le bureau et dans l'APK.
