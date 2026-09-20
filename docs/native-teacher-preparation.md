# Native teacher preparation V1

The Android teacher tab prepares a lesson without a PC or running service. Enter a
teacher display name, session title, class, distance, equal lap count, imported
performance table and total maximum. Defaults are 1000 m, six laps and /12. Review,
then publish and advertise over Nearby. Publishing freezes a new session; previous
sessions, groups and results remain available. The browser retains devices,
reception and PDF results; preparation/publication navigation is retired.

## Local imports

Use Android's document picker. Returning from it may require unlocking the teacher
screen again: the file is staged, and saving requires an unlocked teacher. No source
file is stored by Rythmo. Data and the draft remain in private Android storage.

Roster CSV (UTF-8) or XLSX: NOM, Prénom, Sexe. Header detection ignores case/accents;
columns, sheet and first data row can be adjusted. Accepted profiles include F/Fille
and M/G/Garçon. Choose class name and level separately. Preview all pupils before
saving; invalid pupils and ambiguous duplicates are rejected. Reimport retains IDs.

Performance files contain time/point tables for girls and boys. Select sheets,
columns, time format and first data rows, and record the source maximum and applicable
distance. No extrapolation to another distance. A manual time-cell correction affects
only the import preview and must be explicitly entered as minutes:seconds. Original
workbooks are never modified; formulas are not executed. Limits remain 2 MB input,
8 MB expanded ZIP data, 1000 rows, 50 columns, 30 sheets. Real tables must not enter Git.

## Scoring and compatibility

Schema 2 compares `(lapDurationMs + 500) / 1000` for adjacent equal laps. The current
rounded duration must be less than or equal to the previous rounded duration. Each
success earns one point; N laps have N−1 possible points. Original millisecond times
and pace colors are unchanged. Incomplete and abandoned races remain ungraded.

Performance uses the original lower-bound lookup table without interpolation, clamped
to its first/last row outside the range. With source score P, source maximum S, chosen
total M and regularity R, the exact total is `R + P × (M − (N−1)) / S`. Integer ratios
retain precision. Total and /20 are independently rounded half up to tenths, not one
from an already rounded other. Fictional example: 5 regularity points, 6/7 performance,
chosen maximum 13 gives 11.9/13 and 18.2/20.

Schema 1 and demonstration grading retain their old behavior. Protocol 5 identifies
schema-2 snapshots, so older clients fail explicitly instead of grading incorrectly.
Updated clients accept protocols 3, 4 and 5. Nearby still transmits no live passages.

`AndroidTeacherRepository` owns one shared TeacherStore per process. Native preparation,
HTTPS and Nearby therefore never load competing copies of the archive. The separate
preparation file holds the draft and reusable source tables. Writes are serialized and
run off the main thread. Teacher ViewModel mutations require the local lock.

## Sharing, PDF and interactions

Discovery advertises teacher name, class and session title. Names can change without
disconnecting already associated endpoints. One detected host prompts code comparison;
multiple hosts require a choice. A name is not proof of identity. Only the active
session is downloaded; local timing and server ownership protections still apply.

PDFs keep the passage table and show a compact timing summary, the applied table
threshold, source performance score, weighting, regularity rule and total. The /20
note is the final block, with no footnotes after it. Grading stays together on a page.
Old PDF files are retained; the new layout applies to newly generated reports.

Pupil cards draw their Material ripple inside the card surface, with a short elevation
animation. Press feedback is immediate; saved-count/pace feedback only follows durable
storage. Long press undo and group capture remain supported.

## Validation

Local checks cover 95 JVM tests, teacher-page JavaScript tests, `lintDebug`,
`assembleDebug`, `bundleRelease`, server distribution and coverage report generation.
The Android PDF smoke test uses the platform renderer on a real Android device:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w fr.rythmo.test/fr.rythmo.PdfValidationInstrumentation
```

Use the appropriate device serial and application ID for isolated validation builds.
The instrumentation writes only fictional reports into a unique cache directory,
checks grading and renders six-lap, twenty-lap and legacy reports. Inspect the
rendered pages to check layout; a successful render alone does not prove readability.

For the complete two-device check, import a fictional roster and a local performance
table on the teacher device, preview and publish. On the pupil device, retrieve the
session and compare Nearby association codes. Check the frozen settings, start a
group, record passages offline, restart midway, finish and check each PDF. Reconnect
and send reports using the session's separate teacher code. Verify receipt and that
previous sessions and reports remain accessible. No live passages are transmitted.

Physical validation completed on Xiaomi Mi 9T Pro (teacher) and Samsung SM-T720
(pupil), in isolated validation packages:

- CSV/XLSX selection and staging across the teacher lock; class preview/save and
  explicit performance-cell correction in the native form.
- Native publication of 1000 m / six laps / regularity 5 + performance 8, followed
  by authenticated Nearby retrieval of the identical protocol-5 snapshot.
- Two fictional pupils timed offline, with restart after the third passage,
  portrait and enlarged-text checks, then independent finish PDFs. No live result
  transmission occurred.
- Reconnection, two durable receipts, matching local/received PDF bytes and correct
  independently rounded grading on the teacher. Previous sessions/results retained.
- Platform PDF rendering for six laps, twenty laps and legacy grading. One-page and
  two-page layouts inspected; the final /20 block stays last.

The Xiaomi system document picker required direct finger selection: injected ADB
taps did not select its files. Both imports completed after that selection. Device
sleep, orientation, text-size and Wi-Fi settings were restored after testing.
