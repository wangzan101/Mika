package com.wangzan101.mika.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HelperPrivilegesTest {
    private class FakeDrop(initialUid: Int) : HelperPrivileges.Drop {
        private var current = initialUid
        val calls = mutableListOf<String>()
        var failOn: String? = null
        var ignoreSetUid = false
        override fun currentUid() = current
        override fun setGroups(gids: IntArray) {
            calls += "groups:${gids.toList()}"
            if (failOn == "groups") throw RuntimeException("boom")
        }
        override fun setGid(gid: Int) {
            calls += "gid:$gid"
            if (failOn == "gid") throw RuntimeException("boom")
        }
        override fun setUid(uid: Int) {
            calls += "uid:$uid"
            if (failOn == "uid") throw RuntimeException("boom")
            if (!ignoreSetUid) current = uid
        }
    }

    @Test
    fun notRootDoesNothing() {
        val drop = FakeDrop(initialUid = 2000)
        HelperPrivileges.dropToShellIfRoot(2000, drop)
        assertTrue(drop.calls.isEmpty())
    }

    @Test
    fun rootDropsGroupsGidThenUid() {
        val drop = FakeDrop(initialUid = 0)
        HelperPrivileges.dropToShellIfRoot(0, drop)
        assertEquals(listOf("groups:[]", "gid:2000", "uid:2000"), drop.calls)
    }

    @Test
    fun dropFailureIsObservable() {
        for (step in listOf("groups", "gid", "uid")) {
            val drop = FakeDrop(initialUid = 0).apply { failOn = step }
            try {
                HelperPrivileges.dropToShellIfRoot(0, drop)
                fail("expected DropFailedException when $step fails")
            } catch (e: HelperPrivileges.DropFailedException) {
                assertTrue("recognizable message", e.message!!.contains("2000"))
            }
        }
    }

    @Test
    fun dropThatLeavesWrongUidIsObservable() {
        // setuid 假装成功但 uid 没变:也要报错,不许静默以 root 继续
        val drop = FakeDrop(initialUid = 0).apply { ignoreSetUid = true }
        try {
            HelperPrivileges.dropToShellIfRoot(0, drop)
            fail("expected DropFailedException when uid stays root after drop")
        } catch (e: HelperPrivileges.DropFailedException) {
            assertTrue("recognizable message", e.message!!.contains("2000"))
        }
    }
}
