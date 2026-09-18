# GitHub : publication, CI et sécurité

Dépôt : **[cblgn/rythmo](https://github.com/cblgn/rythmo)**. Le socle reprend les
protections de Storybook, adaptées à Kotlin/Android et au serveur JVM. Son état
effectif doit être vérifié avec `python3 scripts/verify-github-security.py` ; un
fichier de configuration seul ne protège pas une branche.

## Données publiables

L'historique public démarre sur une copie contrôlée du code et d'exemples fictifs.
Les anciennes tables de barème ne sont pas simplement supprimées du dernier
commit : elles ne doivent apparaître dans **aucun commit public**. L'historique
privé antérieur reste dans une archive privée distincte.

Sont exclus : classeurs Excel, anciennes extractions JSON, analyse du classeur,
`server-data/`, `local-data/`, PDF élèves, configurations locales, `.env`, clés et
outils téléchargés. Les identités de démonstration sont inventées. Les exemples
JSON publics sont fictifs et ne constituent pas des consignes pédagogiques.

Le contrôle `scripts/check-public-files.py` refuse les chemins de données locales,
et Gitleaks recherche des secrets dans l'historique. Aucun de ces outils ne peut
reconnaître toutes les données personnelles : vérifier le contenu des PR.

## Contrôles obligatoires

Chaque push sur `main` et chaque PR lancent les vérifications concernées :

- `Android and JVM` : validation du wrapper Gradle, `test`, `lintDebug`,
  `assembleDebug` et `:server:installDist` avec JDK 17, SDK 35 et Build Tools 36.
- `Secrets and workflow checks` : Gitleaks, fichiers interdits, Actionlint,
  syntaxe des scripts et tests des outils CI.
- `Resolve dependencies` : graphe Gradle complet, incluant bibliothèques
  transitives, compilation et tests. Les scopes sont classés sans exclusions.
- `Dependency vulnerability audit` : OSV-Scanner sur toutes les versions
  résolues ; une vulnérabilité connue fait échouer le contrôle, quelle que soit
  sa sévérité. Aucun fichier d'exceptions n'est utilisé.
- `Dependency review` : refuse les vulnérabilités introduites par une PR, en
  attendant les snapshots Gradle de base et de la branche.
- `CodeQL` : quatre analyses, `java-kotlin`, `javascript-typescript`, `python` et
  `actions`. Kotlin est réellement compilé pendant l'extraction.

Les audits de dépendances et CodeQL tournent également chaque semaine. Le
snapshot Gradle est envoyé à GitHub dans un workflow séparé, sans checkout ni
exécution du code de la branche avec le jeton en écriture. Il est transmis aussi
lorsque l'audit révèle une vulnérabilité, pour alimenter les alertes Dependabot.

L'APK debug et les rapports de tests/lint sont téléchargeables depuis le run CI
pendant sept jours. La CI n'installe rien sur le téléphone. Les scripts WSL
`phone.sh` et `logs.sh` restent disponibles.

## Protection de main

Le ruleset `.github/rulesets/protect-main.json` impose une PR, la résolution des
discussions, les neuf contrôles ci-dessus avec une base à jour, et l'absence
d'alertes CodeQL selon les seuils configurés. Les checks sont associés à
l'application GitHub Actions (integration 15368). Il interdit force-push et
suppression, impose un historique linéaire et ne prévoit **aucun bypass**.

Zéro approbation imposée permet au mainteneur de travailler seul. Cela ne dispense
ni de PR ni des contrôles. Les fusions se font en squash ; les branches fusionnées
sont supprimées. Le dépôt public permet ces protections avec GitHub Free.

## Actions, secrets et signalements

Les Actions sont épinglées par SHA complet, également imposé par le réglage du
dépôt. L'allowlist autorise les Actions de GitHub, `gradle/actions/*`,
`dependabot/fetch-metadata` et le scanner officiel SonarSource. Le jeton est en
lecture seule par défaut ; aucune approbation automatique de PR n'est autorisée.
Les checkouts ne conservent pas d'identifiants. Les PR ne peuvent pas écrire dans
le cache Gradle de `main`.

Activer et vérifier : Dependabot alerts/security updates, secret scanning,
protection des secrets avant push et signalement privé de vulnérabilités. CodeQL
utilise le workflow explicite ; ne pas activer un second default setup en parallèle.

Gitleaks, Actionlint et OSV-Scanner sont téléchargés depuis leurs versions
épinglées et vérifiés par SHA-256. Leurs versions se maintiennent dans
`scripts/ci-tools.py` (revue mensuelle, pas de mise à jour Dependabot de ces binaires).

## Dependabot

Recherche chaque lundi, heure de Paris : cinq PR Gradle maximum, trois PR Actions.
Versions mineures/patch groupées ; versions majeures distinctes. Les alertes de
sécurité sont activées indépendamment du calendrier.

Les mises à jour AGP, Gradle et Kotlin restent **manuelles** : vérifier leur
compatibilité ensemble. Les autres mises à jour mineures/patch de Dependabot
peuvent demander l'auto-merge ; elles restent soumises au ruleset et aux analyses.
La politique vérifie l'auteur et les commits du bot. Son workflow privilégié
`pull_request_target` ne fait aucun checkout et n'exécute aucun code de la PR.
Les versions majeures, drafts et métadonnées non vérifiables ne sont pas éligibles.
Activer `DEPENDABOT_AUTOMERGE=true` seulement après vérification du ruleset.

## Audit initial et entretien

L'audit initial du 18 septembre 2026 révélait 51 alertes dans l'ancien outillage.
La mise à niveau vers AGP 9.4.1, Gradle 9.7.1 et Kotlin 2.4.10, complétée par six
contraintes minimales de versions sur des bibliothèques d'outillage, a supprimé
les vulnérabilités trouvées par OSV : 272 versions résolues, zéro alerte lors du
contrôle local. Ces contraintes ne rajoutent pas de bibliothèques à l'application.
Les tests JVM, Android Lint et le build debug passent après ces changements.

Ce résultat décrit un instant donné ; une nouvelle alerte doit faire l'objet d'un
correctif. Consulter [les alertes](https://github.com/cblgn/rythmo/security/dependabot)
et les runs actuels plutôt que déduire l'état de sécurité de ce document.

## SonarQube Cloud : connexion à terminer

Le workflow `sonar.yml` et `sonar-project.properties` préparent une analyse de
`main`, à la manière de Storybook, sans exécuter de PR avec le secret Sonar.
Le projet dédié `cblgn_rythmo` et son secret ne sont pas créés automatiquement.
Importer Rythmo dans l'organisation Sonar `cblgn`, configurer une analyse par CI,
puis ajouter **SONAR_TOKEN** dans les secrets Actions de Rythmo et mettre la
variable **SONAR_ENABLED** à `true`. Ne jamais coller le jeton dans une issue,
un fichier ou une conversation. Le jeton Storybook n'est pas copié.

Le script vérifie l'arrêt de l'analyse automatique avant de transmettre les
sources. L'analyse attend le quality gate. Le workflow apporte les résultats
JUnit ; une mesure de couverture XML dédiée reste à configurer. Sonar n'est pas
un check de PR obligatoire ; les contrôles GitHub fonctionnent sans lui.

## Vérifier

```bash
python3 scripts/ci-tools.py
python3 scripts/check-public-files.py --history
.tools/ci/gitleaks git --redact --no-banner --log-opts="--all"
.tools/ci/actionlint -shellcheck=""
python3 -m unittest discover -s scripts/tests -v
python3 scripts/verify-github-security.py
```

Le dernier script utilise un `gh` authentifié en lecture. Il échoue si la branche
n'est pas réellement protégée, si un réglage diverge, si une alerte reste ouverte
ou si une analyse CodeQL du `main` actuel manque. Il n'accepte pas silencieusement
une limitation de l'offre GitHub.

Références : [rulesets GitHub](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets),
[soumission Gradle](https://github.com/gradle/actions/blob/v6.3.0/docs/dependency-submission.md),
[OSV-Scanner](https://google.github.io/osv-scanner/),
[automatisation Dependabot](https://github.com/dependabot/fetch-metadata).
