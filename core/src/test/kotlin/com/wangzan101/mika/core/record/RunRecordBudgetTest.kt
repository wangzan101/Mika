package com.wangzan101.mika.core.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunRecordBudgetTest {
    @Test fun summaryKeepsTheStepLimit() {
        val summary = RunSummary("r", "goal", startedAtMs = 0)
            .with(RunEvent.TaskStarted(1, "goal", "model", stepLimit = 60))
        assertEquals(60, summary.stepLimit)
    }

    @Test fun oldSummaryWithoutStepLimitStillLoads() {
        val old = """{"id":"r","goal":"goal","startedAtMs":0}"""
        val decoded = RunEvent.json.decodeFromString(RunSummary.serializer(), old)
        assertNull(decoded.stepLimit)
        assertEquals(0, decoded.cacheReportedCalls)
        assertEquals(0, decoded.cacheUnreportedCalls)
        assertEquals(emptyList<String>(), decoded.providers)
    }

    @Test fun taskStartedTokenBudgetRoundTripsAndOldRecordsDefaultToUnlimited() {
        val started = RunEvent.TaskStarted(1, "goal", "model", stepLimit = 60, tokenBudget = 500_000)
        val back = RunEvent.decode(RunEvent.encode(started)) as RunEvent.TaskStarted
        assertEquals(500_000L, back.tokenBudget)
        assertEquals(60, back.stepLimit)
        val old = """{"type":"task_started","atMs":1,"goal":"goal","model":"model"}"""
        assertNull((RunEvent.decode(old) as RunEvent.TaskStarted).tokenBudget)
    }

    @Test fun stepLimitRoundTrips() {
        val summary = RunSummary("r", "goal", startedAtMs = 0, stepLimit = 120)
        val back = RunEvent.json.decodeFromString(RunSummary.serializer(), RunEvent.json.encodeToString(RunSummary.serializer(), summary))
        assertEquals(120, back.stepLimit)
    }

    @Test fun taskStartedTimeBudgetsRoundTripAndOldRecordsDefaultToNull() {
        val started = RunEvent.TaskStarted(
            1,
            "goal",
            "model",
            timeBudgetMs = 600_000,
            modelCallTimeoutMs = 180_000,
        )
        val back = RunEvent.decode(RunEvent.encode(started)) as RunEvent.TaskStarted
        assertEquals(600_000L, back.timeBudgetMs)
        assertEquals(180_000L, back.modelCallTimeoutMs)
        val old = """{"type":"task_started","atMs":1,"goal":"goal","model":"model"}"""
        val decoded = RunEvent.decode(old) as RunEvent.TaskStarted
        assertNull(decoded.timeBudgetMs)
        assertNull(decoded.modelCallTimeoutMs)
    }

    @Test fun summaryKeepsTimeBudgets() {
        val summary = RunSummary("r", "goal", startedAtMs = 0)
            .with(RunEvent.TaskStarted(
                1,
                "goal",
                "model",
                timeBudgetMs = 600_000,
                modelCallTimeoutMs = 180_000,
            ))
        assertEquals(600_000L, summary.timeBudgetMs)
        assertEquals(180_000L, summary.modelCallTimeoutMs)
    }

    @Test fun compressedEventRoundTripsPrefixObservability() {
        val event = RunEvent.Compressed(
            atMs = 10,
            foldedSteps = 4,
            prefixDigest = "abc123",
            epoch = 2,
            reason = "context_size_append",
        )
        assertEquals(event, RunEvent.decode(RunEvent.encode(event)))
    }
}