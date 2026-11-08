# AutoPaterns 3.0 — Minecraft 26.2 (Fabric, client)

Reconstruction complète d'AutoPaterns 2.13.0 (Minecraft 1.21.11) pour **Minecraft Java 26.2**.
Le mod enregistre et rejoue les actions du joueur tick par tick. Il gère aussi la vente et la corbeille
automatiques, la pêche automatique, les waypoints avec surbrillance de blocs, un HUD modulaire et un mode performance.

> La version demandée « 1.26.2 » n'existe pas : depuis 2026, Mojang numérote ses versions par année
> (26.1, 26.2…). Le mod cible **26.2**.

## Installation

1. Minecraft Java **26.2** avec **Fabric Loader ≥ 0.19.5** (installateur : https://fabricmc.net/use/).
2. **Fabric API** pour 26.2 (0.161.0+26.2 ou plus récent), dans le dossier `mods`.
3. **Java 25** (fourni par le launcher officiel pour 26.2).
4. Copier `autopaterns-3.0.0+26.2.jar` dans `mods`.

Mod **client uniquement** : il ne s'installe pas sur le serveur.

Lunar Client : rien ne garantit qu'il charge les mods Fabric en 26.2. Vérifiez la liste des versions qu'il
prend en charge. Le launcher Fabric standard fonctionne.

## Compiler soi-même

JDK 25 requis.

```
./gradlew build                        # JAR dans build/libs/autopaterns-3.0.0+26.2.jar
./gradlew runProductionClientGameTest  # tests en jeu (vrai client, Xvfb sous Linux)
```

Le workflow GitHub Actions (`.github/workflows/build.yml`) compile, lance les tests en jeu, puis publie le JAR,
les journaux et les captures d'écran des tests sur la branche `dist`.

## Menu principal — touche O

La touche O ouvre le menu ; la même touche le referme (elle est modifiable). Le menu a cinq boutons, chacun
avec son état (ON/OFF ou résumé) :

| Bouton | Contenu |
|---|---|
| **PÊCHE AUTOMATIQUE** | marche/arrêt, réglages (relance, attente, anticipation, clics, vente entre deux prises, diagnostic, méthode de capture), compteurs prises/ratés, état de la capture et du calibrage |
| **WAYPOINTS** | patterns de waypoints (créer, renommer, supprimer, options, lancer/arrêter l'enregistrement), import/export, surbrillance de blocs (recherche, « identiques autour ») |
| **PATTERNS** | enregistrer, lecture, boucle, arrêt ; liste des patterns (charger, sauvegarder, renommer, supprimer) ; export/import ; options du pattern (remplacement d'items, action en cas d'erreur) ; vente et corbeille |
| **CONFIGURATION DES TOUCHES** | toutes les touches du mod, conflits signalés en rouge, réassignation clavier ou souris, Suppr pour désassigner, réinitialisation par touche ou globale |
| **MODE PERFORMANCE** | activation, profils (Équilibré / Performance / Maximum / Personnalisé), optimisations une par une, liste de celles réellement appliquées, restauration immédiate, FPS en arrière-plan |

En bas du menu : « HUD… » ouvre l'éditeur du HUD, « Arrêter » arrête l'action en cours, et « Réactiver le mod »
apparaît si le mod s'est mis en pause après une erreur.

Navigation : Échap revient à l'écran précédent, ✕ ferme tout. Les réglages sont sauvegardés automatiquement.

## Raccourcis par défaut

| Action | Touche |
|---|---|
| Menu principal | O |
| Arrêt d'urgence | F6 |
| Enregistrement (démarrer / arrêter) | F7 |
| Boucle (démarrer / arrêter) | F8 |
| Lecture unique | F9 |
| Mode performance (activer / désactiver) | F10 |
| Éditer le HUD | non assignée |
| Waypoints : lancer / arrêter l'enregistrement | F12 |
| Menu des waypoints | J |
| Surbrillance : ajouter / retirer le bloc visé | N |
| Pêche automatique : lancer / arrêter | K |

Les touches déjà personnalisées en 2.x (options.txt) sont reprises : les noms internes n'ont pas changé.
L'arrêt d'urgence est traité au début de chaque tick, avant toute action automatisée, et relâche toutes les touches.

## Fonctionnalités

### Touches de lancement par pattern (3.1)

Menu PATTERNS → sélectionner un pattern sauvegardé → **⌨ Touche de lancement…** :
- **Touche** : cliquer puis appuyer sur la touche voulue (clavier uniquement ; Échap annule, Suppr retire) ;
- **Mode** : *une fois* ou *en boucle* ;
- les conflits avec d'autres raccourcis (jeu, mods) sont signalés ;
- une touche ne lance qu'un seul pattern : l'assigner ailleurs la retire du premier.

En jeu, **sans menu ouvert**, la touche charge le pattern puis le lance (recalage, mode performance… comme
Lecture / Boucle). Si une activité tourne déjà, la touche l'arrête. Les touches sont enregistrées dans
`config.txt` (`hotkey.<nom>=<code>:<ONCE|LOOP>`) et suivent le renommage / la suppression des patterns ;
les fichiers `.apt` ne changent pas. La liste des patterns affiche la touche de chacun.

### Patterns
- Enregistrement tick par tick : touches, clics brefs et maintenus, emplacement de la barre d'action, rotation,
  position, vitesse, état au sol / sprint. La caméra est enregistrée image par image et rejouée lissée (spline Hermite).
- Lecture unique ou en boucle, après un recalage automatique sur le point de départ.
- Correction des écarts : recalage immédiat au-delà de 0,1 bloc, recalage doux tous les 100 ticks, retour à pied
  au-delà de 1,5 bloc, arrêt de sécurité au-delà de 32 blocs.
- Contrôle des blocs : un bloc cassé à l'enregistrement mais encore présent est recassé, puis la lecture reprend.
- Remplacement automatique des outils ou items épuisés (par emplacement).
- Action en cas d'erreur : rien, déconnexion, ou commande.
- Diagnostic détaillé dans `autopaterns/diagnostic.txt` (FPS, saccades, corrections serveur, recalages…).

### Vente et corbeille
- Rejeu des recettes de clics capturées, navigation dans les catégories et les pages, détection des changements
  d'inventaire, délais entre interactions, plusieurs tentatives.
- Déclenchement par seuil de cases libres, pendant la lecture ou entre deux répétitions, ou depuis la pêche.
- Contrôles 3.0 :
  - chaque clic vérifie que le bon menu est ouvert et que la case contient l'item attendu ;
  - un clic n'est jamais répété avant que le serveur ait confirmé le précédent ;
  - plafonds de durée et de clics pour éviter toute boucle infinie ;
  - à l'arrêt, l'item tenu sous la souris est remis dans l'inventaire et le menu est fermé.

### Pêche automatique
- Détection visuelle de la jauge du serveur (bande cyan, zone verte, poisson) par capture et analyse des pixels,
  avec calibration de la copie GPU.
- Contrôle de la canne (maintien / relâchement du clic droit), relance automatique, gestion des tentatives,
  compteurs de prises et d'échecs.
- Journal `autopaterns/peche-erreur.txt` et images de diagnostic.
- Capture portable OpenGL et Vulkan : une seule capture en vol, délai d'abandon, aucune fuite de mémoire,
  repli automatique en cas d'erreur GPU.
- Indépendante des patterns : elle se lance et s'arrête seule.

### Waypoints
- Enregistrement des actions (bloc miné, bloc posé, clic, clic maintenu, blocs identiques autour) sous forme de
  marqueurs affichés dans le monde et en surimpression à travers les murs.
- Surbrillance de blocs (recherche par budget de blocs par tick, sans thread), import/export (fichier et presse-papiers).
- Comme en 2.13, le module n'effectue aucun déplacement automatique : c'est un choix de conception conservé.

### HUD
Modules : statut, progression, écart, compteurs, inventaire, butin, touches, notifications. Nouveau design sombre,
accents cyan et bleu électrique, animations discrètes.

L'éditeur (« HUD… » ou touche dédiée) montre un aperçu en temps réel. Pour chaque module on peut :
- le déplacer, l'aimanter et le redimensionner ;
- régler son échelle (25 à 300 %) et sa transparence ;
- choisir les informations affichées ;
- le rendre toujours visible ;
- le réinitialiser, seul ou avec toute la disposition.

Le HUD est masqué par F1.

### Mode performance
- Sauvegarde des valeurs d'origine sur disque (`autopaterns/graphismes-origine.txt`) **avant** toute modification.
- Restauration exacte à l'arrêt, et après un crash au lancement suivant.
- Une sauvegarde valide n'est jamais écrasée.
- Une seule activation à la fois.
- La liste affichée ne contient que les optimisations réellement appliquées.
- Optimisations disponibles : distance de rendu et de simulation, qualité graphique, particules, nuages, éclairage
  lisse, ombres, transparence, mélange des biomes, distance des entités, vignette, fondu des chunks, météo, mipmaps,
  FPS max, packs de ressources, shaders Iris (si Iris est installé), priorité du processus (Windows).

## Fichiers (dossier `.minecraft/autopaterns/`)

| Fichier | Contenu |
|---|---|
| `config.txt` | réglages généraux (performance, pêche, FPS d'arrière-plan, pattern courant) |
| `dernier.apt`, `patterns/*.apt`, `export/`, `import/` | patterns (formats 2.x VOXORIA-MC 1/2 et AUTOPATERN 3/4 lus et écrits) |
| `ventes.txt`, `corbeille.txt` | recettes de clics |
| `waypoints.txt`, `waypoints-export/` | waypoints |
| `hud.txt` | disposition du HUD (le format 2.x est lu ; le format 3.0 reste lisible ligne par ligne) |
| `graphismes-origine.txt` | sauvegarde du mode performance (supprimée après restauration) |
| `diagnostic.txt`, `peche-erreur.txt`, `peche-diagnostic/` | diagnostics |
| `journal/autopaterns.log` | journal complet du mod, avec les traces d'erreur |

Toutes les écritures sont atomiques : fichier temporaire, puis renommage, avec une copie `.bak`.

## Migration depuis 2.x

Il suffit de remplacer le JAR : le dossier `autopaterns/` est repris tel quel, de même que l'ancien dossier
`autopatern/` des toutes premières versions.

Les patterns à l'ancien format restent lisibles après une nouvelle sauvegarde. Le bug 2.x qui les rendait
illisibles est corrigé, et les fichiers déjà abîmés par ce bug sont récupérés.

## Différences avec 2.13

- **Supprimé, à la demande de l'auteur** : le module « Paquets réseau » (journal, blocage, modification et renvoi
  de paquets).
- **Nouveau** :
  - menu principal à 5 boutons et écran de configuration des touches centralisé ;
  - éditeur de HUD unique ;
  - profils de performance ;
  - renommage des patterns ;
  - réactivation du mod après une erreur, sans redémarrer.
- **Gestion des erreurs** : une exception met le mod en pause en toute sécurité (touches relâchées, réglages
  restaurés) au lieu de le désactiver jusqu'au redémarrage. Le détail est écrit dans `journal/autopaterns.log`.
- **Plus de réflexion Java** sur les classes de Minecraft (sauf pour Iris, mod tiers optionnel). Les noms internes
  codés en dur de la 2.x, qui ne fonctionnent plus en 26.2, sont remplacés par des appels directs.
- **Comportement des écrans pendant la lecture** :
  - le chat et le menu pause arrêtent la lecture proprement, sans déclencher l'action en cas d'erreur ;
  - les autres écrans (mort, menus du serveur) la déclenchent comme avant.
- De nombreux bugs relevés à l'analyse ont été corrigés. Le détail est dans les notes de développement de chaque module.

## Limites connues

- La jauge de pêche et les menus de vente dépendent du serveur : ils ne sont pas testables automatiquement.
  Les tests en jeu couvrent le démarrage, la capture d'écran, l'arrêt et les formats de fichiers.
- Le rendu Vulkan de 26.2 est expérimental. La capture de la pêche utilise une méthode compatible avec les deux
  moteurs de rendu, mais elle n'a été validée qu'en OpenGL.

Auteur : Ratatouille. Tous droits réservés.
