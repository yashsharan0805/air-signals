package dev.codex.fitbitairlab

/** Main-thread queue: priority never interrupts a write; cancellation belongs to its owner. */
internal class OwnedOperationQueue<T>(
    private val execute: (T, (Map<String, Any?>) -> Unit) -> Unit,
    private val cancelActive: () -> Unit,
) {
    private data class Entry<T>(val owner: String, val priority: Int, val value: T, val allowed: () -> Boolean, val done: (Map<String, Any?>) -> Unit)
    private val pending = mutableListOf<Entry<T>>()
    private var active: Entry<T>? = null
    private var cancelling = false
    private var draining = false
    val busy get() = active != null || pending.isNotEmpty()
    fun submit(owner: String, priority: Int, value: T, allowed: () -> Boolean, done: (Map<String, Any?>) -> Unit) {
        pending.add(Entry(owner, priority, value, allowed, done)); drain()
    }
    fun cancel(owner: String) {
        val removed = pending.filter { it.owner == owner }
        pending.removeAll(removed.toSet())
        removed.forEach { it.done(mapOf("status" to "skipped", "code" to "CANCELLED")) }
        if (active?.owner == owner && !cancelling) { cancelling = true; cancelActive() }
    }
    private fun drain() {
        if (active != null || draining) return
        draining = true
        try { while (active == null && pending.isNotEmpty()) {
            val entry = pending.maxBy { it.priority }
            pending.remove(entry)
            if (!entry.allowed()) { entry.done(mapOf("status" to "skipped", "code" to "EXPIRED_OR_SUPPRESSED")); continue }
            active = entry; cancelling = false
            execute(entry.value) { result ->
                if (active !== entry) return@execute
                active = null; cancelling = false
                entry.done(result)
                drain()
            }
        } } finally { draining = false }
    }
}
