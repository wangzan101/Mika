package com.wangzan101.mika.core.feishu

import com.wangzan101.mika.core.run.SendOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** No model keys, proxy configuration, HTTP bodies or signed connection URLs are logged. */
class FeishuApi(
    private val appId: String,
    private val appSecret: String,
    private val base: HttpUrl,
    client: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(20, TimeUnit.SECONDS).build()
    private var token: String? = null
    private var expires = 0L

    constructor(appId: String, appSecret: String) : this(appId, appSecret,
        "https://open.feishu.cn/".toHttpUrl())

    private fun post(path: String, body: JsonObject, bearer: String? = null): JsonObject {
        val request = Request.Builder().url(base.resolve(path)!!)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }.build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Feishu HTTP ${response.code}" }
            val bytes = response.body ?: error("Feishu response missing")
            check(bytes.contentLength() <= MAX_RESPONSE) { "Feishu response too large" }
            val source = bytes.source()
            check(!source.request(MAX_RESPONSE + 1)) { "Feishu response too large" }
            val result = Json.parseToJsonElement(source.readUtf8()).jsonObject
            check(result["code"]?.jsonPrimitive?.intOrNull == 0) { "Feishu API rejected request" }
            return result
        }
    }

    @Synchronized private fun accessToken(): String {
        if (clock() < expires) token?.let { return it }
        val response = post("/open-apis/auth/v3/tenant_access_token/internal", buildJsonObject {
            put("app_id", appId); put("app_secret", appSecret)
        })
        val value = response["tenant_access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: error("Feishu access token missing")
        val seconds = response["expire"]?.jsonPrimitive?.longOrNull?.coerceIn(60, 7200) ?: 60
        token = value; expires = clock() + (seconds - 30) * 1000
        return value
    }

    suspend fun endpoint(): JsonObject = withContext(Dispatchers.IO) {
        post("/callback/ws/endpoint", buildJsonObject { put("AppID", appId); put("AppSecret", appSecret) })["data"]?.jsonObject
            ?: error("Feishu connection endpoint missing")
    }

    /** Obtain credentials before the write. Network failures during a write are never retried here. */
    suspend fun send(chatId: String, text: String, id: String): SendOutcome = withContext(Dispatchers.IO) {
        val bearer = try { accessToken() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return@withContext SendOutcome.Failed }
        try {
            post("/open-apis/im/v1/messages?receive_id_type=chat_id", buildJsonObject {
                put("receive_id", chatId); put("msg_type", "text"); put("uuid", id)
                val bounded = if (text.codePointCount(0,text.length) <= 6000) text else
                    text.substring(0,text.offsetByCodePoints(0,6000)) + "\n（结果较长，完整内容请在手机查看。）"
                put("content", buildJsonObject { put("text", bounded) }.toString())
            }, bearer)
            SendOutcome.Delivered
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { SendOutcome.Uncertain }
    }

    suspend fun sendCard(chatId: String, card: JsonObject, id: String): SendOutcome = withContext(Dispatchers.IO) {
        val content = card.toString()
        if (content.toByteArray(Charsets.UTF_8).size > 28_000) return@withContext SendOutcome.Failed
        val bearer = try { accessToken() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { return@withContext SendOutcome.Failed }
        try {
            post("/open-apis/im/v1/messages?receive_id_type=chat_id", buildJsonObject {
                put("receive_id", chatId); put("msg_type", "interactive"); put("uuid", id); put("content", content)
            }, bearer)
            SendOutcome.Delivered
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { SendOutcome.Uncertain }
    }

    companion object { private const val MAX_RESPONSE = 1024L * 1024 }
}
