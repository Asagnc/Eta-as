package io.github.asagnc.eta.data.repository

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

internal data class ModelUsageSnapshot(
    val providers: List<ModelUsageProviderUi> = emptyList(),
) {
    val totalInputTokens: Long get() = providers.sumOf { it.inputTokens }
    val totalOutputTokens: Long get() = providers.sumOf { it.outputTokens }
    val totalCachedTokens: Long get() = providers.sumOf { it.cachedTokens }

    fun filtered(startMillis: Long?, endMillis: Long?): ModelUsageSnapshot {
        if (startMillis == null && endMillis == null) return this
        return ModelUsageSnapshot(
            providers = providers.mapNotNull { provider ->
                val models = provider.models.mapNotNull { it.filtered(startMillis, endMillis) }
                if (models.isEmpty()) null else provider.copy(models = models)
            },
        )
    }
}

internal data class ModelUsageProviderUi(
    val id: String,
    val name: String,
    val models: List<ModelUsageModelUi>,
) {
    val inputTokens: Long get() = models.sumOf { it.inputTokens }
    val outputTokens: Long get() = models.sumOf { it.outputTokens }
    val cachedTokens: Long get() = models.sumOf { it.cachedTokens }
}

internal data class ModelUsageEvent(
    val atMillis: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long = 0L,
    val conversationId: String? = null,
    val round: Int? = null,
)

internal data class ModelUsageModelUi(
    val id: String,
    val displayName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val conversationCount: Int,
    val activeDays: Int,
    val events: List<ModelUsageEvent> = emptyList(),
    val cachedTokens: Long = 0L,
) {
    val dailyAverageTokens: Long
        get() = if (activeDays <= 0) 0L else inputTokens / activeDays
    val conversationAverageTokens: Long
        get() = if (conversationCount <= 0) 0L else inputTokens / conversationCount

    fun filtered(startMillis: Long?, endMillis: Long?): ModelUsageModelUi? {
        if (startMillis == null && endMillis == null) return this
        val matched = events.filter { event ->
            (startMillis == null || event.atMillis >= startMillis) &&
                (endMillis == null || event.atMillis <= endMillis)
        }.collapsedByRound()
        if (matched.isEmpty()) return null
        val conversations = matched.mapNotNull { it.conversationId }.toSet()
        val days = matched.map { eventDay(it.atMillis) }.toSet()
        val filteredInput = matched.sumOf { it.inputTokens }
        return copy(
            inputTokens = filteredInput,
            outputTokens = matched.sumOf { it.outputTokens },
            cachedTokens = matched.sumOf { it.cachedTokens },
            conversationCount = conversations.size,
            activeDays = days.size,
            events = matched,
        )
    }
}

internal data class ModelUsageDelta(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelDisplayName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long = 0L,
    val conversationId: String? = null,
    val round: Int? = null,
    val atMillis: Long = System.currentTimeMillis(),
    val day: LocalDate = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDate(),
)

internal fun decodeModelUsageSnapshot(raw: String?): ModelUsageSnapshot {
    if (raw.isNullOrBlank()) return ModelUsageSnapshot()
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return ModelUsageSnapshot()
    val providersJson = root.optJSONObject("providers") ?: return ModelUsageSnapshot()
    val providers = buildList {
        providersJson.keys().forEach { providerId ->
            val provider = providersJson.optJSONObject(providerId) ?: return@forEach
            val modelsJson = provider.optJSONObject("models") ?: JSONObject()
            val models = buildList {
                modelsJson.keys().forEach { modelId ->
                    val model = modelsJson.optJSONObject(modelId) ?: return@forEach
                    val events = decodeEvents(model.optJSONArray("events"))
                    val conversations = stringSet(model.optJSONArray("conversations"))
                    val days = stringSet(model.optJSONArray("days"))
                    val input = if (events.isNotEmpty()) {
                        events.sumOf { it.inputTokens }
                    } else {
                        model.optLong("inputTokens")
                    }
                    val output = if (events.isNotEmpty()) {
                        events.sumOf { it.outputTokens }
                    } else {
                        model.optLong("outputTokens")
                    }
                    val cached = if (events.isNotEmpty()) {
                        events.sumOf { it.cachedTokens }
                    } else {
                        model.optLong("cachedTokens")
                    }
                    add(
                        ModelUsageModelUi(
                            id = modelId,
                            displayName = model.optString("displayName").ifBlank { modelId },
                            inputTokens = input,
                            outputTokens = output,
                            conversationCount = if (events.isNotEmpty()) {
                                events.mapNotNull { it.conversationId }.toSet().size
                            } else {
                                conversations.size
                            },
                            activeDays = when {
                                days.isNotEmpty() -> days.size
                                events.isNotEmpty() -> events.map { eventDay(it.atMillis) }.toSet().size
                                else -> 0
                            },
                            events = events,
                            cachedTokens = cached,
                        ),
                    )
                }
            }.sortedWith(
                compareByDescending<ModelUsageModelUi> { it.inputTokens }
                    .thenBy { it.displayName.lowercase() },
            )
            if (models.isNotEmpty()) {
                add(
                    ModelUsageProviderUi(
                        id = providerId,
                        name = provider.optString("name").ifBlank { providerId },
                        models = models,
                    ),
                )
            }
        }
    }.sortedBy { it.name.lowercase() }
    return ModelUsageSnapshot(providers = providers)
}

internal fun applyModelUsageDelta(raw: String?, delta: ModelUsageDelta): String {
    if (delta.providerId.isBlank() || delta.modelId.isBlank()) return raw.orEmpty()
    if (delta.inputTokens <= 0L && delta.outputTokens <= 0L && delta.conversationId.isNullOrBlank()) {
        return raw.orEmpty()
    }
    val root = runCatching { JSONObject(raw.takeUnless { it.isNullOrBlank() } ?: "{}") }
        .getOrDefault(JSONObject())
    val providers = root.optJSONObject("providers") ?: JSONObject().also {
        root.put("providers", it)
    }
    val provider = providers.optJSONObject(delta.providerId) ?: JSONObject().also {
        providers.put(delta.providerId, it)
    }
    provider.put("name", delta.providerName.ifBlank { delta.providerId })
    val models = provider.optJSONObject("models") ?: JSONObject().also {
        provider.put("models", it)
    }
    val model = models.optJSONObject(delta.modelId) ?: JSONObject().also {
        models.put(delta.modelId, it)
    }
    model.put("displayName", delta.modelDisplayName.ifBlank { delta.modelId })
    val events = decodeEvents(model.optJSONArray("events")).toMutableList()
    val incoming = ModelUsageEvent(
        atMillis = delta.atMillis,
        inputTokens = delta.inputTokens.coerceAtLeast(0L),
        outputTokens = delta.outputTokens.coerceAtLeast(0L),
        cachedTokens = delta.cachedTokens.coerceAtLeast(0L),
        conversationId = delta.conversationId,
        round = delta.round,
    )
    val replaceAt = events.indexOfLast { event ->
        incoming.round != null &&
            !incoming.conversationId.isNullOrBlank() &&
            event.round == incoming.round &&
            event.conversationId == incoming.conversationId
    }
    if (replaceAt >= 0) {
        val previous = events[replaceAt]
        model.put("inputTokens", model.optLong("inputTokens") - previous.inputTokens + incoming.inputTokens)
        model.put("outputTokens", model.optLong("outputTokens") - previous.outputTokens + incoming.outputTokens)
        model.put("cachedTokens", model.optLong("cachedTokens") - previous.cachedTokens + incoming.cachedTokens)
        events[replaceAt] = incoming
    } else {
        model.put("inputTokens", model.optLong("inputTokens") + incoming.inputTokens)
        model.put("outputTokens", model.optLong("outputTokens") + incoming.outputTokens)
        model.put("cachedTokens", model.optLong("cachedTokens") + incoming.cachedTokens)
        events += incoming
    }
    val conversations = stringSet(model.optJSONArray("conversations")).toMutableSet()
    delta.conversationId?.takeIf { it.isNotBlank() }?.let(conversations::add)
    model.put("conversations", JSONArray(conversations.sorted()))
    val days = stringSet(model.optJSONArray("days")).toMutableSet()
    days += delta.day.toString()
    model.put("days", JSONArray(days.sorted()))
    val trimmed = if (events.size > MAX_MODEL_EVENTS) {
        events.takeLast(MAX_MODEL_EVENTS)
    } else {
        events
    }
    model.put("events", encodeEvents(trimmed))
    return root.toString()
}

private fun decodeEvents(array: JSONArray?): List<ModelUsageEvent> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val at = item.optLong("t")
            if (at <= 0L) continue
            add(
                ModelUsageEvent(
                    atMillis = at,
                    inputTokens = item.optLong("in"),
                    outputTokens = item.optLong("out"),
                    cachedTokens = item.optLong("k"),
                    conversationId = item.optString("c").takeIf { it.isNotBlank() },
                    round = if (item.has("r")) item.optInt("r") else null,
                ),
            )
        }
    }
}

private fun encodeEvents(events: List<ModelUsageEvent>): JSONArray =
    JSONArray().also { array ->
        events.forEach { event ->
            array.put(
                JSONObject().also { json ->
                    json.put("t", event.atMillis)
                    json.put("in", event.inputTokens)
                    json.put("out", event.outputTokens)
                    if (event.cachedTokens > 0L) json.put("k", event.cachedTokens)
                    json.put("c", event.conversationId.orEmpty())
                    event.round?.let { json.put("r", it) }
                },
            )
        }
    }

private fun stringSet(array: JSONArray?): Set<String> {
    if (array == null) return emptySet()
    return buildSet {
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun eventDay(atMillis: Long): LocalDate =
    Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDate()

private const val MAX_MODEL_EVENTS = 4000

internal fun ModelUsageSnapshot.alignedToConversationTotals(
    inputTokens: Long,
    outputTokens: Long,
    cachedTokens: Long,
): ModelUsageSnapshot {
    val models = providers.flatMap { provider -> provider.models }
    if (models.isEmpty()) return this
    val inputs = distributeTotals(inputTokens, models.map { it.inputTokens })
    val outputs = distributeTotals(outputTokens, models.map { it.outputTokens })
    val caches = distributeTotals(cachedTokens, models.map { it.inputTokens })
    var index = 0
    return copy(
        providers = providers.map { provider ->
            provider.copy(
                models = provider.models.map { model ->
                    val aligned = model.copy(
                        inputTokens = inputs[index],
                        outputTokens = outputs[index],
                        cachedTokens = caches[index],
                    )
                    index += 1
                    aligned
                },
            )
        },
    )
}

internal fun distributeTotals(total: Long, weights: List<Long>): List<Long> {
    if (weights.isEmpty()) return emptyList()
    val safe = weights.map { it.coerceAtLeast(0L) }
    val sum = safe.sum()
    if (sum <= 0L) {
        return List(safe.size) { index -> if (index == safe.lastIndex) total.coerceAtLeast(0L) else 0L }
    }
    val raw = safe.map { total.coerceAtLeast(0L) * it / sum }
    val drift = total.coerceAtLeast(0L) - raw.sum()
    return raw.mapIndexed { index, value -> if (index == raw.lastIndex) value + drift else value }
}

internal fun List<ModelUsageEvent>.collapsedByRound(): List<ModelUsageEvent> {
    if (isEmpty()) return this
    val kept = ArrayList<ModelUsageEvent>(size)
    val indexByKey = HashMap<String, Int>()
    forEach { event ->
        val round = event.round
        val conversation = event.conversationId
        if (round == null || conversation.isNullOrBlank()) {
            kept += event
        } else {
            val key = "$conversation#$round"
            val existing = indexByKey[key]
            if (existing == null) {
                indexByKey[key] = kept.size
                kept += event
            } else {
                kept[existing] = event
            }
        }
    }
    return kept
}

internal fun scaledUsageTotal(conversationTotal: Long, filteredWeight: Long, wholeWeight: Long): Long {
    if (conversationTotal <= 0L || filteredWeight <= 0L || wholeWeight <= 0L) return 0L
    if (filteredWeight >= wholeWeight) return conversationTotal
    return conversationTotal * filteredWeight / wholeWeight
}
