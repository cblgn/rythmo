# Barèmes configurables

Le format cible est un **JSON versionné**, indépendant d'un classeur, d'une classe
ou d'une épreuve particulière. Le professeur prépare le barème dans l'interface
enseignant ; il ne doit pas écrire du JSON. Un import CSV des tables peut être
ajouté plus tard. Excel n'est ni requis ni une dépendance de l'application.

Le dépôt public contient uniquement des exemples inventés. Les tables reçues
pour un cours réel et leur extraction restent locales, hors de Git.

## Données du barème

| Ensemble | Champs |
|---|---|
| Identité | identifiant, nom, révision, statut, version du format |
| Épreuve | distance totale, nombre de tours ou distance entre passages |
| Applicabilité | niveau et profils de notation, notamment sexe |
| Composantes | identifiant, nom, maximum, règle et paramètres explicites |
| Performance | profil, seuil de durée, points |
| Résultat | note maximale et sous-notes détaillées |

La classe sélectionne les élèves. Son nom ne doit pas être codé dans une formule
de notation. Le barème définit sa propre échelle et la décomposition de la note ;
Rythmo ne doit imposer ni /20, ni un partage fixe performance/régularité/progression.

Les durées utilisent des **millisecondes entières** et les notes des **dixièmes de
point entiers**. Les seuils, les arrondis, les égalités, le comportement hors plage
et une éventuelle pondération doivent être explicites. La somme des maxima des
composantes correspond au maximum final, sauf conversion définie dans le barème.

Une séance conserve la version validée de son barème. Modifier un barème ne
recalcule pas silencieusement les séances précédentes. Chaque sous-note doit
pouvoir être expliquée dans le bilan du professeur.

## Exemple fictif

[`data/rubrics/examples/demo-1000m.json`](../data/rubrics/examples/demo-1000m.json)
illustre deux profils et deux composantes. Les valeurs sont inventées ; ce
n'est pas une recommandation pédagogique. Cet exemple n'est pas chargé comme
barème actif.

Le moteur général de sous-notes et son éditeur restent à implémenter. Le MVP
actuel utilise son barème de démonstration à coefficients, avec échelle maximale
configurable. La distance, le nombre de tours identiques ou la distance entre
passages sont déjà configurables indépendamment de la notation.
