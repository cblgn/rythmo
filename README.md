# Rythmo

[![CI](https://github.com/cblgn/rythmo/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/cblgn/rythmo/actions/workflows/ci.yml) [![CodeQL](https://github.com/cblgn/rythmo/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/cblgn/rythmo/actions/workflows/codeql.yml) [![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=cblgn_rythmo&metric=alert_status)](https://sonarcloud.io/dashboard?id=cblgn_rythmo) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=cblgn_rythmo&metric=coverage)](https://sonarcloud.io/component_measures?id=cblgn_rythmo&metric=coverage&view=list) [![Security](https://sonarcloud.io/api/project_badges/measure?project=cblgn_rythmo&metric=security_rating)](https://sonarcloud.io/project/issues?id=cblgn_rythmo)

Évaluation de demi-fond hors ligne, pour Android, avec serveur enseignant local sur PC ou téléphone Android.

## Préparer un cours sur Android — V1

Dans **⋮ → Accès professeur → Séance**, importer la classe (CSV/XLSX : NOM,
Prénom, Sexe) puis le barème de performance filles/garçons. Régler la distance,
le nombre de tours et le maximum total ; la régularité vaut automatiquement un
point par comparaison réussie après arrondi des tours à la seconde. Le reste
pondère la performance issue de la table importée.

**Vérifier ma séance → Rendre la séance disponible** publie une séance figée.
Sur les tablettes élèves, **Récupérer la séance**, comparer le code avec le
professeur, puis préparer le groupe. Aucun PC ni réglage Wi-Fi manuel nécessaire.
Le nom professeur et le titre identifient la séance à rejoindre. Le code des
bilans est consultable dans l’espace professeur ; il est distinct du PIN local.

Voir [le parcours natif et ses règles de notation](docs/native-teacher-preparation.md).
La console navigateur conserve le suivi et les PDF, sans préparation/publication.
Les instructions PC/USB ci-dessous restent des outils de compatibilité et de test.

## Essayer avec un PC WSL et un téléphone USB

Dans un premier terminal :

```bash
./scripts/server.sh
```

Ouvrir dans le navigateur du PC le lien affiché dans le terminal. Il contient la clé enseignant. Le code d’association des chronométreurs est indiqué dans cette interface et dans le terminal.

Dans un second terminal :

```bash
./scripts/phone.sh --fast
./scripts/connect-server.sh
```

Si WSL est exposé uniquement en IPv6 côté Windows, le script démarre un petit relais local automatiquement. Dans ce cas, garder ce second terminal ouvert pendant les synchronisations ; `Ctrl+C` ferme le relais. Aucun droit administrateur n’est nécessaire.

Sur le téléphone :

1. L’application ouvre directement **Préparer le groupe**, ou reprend la série active. Au premier lancement, le professeur configure son PIN local et conserve son code de secours.
2. Garder l’adresse `https://127.0.0.1:8765`, renseigner le code d’association et nommer l’appareil.
3. Dans **⋮ → Accès professeur**, associer le serveur HTTPS en comparant les six groupes du code de vérification au code affiché sur le serveur. Puis **Récupérer la séance**, sélectionner de 1 à 8 élèves, puis **Valider le groupe**. Chaque élève est ainsi affecté à un seul appareil pour cette séance.
4. **Démarrer la course**. Toucher la case de chaque élève à ses passages. Le Wi-Fi et le câble peuvent être déconnectés pendant la course.
5. Chaque arrivée sauvegarde le résultat et génère immédiatement un PDF. Toucher une case terminée permet de consulter le bilan et de corriger chaque passage une seule fois.
6. Reconnecter le téléphone et, si nécessaire, rétablir le tunnel USB. **Envoyer les bilans** demande le code professeur, puis transmet les données et les PDF, avec confirmation de réception. Les copies locales restent disponibles.

**Actions professeur** permet l’abandon d’un seul élève (blessure…), ou la clôture de la série avec envoi des PDF disponibles. Ces actions nécessitent un code professeur à six chiffres, distinct du code d’association, visible dans l’espace enseignant du serveur. Le contrôle fonctionne hors ligne après synchronisation ; après cinq erreurs, une pause de 30 secondes s’applique. Une clôture sans réseau conserve les bilans pour un envoi ultérieur. Les abandons restent non notés et leurs passages sont conservés. Les élèves affectés à une série ne sont pas réattribués dans la même séance : publier une nouvelle séance pour une nouvelle tentative.

## Données de démonstration

| Niveau | Classes |
|---|---|
| 6e | Mistral, Tramontane |
| 5e | Azur, Indigo |
| 4e | Quartz, Améthyste |
| 3e | Ouessant, Belle-Île |

Chaque classe contient 30 identités fictives, 15 filles et 15 garçons. Le professeur peut modifier la liste avant publication d’une séance, au format `Prénom;Nom;F` ou `Prénom;Nom;G`, une ligne par élève.

Le barème **Démonstration v1** utilise une référence de 10 minutes pour 2000 m, garçon, 6e, multipliée par distance/2000, par le coefficient sexe (1 / 1,10) et par le coefficient niveau (1 / 0,95 / 0,90 / 0,85). Note provisoire : `min(20, 20 × référence / temps total)`, arrondie au dixième. Les coefficients sont modifiables ; chaque nouvelle séance conserve sa propre copie. La régularité reste informative.

Le parcours est configurable : distance entre passages, ou nombre de tours identiques. **1000 m en six tours identiques** affiche « Tour 1/6 » à « Tour 6/6 » et compare les six tours sans arrondir leur longueur pour les calculs. Un parcours avec passages fixes peut avoir un dernier segment plus court, exclu des comparaisons avec un tour complet.

L’échelle maximale du barème de démonstration est configurable. Le format cible
des barèmes est un JSON versionné, avec sous-notes et maxima, décrit dans
[docs/baremes.md](docs/baremes.md). Le dépôt contient uniquement un exemple
fictif ; le moteur général de notation reste à implémenter. Aucun classeur ou
table de barème issue d'un cours réel n'est publié.

## Wi-Fi local et mode enseignant Android

Internet n’est pas nécessaire. Sur le même LAN, **Rechercher le professeur** utilise UDP 8766 ; les échanges HTTPS utilisent TCP 8765. L’adresse peut être saisie manuellement. Le point d’accès et les autorisations réseau doivent être préparés par l’enseignant. Sous WSL2, la découverte Wi-Fi et l’accès depuis le LAN dépendent du réseau miroir / routage et du pare-feu Windows ; le tunnel USB permet de développer sans cette configuration.

Le menu **⋮ → Accès professeur** ouvre les réglages après saisie du PIN local : démarrer le serveur, puis ouvrir l’interface de gestion. Le démarrage est indisponible pendant une course locale. Le serveur déjà actif continue après le retour aux élèves ou le verrouillage ; son arrêt reste explicite et protégé. Une notification indique que le serveur est actif. Pour l’utiliser après les tests USB, libérer le port avec `./scripts/connect-server.sh --disconnect`. Les serveurs PC et téléphone ont des stockages distincts : synchroniser la séance vers le serveur qui l’a créée.

## Fiabilité et stockage

- Chaque passage est enregistré avant d’être confirmé à l’écran. Les fichiers JSON sont écrits puis remplacés atomiquement.
- Les chronos utilisent l’horloge monotone du téléphone. Une fermeture de l’app conserve les passages ; les PDF manquants sont régénérés à la réouverture. Un redémarrage du téléphone interrompt le chrono et impose de clôturer les coureurs encore actifs.
- Les PDF sont stockés dans les fichiers privés durables de l’application, avec une version par correction. Les résultats originaux sont conservés.
- Les envois sont idempotents. Une interruption ne supprime aucun résultat ; relancer l’envoi complète les bilans restants.
- Les données PC se trouvent dans `server-data/`. Conserver ce dossier pour garder les séances et les résultats. Ne pas désinstaller l’application Android pour mettre à jour : utiliser l’installation `-r` du script.
- Le mode individuel historique reste accessible depuis les réglages professeur ; le stockage durable des séries et la synchronisation concernent le nouveau parcours groupe.

## Développement

Le dépôt [GitHub Rythmo](https://github.com/cblgn/rythmo) publie uniquement le code
et des exemples fictifs. La CI compile l'APK, exécute les tests et Android Lint,
analyse les secrets, le code et les dépendances. Voir
[CI et protections GitHub](docs/github-security.md).

```bash
./scripts/phone.sh            # tests, compilation, installation, lancement
./scripts/phone.sh --fast     # sans tests
./scripts/logs.sh
```

La compilation utilise le SDK 37, avec une cible Android 16 (API 36) et un minimum Android 8 (API 26). Les appareils anciens restent compatibles. `bundleRelease` produit un AAB non signé ; la signature et la publication Play Store se préparent séparément.

Pour les vérifications seules, après préparation de l’environnement Java/SDK par les scripts :

```bash
./gradlew test
./gradlew assembleDebug bundleRelease
./gradlew :server:installDist
```

`core` partage les calculs, modèles, barèmes et protocole entre Android et PC ; `app` contient les écrans, la génération Android des PDF et le stockage client ; `server` lance le serveur PC. Les seules nouvelles bibliothèques sont Kotlin Serialization pour les données JSON et NanoHTTPD pour le serveur embarqué.

L’accueil donne priorité aux élèves : logo, accès professeur discret et préparation/reprise du groupe. Le chrono au dixième reste fixe au-dessus de cartes défilantes (deux colonnes sur téléphone, quatre sur tablette large). Les cartes affichent le prénom et l’initiale du nom (nom complet en cas de conflit), le dernier temps cumulé et le nombre de passages. Après sauvegarde, le tour et son écart s’affichent quatre secondes. Bleu : ±1 seconde du tour précédent ; vert : plus rapide ; rouge : plus lent. Le premier tour et un dernier segment plus court restent neutres. Chaque arrivée conserve sa génération individuelle de PDF.

Le thème « Terre battue » associe orange brûlé et ivoire. Le logo représente trois couloirs verticaux avec un point blanc ; la signature Rythmo est en italique. Les couleurs d’allure restent indépendantes du thème.

### Passages groupés et erreur de saisie

**Passage groupé** capture l’instant et présélectionne les coureurs actifs. Le même bouton devient **Valider · n** ; toucher une carte retire ou ajoute un élève. **Annuler** reste à côté du bouton. La validation enregistre le lot atomiquement ; la sélection n’ajoute aucun délai aux temps. Le chronomètre continue. Chaque carte ignore les doubles appuis pendant la sauvegarde puis une seconde.

Un appui long d’une seconde propose d’annuler le dernier passage pendant quinze secondes après sa sauvegarde, même à l’arrivée, tant qu’il n’a été ni corrigé ni envoyé. La fenêtre se ferme lors d’une relance complète. L’annulation reste tracée dans les données et le prochain PDF ; les anciennes versions PDF restent stockées. Une arrivée annulée remet l’élève en course.

### Association HTTPS hors ligne

Le serveur expose la synchronisation TLS sur **8765**, la découverte UDP sur **8766**, et son interface professeur uniquement sur **127.0.0.1:8767**. Le navigateur du PC ou du téléphone hébergeur utilise le lien local affiché au démarrage. L’interface d’administration n’est plus accessible depuis un autre appareil.

La première association exige le PIN local du chronométreur, puis la comparaison du code de vérification de six groupes affiché par le serveur et le client. Le client mémorise l’empreinte complète du certificat. Un certificat différent est refusé ; aucune donnée ni aucun code d’association n’est envoyé avant validation TLS. Un changement d’adresse exige d’associer cette adresse dans les réglages. Il n’y a aucun repli HTTP, y compris en USB.

Sur PC, le JDK crée une identité PKCS12 et des fichiers privés sous `server-data/tls/`. Sur Android, la clé privée reste dans Android Keystore. Sauvegarder les données du serveur PC, y compris ce dossier ; une identité perdue nécessite une nouvelle association. Le protocole passe à la version 3 : mettre à jour le serveur et les chronométreurs ensemble. Les anciennes courses sont conservées ; réassocier leur serveur avant l’envoi.

### PIN local et secours

Le premier lancement demande un PIN à six chiffres, sa confirmation et la conservation d’un code de secours aléatoire de 128 bits. Une course déjà démarrée lors d’une mise à jour peut se terminer avant cette configuration. Les réglages se verrouillent au retour aux élèves et lorsque l’application passe en arrière-plan ; une rotation garde le déverrouillage. Un rôle enseignant anciennement enregistré ne donne aucun accès.

**PIN oublié** remplace le PIN et renouvelle le code de secours, sans supprimer les séances ni les PDF. L’ancien code de secours devient invalide. Le PIN et le secours ne sont jamais stockés en clair : vérificateurs PBKDF2-HMAC-SHA256 avec sels indépendants, dans les fichiers privés Android. Cinq erreurs de PIN ou de secours entraînent une pause commune de trente secondes, conservée après redémarrage. Sans ces deux secrets, aucun effacement automatique n’est proposé. Cette protection locale est distincte du code professeur de la séance ; elle ne verrouille pas Android.

### Page professeur

Les cinq onglets sont libres : **Appareils**, **Séance**, **Publication**, **Réception**, **Bilan et PDF**. Séance conserve son brouillon dans ce navigateur, y compris après actualisation, et calcule le parcours (par exemple 1000 m = 2 × 400 m + 200 m). L’option de tours identiques conserve notamment 1000 m en six tours. Publication affiche un récapitulatif et crée une nouvelle séance figée.

Réception distingue les élèves non affectés, les bilans attendus, les bilans reçus et les abandons. Les élèves non affectés ne sont pas comptés comme manquants. Les onglets Réception et Bilan s’actualisent toutes les cinq secondes lorsqu’ils sont visibles, sans chevauchement des actualisations, et conservent l’affichage si le réseau échoue. Les dates de synchronisation ne décrivent pas une présence en ligne.

### Vérifications du parcours élève

Les tests JVM couvrent les calculs, les corrections, le PIN, le secours, la limitation persistante des essais, les verrouillages du ViewModel et les couleurs après restauration. `node --test tests/teacher-page.test.cjs` vérifie les parcours, les compteurs de réception et la lecture du brouillon ; cette commande est également exécutée en CI. Des previews Compose couvrent la préparation, huit coureurs, la série terminée, une tablette large et les grands caractères.

Validation locale : tests JVM, `lintDebug`, `assembleDebug`, construction du serveur ; navigateur Chromium pour les cinq onglets, clavier, largeur mobile, publication, brouillon après rechargement, actualisation périodique et conservation de l’affichage hors ligne. Sur Xiaomi, une copie séparée `fr.rythmo.validation` a permis de vérifier la création du PIN, la confirmation du secours, le reverrouillage en arrière-plan, la continuité du serveur, le blocage de son démarrage pendant une course, la reprise des passages, le PDF individuel et le report de la configuration initiale pendant une course existante, les grands caractères et le maintien du chrono lors du défilement. Un changement de configuration conserve le déverrouillage. Les données de l’application principale sont préservées. Huit arrivées simultanées, l’annulation auditée, les huit PDF et la nouvelle révision après annulation ont aussi été vérifiés sur ce téléphone. La découverte Wi-Fi et l’utilisation sur plusieurs appareils restent à vérifier en conditions de cours.
