# Android Nearby MVP

Android hosts can advertise a session using Google Nearby Connections `P2P_STAR`.
No manually configured hotspot, IP address, Internet connection or Google account sign-in is required
by Rythmo. Compatible Google Play Services, Bluetooth/Wi-Fi radios and Android
permissions are required. On Android 11, discovery also requires location enabled.
Google documents SDK diagnostic collection under the device's Usage & diagnostics
setting; Nearby is not a claim that Google Play Services makes no network requests.

## Connection flow

1. Teacher access → unlock → open the local teacher console and publish the session.
2. In teacher access, select **Rendre la séance disponible à proximité** and allow
   the required Android permissions. The teacher service stays active after leaving
   settings; connected devices are listed separately from historical synchronization.
3. On the student tablet, select **À proximité → Récupérer la séance**. A single
   discovered host is proposed automatically; choose the correct host if several
   are found. Compare the code on both devices and accept on both sides. The teacher
   must unlock teacher access before accepting if it has relocked.
4. Connection triggers a complete session snapshot automatically. Select 1–8 pupils
   and validate the group. The host rejects pupils already assigned elsewhere.
5. Time the race locally. Disconnecting, closing the UI or leaving radio range does
   not discard saved passages. No timing, correction or cancellation is sent live.
6. After finishing, **Reconnecter le professeur** if needed, compare the new code,
   then **Envoyer les bilans** and enter the session teacher code. PDFs remain local;
   only the server's durable receipt marks the result received.

## Architecture and compatibility

`core` owns JSON `SyncMessage`, `SessionSyncClient`, `SessionSyncServer`, the transport
contracts, the snapshot merge and existing domain/store validation. The protocol
supports only snapshot retrieval, group assignment and explicit final-result upload.
Messages have protocol version 1, type and request ID; snapshots retain HTTP protocol
3 and add optional server identity and durable state revision. Unknown commands or
incompatible versions are rejected. Existing HTTPS endpoints remain compatible.

`NearbyTransport` owns only Google SDK interaction, discovery, connection callbacks
and bounded message transfer. Its state is serialized on the main dispatcher; JSON,
stream reads and server writes run on IO. `NearbySessionHost` binds an authenticated
endpoint to its introduced device identity; group/result validation remains shared.
The teacher's browser and Nearby use the same `TeacherStore` instance.

Small messages use BYTES. Messages over 32 KB use STREAM, capped at 3 MB and accepted
only after both complete reading and successful transfer. This deliberately replaces
the planned FILE payload: Nearby FILE reception uses public Downloads. Streams avoid
leaving student reports or authorization fields there. Interrupted streams are closed,
bounded and timed out. No external-storage permission is needed.

Snapshots update preparation without replacing local races. Groups record their
origin server; an old group can acquire the server identity only from a matching
server-confirmed claim. Results use the existing runner/revision idempotency rules.
A lost receipt can be retried without a duplicate result. Local files are not deleted.

**Réseau local / PC** retains HTTPS certificate pinning and the existing pairing flow.
There is no silent fallback, WebRTC, live broadcast, cross-device clock transfer or
restoration of an in-progress race onto another tablet. The source tablet retains
its clock and all unsent data. A full device reboot still interrupts the monotonic
clock as before. Every new Nearby connection requires code comparison in this MVP.

## Validation

Run `./gradlew test lintDebug assembleDebug :server:installDist` using the repository's
Android build environment. `SessionSyncTest` exercises a serialized fake transport,
late join, offline timing, snapshot refresh, commands, server validation, receipts,
large messages, malformed/versioned messages and preserved local archives.

For physical validation, install the validation application ID on Xiaomi (host) and
Samsung tablet (client), retaining both devices' existing archives. Use fictional
pupils only. Verify discovery, rejection and acceptance, eight pupils/six passages,
offline restart, reconnect, eight receipts and byte-identical PDFs. Compare server
state before and during timing to confirm no live results. Exercise an upload larger
than 32 KB to validate the stream path. Two devices validate one physical client;
several simultaneous physical clients require additional devices.

References: [Nearby overview](https://developers.google.com/nearby/connections/overview),
[association](https://developers.google.com/nearby/connections/android/manage-connections),
[permissions](https://developers.google.com/nearby/connections/android/get-started),
[payloads](https://developers.google.com/nearby/connections/android/exchange-data).

## MVP validation record

Validated on 2026-09-19 using Mi 9T Pro and SM-T720, both on Android 11, with the
separate `fr.rythmo.validation` application and fictional pupils:

- Matching Nearby association codes confirmed on both devices; snapshot retrieved
  automatically and origin server identity persisted.
- Eight pupils assigned through Nearby; six batch passages recorded with Wi-Fi off
  and no active application connection. Restart after the third passage preserved
  all original times. No live result appeared in the teacher store.
- Eight PDFs generated offline (about 380 KB each), then manually sent through
  Nearby after a fresh association. All eight receipts persisted; every received
  PDF matched its local original byte for byte. Prior sessions/results were retained.
- Physical transfer used the existing Wi-Fi environment. Operation with both radios
  enabled but no shared network still needs a dedicated physical test. Disabling
  the teacher Wi-Fi radio is not equivalent to that scenario.
- 76 JVM tests passed; Android lint reported no errors; debug APK and PC server
  built. OSV found no issues in 318 resolved Maven dependencies.

The main-branch CI and CodeQL checks passed after the authorized history translation,
and GitHub protections were restored and verified. Sonar's existing gate still fails
on new-code coverage (27.9%, threshold 80%); this local feature has not been published
or analyzed by Sonar. Simultaneous physical clients and recent Android permission
flows remain additional device tests, not claims made by this two-device run.
