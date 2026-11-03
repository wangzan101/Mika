package com.wangzan101.mika.task

import com.wangzan101.mika.core.trigger.ConditionNotify
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerWindowState
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class FileTriggerStoreTest {
    @Test fun claimAndLedgerSurviveStoreReopen() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        val first = FileTriggerStore(file)
        first.upsert(TriggerDefinition("hourly", "thread", "check", 1_000, 1_000))
        first.claimDue(3_100)
        val restored = FileTriggerStore(file)
        assertEquals(4_000L, restored.get("hourly")!!.nextAtMs)
        assertEquals(2L, restored.windows().single().missedIntervals)
        assertEquals(TriggerWindowState.Claimed, restored.windows().single().state)
        assertEquals(emptyList<Any>(), restored.claimDue(3_100))
    }

    @Test fun reminderAndCalendarSettingsSurviveStoreReopen() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        FileTriggerStore(file).upsert(TriggerDefinition("daily", "thread", "remind", 1_000,
            reminderText = "该喝水了", calendarTime = "09:00", calendarZone = "Asia/Shanghai",
            calendarDays = listOf(1, 5)))
        val restored = FileTriggerStore(file).get("daily")!!
        assertEquals("该喝水了", restored.reminderText)
        assertEquals("09:00", restored.calendarTime)
        assertEquals(listOf(1, 5), restored.calendarDays)
    }

    @Test fun conditionStateSurvivesStoreReopen() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        FileTriggerStore(file).upsert(TriggerDefinition("price", "thread", "告诉我", 1_000, conditionGoal = "降价超过 50",
            notifyWhen = ConditionNotify.EachTimeMet, cooldownMs = 60_000, lastValue = "¥680",
            baselineValue = "¥700", baselineAtMs = 10, lastFiredAtMs = 10))
        val restored = FileTriggerStore(file).get("price")!!
        assertEquals(ConditionNotify.EachTimeMet, restored.notifyWhen)
        assertEquals(60_000L, restored.cooldownMs)
        assertEquals("¥680", restored.lastValue)
        assertEquals("¥700", restored.baselineValue)
        assertEquals(10L, restored.baselineAtMs)
        assertEquals(10L, restored.lastFiredAtMs)
    }

    // 升级前存下的计划文件没有新字段:照常读,按默认值算
    @Test fun planFileFromBeforeConditionStateStillLoads() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        file.writeText("""{"triggers":[{"id":"old","threadId":"thread","goal":"告诉我","nextAtMs":1000,"conditionGoal":"下雨","lastConditionMet":true}],"windows":[]}""")
        val restored = FileTriggerStore(file).get("old")!!
        assertEquals(true, restored.lastConditionMet)
        assertEquals(ConditionNotify.BecomesMet, restored.notifyWhen)
        assertEquals(null, restored.baselineValue)
        assertEquals(false, File(file.parentFile, file.name + ".corrupt").exists())
    }
}
