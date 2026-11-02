# Air Signals

Android notification-to-vibration bridge for Fitbit Air. The working 0.4 preview supports per-app A/B/C groups, a user-started foreground relay, burst coalescing and strength restoration checks.

## Build

The baseline uses the focused build helper `python3 scripts/build.py` with Android SDK 36/build-tools 36.0.0, Java 21, Kotlin 2.1.20 and cached Maven dependencies listed in the script. Set `JAVA_HOME` and `ANDROID_HOME` for your machine. The helper downloads RxJava/Timber when absent. Outputs and generated debug signing keys are ignored. Use `TASK_KEYSTORE` to retain an existing local signing identity when updating an installed build. No signing key is committed.

## Source and provenance

- `app/src`: modified transport, native host and minimal Flutter channel adapters.
- `app/assets/licenses`: upstream and runtime dependency notices.
- `vendor/fitbit-air`: upstream ARM64 Golden Gate native libraries.
- `app/provenance.json`: source URL, exact commit and modifications.
- `docs/guide.txt`: setup and behavior.
- `docs/validation`: device results and explicit validation limits.

Transport upstream: https://github.com/ThoreKoritzius/fitbit-air at `5f76f5f8d48d1dab420feeebc6ac87f52be78ba4`. No Google Health code, APKs, credentials, personal notification content or signing keys are included. See bundled license notices.

## Validation boundary

On the S25+ and Air firmware 67.20001.253.2, the user confirmed distinguishable groups, synthetic notification delivery, locked-phone burst coalescing and delivery after Bluetooth cycling plus manual alert restart. Google Health alarms loaded after the 0.4 trials. Earlier versions intermittently stalled that screen. Battery impact, automatic out-of-range recovery and long-term coexistence remain unverified.
