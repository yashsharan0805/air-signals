package dev.codex.fitbitairlab

data class PendingAlert(val source: String, val groups: Int, val gapMillis: Int, val received: Long, val identity: String)

/** The queue never needs notification text, senders, titles or contact identifiers. */
class AlertQueue(private val capacity: Int = 5, private val ttlMillis: Long = 45000) {
    private val pending = LinkedHashMap<String, PendingAlert>()
    private val seen = LinkedHashMap<String, Long>()
    @Synchronized fun offer(item: PendingAlert, now: Long): String {
        seen.entries.removeAll { now - it.value > 60000 }
        if (seen.containsKey(item.identity)) return "duplicate"
        if (now - item.received >= ttlMillis) return "expired"
        if (!pending.containsKey(item.source) && pending.size >= capacity) return "queue_full"
        seen[item.identity] = now
        while (seen.size > 128) seen.remove(seen.keys.first())
        val merged = pending.containsKey(item.source)
        pending[item.source] = item
        return if (merged) "coalesced" else "queued"
    }
    @Synchronized fun take(now: Long): PendingAlert? {
        pending.entries.removeAll { now - it.value.received >= ttlMillis }
        val key = pending.keys.firstOrNull() ?: return null
        return pending.remove(key)
    }
    @Synchronized fun clear() { pending.clear() }
    @Synchronized fun size() = pending.size
}

object QueueChecks {
    fun run(): Map<String, Any> {
        val q = AlertQueue(2, 1000)
        fun item(source: String, id: String, time: Long) = PendingAlert(source, 1, 1800, time, id)
        check(q.offer(item("a", "a1", 0), 0) == "queued")
        check(q.offer(item("a", "a1", 0), 1) == "duplicate")
        check(q.offer(item("a", "a2", 10), 10) == "coalesced")
        check(q.offer(item("b", "b1", 20), 20) == "queued")
        check(q.offer(item("c", "c1", 30), 30) == "queue_full")
        check(q.take(30)?.identity == "a2")
        check(q.take(1020) == null) // An item can expire while it waits.
        check(q.offer(item("z", "expired", 0), 1000) == "expired")
        check(q.offer(item("z", "fresh", 1030), 1030) == "queued")
        q.clear(); check(q.take(1031) == null)
        return mapOf("passed" to true, "scenarios" to listOf("duplicate suppression", "same-app burst coalescing", "queue capacity", "latest update retained", "expiry while queued", "already-expired rejection", "stop clears pending"))
    }
}
