package io.github.asagnc.eta.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ReasoningEffortSerializer::class)
enum class ReasoningEffort(
    val wireValue: String,
    val displayName: String,
    internal val rank: Int,
) {
    @SerialName("off")
    OFF("off", "Off", 0),

    /**
     * 自动档：由 Eta 按请求用途与轮次决定实际档位（辅助请求低档、主循环首轮高档、
     * 工具回填轮关思考、上一轮工具失败时升回高档，见 ProviderReasoning.resolveAutoEffort），
     * 再按模型能力归一化。rank 与 DEFAULT 相同，只为在列表里占位，不参与"降到最近可用档"的比较。
     */
    @SerialName("auto")
    AUTO("auto", "Auto", 1),

    @SerialName("default")
    DEFAULT("default", "Default", 1),

    @SerialName("minimal")
    MINIMAL("minimal", "Minimal", 2),

    @SerialName("low")
    LOW("low", "Low", 3),

    @SerialName("medium")
    MEDIUM("medium", "Medium", 4),

    @SerialName("high")
    HIGH("high", "High", 5),

    @SerialName("xhigh")
    XHIGH("xhigh", "XHigh", 6),

    @SerialName("max")
    MAX("max", "Max", 7),
    ;

    val enablesReasoning: Boolean
        get() = this != OFF

    companion object {
        fun fromWireValue(value: String?): ReasoningEffort? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireValue == normalized }
                ?: when (normalized) {
                    "none" -> OFF
                    "x-high", "extra_high", "extra-high" -> XHIGH
                    else -> null
                }
        }

        fun fromLegacy(thinkingEnabled: Boolean): ReasoningEffort =
            if (thinkingEnabled) DEFAULT else OFF
    }
}

object ReasoningEffortSerializer : KSerializer<ReasoningEffort> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ReasoningEffort", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ReasoningEffort) {
        encoder.encodeString(value.wireValue)
    }

    override fun deserialize(decoder: Decoder): ReasoningEffort =
        ReasoningEffort.fromWireValue(decoder.decodeString()) ?: ReasoningEffort.DEFAULT
}

@Serializable
data class ModelReasoningCapabilities(
    val supportedEfforts: List<ReasoningEffort> = emptyList(),
    val defaultEffort: ReasoningEffort? = null,
    val defaultEnabled: Boolean? = null,
    val mandatory: Boolean = false,
    val canDisable: Boolean = false,
    val supportsBudget: Boolean = false,
    val maxBudgetTokens: Int? = null,
    val supportsMaxTokens: Boolean? = null,
) {
    val selectableEfforts: List<ReasoningEffort>
        get() = buildList {
            if (canDisable && !mandatory) add(ReasoningEffort.OFF)
            add(ReasoningEffort.DEFAULT)
            supportedEfforts
                .asSequence()
                .filter {
                    it != ReasoningEffort.OFF &&
                        it != ReasoningEffort.DEFAULT &&
                        it != ReasoningEffort.AUTO
                }
                .distinct()
                .sortedBy(ReasoningEffort::rank)
                .forEach(::add)
        }

    fun normalize(requested: ReasoningEffort): ReasoningEffort {
        // 自动档是用户选择，不是"模型能力"，绝不参与归一化：
        // 具体档位由 ProviderReasoning 按用途／轮次解析，这里保留它。
        if (requested == ReasoningEffort.AUTO) return ReasoningEffort.AUTO
        val selectable = selectableEfforts
        if (requested in selectable) return requested
        if (requested == ReasoningEffort.OFF || requested == ReasoningEffort.DEFAULT) {
            return ReasoningEffort.DEFAULT
        }
        return selectable
            .filter { it != ReasoningEffort.OFF && it.rank <= requested.rank }
            .maxByOrNull(ReasoningEffort::rank)
            ?: ReasoningEffort.DEFAULT
    }
}
