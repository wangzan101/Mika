package com.wangzan101.mika.feishu

import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FeishuSettingsTest {
    @get:Rule val temporary=TemporaryFolder()
    private class Cipher : KeyCipher {
        var fail=false
        override fun encrypt(plain: ByteArray): ByteArray {
            check(!fail)
            return plain.map { (it.toInt() xor 0x65).toByte() }.toByteArray()
        }
        override fun decrypt(data: ByteArray)=encrypt(data)
    }
    @Test fun configAndInboxAreEncryptedAndCanBeReopened() {
        val file=File(temporary.root,"feishu.bin"); val cipher=Cipher()
        val store=FeishuEncryptedFile(file,cipher)
        assertNull(store.read())
        store.write("app-secret-and-owner")
        assertFalse(file.readText().contains("app-secret"))
        assertEquals("app-secret-and-owner",FeishuEncryptedFile(file,cipher).read())
        store.clear(); assertNull(store.read())
    }
    @Test fun failedEncryptionKeepsPreviousConfiguration() {
        val file=File(temporary.root,"feishu.bin"); val cipher=Cipher()
        val store=FeishuEncryptedFile(file,cipher)
        store.write("previous")
        cipher.fail=true
        assertThrows(IllegalStateException::class.java) { store.write("new-secret") }
        cipher.fail=false
        assertEquals("previous",store.read())
        assertFalse(File(temporary.root,"feishu.bin.tmp").exists())
    }
    @Test fun credentialsAreNotPrintedByConfigToString() {
        assertFalse(FeishuConfig(true,"cli_test","secret-value").toString().contains("secret-value"))
        assertFalse(FeishuConfig().enabled)
        assertFalse(FeishuConfig().mirrorLocal)
    }
}
