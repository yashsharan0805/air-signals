package dev.codex.fitbitairlab

import org.junit.Assert.*
import org.junit.Test

class OwnedOperationQueueTest {
    private val started = mutableListOf<String>()
    private val completions = mutableMapOf<String, (Map<String, Any?>) -> Unit>()
    private var cancelled = 0
    private val queue = OwnedOperationQueue<String>({ value, done -> started.add(value); completions[value] = done }, { cancelled++ })
    private fun submit(value: String, owner: String = "relay", priority: Int = 0, allowed: () -> Boolean = { true }, done: (Map<String, Any?>) -> Unit = {}) = queue.submit(owner, priority, value, allowed, done)
    private fun finish(value: String) = completions.getValue(value)(mapOf("status" to "success"))

    @Test fun scheduledAlertHasPriorityWithoutInterruptingAnInFlightWrite() {
        submit("sending"); submit("notification"); submit("alarm", "scheduled", 10)
        assertEquals(listOf("sending"), started)
        finish("sending"); assertEquals(listOf("sending", "alarm"), started)
        finish("alarm"); assertEquals(listOf("sending", "alarm", "notification"), started)
        finish("notification"); assertFalse(queue.busy)
    }
    @Test fun stoppingNotificationForwardingCannotCancelATimedAlert() {
        submit("alarm", "scheduled", 10)
        var skipped = false
        submit("notification", done = { skipped = it["status"] == "skipped" })
        queue.cancel("relay")
        assertTrue(skipped); assertEquals(0, cancelled)
        queue.cancel("scheduled"); queue.cancel("scheduled")
        assertEquals(1, cancelled)
        finish("alarm")
    }
    @Test fun expiredOrMutedWorkIsRecheckedImmediatelyBeforeDelivery() {
        var allowed = true; var skipped = false
        submit("alarm", "scheduled", 10)
        submit("old-notification", allowed = { allowed }, done = { skipped = it["status"] == "skipped" })
        allowed = false; finish("alarm")
        assertEquals(listOf("alarm"), started)
        assertTrue(skipped); assertFalse(queue.busy)
    }
    @Test fun duplicateCompletionCannotCompleteAnotherOwnersCommand() {
        submit("first"); submit("alarm", "scheduled", 10)
        finish("first"); finish("first")
        assertEquals(listOf("first", "alarm"), started)
        assertTrue(queue.busy)
        finish("alarm"); assertFalse(queue.busy)
    }
    @Test fun synchronousCompletionAndCallbackSubmissionRemainSerialized() {
        val values = mutableListOf<String>()
        val immediate = OwnedOperationQueue<String>({ value, done -> values.add(value); done(mapOf("status" to "success")) }, {})
        immediate.submit("a", 0, "first", { true }) {
            immediate.submit("b", 0, "second", { true }) { }
        }
        assertEquals(listOf("first", "second"), values)
        assertFalse(immediate.busy)
    }
}
