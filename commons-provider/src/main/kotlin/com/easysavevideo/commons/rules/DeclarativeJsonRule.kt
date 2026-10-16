package com.easysavevideo.commons.rules

import com.easysavevideo.commons.CommonsFailure
import com.easysavevideo.commons.Json
import com.easysavevideo.commons.StrictJson
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryFailure

/**
 * A finite, data-only mapping plan. Compilation validates syntax, not publisher trust or authority.
 * This schema is NOT ProviderPackage v1 and is not connected to package installation or a provider
 * registry. No rule can supply code, requests, credentials, source identity, or resource budgets.
 */
class DeclarativeJsonRule private constructor(
    override val ruleVersion: Long,
    internal val itemsPath: JsonRulePath,
    internal val fields: JsonRuleFields,
    internal val nextCursorPath: JsonRulePath?,
) : CandidateRulePlan {
    override fun toString(): String = "DeclarativeJsonRule([redacted])"

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_RULE_BYTES = 16_384
        const val MAX_PATH_SEGMENTS = 8
        const val MAX_PATH_KEY_LENGTH = 128
        const val MAX_PATH_INDEX = 99

        /** Unknown fields and duplicate JSON keys fail closed. Returned paths have no mutable view. */
        fun compile(bytes: ByteArray, checkCancelled: () -> Unit = {}): DeclarativeJsonRule {
            fun check() {
                if (Thread.currentThread().isInterrupted) ruleFailure(DiscoveryError.CANCELLED)
                try { checkCancelled() }
                catch (failure: DiscoveryFailure) { throw failure }
                catch (_: Exception) { ruleFailure(DiscoveryError.CANCELLED) }
                if (Thread.currentThread().isInterrupted) ruleFailure(DiscoveryError.CANCELLED)
            }
            check()
            if (bytes.size > MAX_RULE_BYTES) invalidRule()
            val root = try { StrictJson.read(bytes.copyOf(), ::check) as? Json.Obj ?: invalidRule() }
                catch (_: CommonsFailure) { invalidRule() }
            val values = root.fields
            if (!values.keys.containsAll(setOf("schemaVersion", "ruleVersion", "itemsPath", "fields")) ||
                values.keys.any { it !in setOf("schemaVersion", "ruleVersion", "itemsPath", "fields", "nextCursorPath") }) invalidRule()
            if ((values["schemaVersion"] as? Json.Num)?.raw != SCHEMA_VERSION.toString()) invalidRule()
            val version = positiveInteger(values["ruleVersion"])
            val rule = compileMapping(version, values.filterKeys {
                it in setOf("itemsPath", "fields", "nextCursorPath")
            })
            check()
            return rule
        }

        /** Shares the already parsed finite mapping, without reinterpreting old kind1 rule bytes. */
        internal fun compileMapping(version: Long, values: Map<String, Json>): DeclarativeJsonRule {
            if (version <= 0 || !values.keys.containsAll(setOf("itemsPath", "fields")) ||
                values.keys.any { it !in setOf("itemsPath", "fields", "nextCursorPath") }) invalidRule()
            val mappings = (values["fields"] as? Json.Obj)?.fields ?: invalidRule()
            if ("pageUrl" !in mappings || mappings.keys.any {
                    it !in setOf("pageUrl", "title", "description", "reportedDurationMs", "reportedPublishedAtEpochMillis")
                }) invalidRule()
            fun field(name: String): JsonRulePath? = if (name in mappings) JsonRulePath.parse(mappings[name]) else null
            return DeclarativeJsonRule(version, JsonRulePath.parse(values["itemsPath"]),
                JsonRuleFields(field("pageUrl") ?: invalidRule(), field("title"), field("description"),
                    field("reportedDurationMs"), field("reportedPublishedAtEpochMillis")),
                if ("nextCursorPath" in values) JsonRulePath.parse(values["nextCursorPath"]) else null)
        }

        private fun positiveInteger(value: Json?): Long {
            val raw = (value as? Json.Num)?.raw ?: invalidRule()
            if (raw.isEmpty() || raw.any { it !in '0'..'9' }) invalidRule()
            return raw.toLongOrNull()?.takeIf { it > 0 } ?: invalidRule()
        }
    }
}

internal class JsonRuleFields(
    val pageUrl: JsonRulePath,
    val title: JsonRulePath?,
    val description: JsonRulePath?,
    val durationMs: JsonRulePath?,
    val publishedAtEpochMillis: JsonRulePath?,
)

/** Literal object keys and exact array indexes only. No dotted paths, wildcard, recursion or code. */
internal class JsonRulePath private constructor(private val segments: List<Segment>) {
    private sealed interface Segment {
        class Key(val value: String) : Segment
        class Index(val value: Int) : Segment
    }

    fun read(root: Json, check: () -> Unit): Json? {
        var value: Json? = root
        for (segment in segments) {
            check()
            if (value == null || value === Json.Nil) return null
            value = when (segment) {
                is Segment.Key -> (value as? Json.Obj ?: invalidRuleResponse()).fields[segment.value]
                is Segment.Index -> (value as? Json.Arr ?: invalidRuleResponse()).values.getOrNull(segment.value)
            }
        }
        return value
    }

    companion object {
        fun parse(value: Json?): JsonRulePath {
            val input = (value as? Json.Arr)?.values ?: invalidRule()
            if (input.size > DeclarativeJsonRule.MAX_PATH_SEGMENTS) invalidRule()
            val segments = input.map { segment ->
                when (segment) {
                    is Json.Str -> {
                        if (segment.value.length !in 1..DeclarativeJsonRule.MAX_PATH_KEY_LENGTH ||
                            segment.value.any { it.isISOControl() }) invalidRule()
                        Segment.Key(segment.value)
                    }
                    is Json.Num -> {
                        if (segment.raw.isEmpty() || segment.raw.any { it !in '0'..'9' }) invalidRule()
                        Segment.Index(segment.raw.toIntOrNull()?.takeIf {
                            it in 0..DeclarativeJsonRule.MAX_PATH_INDEX
                        } ?: invalidRule())
                    }
                    else -> invalidRule()
                }
            }
            // The copied collection is private: no getter, data-class copy, or caller-owned list.
            return JsonRulePath(segments.toList())
        }
    }
}

internal fun ruleFailure(code: DiscoveryError): Nothing = throw DiscoveryFailure(code)
internal fun invalidRule(): Nothing = ruleFailure(DiscoveryError.SOURCE_CONFIGURATION)
internal fun invalidRuleResponse(): Nothing = ruleFailure(DiscoveryError.INVALID_RESULT)
