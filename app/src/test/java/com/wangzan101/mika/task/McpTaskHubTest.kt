package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class McpTaskHubTest {
    private val spec = RunSpec(RunId("mcp-test"), "test", "remote", provenance = RunProvenance(RunOrigin.Mcp, Attendance.Reachable))

    @Test fun retainsResultsAndStopsOnlyItsOwnTasks() {
        val stopped = mutableListOf<String>()
        val hub = McpTaskHub({}, stopped::add)
        hub.submit(spec)
        assertEquals("queued", hub.status(spec.id)!!["status"]!!.jsonPrimitive.content)
        val state = MutableStateFlow<TaskState>(TaskState.Running(1, "reading"))
        hub.bind(spec.id.value, state, false) { true }
        assertTrue(hub.cancel(spec.id))
        assertEquals(listOf(spec.id.value), stopped)
        assertFalse(hub.cancel(RunId("ui-task")))
        hub.ended(spec.id.value, TaskState.Succeeded("found", verified = false, reviewed = false))
        val result = hub.status(spec.id)!!
        assertEquals("succeeded", result["status"]!!.jsonPrimitive.content)
        assertEquals("found", result["answer"]!!.jsonPrimitive.content)
        assertFalse(result["verified"]!!.jsonPrimitive.boolean)
        assertFalse(hub.cancel(spec.id))
    }

    @Test fun repliesAreAcknowledgedByTheRunAndStopAfterCompletion() = runBlocking {
        val hub = McpTaskHub({}, {})
        hub.submit(spec)
        assertFalse(hub.reply(spec.id, "too early"))
        val state = MutableStateFlow<TaskState>(TaskState.WaitingInput("which group?", listOf("A", "B")))
        var reply: String? = null
        hub.bind(spec.id.value, state, false) { reply = it; true }
        assertEquals("needs_input", hub.status(spec.id)!!["status"]!!.jsonPrimitive.content)
        assertTrue(hub.reply(spec.id, "A"))
        assertEquals("A", reply)
        hub.ended(spec.id.value, TaskState.Cancelled)
        assertFalse(hub.reply(spec.id, "too late"))
        assertEquals("A", reply)
    }

    @Test fun cancellationBeforeTheServiceStartsIsRetained() {
        val hub = McpTaskHub({}, {})
        hub.submit(spec)
        assertTrue(hub.cancel(spec.id))
        assertTrue(hub.cancelled(spec.id.value))
        hub.ended(spec.id.value, TaskState.Cancelled)
        assertEquals("cancelled", hub.status(spec.id)!!["status"]!!.jsonPrimitive.content)
    }

    @Test fun queuedTasksBecomeRunningAndOldResultsAreBounded() {
        val hub = McpTaskHub({}, {})
        hub.submit(spec)
        hub.bind(spec.id.value, MutableStateFlow(TaskState.Running(1, "read")), true) { true }
        assertEquals("queued", hub.status(spec.id)!!["status"]!!.jsonPrimitive.content)
        hub.running(spec.id.value)
        assertEquals("running", hub.status(spec.id)!!["status"]!!.jsonPrimitive.content)
        repeat(101) {
            val another = spec.copy(id = RunId("completed-$it"))
            hub.submit(another)
            hub.ended(another.id.value, TaskState.Cancelled)
        }
        assertNull(hub.status(RunId("completed-0")))
        assertNotNull(hub.status(spec.id))
    }

    @Test fun phoneTakeoverIsNotReportedAsAQuestionThatCanBeAnsweredRemotely() {
        val result = McpTaskHub.snapshot("id", TaskState.WaitingTakeover("login", "Log in on the phone"), false)
        assertEquals("needs_phone", result["status"]!!.jsonPrimitive.content)
        assertEquals("Log in on the phone", result["message"]!!.jsonPrimitive.content)
    }
}
