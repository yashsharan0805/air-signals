# Timers and alarms — 0.7 source build

Air Signals schedules its own countdown and calendar alerts on Android, then uses the existing short-lived Fitbit Air haptic transport at expiry. It does not create native Fitbit alarms or mirror Clock apps. There is no verified phone-facing tap event, so Stop and Snooze are phone controls.

## Behavior

- One countdown, between one second and seven days, with pause/resume, cancel, and restart. A replacement countdown invalidates the previous occurrence.
- Up to twenty one-time or weekday alarms, each with a label, A/B/C signal, enabled state, next-occurrence skip, and five-minute snooze. Add opens a Material 3 clock dial first; the keyboard toggle supports direct input. Both the picker and alarm list follow the phone’s 12/24-hour setting. Confirming a time opens the remaining alarm options, and tapping the selected time reopens the picker. Cancel never commits a changed time.
- A user grants Alarms & reminders access and phone notification permission. Bluetooth permission is needed only for wrist delivery. Phone alarm sound is independently optional and uses alarm volume.
- The notification relay, selected-app rules, and screen-on notification muting do not gate scheduled alerts. The listener is not used to process the app's own expiry notifications.
- A ringing session lasts at most two minutes. Stop cancels pending groups; an already-sent write is allowed to finish verification. Concurrent ringing alerts keep individual Stop/Snooze actions and share serialized wrist delivery.
- Wrist repetition happens fifteen seconds after a completed signal, with rotation between concurrent alerts. A failed or ambiguous result or unverified channel handoff pauses wrist delivery for that ringing session; the phone alert remains available. This is deliberate repetition of an active alarm, not an automatic retry of an ambiguous write.
- A short-lived media/connected-device foreground service rings only after an explicit schedule fires. The service does not run throughout the countdown. Per-alert notifications also have Android-managed timeouts in case the process or service exits.

## Scheduling and recovery

`TimeAlertState.kt` contains a testable state machine with injected clock, store, and scheduling backend. `TimeAlertRuntime.kt` supplies Android persistence, exact alarms, broadcast receivers, notification actions, and bounded ringing. `TimeAlertsScreen.kt` owns the timer/alarm UI.

Countdowns and snoozes use `ELAPSED_REALTIME_WAKEUP` and retain a wall-clock deadline for reboot recovery. Normal calendar alarms use `setAlarmClock` and local `java.time` calendar calculations, including DST handling. A fresh occurrence token and a due-time check reject stale/early/duplicate broadcasts. Missed occurrences over sixty seconds late do not ring.

`BOOT_COMPLETED` restores credential-protected state after the first unlock. Time/time-zone changes recalculate calendar alarms while retaining elapsed countdown duration. Package replacement and opening the app restore pending schedules. Android force-stop prevents delivery until the app is reopened; phone power-off and absent Bluetooth prevent wrist delivery. Scheduling permission revocation cancels Android exact alarms, and the UI explains that access must be restored.

`OwnedOperationQueue` serializes all regular Air commands, prioritizes scheduled signals after any current write finishes, checks eligibility again before starting, and scopes cancellation to the originating owner. Stopping notification forwarding cannot cancel a scheduled signal. Notification work can expire while waiting for an alarm, and is checked again before sending. Manual previews/device checks are unavailable during a ringing session.

## Capability boundaries

Google documents firm double-tap dismissal for native Air alarms: https://support.google.com/googlehealth/answer/17033101?hl=en

That does not expose a general tap callback to this app. The upstream passive hardware trials found no phone-facing tap traffic on the tested firmware: https://github.com/ThoreKoritzius/fitbit-air/blob/main/doc/research.md#passive-tap-observation-2026-10-17

The recorded local `/alarms` probe returned `4.01 Unauthorized`; see `validation/air-signals-calibration.json`. Native alarm programming, native timer behavior, gesture shortcuts, and Google/Samsung Clock integrations need separate evidence. No acceleration classifier, polling-based fake tap detector, firmware modification, or arbitrary CoAP writes are included. A connected physical Air is required to re-check these boundaries for another firmware.

## Validation procedure

Build with the existing project signing identity when preparing an update:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Use an ARM64 Android 16/API 36 emulator with audio muted. Install the app and instrumentation APKs. Grant `POST_NOTIFICATIONS` and allow the `SCHEDULE_EXACT_ALARM` app-op; leave Bluetooth and notification-listener access denied. Run `dev.codex.fitbitairlab.test/androidx.test.runner.AndroidJUnitRunner` with `am instrument -w -r`.

Coverage includes monotonic countdowns, pause/resume, stale events, lateness, reboot recovery, weekly/one-time schedules, DST, skip-next, snooze across midnight, concurrent alerts, JSON persistence, exact-access changes, owner cancellation and priority, UI persistence, real Android expiry broadcasts, media foreground-service startup, Stop/Snooze PendingIntents, and notification cleanup if ringing stops.

Review light/dark layouts, large text, and timer/alarm editor controls. On a physical phone/Air, measure expiry-to-buzz latency, verify Stop/Snooze and strength restoration, test Bluetooth loss and screen lock, and check Google Health synchronization. Emulator success does not establish physical wrist vibration or real-device battery reliability.
