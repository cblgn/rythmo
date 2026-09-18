# Rythmo

Évaluation de demi-fond hors ligne, pour Android, avec serveur enseignant local sur PC ou téléphone Android.

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

1. Choisir **Chronométrer un groupe**.
2. Garder l’adresse `http://127.0.0.1:8765`, renseigner le code d’association et nommer l’appareil.
3. **Récupérer la séance**, sélectionner de 1 à 8 élèves, puis **Préparer le groupe**. Cette étape réserve les élèves pour éviter les doublons entre appareils.
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

Internet n’est pas nécessaire. Sur le même LAN, **Rechercher le professeur** utilise UDP 8766 ; les échanges HTTP locaux utilisent TCP 8765. L’adresse peut être saisie manuellement. Le point d’accès et les autorisations réseau doivent être préparés par l’enseignant. Sous WSL2, la découverte Wi-Fi et l’accès depuis le LAN dépendent du réseau miroir / routage et du pare-feu Windows ; le tunnel USB permet de développer sans cette configuration.

L’APK possède également un **Espace enseignant** : démarrer le serveur, puis ouvrir l’interface de gestion. Une notification indique que le serveur est actif. Pour l’utiliser après les tests USB, libérer le port avec `./scripts/connect-server.sh --disconnect`. Les serveurs PC et téléphone ont des stockages distincts : synchroniser la séance vers le serveur qui l’a créée.

## Fiabilité et stockage

- Chaque passage est enregistré avant d’être confirmé à l’écran. Les fichiers JSON sont écrits puis remplacés atomiquement.
- Les chronos utilisent l’horloge monotone du téléphone. Une fermeture de l’app conserve les passages ; les PDF manquants sont régénérés à la réouverture. Un redémarrage du téléphone interrompt le chrono et impose de clôturer les coureurs encore actifs.
- Les PDF sont stockés dans les fichiers privés durables de l’application, avec une version par correction. Les résultats originaux sont conservés.
- Les envois sont idempotents. Une interruption ne supprime aucun résultat ; relancer l’envoi complète les bilans restants.
- Les données PC se trouvent dans `server-data/`. Conserver ce dossier pour garder les séances et les résultats. Ne pas désinstaller l’application Android pour mettre à jour : utiliser l’installation `-r` du script.
- Le mode individuel historique reste accessible depuis l’accueil ; le stockage durable des séries et la synchronisation concernent le nouveau parcours groupe.

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

Pour les vérifications seules, après préparation de l’environnement Java/SDK par les scripts :

```bash
./gradlew test
./gradlew assembleDebug
./gradlew :server:installDist
```

`core` partage les calculs, modèles, barèmes et protocole entre Android et PC ; `app` contient les écrans, la génération Android des PDF et le stockage client ; `server` lance le serveur PC. Les seules nouvelles bibliothèques sont Kotlin Serialization pour les données JSON et NanoHTTPD pour le serveur embarqué.

L’accueil utilise le logo Rythmo et deux cartes Material 3 pour les rôles. Un écran de démarrage natif avec logo est fourni, sans attente artificielle ; Android 12+ utilise son écran de démarrage système. Le mode choisi et les courses sont conservés entre lancements ; « Accueil » permet de retrouver les deux entrées.

Vérifications effectuées : tests JVM et compilation debug ; interface enseignant dans un navigateur à largeur PC et téléphone ; synchronisation initiale PC → Xiaomi par USB ; huit coureurs, PDF dès la première arrivée, huit PDF finaux et reprise après fermeture de l’app. La dernière version est installée sur le Xiaomi : nouvel accueil avec logo contrôlé, huit courses conservées après mise à jour, mauvais code professeur refusé sans envoi, puis bon code accepté et huit bilans/PDF reçus par le serveur avec les originaux locaux inchangés. Les six tours configurables sont couverts par les tests et leur publication depuis le navigateur. La découverte Wi-Fi avec plusieurs appareils, l’hébergement enseignant sur Android et les actions d’abandon/clôture protégées en course restent à valider en conditions réelles.
