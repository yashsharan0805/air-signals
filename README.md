# Air Signals

**Give selected Android apps their own vibration signal on your Fitbit Air.**

Air Signals listens for notifications from apps you choose and sends a recognizable group of buzzes to your wrist. It runs locally on your phone, with a Material 3 interface and an explicit **Start alerts / Stop alerts** control.

> **Experimental preview · 0.5** — tested on one Fitbit Air firmware and a Galaxy S25+. The transport uses undocumented device behavior; battery impact and long-term reliability are still being evaluated. This is an independent project, unaffiliated with Google or Fitbit.

<img src="docs/images/home.png" alt="Air Signals home screen with connection permissions and alert controls" width="280" /> <img src="docs/images/patterns.png" alt="Three editable wrist signals with one, two, and three groups" width="280" />

## Features

- Assign **A, B, or C** to up to **five apps**.
- Edit each signal to send **1–3 groups**, with an approximate **1.2–3.0 second gap** between groups.
- Preview a signal after stopping alerts and confirming the preview.
- Combine short bursts from the same app, expire stale alerts, and limit repeated deliveries.
- Check that the Air's original vibration strength is restored after each completed operation.
- Use wallpaper-based colors, system light/dark mode, scalable text, and a navigation rail in wide windows.

A **group** can contain several buzzes. This app does not provide raw waveform control, individual pulse duration, or precise motor timing.

## Requirements

| Component | Requirement |
| --- | --- |
| Phone | Android 12L / API 32 or newer; ARM64 |
| Tracker | Fitbit Air, already paired through Google Health |
| Connectivity | Bluetooth enabled, with the Air nearby |
| App permissions | Nearby devices, Android notification access, and the app's status notification |
| Verified hardware | Samsung Galaxy S25+; Fitbit Air firmware `67.20001.253.2` |

Only ARM64 native libraries are included. Other tracker models, firmware versions, and phone configurations have not been verified.

## Build and install

There is currently no published binary release. Build the debug APK from source:

1. Install **JDK 21**, **Android SDK Platform 36**, **Build Tools 36.0.0**, and **Android Platform Tools**. Android Studio can install the SDK packages.
2. Configure `JAVA_HOME` and `ANDROID_HOME` for your local JDK and Android SDK.
3. Clone and build:

   ```sh
   git clone https://github.com/yashsharan0805/air-signals.git
   cd air-signals
   ./gradlew :app:assembleDebug
   ```

4. Install on an attached, authorized Android device:

   ```sh
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

The Gradle wrapper is included; no global Gradle installation is needed. The first build downloads dependencies. You can also open the repository in Android Studio and run the `app` configuration.

Debug builds use your machine's debug signing key. Updating an existing installation requires the same signing identity; Android rejects an update signed with another key. The optional `AIR_SIGNALS_KEYSTORE` configuration is for retaining an existing local diagnostic identity with alias `air-lab`. Signing keys and passwords are not included in the repository.

## Set up your signals

1. Pair the Air in Google Health and check that the connection works there.
2. Open **Air Signals → Home** and allow Bluetooth access, notification access, and the status notification.
3. Open **Apps**, search for an app, and choose **Signal A**, **B**, or **C**. Choose **Off** to remove an assignment. Tap **Save** at the top.
4. Open **Patterns** to edit the group count and gap. **Save** applies a change; **Cancel** preserves the previous signal.
5. Return to **Home → Start alerts**. The persistent notification includes a **Stop alerts** action.

Fresh installations start with no app assignments. Stop alerts before previewing a pattern or running the read-only device checks. After a phone restart, or if a delivery failure pauses the service, open the app and start alerts again.

## Delivery behavior

| Behavior | Current setting |
| --- | --- |
| Burst coalescing | A one-second debounce combines nearby notifications from the same app |
| Pending queue | At most five apps, with one pending alert per app |
| Cooldown | Five seconds after a delivery completes |
| Expiry | Pending alerts expire after 45 seconds |
| Filtered notifications | Ongoing notifications, group summaries, and Air Signals' own notifications |
| Delivery failure | Alerts pause; an ambiguous or failed group is not automatically replayed |

A notification arriving while an earlier signal is already being sent can create a later alert. This is not an exact-once delivery guarantee. Connection setup, protocol exchanges, and channel handoff add latency; operation completion time does not measure when the wearer first felt a buzz.

### How it reaches the Air

Android's `NotificationListenerService` identifies notifications from selected apps. The app then uses the open-source transport from [`ThoreKoritzius/fitbit-air`](https://github.com/ThoreKoritzius/fitbit-air):

```text
Selected app notification
        ↓
Android notification listener
        ↓
Bounded queue and A/B/C signal
        ↓
BLE → Gattlink → DTLS → CoAP
        ↓
Fitbit Air vibration feedback
```

The current implementation triggers feedback through a temporary vibration-strength setting sequence, then reads back the original strength. It hands the tracker channel back and releases its GATT client after each operation. Pairing remains managed by Google Health.

## Privacy and permissions

Android grants notification access system-wide. Air Signals filters by the selected app first and uses package identity, notification ID, timing, and flags. It does **not** read notification bodies, titles, senders, contacts, or actions.

- **Bluetooth:** communicate with the paired Air.
- **Notification access:** detect notifications from your chosen apps.
- **Status notification / foreground service:** keep user-started alert routing visible and provide Stop controls.
- **Wake lock:** allow an active delivery to complete while the phone is idle.

There is no `INTERNET`, location, contacts, or health-data permission. Local private diagnostics contain selected app package names, timing, and transport outcomes. The debug build can be inspected through ADB by an authorized developer.

## Verification and known limitations

| Check | Result and scope |
| --- | --- |
| Android build and lint | Passed; zero lint errors and 28 warnings in the recorded 0.5 build |
| Emulator UI tests | Six checks passed on an ARM64 Android 16 / API 36 emulator |
| UI review | Light mode, dark mode at 150% text size, and a wide window with navigation rail |
| Physical vibration tests | In 0.4, the wearer distinguished A/B/C and confirmed notification delivery and locked-phone burst coalescing |
| Reconnection | Delivery verified after cycling Bluetooth and manually restarting alerts |
| 0.5 phone installation | Installed on the S25+; APK hash verified and Home screen checked in dark mode |

The 0.5 UI tests cover setup gating, search, app assignment saving, pattern Save/Cancel, activity recreation, and screen capture. They exercise local UI/preferences and do not establish physical motor behavior. See the [UI report](docs/validation/ui-0.5.json), [calibration evidence](docs/validation/air-signals-calibration.json), and [notification trials](docs/validation/air-signals-notification-tests.json).

**Google Health coexistence needs further testing.** An earlier build intermittently stalled its alarm list. The 0.4 change to release the idle Bluetooth client passed subsequent short checks with the alarm list loading. That does not establish uninterrupted sync or long-term compatibility.

Battery impact, automatic out-of-range recovery, reboot recovery, all notification types, and other firmware versions remain unverified. A complete TalkBack/accessibility audit has not been performed.

## Development

```sh
# Build and run Android lint
./gradlew :app:assembleDebug :app:lintDebug

# UI tests on a fresh ARM64 emulator
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Use an emulator without notification access or Bluetooth permissions for the setup-gating tests. UI tests change only local test settings and never start the relay or send BLE commands.

| Path | Contents |
| --- | --- |
| `app/src/dev/codex/fitbitairlab/` | Compose UI, app assignments, queue, notification listener, and foreground service |
| `app/src/dev/airpulse/fitbit_air/` | Modified Fitbit Air transport |
| `app/src/com/fitbit/goldengate/` | Open-source Golden Gate Kotlin bindings |
| `app/src/io/flutter/` | Minimal channel adapters for the native Android host |
| `vendor/fitbit-air/` | ARM64 Golden Gate native libraries |
| `app/tests/` | UI and local-preference instrumentation tests |
| `docs/` | Usage guide, screenshots, and validation records |

When reporting a bug, include the app version, Android version, tracker firmware, and a reproducible test using synthetic notifications. Keep message content, device addresses, and other personal data out of public reports.

## Credits and license

Air Signals' original application changes are available under the [MIT license](LICENSE). Third-party components retain their respective licenses.

The transport is derived from [Thore Koritzius' `fitbit-air`](https://github.com/ThoreKoritzius/fitbit-air), pinned at [`5f76f5f8d48d1dab420feeebc6ac87f52be78ba4`](https://github.com/ThoreKoritzius/fitbit-air/tree/5f76f5f8d48d1dab420feeebc6ac87f52be78ba4). Golden Gate, Mbed TLS, and lwIP provide the native transport layers. See [third-party notices](THIRD_PARTY_NOTICES.md), [source provenance](app/provenance.json), and the [bundled license texts](app/assets/licenses/).
