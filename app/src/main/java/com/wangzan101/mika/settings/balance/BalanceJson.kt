package com.wangzan101.mika.settings.balance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val balanceJson = Json { ignoreUnknownKeys = true }

internal fun parseObject(body: String): JsonObject? =
    runCatching { balanceJson.parseToJsonElement(body) as? JsonObject }.getOrNull()

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

internal fun JsonObject.str(name: String): String? {
    val el = this[name] ?: return null
    if (el is JsonNull) return null
    val p = el as? JsonPrimitive ?: return null
    return p.content
}

internal fun JsonObject.bool(name: String): Boolean? = when (str(name)) {
    "true" -> true
    "false" -> false
    else -> null
}

/** 数字或数字字符串。NaN、Infinity 不算。 */
internal fun JsonObject.double(name: String): Double? {
    val raw = str(name) ?: return null
    return raw.toDoubleOrNull()?.takeIf { it.isFinite() }
}

internal fun JsonObject.int(name: String): Int? = double(name)?.toInt()

internal fun JsonObject.long(name: String): Long? {
    val raw = str(name) ?: return null
    return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
}

internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject

/** 服务端的一句说明:压成一行,截到界面放得下。空的当作没有。 */
internal fun String.shortMessage(): String? {
    val line = lineSequence().firstOrNull()?.trim().orEmpty().replace(Regex("\\s+"), " ")
    if (line.isEmpty()) return null
    return line.take(160)
}
