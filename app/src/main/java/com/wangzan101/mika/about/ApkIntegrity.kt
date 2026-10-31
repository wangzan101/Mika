package com.wangzan101.mika.about

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest

/** 下载地址只接受官方仓库的 HTTPS 发布附件；重定向由系统下载服务处理。 */
internal object ApkIntegrity {
    fun requireOfficialUrl(url: String) {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps && parsed.host == "github.com" && parsed.port == 443)
        require(parsed.username.isEmpty() && parsed.password.isEmpty())
        require(parsed.encodedPath.startsWith("/wangzan101/Mika/releases/download/"))
        require(parsed.encodedPath.endsWith(".apk", ignoreCase = true))
    }

    fun verify(file: File, size: Long?, digest: String?) {
        require(file.isFile && file.length() > 0)
        if (size != null) require(file.length() == size)
        if (digest != null) {
            require(digest.matches(Regex("sha256:[0-9a-fA-F]{64}")))
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    sha.update(buffer, 0, count)
                }
            }
            val actual = sha.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(digest.substringAfter(':'), ignoreCase = true))
        }
    }
}
