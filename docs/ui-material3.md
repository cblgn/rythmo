# Interface Material 3

Une carte remplie représente un élève. Le nom utilise `titleMedium`, le temps
`titleLarge` et l’avancement `labelLarge`. Un `HorizontalDivider` avec retrait
sépare l’identité des données, sans ajouter de cadre autour de chaque ligne.
Le chrono commun emploie `displayMedium` avec chiffres monospace.

La carte entière est une cible tactile. Le retour d’appui utilise le ripple
Material, découpé à la forme de la carte. Pour un passage groupé, les cartes
éligibles affichent une case à cocher et une bordure de sélection ; TalkBack
annonce l’état sélectionné, l’avancement et l’allure. La validation reste dans
le bouton inférieur qui a capturé l’instant. Le retour Android annule la sélection.

Les cartes défilent indépendamment du chrono et des actions. Le texte peut
s’agrandir sans hauteur de carte bloquée. Les couleurs métier d’allure restent
bleu/vert/rouge, avec un symbole et une description accessible. L’orange est la
couleur des actions et de la sélection, indépendante de l’allure.

Les composants Compose restent natifs : `TopAppBar`, `Card`, `HorizontalDivider`,
`Checkbox`, `Button`, `TextButton`, `DropdownMenu` et `AlertDialog`. Les icônes
Material Symbols sont embarquées et accompagnent des libellés.

Références consultées : [Cards](https://m3.material.io/components/cards/guidelines),
[Divider](https://m3.material.io/components/divider/guidelines),
[implémentation Compose](https://developer.android.com/develop/ui/compose/components/card).
