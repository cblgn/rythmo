# Point de reprise Rythmo — 22 septembre 2026

## Statut

Rythmo est un prototype fonctionnel dont le besoin pédagogique reste à préciser.
Le tag annoté `checkpoint-2026-09-22` désigne le commit de référence, documentation
comprise. Il ne représente ni une release 0.1 ni une validation définitive des
épreuves et barèmes. Ne pas déplacer ce tag ; créer un nouveau repère pour une
étape ultérieure. Dependabot et les protections de branche restent actifs.

Aucun changement de fonctionnalité, de schéma, de protocole ou de version Android
n’accompagne ce gel. Les métadonnées existantes `versionName = "1.0"` et
`versionCode = 1` restent celles du prototype ; elles ne constituent pas une
annonce de publication.

## Ce qui est conservé

- Préparation professeur native : listes CSV/XLSX, tables de performance, distance,
  tours identiques, total de points, brouillon et publication d’une séance figée.
- Groupes de 1 à 8 élèves, chrono local, sauvegarde de chaque passage, passages
  groupés, annulation immédiate encadrée, arrivée/abandon et PDF individuels.
- Nearby Android avec association explicite, séance active unique et récupération
  initiale. Aucun passage transmis en direct ; envoi des bilans sur demande.
- Synchronisation HTTPS et serveur PC conservés pour compatibilité et essais.
- PIN local, récupération hors ligne et code professeur de séance distinct.
- Données et calculs historiques conservés, y compris les anciennes corrections.

Les nouveaux barèmes actuels concernent une **distance imposée avec tours égaux**.
Le schéma 2 attribue un point quand le tour, arrondi à la seconde, est aussi rapide
ou plus rapide que le précédent. N tours donnent N−1 comparaisons. La performance
issue d’une table importée reçoit le reste du maximum choisi ; la conversion /20
est proportionnelle. C’est une hypothèse de travail implémentée, pas une règle
universelle pour les quatre niveaux. Voir [les règles actuelles](native-teacher-preparation.md).

## Questions à trancher avant de reprendre les évolutions métier

| Sujet | À recueillir auprès du donneur d’ordre |
|---|---|
| 6e, 5e, 4e, 3e | Épreuve et objectif pédagogique propres à chaque niveau |
| Grandeur imposée | Distance à parcourir ou durée pendant laquelle courir |
| Mesures | Temps, distance atteinte, passages et éventuels segments incomplets |
| Évaluation | Performance seule, régularité seule ou combinaison |
| Régularité | Comparaison retenue, tolérance, arrondis et maximum |
| Performance | Tables, profils applicables, unités et comportement aux seuils |
| Note finale | Pondérations, maxima, arrondis et conversion éventuelle sur 20 |
| Cas particuliers | Arrêt, abandon, absence, essai incomplet et nouvelle tentative |

Pour chaque épreuve réellement souhaitée, recueillir un exemple complet de saisie
avec le résultat et la note attendus. Conserver les exemples réels en privé ;
utiliser des équivalents fictifs dans les tests. Les épreuves à durée imposée,
les parcours multiphases et une interface offrant les trois modes d’évaluation
ne sont pas livrés par ce point de reprise. Ne pas les déduire automatiquement
des règles actuelles ni généraliser le modèle avant validation du besoin.

## Validation et limites

L’état fonctionnel de départ est `b06546dc4ae114e536c4f2208a4feaf407a6e1eb`.
Sa [CI sur main](https://github.com/cblgn/rythmo/actions/runs/35641465264),
[Sonar](https://github.com/cblgn/rythmo/actions/runs/35641465200), CodeQL et l’audit
des dépendances ont passé. La suite contient 143 tests JVM/Android ; le dernier
correctif synchronise le test de préparation sur la fin de sauvegarde du brouillon.
Les tests navigateur et Python complètent cette couverture. Les validations
physiques antérieures sont décrites dans [le compte rendu natif](native-teacher-preparation.md).

Le manifeste privé de sauvegarde consigne les contrôles et builds du commit
finalement tagué. Les tests automatisés ne remplacent pas la validation pédagogique
avec l’enseignant. Une reconstruction future peut nécessiter le téléchargement du
JDK, du SDK et des dépendances ; l’APK et la distribution serveur conservés offrent
une copie directement exploitable sans recompiler.

## Conservation et restauration

La sauvegarde privée datée réside dans `/home/pl/backups/rythmo`, hors dépôt.
Elle contient un bundle Git, une archive source, l’APK debug, la distribution du
serveur, un manifeste, les empreintes SHA-256 et les fichiers privés inventoriés.
Les liens locaux vers les données sont remplacés par les fichiers eux-mêmes dans
la sauvegarde. Les caches, SDK et dépendances téléchargeables ne sont pas archivés.
Aucun classeur, résultat, PDF, clé ou manifeste privé n’est publié sur GitHub.

1. Lire le manifeste et vérifier `SHA256SUMS` avant toute restauration.
2. Restaurer le bundle dans un nouveau dossier, puis sélectionner le tag ; ne pas
   écraser un dépôt ou des données existants. Le bundle permet de récupérer le
   code sans GitHub.
3. Extraire les données privées dans un dossier distinct. La notice privée indique
   les destinations et les identités disponibles ; conserver les données serveur
   et leur identité TLS ensemble lorsqu’elle existe.
4. Pour exécuter le serveur conservé, utiliser son lanceur avec le chemin du dossier
   de données restauré et un JDK 17. Ne pas lancer un serveur sur la sauvegarde
   originale. Vérifier les ports avant lancement.
5. Pour reconstruire, préparer Java/Android puis lancer les commandes de validation
   du README. Les clés de signature locales sont conservées séparément ; utiliser
   la même clé pour une mise à jour Android compatible. L’APK conservé est un debug
   `fr.rythmo`, pas une version Play Store ni le paquet `fr.rythmo.validation`.

**Périmètre PC uniquement :** les séances, PDF, PIN et clés Android Keystore du
Xiaomi et de la tablette ne sont pas sauvegardés ici. Ne pas désinstaller ces
applications en supposant pouvoir restaurer leurs données depuis cette archive.

Cette sauvegarde locale ne couvre pas la panne ou la perte du PC : copier ensuite
le dossier complet sur un autre support. Aucune copie externe n’est effectuée
par ce gel.
