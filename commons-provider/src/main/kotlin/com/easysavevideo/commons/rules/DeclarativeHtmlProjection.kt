package com.easysavevideo.commons.rules

import com.easysavevideo.commons.Json
import com.easysavevideo.commons.isWellFormedUnicode
import com.easysavevideo.core.model.EpochClock
import com.easysavevideo.discovery.CandidateUrl
import com.easysavevideo.discovery.DiscoveryError
import com.easysavevideo.discovery.DiscoveryLimits
import com.easysavevideo.discovery.DiscoveryOperation
import com.easysavevideo.discovery.ProviderDescriptor
import com.easysavevideo.discovery.ProviderPage
import com.easysavevideo.discovery.ResultNormalizer
import com.easysavevideo.discovery.SourceReportedCandidateMetadata
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Closed, data-only tree projection. Paths are structured steps, never CSS or executable text. */
class DeclarativeHtmlProjection private constructor(
    internal val container: HtmlRulePath,
    internal val items: HtmlRulePath,
    internal val fields: Map<String, HtmlRuleField>,
    internal val nextCursor: HtmlRuleField?,
) {
    override fun toString(): String = "DeclarativeHtmlProjection([redacted])"

    companion object {
        internal fun compile(values: Map<String, Json>, check: () -> Unit): DeclarativeHtmlProjection {
            check()
            if (!values.keys.containsAll(setOf("container", "items", "fields")) ||
                values.keys.any { it !in setOf("container", "items", "fields", "nextCursor") }) invalidRule()
            val mappings = (values["fields"] as? Json.Obj)?.fields ?: invalidRule()
            if ("pageUrl" !in mappings || mappings.keys.any { it !in FIELD_NAMES }) invalidRule()
            val fields = linkedMapOf<String, HtmlRuleField>()
            for ((name, value) in mappings) {
                check()
                fields[name] = HtmlRuleField.parse(value, name, check)
            }
            return DeclarativeHtmlProjection(HtmlRulePath.parse(values["container"], false, check),
                HtmlRulePath.parse(values["items"], false, check), Collections.unmodifiableMap(fields),
                if ("nextCursor" in values) HtmlRuleField.parse(values["nextCursor"], "nextCursor", check) else null)
        }
        private val FIELD_NAMES = setOf("pageUrl", "title", "description", "reportedDurationMs", "reportedPublishedAtEpochMillis")
    }
}

internal class HtmlRuleField private constructor(val path: HtmlRulePath, val attribute: String?) {
    companion object {
        fun parse(value: Json?, field: String, check: () -> Unit): HtmlRuleField {
            val values = (value as? Json.Obj)?.fields ?: invalidRule()
            if (values.keys != setOf("path", "text") && values.keys != setOf("path", "attribute")) invalidRule()
            val attribute = if ("text" in values) {
                if (field !in setOf("title", "description") || (values["text"] as? Json.Bool)?.value != true) invalidRule()
                null
            } else {
                val name = (values["attribute"] as? Json.Str)?.value ?: invalidRule()
                val allowed = when (field) {
                    "pageUrl" -> setOf("href", "data-url")
                    "title" -> setOf("data-title")
                    "description" -> setOf("data-description")
                    "reportedDurationMs" -> setOf("data-duration-ms")
                    "reportedPublishedAtEpochMillis" -> setOf("data-published-at-ms")
                    "nextCursor" -> setOf("data-cursor")
                    else -> invalidRule()
                }
                if (name !in allowed) invalidRule()
                name
            }
            return HtmlRuleField(HtmlRulePath.parse(values["path"], true, check), attribute)
        }
    }
}

/** Private detached steps. No downloaded string is passed to Jsoup's selector/evaluator API. */
internal class HtmlRulePath private constructor(private val steps: List<Step>) {
    private class Step(val descendants: Boolean, val tag: String, val id: String?, val className: String?,
        val attribute: String?, val value: String?)

    fun find(scope: Node, tree: HtmlProjectionTree): List<Element> {
        var scopes = listOf(scope)
        for (step in steps) {
            val found = Collections.newSetFromMap(IdentityHashMap<Element, Boolean>())
            for (parent in scopes) {
                tree.charge()
                if (step.descendants) {
                    tree.walkChildren(parent) { node ->
                        if (node is Element && matches(node, step, tree)) found.add(node)
                        true
                    }
                } else {
                    for (index in 0 until parent.childNodeSize()) {
                        tree.charge()
                        val node = parent.childNode(index)
                        if (node is Element && matches(node, step, tree)) found.add(node)
                    }
                }
            }
            // Overlapping ancestor scopes never duplicate results or disturb document order.
            scopes = found.sortedWith { left, right ->
                tree.charge()
                tree.orderOf(left).compareTo(tree.orderOf(right))
            }
        }
        return scopes.map { it as? Element ?: invalidRuleResponse() }
    }

    private fun matches(element: Element, step: Step, tree: HtmlProjectionTree): Boolean {
        tree.charge()
        if (element.normalName() != step.tag) return false
        if (step.id != null && !tree.equal(element.attr("id"), step.id)) return false
        if (step.className != null && !classToken(element.attr("class"), step.className, tree)) return false
        if (step.attribute != null && (!element.hasAttr(step.attribute) ||
                !tree.equal(element.attr(step.attribute), step.value!!))) return false
        return true
    }

    private fun classToken(value: String, wanted: String, tree: HtmlProjectionTree): Boolean {
        var start = 0
        while (start < value.length) {
            tree.charge()
            if (asciiSpace(value[start])) { start++; continue }
            var end = start
            while (end < value.length && !asciiSpace(value[end])) { tree.charge(); end++ }
            if (end - start == wanted.length) {
                var same = true
                for (index in wanted.indices) {
                    tree.charge()
                    if (value[start + index] != wanted[index]) { same = false; break }
                }
                if (same) return true
            }
            start = end
        }
        return false
    }

    companion object {
        private val TAGS = setOf("html", "body", "main", "section", "article", "div", "ul", "ol", "li",
            "a", "span", "h1", "h2", "h3", "p", "time")
        private val ATTRIBUTES = setOf("href", "data-url", "data-duration-ms", "data-published-at-ms",
            "data-title", "data-description", "data-cursor")

        fun parse(value: Json?, allowEmpty: Boolean, check: () -> Unit): HtmlRulePath {
            val values = (value as? Json.Arr)?.values ?: invalidRule()
            if (values.size !in (if (allowEmpty) 0 else 1)..4) invalidRule()
            val steps = values.map { raw ->
                check()
                val fields = (raw as? Json.Obj)?.fields ?: invalidRule()
                if (!fields.keys.containsAll(setOf("axis", "tag")) ||
                    fields.keys.any { it !in setOf("axis", "tag", "id", "class", "attribute") }) invalidRule()
                val descendants = when ((fields["axis"] as? Json.Str)?.value) {
                    "child" -> false
                    "descendant" -> true
                    else -> invalidRule()
                }
                val tag = (fields["tag"] as? Json.Str)?.value?.takeIf { it in TAGS } ?: invalidRule()
                fun token(name: String): String? = if (name in fields)
                    (fields[name] as? Json.Str)?.value?.takeIf { asciiToken(it, 64) } ?: invalidRule() else null
                val predicate = if ("attribute" in fields) {
                    val predicate = (fields["attribute"] as? Json.Obj)?.fields ?: invalidRule()
                    if (predicate.keys != setOf("name", "value")) invalidRule()
                    val name = (predicate["name"] as? Json.Str)?.value?.takeIf { it in ATTRIBUTES } ?: invalidRule()
                    val text = (predicate["value"] as? Json.Str)?.value ?: invalidRule()
                    if (text.length > 128 || text.any { it.isISOControl() }) invalidRule()
                    name to text
                } else null
                Step(descendants, tag, token("id"), token("class"), predicate?.first, predicate?.second)
            }
            return HtmlRulePath(steps.toList())
        }
        private fun asciiSpace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\n' || char == '\r' || char == '\u000c'
    }
}

/** Post-parse input-tree and finite projection bounds; these do not bound Jsoup's initial peak. */
internal class HtmlProjectionTree(root: Node, private val check: () -> Unit) {
    private val order = IdentityHashMap<Node, Int>()
    private var work = 0
    private class Frame(val node: Node, val depth: Int, var nextChild: Int = 0)

    init {
        fun admit(node: Node, depth: Int) {
            charge()
            if (depth > MAX_DEPTH || order.size == MAX_NODES) invalidRuleResponse()
            order[node] = order.size
            if (node is Element) {
                val attributes = node.attributes()
                if (attributes.size() > MAX_ATTRIBUTES) invalidRuleResponse()
                for (attribute in attributes) {
                    charge()
                    if (attribute.value.length > MAX_ATTRIBUTE_LENGTH) invalidRuleResponse()
                }
            }
        }
        admit(root, 0)
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, 0))
        while (stack.isNotEmpty()) {
            charge()
            val frame = stack.peekLast()
            if (frame.nextChild == frame.node.childNodeSize()) { stack.removeLast(); continue }
            val child = frame.node.childNode(frame.nextChild++)
            admit(child, frame.depth + 1)
            stack.addLast(Frame(child, frame.depth + 1))
        }
    }

    fun charge() {
        check()
        if (++work > MAX_WORK) invalidRuleResponse()
    }

    fun equal(left: String, right: String): Boolean {
        charge()
        if (left.length != right.length) return false
        for (index in left.indices) { charge(); if (left[index] != right[index]) return false }
        return true
    }

    fun orderOf(node: Node): Int = order[node] ?: invalidRuleResponse()

    /** No recursion or sibling-wide work queue; at most the previously admitted tree depth. */
    fun walkChildren(parent: Node, visitor: (Node) -> Boolean) {
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(parent, 0))
        while (stack.isNotEmpty()) {
            charge()
            val frame = stack.peekLast()
            if (frame.nextChild == frame.node.childNodeSize()) { stack.removeLast(); continue }
            val child = frame.node.childNode(frame.nextChild++)
            charge()
            if (visitor(child)) stack.addLast(Frame(child, frame.depth + 1))
        }
    }

    fun text(element: Element, maximum: Int): String? {
        // A descendant path may select inside a suppressed domain; selecting a child does not
        // make script/template/fallback text eligible for source-reported display metadata.
        var ancestor: Node? = element
        while (ancestor != null) {
            charge()
            if (ancestor is Element && ancestor.normalName() in SUPPRESSED_TEXT) return null
            ancestor = ancestor.parentNode()
        }
        val output = StringBuilder()
        var units = 0
        var pendingSpace = false
        walkChildren(element) { node ->
            if (node is Element && node.normalName() in SUPPRESSED_TEXT) false
            else {
                if (node is TextNode) {
                    val text = node.wholeText
                    for (char in text) {
                        charge()
                        if (++units > maximum) invalidRuleResponse()
                        if (plainSpace(char)) pendingSpace = output.isNotEmpty()
                        else {
                            if (pendingSpace) { output.append(' '); pendingSpace = false }
                            output.append(char)
                            if (output.length > maximum) invalidRuleResponse()
                        }
                    }
                    if (output.isNotEmpty()) pendingSpace = true
                }
                true
            }
        }
        return output.toString().takeIf { it.isNotEmpty() }
    }

    fun plain(value: String, maximum: Int): String? {
        if (value.length > maximum) invalidRuleResponse()
        val output = StringBuilder()
        var pendingSpace = false
        for (char in value) {
            charge()
            if (plainSpace(char)) pendingSpace = output.isNotEmpty()
            else {
                if (pendingSpace) { output.append(' '); pendingSpace = false }
                output.append(char)
            }
        }
        return output.toString().takeIf { it.isNotEmpty() }
    }

    companion object {
        const val MAX_NODES = 4096
        const val MAX_DEPTH = 64
        const val MAX_ATTRIBUTES = 16
        const val MAX_ATTRIBUTE_LENGTH = 4096
        const val MAX_WORK = 200_000
        private val SUPPRESSED_TEXT = setOf("script", "style", "template", "iframe", "object", "svg", "math", "noscript")
        private fun plainSpace(char: Char): Boolean = char.isWhitespace() || Character.isSpaceChar(char) ||
            char.isISOControl() || char in '\u202a'..'\u202e' || char in '\u2066'..'\u2069' ||
            char == '\u200e' || char == '\u200f' || char == '\u061c'
    }
}

/** Parses supplied bytes only. No fetch, script, renderer, media evidence, or validation authority. */
class DeclarativeHtmlProjectionExecutor(
    private val projection: DeclarativeHtmlProjection,
    descriptor: ProviderDescriptor,
    private val sourceName: String,
    private val limits: DiscoveryLimits = DiscoveryLimits(),
    private val clock: EpochClock = EpochClock(System::currentTimeMillis),
    private val originalRequestUrl: String,
) {
    val descriptor: ProviderDescriptor = descriptor.copy(
        compatibleCursorVersions = Collections.unmodifiableSet(LinkedHashSet(descriptor.compatibleCursorVersions)))

    init {
        require(sourceName.length in 1..128 && sourceName.isNotBlank() && isWellFormedUnicode(sourceName) &&
            sourceName.none { it.isISOControl() })
    }

    fun execute(bytes: ByteArray, operation: DiscoveryOperation): ProviderPage = executeObserved(bytes, operation) {}

    internal fun executeObserved(bytes: ByteArray, operation: DiscoveryOperation,
        structureRecognized: () -> Unit): ProviderPage {
        fun check() {
            if (Thread.currentThread().isInterrupted) ruleFailure(DiscoveryError.CANCELLED)
            operation.check(clock.nowMillis())
        }
        check()
        val maximum = minOf(BoundedHttpRule.MAX_RESPONSE_BYTES, limits.maxResponseBytes,
            operation.context.resourceBudget.maxBytes ?: Long.MAX_VALUE)
        if (bytes.size > maximum) ruleFailure(DiscoveryError.RESPONSE_TOO_LARGE)
        val base = safeBase(originalRequestUrl) ?: invalidRule()
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.copyOf())).toString() }
            catch (_: CharacterCodingException) { invalidRuleResponse() }
        check()
        // Jsoup performs tree construction only. Its initial CPU/peak allocation is not hard-preemptible.
        val document = try { Jsoup.parse(text, "") }
            catch (_: RuntimeException) { invalidRuleResponse() }
        check()
        val tree = HtmlProjectionTree(document, ::check)
        val container = projection.container.find(document, tree).singleOrNull() ?: invalidRuleResponse()
        val items = projection.items.find(container, tree)
        if (items.size > minOf(10, limits.maxCandidatesPerResponse)) ruleFailure(DiscoveryError.TOO_MANY_CANDIDATES)
        structureRecognized()
        val next = projection.nextCursor?.let { extract(it, container, tree, 2048) }
        if (next != null && (next.isBlank() || !isWellFormedUnicode(next) || next.any { it.isISOControl() } ||
                next.toByteArray(Charsets.UTF_8).size > 2048)) invalidRuleResponse()
        val candidates = linkedMapOf<String, CandidateUrl>()
        for (item in items) {
            check()
            val fields = projection.fields
            val raw = extract(fields.getValue("pageUrl"), item, tree, 4096)
            val title = fields["title"]?.let { extract(it, item, tree, 512) }
            val description = fields["description"]?.let { extract(it, item, tree, 2048) }
            val duration = number(fields["reportedDurationMs"]?.let { extract(it, item, tree, 19) }, true)
            val published = number(fields["reportedPublishedAtEpochMillis"]?.let { extract(it, item, tree, 19) }, false)
            val url = raw?.let { candidateUrl(base, it) } ?: continue
            candidates.putIfAbsent(url, CandidateUrl(url, SourceReportedCandidateMetadata(title = title,
                sourceName = sourceName, description = description, reportedDurationMs = duration,
                reportedPublishedAtEpochMillis = published)))
        }
        check()
        return ProviderPage(candidates = candidates.values.toList(), nextCursor = next, exhausted = next == null,
            responseBytes = bytes.size.toLong())
    }

    private fun extract(field: HtmlRuleField, scope: Element, tree: HtmlProjectionTree, maximum: Int): String? {
        val selected = field.path.find(scope, tree)
        if (selected.size > 1) invalidRuleResponse()
        val element = selected.singleOrNull() ?: return null
        val name = field.attribute ?: return tree.text(element, maximum)
        if (!element.hasAttr(name)) return null
        val value = element.attr(name)
        if (value.length > maximum) invalidRuleResponse()
        return if (name == "data-title" || name == "data-description") tree.plain(value, maximum) else value
    }

    private fun number(value: String?, positive: Boolean): Long? {
        if (value == null) return null
        if (value.length !in 1..19 || value.any { it !in '0'..'9' }) invalidRuleResponse()
        return value.toLongOrNull()?.takeIf { it >= 0 && (!positive || it > 0) } ?: invalidRuleResponse()
    }

    private fun safeBase(value: String): URI? = try {
        URI(value).takeIf { it.scheme?.lowercase(Locale.ROOT) in setOf("https", "http") &&
            !it.host.isNullOrBlank() && it.rawUserInfo == null && it.rawFragment == null &&
            value.length <= 16_384 && value.none { char -> char.isISOControl() || char == '\\' } }
    } catch (_: Exception) { null }

    private fun candidateUrl(base: URI, value: String): String? = try {
        if (value.isBlank() || value.length > 4096 || !isWellFormedUnicode(value) ||
            value.any { it.isISOControl() || it == '\\' }) null
        else {
            val relative = URI(value)
            if (relative.rawUserInfo != null || relative.rawFragment != null ||
                relative.scheme?.lowercase(Locale.ROOT)?.let { it !in setOf("http", "https") } == true) null
            else ResultNormalizer.canonicalUrl(base.resolve(relative).toString())?.takeIf { it.length <= 4096 }
        }
    } catch (_: Exception) { null }

    override fun toString(): String = "DeclarativeHtmlProjectionExecutor([redacted])"
}
