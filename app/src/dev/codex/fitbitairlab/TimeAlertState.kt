package dev.codex.fitbitairlab

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal interface TimeClock {
    val wall: Long
    val elapsed: Long
    val boot: Int
    val zone: ZoneId
}

internal data class TimeDeadline(val wall: Long, val elapsed: Long, val boot: Int) {
    fun remaining(clock: TimeClock) = if (boot == clock.boot) elapsed - clock.elapsed else wall - clock.wall
    companion object {
        fun after(clock: TimeClock, millis: Long) = TimeDeadline(clock.wall + millis, clock.elapsed + millis, clock.boot)
        fun at(clock: TimeClock, wall: Long) = after(clock, wall - clock.wall)
    }
}

internal data class Countdown(
    val duration: Long, val profile: Int, val state: String = "running",
    val remaining: Long = duration, val deadline: TimeDeadline? = null, val token: String = UUID.randomUUID().toString(),
)

internal data class WakeAlarm(
    val id: String = UUID.randomUUID().toString(), val label: String = "", val hour: Int, val minute: Int,
    val days: Set<Int> = emptySet(), val profile: Int = 1, val enabled: Boolean = true,
    val deadline: TimeDeadline? = null, val token: String = UUID.randomUUID().toString(),
    val snoozed: Boolean = false, val lastFiredDate: String? = null, val skipDate: String? = null,
)

internal data class RingingAlert(
    val key: String, val token: String, val title: String, val profile: Int, val expires: TimeDeadline,
)

internal data class TimeAlertState(
    val timer: Countdown? = null, val alarms: List<WakeAlarm> = emptyList(),
    val active: List<RingingAlert> = emptyList(), val phoneSound: Boolean = true,
    val permissionWasGranted: Boolean = false, val lastStatus: String = "",
)

internal interface TimeStateStore {
    fun read(): TimeAlertState
    fun write(state: TimeAlertState)
}

internal interface TimeAlarmBackend {
    fun canSchedule(): Boolean
    fun schedule(key: String, token: String, deadline: TimeDeadline, elapsedBased: Boolean)
    fun cancel(key: String)
}

internal object TimeMath {
    fun nextAlarm(alarm: WakeAlarm, after: Long, zone: ZoneId): Long {
        val start = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        for (offset in 0L..14L) {
            val date = start.plusDays(offset)
            if (date.toString() == alarm.lastFiredDate || date.toString() == alarm.skipDate) continue
            if (alarm.days.isNotEmpty() && date.dayOfWeek.value !in alarm.days) continue
            val instant = date.atTime(alarm.hour, alarm.minute).atZone(zone).toInstant().toEpochMilli()
            if (instant > after) return instant
        }
        error("Could not find the next alarm")
    }
}

/** Scheduling and state transitions are independent of Bluetooth and Android services. */
internal class TimeAlertManager(private val store: TimeStateStore, private val clock: TimeClock, private val backend: TimeAlarmBackend) {
    private fun token() = UUID.randomUUID().toString()
    private fun requireAccess() = check(backend.canSchedule()) { "Allow Alarms & reminders before scheduling." }
    private fun put(key: String, token: String, deadline: TimeDeadline, elapsed: Boolean) {
        try { backend.schedule(key, token, deadline, elapsed) } catch (e: SecurityException) {
            store.write(store.read().copy(permissionWasGranted = false, lastStatus = "Alarms & reminders access changed. Allow access to resume schedules."))
            throw IllegalStateException("Allow Alarms & reminders before scheduling.", e)
        }
    }
    fun snapshot(): TimeAlertState {
        val old = store.read()
        val active = old.active.filter { it.expires.boot == clock.boot && it.expires.remaining(clock) > 0 }
        val state = if (active != old.active) old.copy(active = active, lastStatus = "The active alert ended.") else old
        if (state != old) store.write(state)
        return state
    }
    fun startTimer(duration: Long, profile: Int = 1) {
        requireAccess()
        require(duration in 1000L..604800000L && profile in 1..3) { "Choose a timer between one second and seven days." }
        val timer = Countdown(duration, profile, deadline = TimeDeadline.after(clock, duration))
        val state = snapshot()
        store.write(state.copy(timer = timer, active = state.active.filterNot { it.key == "timer" }, permissionWasGranted = true, lastStatus = ""))
        put("timer", timer.token, timer.deadline!!, true)
    }
    fun pauseTimer() {
        val state = snapshot(); val timer = state.timer ?: return
        if (timer.state != "running") return
        val remaining = timer.deadline?.remaining(clock)?.coerceAtLeast(0) ?: 0
        check(remaining > 0) { "The timer has already expired." }
        store.write(state.copy(timer = timer.copy(state = "paused", remaining = remaining, deadline = null, token = token())))
        backend.cancel("timer")
    }
    fun resumeTimer() {
        requireAccess()
        val state = snapshot(); val old = state.timer ?: return
        if (old.state != "paused" || old.remaining <= 0) return
        val timer = old.copy(state = "running", deadline = TimeDeadline.after(clock, old.remaining), token = token())
        store.write(state.copy(timer = timer, permissionWasGranted = true, lastStatus = ""))
        put("timer", timer.token, timer.deadline!!, true)
    }
    fun cancelTimer() {
        val state = snapshot()
        store.write(state.copy(timer = state.timer?.copy(state = "cancelled", deadline = null, token = token()), active = state.active.filterNot { it.key == "timer" }))
        backend.cancel("timer")
    }
    fun saveAlarm(alarm: WakeAlarm) {
        requireAccess()
        require(alarm.hour in 0..23 && alarm.minute in 0..59 && alarm.days.all { it in 1..7 } && alarm.profile in 1..3)
        val state = snapshot()
        require(state.alarms.size < 20 || state.alarms.any { it.id == alarm.id }) { "Choose up to twenty alarms." }
        val fresh = alarm.copy(label = alarm.label.trim().take(60), token = token(), snoozed = false, lastFiredDate = null, skipDate = null)
        val saved = fresh.copy(deadline = if (fresh.enabled) TimeDeadline.at(clock, TimeMath.nextAlarm(fresh, clock.wall, clock.zone)) else null)
        store.write(state.copy(alarms = state.alarms.filterNot { it.id == saved.id } + saved, active = state.active.filterNot { it.key == "alarm/${saved.id}" }, permissionWasGranted = true, lastStatus = ""))
        if (saved.enabled) put("alarm/${saved.id}", saved.token, saved.deadline!!, false) else backend.cancel("alarm/${saved.id}")
    }
    fun setAlarmEnabled(id: String, enabled: Boolean) {
        val alarm = snapshot().alarms.firstOrNull { it.id == id } ?: return
        if (enabled) saveAlarm(alarm.copy(enabled = true)) else {
            val state = snapshot()
            store.write(state.copy(alarms = state.alarms.map { if (it.id == id) it.copy(enabled = false, deadline = null, token = token()) else it }, active = state.active.filterNot { it.key == "alarm/$id" }))
            backend.cancel("alarm/$id")
        }
    }
    fun deleteAlarm(id: String) {
        val state = snapshot()
        store.write(state.copy(alarms = state.alarms.filterNot { it.id == id }, active = state.active.filterNot { it.key == "alarm/$id" }))
        backend.cancel("alarm/$id")
    }
    fun skipNext(id: String) {
        requireAccess()
        val state = snapshot(); val alarm = state.alarms.firstOrNull { it.id == id && it.enabled } ?: return
        if (alarm.days.isEmpty()) { setAlarmEnabled(id, false); return }
        val due = alarm.deadline?.wall ?: return
        val updated = alarm.copy(skipDate = Instant.ofEpochMilli(due).atZone(clock.zone).toLocalDate().toString(), snoozed = false, token = token())
        val next = updated.copy(deadline = TimeDeadline.at(clock, TimeMath.nextAlarm(updated, maxOf(clock.wall, due), clock.zone)))
        store.write(state.copy(alarms = state.alarms.map { if (it.id == id) next else it }))
        put("alarm/$id", next.token, next.deadline!!, false)
    }
    /** An occurrence token and its due time must both match; late/stale broadcasts cannot replay an alert. */
    fun fire(key: String, occurrence: String): RingingAlert? {
        var state = snapshot()
        val alarm = state.alarms.firstOrNull { key == "alarm/${it.id}" && it.enabled && it.token == occurrence }
        val timer = state.timer?.takeIf { key == "timer" && it.state == "running" && it.token == occurrence }
        val due = alarm?.deadline ?: timer?.deadline ?: return null
        val remaining = if (alarm != null && !alarm.snoozed) due.wall - clock.wall else due.remaining(clock)
        if (remaining > 0) return null
        val title = if (timer != null) "Timer finished" else alarm!!.label.ifBlank { "Alarm" }
        val profile = timer?.profile ?: alarm!!.profile
        if (timer != null) state = state.copy(timer = timer.copy(state = "finished", deadline = null, remaining = 0))
        if (alarm != null) {
            val firedDate = if (alarm.snoozed) alarm.lastFiredDate else Instant.ofEpochMilli(due.wall).atZone(clock.zone).toLocalDate().toString()
            val changed = alarm.copy(enabled = alarm.days.isNotEmpty(), snoozed = false, lastFiredDate = firedDate, token = token(), deadline = null)
            val next = if (changed.enabled) changed.copy(deadline = TimeDeadline.at(clock, TimeMath.nextAlarm(changed, clock.wall, clock.zone))) else changed
            state = state.copy(alarms = state.alarms.map { if (it.id == alarm.id) next else it })
        }
        val ring = if (remaining >= -60000) RingingAlert(key, occurrence, title, profile, TimeDeadline.after(clock, 120000)) else null
        store.write(state.copy(active = if (ring != null) state.active + ring else state.active, lastStatus = if (ring == null) "$title was missed while the phone was unavailable." else ""))
        state.alarms.firstOrNull { key == "alarm/${it.id}" && it.enabled }?.let { put(key, it.token, it.deadline!!, false) }
        return ring
    }
    fun stop(occurrence: String? = null) {
        val state = snapshot()
        store.write(state.copy(active = if (occurrence == null) emptyList() else state.active.filterNot { it.token == occurrence }))
    }
    fun snooze(occurrence: String) {
        requireAccess()
        val state = snapshot(); val ring = state.active.firstOrNull { it.token == occurrence } ?: return
        val deadline = TimeDeadline.after(clock, 300000)
        val fresh = token()
        if (ring.key == "timer") {
            val timer = state.timer ?: return
            store.write(state.copy(timer = timer.copy(state = "running", remaining = 300000, deadline = deadline, token = fresh), active = state.active.filterNot { it.token == occurrence }, permissionWasGranted = true))
        } else {
            val alarm = state.alarms.firstOrNull { ring.key == "alarm/${it.id}" } ?: return
            store.write(state.copy(alarms = state.alarms.map { if (it.id == alarm.id) it.copy(enabled = true, snoozed = true, deadline = deadline, token = fresh) else it }, active = state.active.filterNot { it.token == occurrence }, permissionWasGranted = true))
        }
        put(ring.key, fresh, deadline, true)
    }
    fun setPhoneSound(enabled: Boolean) { store.write(snapshot().copy(phoneSound = enabled)) }
    fun status(message: String) { store.write(snapshot().copy(lastStatus = message)) }
    fun permissionRevoked() { val state = snapshot(); if (state.permissionWasGranted) store.write(state.copy(permissionWasGranted = false)) }
    fun restore(clockChanged: Boolean = false, reboot: Boolean = false) {
        val old = snapshot()
        var missed = false
        val timer = old.timer?.let {
            if (it.state != "running") it else {
                val left = it.deadline?.remaining(clock) ?: 0
                if (left <= 0 && (reboot || left < -60000)) { missed = true; it.copy(state = "finished", remaining = 0, deadline = null, token = token()) }
                else it.copy(deadline = TimeDeadline.after(clock, left), token = token())
            }
        }
        val alarms = old.alarms.map { alarm ->
            if (!alarm.enabled) alarm else {
                val late = (alarm.deadline?.wall ?: Long.MAX_VALUE) < clock.wall
                if (!alarm.snoozed && (clockChanged || alarm.deadline == null || (late && (reboot || clock.wall - alarm.deadline.wall > 60000)))) {
                    if (late && !clockChanged && alarm.days.isEmpty()) { missed = true; alarm.copy(enabled = false, deadline = null, token = token()) }
                    else alarm.copy(deadline = TimeDeadline.at(clock, TimeMath.nextAlarm(alarm, clock.wall, clock.zone)), token = token())
                } else if (alarm.snoozed) {
                    val left = alarm.deadline?.remaining(clock) ?: 0
                    if (left <= 0 && (reboot || left < -60000)) {
                        missed = true
                        if (alarm.days.isEmpty()) alarm.copy(enabled = false, deadline = null, snoozed = false, token = token())
                        else alarm.copy(deadline = TimeDeadline.at(clock, TimeMath.nextAlarm(alarm, clock.wall, clock.zone)), snoozed = false, token = token())
                    } else alarm.copy(deadline = TimeDeadline.after(clock, left), token = token())
                } else alarm.copy(token = token())
            }
        }
        val access = backend.canSchedule()
        store.write(old.copy(timer = timer, alarms = alarms, active = if (reboot) emptyList() else old.active, permissionWasGranted = access, lastStatus = if (missed) "An expired timer or alarm was missed while the phone was unavailable." else old.lastStatus))
        backend.cancel("timer")
        if (access && timer?.state == "running") put("timer", timer.token, timer.deadline!!, true)
        alarms.forEach { alarm ->
            if (access && alarm.enabled) put("alarm/${alarm.id}", alarm.token, alarm.deadline!!, alarm.snoozed) else backend.cancel("alarm/${alarm.id}")
        }
    }
}

internal object TimeStateJson {
    private fun TimeDeadline.json() = JSONObject().put("wall", wall).put("elapsed", elapsed).put("boot", boot)
    private fun JSONObject.deadline(key: String) = optJSONObject(key)?.let { TimeDeadline(it.getLong("wall"), it.getLong("elapsed"), it.getInt("boot")) }
    fun encode(state: TimeAlertState): String = JSONObject().apply {
        put("schema", 1); put("phoneSound", state.phoneSound); put("permissionWasGranted", state.permissionWasGranted); put("lastStatus", state.lastStatus)
        state.timer?.let { put("timer", JSONObject().put("duration", it.duration).put("profile", it.profile).put("state", it.state).put("remaining", it.remaining).put("token", it.token).put("deadline", it.deadline?.json())) }
        put("alarms", JSONArray(state.alarms.map { JSONObject().put("id", it.id).put("label", it.label).put("hour", it.hour).put("minute", it.minute).put("days", JSONArray(it.days.sorted())).put("profile", it.profile).put("enabled", it.enabled).put("deadline", it.deadline?.json()).put("token", it.token).put("snoozed", it.snoozed).put("lastFiredDate", it.lastFiredDate).put("skipDate", it.skipDate) }))
        put("active", JSONArray(state.active.map { JSONObject().put("key", it.key).put("token", it.token).put("title", it.title).put("profile", it.profile).put("expires", it.expires.json()) }))
    }.toString()
    fun decode(text: String): TimeAlertState {
        val root = JSONObject(text)
        val timer = root.optJSONObject("timer")?.let { Countdown(it.getLong("duration"), it.getInt("profile"), it.getString("state"), it.getLong("remaining"), it.deadline("deadline"), it.getString("token")) }
        val alarms = root.optJSONArray("alarms") ?: JSONArray()
        val active = root.optJSONArray("active") ?: JSONArray()
        return TimeAlertState(timer, (0 until alarms.length()).map { index ->
            val a = alarms.getJSONObject(index); val days = a.getJSONArray("days")
            WakeAlarm(a.getString("id"), a.getString("label"), a.getInt("hour"), a.getInt("minute"), (0 until days.length()).map { days.getInt(it) }.toSet(), a.getInt("profile"), a.getBoolean("enabled"), a.deadline("deadline"), a.getString("token"), a.optBoolean("snoozed"), a.optString("lastFiredDate").takeIf { it.isNotBlank() && it != "null" }, a.optString("skipDate").takeIf { it.isNotBlank() && it != "null" })
        }, (0 until active.length()).map { index -> val a = active.getJSONObject(index); RingingAlert(a.getString("key"), a.getString("token"), a.getString("title"), a.getInt("profile"), a.deadline("expires")!!) }, root.optBoolean("phoneSound", true), root.optBoolean("permissionWasGranted"), root.optString("lastStatus"))
    }
}
