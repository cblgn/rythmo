# Teacher preparation and timing

**Superseded for new Android sessions:** see [Native preparation V1](native-teacher-preparation.md).
The browser preparation flow below describes the preceding iteration; V1 now uses
the Android form and protocol 5 for rounded regularity and weighted source tables.

The previous teacher console offered **Classe → Épreuve → Barème → Aperçu → Publier**.
Android provides **Séance / Appareils / Bilans** entry points; PC/LAN settings are
secondary. Published sessions remain immutable.

## Classes and files

Import UTF-8 CSV or XLSX, choose a sheet, map first name, last name and profile,
and choose the first data row. Inspect the preview before using it in the draft.
Save the class explicitly. Reimport matches the selected existing class and
normalized full names, retaining pupil IDs; ambiguous duplicate names are rejected.
Rename ambiguous pupils before importing. Published rosters never change on reimport.

Files are read locally. XLSX macros and formulas are not executed; formula cells
are skipped and reported. Choose the sheets containing actual values. ZIP size,
expanded content, rows and columns are bounded; external XML entities are rejected.
Legacy `.xls` and non-UTF-8 CSV are not supported: export XLSX or UTF-8 CSV first.
The preview does not save the source file. No real workbook or table belongs in Git.

An exported Rythmo preparation JSON contains the class roster, course and rubric,
not credentials, receipts or results. Import validates its version and loads only
the draft. Publishing always creates a new session and rubric version.

## Configurable distance assessment

This first model supports a fixed total distance and equal laps or fixed passage
spacing. Duration-based assessments, plots and multi-phase 12-minute courses remain
future work; they require different recording interactions.

Performance tables use integer millisecond lower bounds and integer tenths of points:
select the last threshold not exceeding the total time, with no interpolation.
Outside the table, use the first or last row. Times strictly increase and points
cannot increase. Imports explicitly choose seconds, minutes:seconds or Excel day
fractions, which are converted to integer milliseconds.

Compare interval N against N−1. A comparison succeeds when `duration[N] −
duration[N−1] <= configuredThresholdMs`. Six intervals give five comparisons.
Threshold 0 rewards equal or faster intervals; −2000 requires at least two seconds
of acceleration. A shorter final segment is excluded from these comparisons.
The first interval is only a reference. The comparison subscore is the configured
maximum multiplied by successful comparisons / eligible comparisons, rounded to
the nearest tenth (half up). Add the performance subscore and convert the resulting
sum proportionally to /20, also rounded half up. Incomplete/abandoned pupils are ungraded.

`AssessmentRubric` and `AssessmentScore` live in `core`; Android PDFs and teacher
results use those same calculations. Legacy demo assessments still load unchanged.
Snapshots carrying a configurable assessment announce protocol 4 so old clients
reject them rather than silently applying their demonstration formula. Updated
clients accept both protocol 3 and 4. Nearby still sends no live passages.

## Track interface and PDF

The shared clock remains fixed above the responsive grid. Each elevated Material
surface centers the pupil's name and places progress at the bottom right. Four
seconds of feedback follow a saved passage. Finished pupils use a neutral surface,
a flag, their final time and an explicit PDF state; pace remains blue/green/red.
Large text can reduce the number of columns and enable scrolling without moving
the shared clock. Order stays stable across window changes.

Editing intervals is removed from both app modes. Historical corrections remain
readable and keep their effects on old results. The short undo window for accidental
passages remains. Group capture retains one timestamp for the selected pupils;
queued rapid captures remain deferred until field feedback demonstrates a need.

PDFs use the orange track identity, place cumulative time in the last table column
and total duration last in the summary. They show both component scores, the rubric
total and its conversion to /20. Reports are still created independently at arrival.

The teacher notification opens a protected stop confirmation. Backgrounding or
removing the activity from recents does not intentionally stop session sharing.
Stopping the service removes its foreground notification.

## Validation

Run JVM tests, `node --test tests/teacher-page.test.cjs`, `lintDebug`, `assembleDebug`
and `:server:installDist`. Build and configuration caches are local; repeat the same
Gradle command to confirm `Configuration cache entry reused` without source changes.
No remote cache or coverage exclusions were added.
CodeQL and dependency graph generation explicitly disable the configuration cache;
CodeQL also retains its existing forced compilation without the build cache.

Check class reimport, frozen publication, draft reload, table boundaries and /20
rounding. Inspect portrait/landscape with 1–8 pupils and large text. Verify undo,
offline restart, individual finish PDFs, notification PIN protection and stop.

Design references: [Material 3 cards](https://m3.material.io/components/cards/guidelines),
[Android adaptive layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive/get-started-with-adaptive-apps).

### Local validation, 20 September 2026

- 88 JVM tests passed (39 core, 49 Android), plus the teacher JavaScript tests.
  `lintDebug`, `assembleDebug`, `bundleRelease`, server distribution and both JVM
  coverage reports completed successfully. A repeated build reused the configuration
  cache. Workflow lint and the public-file check passed.
- Chromium exercised CSV import, stable IDs on reimport, frozen publication, draft
  reload, preparation export/import, mobile widths and keyboard tab navigation.
  XLSX preview also passed in Chromium and on Android's native XML parser.
- Xiaomi Mi 9T Pro as teacher and Samsung SM-T720 as pupil device, both Android 11:
  a fictional eight-pupil 1000 m / six-interval session was retrieved over Nearby.
  Timing continued offline, survived process restart and generated eight one-page
  PDFs independently. Reassociation with matching codes and explicit upload produced
  eight persisted receipts; server times, PDF bytes and component scores matched.
  Existing sessions and results were preserved in the validation installations.
  The final tablet layout displayed eight pupils in two portrait columns and four
  landscape columns; installing the final APK preserved both client archives.
  At 1.6× text size, the grid scrolls while the header stays fixed. A final-time
  overlap found on the tablet was fixed by constraining and centering that text
  within each pupil button, then checked again on the device.
- The notification stop intent required the teacher PIN and confirmation, then
  removed the notification. Sharing was restarted for the upload test.

Physical tests use `fr.rythmo.validation`, not the main application. Ordinary pupil
taps and shared timestamp batches passed; an automated rapid sequence using cached
screen coordinates did not reliably target all eight buttons. This is not a measured
rapid-tap performance benchmark. Queued captures and newer Android permission flows
still need separate field validation. Local success does not constitute a new GitHub
CI or Sonar run for these uncommitted changes.
