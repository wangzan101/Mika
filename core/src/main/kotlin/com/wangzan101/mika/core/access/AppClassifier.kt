package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

class AppClassifier(private val model: ChatModel, private val fallback: ChatModel? = null) {
    suspend fun classify(apps: List<Pair<String, String>>): Map<String, AppClassification> {
        if (apps.isEmpty()) return emptyMap()
        val input = JsonArray(apps.map { (pkg, label) -> buildJsonObject { put("package", pkg); put("label", label) } })
        for (candidate in listOfNotNull(model, fallback?.takeUnless { it === model })) {
            val result = try {
                withTimeoutOrNull(30_000L) {
                    val reply = candidate.complete(Prompts.role("app_classifier"),
                        listOf(Message(Role.User, listOf(Part.Text(input.toString())), Author.User)), emptyList())
                    parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }, apps.map { it.first }.toSet())
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (!result.isNullOrEmpty()) return result
        }
        return emptyMap()
    }

    companion object {
        fun parse(raw: String, packages: Set<String>): Map<String, AppClassification> = runCatching {
            val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            Json.parseToJsonElement(json).jsonArray.mapNotNull { item ->
                val o = item.jsonObject
                val pkg = o["package"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (pkg !in packages) return@mapNotNull null
                val category = o["category"]?.jsonPrimitive?.content?.let { value -> AppCategory.entries.firstOrNull { it.name == value } }
                    ?: AppCategory.Unknown
                pkg to AppClassification(category, o["reason"]?.jsonPrimitive?.content.orEmpty().take(240))
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
