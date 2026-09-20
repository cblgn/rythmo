# Rythmo — Codex instructions

## Project goal

Rythmo is a small Android application for middle-school PE running assessments.

The current goal is to build a simple, reliable and demonstrable MVP.

Do not over-engineer the project.

Prioritize:

1. working software,
2. simple UX,
3. correctness of race calculations,
4. maintainable code,
5. fast iteration.

## Technology

Use:

* Kotlin
* Jetpack Compose
* Material 3
* Android ViewModel
* Kotlin coroutines where appropriate
* Android `PdfDocument` for PDF generation

The application must work completely offline.

Do not introduce:

* an external backend or Internet-dependent HTTP APIs (the local teacher server and LAN sync are explicitly authorised),
* authentication,
* cloud services,
* unnecessary third-party dependencies.

## Kotlin conventions

Use modern idiomatic Kotlin.

Prefer:

* immutable data classes,
* `val` over `var`,
* small focused functions,
* explicit domain types where useful,
* nullability rather than sentinel values,
* sealed types/enums when representing a finite state.

Avoid:

* unnecessary abstraction,
* premature generic frameworks,
* deeply nested code,
* large ViewModels,
* business logic inside Composables.

Keep code readable without excessive comments.

## Architecture

Keep the architecture simple.

Expected separation:

```text
UI / Compose
     ↓
ViewModel
     ↓
Domain logic
     ↓
Android infrastructure
```

Business calculations must not live inside Compose components.

Race calculations should be implemented in a dedicated domain component such as:

```text
RaceCalculator
```

PDF generation should be isolated from the UI, for example:

```text
PdfExporter
```

Do not introduce Clean Architecture layers purely for architectural purity.

Add abstractions only when they provide an immediate benefit.

## Domain rules

The user has explicitly authorised the session extension: independent groups of 1–8 pupils, a variable number of devices, local teacher server on PC or Android, preparation/download before the lesson and upload of structured results plus PDFs afterwards. Each client owns its monotonic clock. Persist every passage before confirming it in the UI; create each finisher's PDF immediately without waiting for the group. Keep original times, one confirmed correction per segment, revisioned PDFs and receipt acknowledgements. Restarting or selecting another group must never delete saved evaluations.

The shared `core` JVM module owns domain calculations, immutable session snapshots, the demonstration rubric and sync protocol. `server` runs the teacher server on PC; Android uses the same implementation in teacher mode. Eight fictional classes (two per level, 30 pupils each, 15 girls/15 boys) are provided: 6e Mistral/Tramontane, 5e Azur/Indigo, 4e Quartz/Améthyste, 3e Ouessant/Belle-Île. Pupils have stable identifiers.

Session distances range from 400 to 4000 m in 100 m steps. Record passages every 400 m and at the finish. A final shorter segment contributes to the total but is excluded from comparisons with 400 m laps and from the average/progression/irregularity of full laps.

Approved demonstration rubric: reference = 600 seconds × distance/2000 × sex coefficient (boys 1, girls 1.10) × level coefficient (6e 1, 5e .95, 4e .90, 3e .85). Grade = min(20, 20 × reference/total), rounded to one decimal. Store durations as integer milliseconds, coefficients as permille and grades as integer tenths. Regularity does not affect this provisional grade. Abandoned/incomplete races are ungraded. Every published session freezes its own rubric version.

The original individual mode remains available and keeps the following 2000 m workflow.

The MVP handles a 2000 m race composed of five 400 m laps.

Independent lap durations are entered at:

* 400 m
* 800 m
* 1200 m
* 1600 m
* 2000 m

The UI records the duration of each 400 m lap, not the time since the start.
A lap can be faster, slower, or equal to the previous one: any positive duration is valid.
Convert minutes and seconds to integer milliseconds, then add each duration to the previous cumulative time.
For example, entering 1:23 then 1:25 gives an 800 m cumulative time of 2:48 and a +2 s difference, not −81 s.
Record the local start date and time at the first valid COMMENCER action and retain it in the PDF report.
Offer manual entry or automatic stopwatch mode before COMMENCER. In automatic mode, start the clock explicitly and record each passage using Android's monotonic elapsed realtime clock, retaining integer millisecond precision. Stop after five passages. Display the current lap duration separately from total elapsed time. Keep the actual stopwatch start time separately from the evaluation preparation time; show the mode and both timestamps in the PDF. Allow switching modes freely before timing starts. Once timing data exists, switching modes asks to reset the race while retaining the pupil's identity. A Nouvelle évaluation action resets identity, timing, corrections and PDF selection, cancels any running clock and returns to identification; already generated PDFs remain intact. Clear saved correction data as part of reset.
After the fifth passage, allow one confirmed correction per lap. Preserve the initial duration, corrected duration and correction time in the evaluation and PDF. Recalculate statistics using the corrected durations. Invalid or cancelled edits do not consume the correction; the limit must survive state restoration.
The existing RaceCalculator consumes these derived cumulative times and computes laps as follows:

```text
lap[0] = cumulative[0]
lap[n] = cumulative[n] - cumulative[n - 1]
```

Difference between laps:

```text
difference[n] = lap[n] - lap[n - 1]
```

A negative difference means the runner was faster.

A positive difference means the runner was slower.

Use the following visual tolerance:

```text
-1 s <= difference <= +1 s → equivalent / blue
difference < -1 s          → faster / green
difference > +1 s          → slower / red
```

Durations must be represented using integer values such as milliseconds.

Never use `Float` or `Double` as the source-of-truth representation of durations.

## MVP scope

Current MVP features are:

* student last name,
* student first name,
* class,
* automatically generated date,
* 2000 m timing screen,
* five independent lap entries with automatically calculated cumulative times,
* automatic lap calculations,
* differences between laps,
* blue/green/red visual feedback,
* total time,
* average lap time,
* progression,
* cumulative irregularity,
* PDF generation,
* PDF opening/sharing.

Do not implement unless explicitly requested:

* user accounts,
* backend,
* cloud sync,
* Excel import,
* advanced class management,
* historical analytics,
* multiple race types,
* configurable grading policies.

A future scoring abstraction may look like:

```kotlin
interface ScoringPolicy {
    fun score(result: RaceResult): Double
}
```

Do not invent a grading formula until one is explicitly provided.

## UI principles

The application is used next to a running track.

Optimize for:

* one-handed phone usage,
* large touch targets,
* readable text,
* strong contrast,
* minimal number of interactions,
* fast time entry,
* portrait orientation.

Avoid unnecessary dialogs, screens and menus.

Material 3 should provide the general visual language.

## Testing

Domain calculations must have unit tests.

At minimum test the following cumulative times:

```text
1:29
2:58
4:25
5:54
7:21
```

Expected lap times:

```text
1:29
1:29
1:27
1:29
1:27
```

Expected differences:

```text
null
0
-2
+2
-2
```

Also test a perfectly regular race.

When modifying domain calculations, update or add tests before considering the task complete.

## Build and validation

After meaningful changes, run the relevant checks.

Prefer:

```bash
./gradlew test
./gradlew assembleDebug
```

If a command fails because of code introduced by the task:

1. diagnose the failure,
2. fix it,
3. rerun the command.

Do not stop at the first compilation error if it can reasonably be fixed.

Before finishing a task, inspect:

```bash
git status
git diff
```

Do not commit changes unless explicitly requested.

## Development workflow

GitHub : `cblgn/rythmo`. Publication limitée au code et aux exemples fictifs.
Travailler par PR et attendre tous les contrôles : tests, lint, build, secrets,
CodeQL et audit des dépendances. Appliquer le ruleset de protection de `main`
sans bypass ; vérifier son état effectif avec le script ci-dessous. Les mises
à jour AGP, Gradle et Kotlin nécessitent une revue manuelle de compatibilité.
Voir `docs/github-security.md` et `scripts/verify-github-security.py`.
Ne jamais versionner `server-data/`, `local-data/`, PDF, classeurs sources,
tables de barèmes réelles, codes ou `.env`. Les notes et tables privées du
classeur ne font pas partie du projet publiable, y compris dans l'historique Git.

Serveur enseignant PC : `./scripts/server.sh`. Ouvrir le lien affiché (avec sa clé enseignant) dans le navigateur du PC. Les 8 classes fictives sont disponibles immédiatement ; publier une nouvelle séance fige son épreuve, ses élèves et son barème. Le stockage durable reste dans `server-data/` (ignoré par Git).

Pour tester PC ↔ Xiaomi par USB : lancer le serveur, puis `./scripts/connect-server.sh`. Dans le mode Chronométreur, utiliser `https://127.0.0.1:8765` et le code d’association affiché par le serveur. Récupérer la séance, sélectionner au maximum 8 élèves et préparer le groupe pendant la connexion ; la course fonctionne ensuite sans réseau. Chaque arrivée produit son PDF. En fin de série, « Envoyer les bilans » attend la confirmation du serveur et conserve les copies locales. Le tunnel USB ne valide pas la découverte Wi-Fi.

Si le serveur WSL est accessible seulement via IPv6 côté Windows, le script utilise automatiquement `scripts/usb-relay.ps1`, un relais local sans droits administrateur. Garder ce terminal ouvert pendant la synchronisation ; `Ctrl+C` arrête le relais. Le script vérifie que le serveur répond avant de configurer ce relais.

Sur un LAN, le serveur écoute en HTTPS/TLS sur TCP 8765 et répond à la découverte locale en UDP 8766 ; « Rechercher le professeur » trouve les serveurs joignables sur le même réseau. Avec WSL2, l’accès LAN nécessite le réseau miroir ou une configuration réseau Windows adaptée. L’adresse manuelle reste disponible. Aucun port n’est ouvert automatiquement dans le pare-feu.

Le mode Enseignant Android peut héberger le même serveur en service au premier plan. Activer le Wi-Fi ou le point d’accès dans les réglages Android, puis démarrer le serveur. L’interface enseignant locale s’ouvre dans le navigateur. Désactiver préalablement le tunnel USB (`./scripts/connect-server.sh --disconnect`) pour libérer le port 8765 sur le téléphone. Les données des serveurs PC et Android sont distinctes ; une séance doit être récupérée et renvoyée au même serveur.

Depuis WSL, avec le téléphone Android connecté et le débogage USB autorisé :

La première exécution prépare automatiquement les outils manquants, sans `sudo` : JDK 17 Linux, SDK Android 37 Linux et Build Tools 36 dans `.tools/`. Elle nécessite Internet, `curl` et `unzip`, et accepte les licences des composants SDK installés. Les exécutions suivantes réutilisent ces outils. La compilation WSL utilise un SDK **Linux** ; seul ADB utilise les outils **Windows** pour accéder au téléphone USB.

```bash
./scripts/phone.sh
```

Cette commande lance les tests unitaires, construit l’APK debug, cherche `adb.exe` sous `%LOCALAPPDATA%/Android/Sdk/platform-tools/` puis installe et démarre `fr.rythmo`. Si ADB Windows est absent, les platform-tools Windows sont téléchargés dans `.tools/adb-windows/`. Pour itérer rapidement sans relancer les tests :

```bash
./scripts/phone.sh --fast
```

Pour afficher uniquement les logs du processus Rythmo sur le premier téléphone autorisé :

```bash
./scripts/logs.sh
```

Les scripts retournent une erreur explicite si `adb.exe` est absent, si aucun téléphone autorisé n’est connecté, ou si une étape de compilation, d’installation ou de lancement échoue.

Lors du premier branchement, déverrouiller le téléphone et accepter « Autoriser le débogage USB ». L’état `unauthorized` signifie que cette autorisation attend une action sur le téléphone. Sur Xiaomi/MIUI, activer aussi « Installer via USB » dans les Options pour les développeurs et accepter la demande d’installation ; sinon Android renvoie `INSTALL_FAILED_USER_RESTRICTED`. Avec plusieurs appareils, `ANDROID_SERIAL=numero ./scripts/phone.sh --fast` permet d’en sélectionner un ; sinon le premier téléphone autorisé est utilisé. `ADB_PATH` permet de préciser un autre chemin WSL vers `adb.exe`.

Les caches restent dans `.gradle-user/` et `.android-user/` ; `local.properties` pointe vers le SDK Linux retenu. Ces fichiers et `.tools/` sont ignorés par Git. Les outils suivent la [documentation SDK Android](https://developer.android.com/tools/sdkmanager) et les [platform-tools officiels](https://developer.android.com/tools/releases/platform-tools).

## Working style

When given an implementation task:

* inspect the existing code first,
* reuse existing patterns when reasonable,
* implement the feature rather than only describing how to implement it,
* make reasonable technical decisions autonomously,
* avoid asking for confirmation for minor implementation choices,
* keep the solution proportional to this small application.

If something is ambiguous but does not materially affect the product behavior, choose the simplest reasonable solution and continue.

If a decision would materially change the requested behavior, mention the assumption in the final summary.

## Completion criteria

Latest session requirements: the group course can use a fixed passage distance or 1–20 equal laps (notably 1000 m / 6 laps). Equal laps compare durations regardless of rounded metre markers; display lap numbers. Legacy individual 2000 m / 5×400 m remains available. Teacher-only closure, individual abandonment and upload require a separate six-digit teacher code, verified offline with a salted PBKDF2 verifier; uploading also checks the teacher code on the server. Preserve recorded passages when abandoning. Rubric score scales and components must be configurable. The generic JSON format is a proposal; only fictional examples may be published. See `docs/baremes.md` and `data/rubrics/examples/`.

A task is complete when:

* the requested behavior is implemented,
* relevant tests pass,
* the project compiles when the environment allows it,
* there are no obvious regressions,
* the final response briefly explains what changed and any remaining limitation.

## Ajustements approuvés du chrono et de la sécurité

Le chrono commun est centré ; cartes compactes avec prénom + initiale (développer les conflits), dernier cumul et avancement. Après un passage, montrer tour/écart pendant quatre secondes. Les couleurs indiquent l’allure par rapport au tour précédent (tolérance ±1 s), pas le nombre de passages. Le menu ⋮ donne accès au professeur ; aucun slogan.

Passage groupé : capturer l’instant, sélectionner, sauvegarder le lot atomiquement. Bloquer les doubles appuis par élève pendant l’écriture puis une seconde. Appui long d’une seconde + confirmation pour annuler le dernier passage dans les quinze secondes suivant la sauvegarde, y compris une arrivée non corrigée et non envoyée. Conserver l’annulation, les temps initiaux et les anciens PDF ; une relance complète ferme la fenêtre d’annulation.

Protocole 3 : synchronisation HTTPS uniquement sur 8765, console professeur uniquement en boucle locale sur 8767. L’association par PIN local et comparaison du code de vérification épingle le certificat complet ; aucun repli HTTP. Les clés PC restent dans server-data/tls/ ; les clés Android dans Android Keystore. Ne jamais versionner ces fichiers.

Identité choisie : design 1 « Terre battue », orange brûlé et ivoire, piste à trois couloirs verticale, point blanc conservé et mot Rythmo en italique comme le design 3. Le bleu reste réservé à l’allure équivalente, indépendamment de la couleur principale du thème.

## Working language and Nearby MVP

Use English for new commit messages, issue and PR titles/descriptions, and technical comments. Keep the application UI and user conversation in French. The one-time translation of the existing French public commits was explicitly authorized; ordinary work must retain all branch protections.

Nearby Connections P2P_STAR is authorized for Android. Keep SDK dependencies inside Android infrastructure; share JSON synchronization and domain validation with HTTPS. Never transmit passages live. Persist timing locally, retrieve the session after explicit association, and manually upload finished results/PDFs with teacher authorization. A reconnect must not overwrite local races or send them to a different teacher server.
