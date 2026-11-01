package com.easysavevideo.searxng

import com.easysavevideo.discovery.CandidateUrl
import com.easysavevideo.discovery.SourceReportedCandidateMetadata
import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

internal data class SearxngHtmlPage(val candidates: List<CandidateUrl>, val hasNext: Boolean) {
    override fun toString(): String = "SearxngHtmlPage(count=${candidates.size}, hasNext=$hasNext)"
}

/**
 * Original, deliberately narrow Simple-theme reader. Interface references only:
 * https://docs.searxng.org/dev/templates.html and https://docs.searxng.org/dev/search_api.html.
 * No upstream implementation/template is included. Jsoup consumes already bounded local text;
 * it never connects, renders, executes scripts, follows forms, or loads images here.
 *
 * Only complete, explicitly balanced documents and default/videos result rows are supported.
 * Unknown themes, repaired markup and ambiguous error/empty pages fail closed. The generic
 * upstream empty-state container is not sufficient evidence of an empty search: currently only
 * its English no-results message is recognized. Other translations fail as INVALID_RESPONSE.
 * Metadata and pagination remain untrusted hints, never media or Save authority.
 */
internal object SearxngHtmlResponseParser {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_NODES = 20_000
    private const val MAX_DEPTH = 64
    private const val MAX_TAG_CHARS = 16_384
    private const val MAX_TEXT_CHARS = 65_536
    private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val rawTags = setOf("script", "style", "title", "textarea", "iframe", "noembed", "noframes", "xmp")
    private val omittedTextTags = setOf("script", "style", "iframe", "object", "embed", "svg", "math", "template", "noscript", "noembed", "noframes")
    private val blockedMarkers = setOf("captcha", "g-recaptcha", "h-captcha", "cf-turnstile", "cf-challenge", "cf-error-details", "challenge", "challenge-form", "challenge-stage", "challenge-running", "cf-challenge-running", "anubis", "anubis_challenge", "anubis_version", "login", "login-form", "error", "error-page", "dialog-error")

    fun parse(bytes: ByteArray, endpoint: SearxngEndpoint, currentPage: Int, maxCandidates: Int, check: () -> Unit): SearxngHtmlPage {
        check()
        if (endpoint.format != SearxngResultFormat.HTML_WEB || currentPage !in 1..100 || maxCandidates !in 1..100)
            throw SearxngFailure(SearxngError.INVALID_REQUEST)
        if (bytes.size > MAX_BYTES) throw SearxngFailure(SearxngError.RESPONSE_TOO_LARGE)
        val html = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { invalidResponse() }
        check()
        preflight(html, check)
        val parser = Parser.htmlParser().setTrackErrors(1)
        // A bounded read size provides cancellation checkpoints while Jsoup consumes input.
        val document = object : StringReader(html) {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                check()
                return super.read(buffer, offset, minOf(length, 4096))
            }
        }.use { parser.parseInput(it, "") }
        check()
        if (parser.errors.isNotEmpty()) invalidResponse()
        val elements = descendants(document, check)
        val ids = linkedMapOf<String, Element>()
        for (element in elements) {
            check()
            if (element.attributes().size() > 64) invalidResponse()
            val id = element.id()
            if (id.isNotEmpty() && ids.put(id, element) != null) invalidResponse()
            val markers = element.classNames() + id
            if (markers.any { it.lowercase(Locale.ROOT) in blockedMarkers } ||
                element.normalName() == "input" && element.attr("type").equals("password", true) ||
                element.normalName() == "meta" && element.attr("http-equiv").equals("refresh", true) ||
                element.normalName() in setOf("base", "template", "frameset", "plaintext")) invalidResponse()
        }
        val generators = elements.filter { it.normalName() == "meta" && it.attr("name").equals("generator", true) }
        if (generators.size != 1 || !generators.single().attr("content").lowercase(Locale.ROOT).startsWith("searxng/")) invalidResponse()
        if (!document.body().hasClass("search_endpoint")) invalidResponse()
        val main = ids["main_results"] ?: invalidResponse()
        val results = ids["results"] ?: invalidResponse()
        val urls = ids["urls"] ?: invalidResponse()
        if (main.normalName() != "main" || main.parent() !== document.body() ||
            results.normalName() != "div" || results.parent() !== main ||
            urls.normalName() != "div" || urls.parent() !== results) invalidResponse()

        val rows = elements.filter { it.normalName() == "article" && it.hasClass("result") }
        if (rows.size > maxCandidates) throw SearxngFailure(SearxngError.TOO_MANY_CANDIDATES)
        val alerts = elements.filter { it.hasClass("dialog-error-block") || it.attr("role").equals("alert", true) }
        if (rows.isEmpty()) {
            if (alerts.size != 1 || !supportedEmpty(urls, alerts.single(), check)) invalidResponse()
        } else if (alerts.isNotEmpty()) invalidResponse()

        val found = linkedMapOf<String, CandidateUrl>()
        for (row in rows) {
            check()
            var parent = row.parent()
            while (parent !== urls) {
                if (parent == null || parent.normalName() != "div" ||
                    parent.classNames().none { it == "template_group_default" || it == "template_group_videos" }) invalidResponse()
                parent = parent.parent()
            }
            val types = row.classNames().filter { it.startsWith("result-") }
            if (types.size != 1 || types.single() !in setOf("result-default", "result-videos")) invalidResponse()
            val rowElements = descendants(row, check)
            if (rowElements.any { it !== row && it.normalName() == "article" }) invalidResponse()
            val inner = rowElements.filter { it.hasClass("result_inner") }
            if (inner.size > 1 || inner.singleOrNull()?.parent()?.let { it !== row } == true) invalidResponse()
            val owner = inner.singleOrNull() ?: row
            val headings = rowElements.filter { it.normalName() == "h3" }
            if (headings.size != 1 || headings.single().parent() !== owner) invalidResponse()
            val heading = headings.single()
            val anchors = descendants(heading, check).filter { it.normalName() == "a" }
            if (anchors.size != 1 || anchors.single().parent() !== heading ||
                heading.children().size != 1 || heading.childNodes().any { it is TextNode && !it.isBlank }) invalidResponse()
            val link = anchors.single()
            if (!link.hasAttr("href")) invalidResponse()
            val descriptions = rowElements.filter { it.hasClass("content") }
            if (descriptions.size > 1 || descriptions.any { it.parent() !== owner || it.normalName() != "p" }) invalidResponse()
            val url = SearxngUrlPolicy.candidate(link.attr("href")) ?: continue
            val title = plainText(link, 512, check)
            val description = descriptions.singleOrNull()?.takeUnless { it.hasClass("empty_element") }
                ?.let { plainText(it, 2048, check) }
            found.putIfAbsent(url, CandidateUrl(url, SourceReportedCandidateMetadata(
                title = title, sourceName = URI(url).host, description = description)))
        }
        val next = elements.filter { it.hasClass("next_page") }
        if (next.size > 1) invalidResponse()
        val hasNext = next.singleOrNull()?.let { form ->
            val pagination = ids["pagination"] ?: invalidResponse()
            if (rows.isEmpty() || pagination.normalName() != "nav" || pagination.parent() !== results ||
                form.normalName() != "form" || form.parent() !== pagination ||
                form.attr("method").lowercase(Locale.ROOT) !in setOf("get", "post") ||
                !SearxngUrlPolicy.isContinuationAction(endpoint, form.attr("action"))) invalidResponse()
            val pageInputs = descendants(form, check).filter { it.attr("name") == "pageno" }
            if (pageInputs.size != 1) invalidResponse()
            val field = pageInputs.single()
            val value = field.attr("value")
            if (field.normalName() != "input" || !field.attr("type").equals("hidden", true) ||
                value.length !in 1..3 || value.any { it !in '0'..'9' } || value.startsWith('0')) invalidResponse()
            val nextPage = value.toIntOrNull() ?: invalidResponse()
            if (nextPage !in 2..100 || nextPage != currentPage + 1) invalidResponse()
            // Only a same-endpoint page hint survives. No action, other fields, links or engine state escape.
            true
        } ?: false
        check()
        return SearxngHtmlPage(found.values.toList(), hasNext)
    }

    private fun supportedEmpty(urls: Element, alert: Element, check: () -> Unit): Boolean {
        if (alert.parent() !== urls || alert.normalName() != "div" || !alert.hasClass("dialog-error-block") ||
            !alert.attr("role").equals("alert", true) || urls.children().size != 1 ||
            urls.childNodes().any { it is TextNode && !it.isBlank }) return false
        val children = alert.children()
        if (children.size != 3 || children[0].normalName() != "p" || children[1].normalName() != "p" || children[2].normalName() != "ul") return false
        val message = plainText(children[1], 512, check)?.lowercase(Locale.ROOT) ?: return false
        return message.startsWith("no results were found.") || message.startsWith("there are no more results.")
    }

    private fun descendants(root: Node, check: () -> Unit): List<Element> {
        val result = mutableListOf<Element>()
        val stack = java.util.ArrayDeque<Pair<Node, Int>>()
        stack.addLast(root to 0)
        var count = 0
        while (stack.isNotEmpty()) {
            check()
            val (node, depth) = stack.removeLast()
            if (++count > MAX_NODES || depth > MAX_DEPTH) invalidResponse()
            if (node is Element) result.add(node)
            if (node is TextNode && node.wholeText.length > MAX_TEXT_CHARS) invalidResponse()
            for (index in node.childNodeSize() - 1 downTo 0) stack.addLast(node.childNode(index) to depth + 1)
        }
        return result
    }

    private fun plainText(root: Element, maximum: Int, check: () -> Unit): String? {
        val out = StringBuilder(maximum)
        val stack = java.util.ArrayDeque<Node>()
        stack.addLast(root)
        var space = false
        while (stack.isNotEmpty() && out.length < maximum) {
            check()
            val node = stack.removeLast()
            if (node is Element) {
                if (node.normalName() in omittedTextTags || node.hasAttr("hidden") || node.attr("aria-hidden").equals("true", true)) continue
                if (node.isBlock || node.normalName() == "br") space = true
            }
            if (node is TextNode) {
                for ((index, char) in node.wholeText.withIndex()) {
                    if (index % 256 == 0) check()
                    if (char.isWhitespace() || Character.isSpaceChar(char) || char.isISOControl() || Character.getType(char) == Character.FORMAT.toInt()) space = true
                    else {
                        if (space && out.isNotEmpty() && out.length < maximum) out.append(' ')
                        space = false
                        if (out.length < maximum) out.append(char)
                    }
                    if (out.length >= maximum) break
                }
            }
            for (index in node.childNodeSize() - 1 downTo 0) stack.addLast(node.childNode(index))
        }
        return out.toString().dropLastWhile { it.isHighSurrogate() }.trim().takeIf { it.isNotEmpty() }
    }

    /** Linear lexical gate before any DOM allocation; intentionally rejects omitted end tags. */
    private fun preflight(html: String, check: () -> Unit) {
        var offset = 0
        var tokens = 0
        var doctype = false
        var rootSeen = false
        var headSeen = false
        var bodySeen = false
        val stack = mutableListOf<String>()
        fun tick() { if (offset % 256 == 0) check() }
        while (offset < html.length) {
            tick()
            if (html[offset] == '\u0000') invalidResponse()
            val raw = stack.lastOrNull()?.takeIf { it in rawTags }
            if (raw != null && !html.regionMatches(offset, "</$raw", 0, raw.length + 2, true)) {
                val start = offset
                while (offset < html.length && !html.regionMatches(offset, "</$raw", 0, raw.length + 2, true)) {
                    tick()
                    if (html[offset] == '\u0000' || offset - start >= MAX_TEXT_CHARS) invalidResponse()
                    offset++
                }
                continue
            }
            if (html[offset] != '<') {
                val start = offset
                while (offset < html.length && html[offset] != '<') {
                    tick()
                    if (html[offset] == '\u0000' || offset - start >= MAX_TEXT_CHARS ||
                        stack.isEmpty() && !html[offset].isWhitespace() && !(offset == 0 && html[offset] == '\ufeff')) invalidResponse()
                    offset++
                }
                continue
            }
            if (++tokens > MAX_NODES) invalidResponse()
            if (html.startsWith("<!--", offset)) {
                offset += 4
                val start = offset
                while (offset < html.length && !html.startsWith("-->", offset)) {
                    tick()
                    if (html[offset] == '\u0000' || offset - start >= MAX_TEXT_CHARS) invalidResponse()
                    offset++
                }
                if (offset == html.length) invalidResponse()
                offset += 3
                continue
            }
            val start = offset++
            var quote: Char? = null
            while (offset < html.length) {
                tick()
                val char = html[offset]
                if (char == '\u0000' || offset - start >= MAX_TAG_CHARS || char == '<') invalidResponse()
                if (quote != null) { if (char == quote) quote = null }
                else if (char == '\'' || char == '"') quote = char
                else if (char == '>') break
                offset++
            }
            if (offset == html.length || quote != null) invalidResponse()
            val token = html.substring(start + 1, offset++)
            if (token.equals("!doctype html", true)) {
                if (doctype || rootSeen || stack.isNotEmpty()) invalidResponse()
                doctype = true
                continue
            }
            val closing = token.startsWith('/')
            val content = if (closing) token.substring(1) else token
            val name = content.takeWhile { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == ':' }.lowercase(Locale.ROOT)
            if (name.isEmpty() || name.length > 64 || name[0] !in 'a'..'z') invalidResponse()
            val remainder = content.substring(name.length)
            if (remainder.isNotEmpty() && !remainder[0].isWhitespace() && remainder != "/") invalidResponse()
            if (closing) {
                if (remainder.isNotBlank() || stack.lastOrNull() != name) invalidResponse()
                stack.removeAt(stack.lastIndex)
            } else {
                if (stack.isEmpty()) {
                    if (!doctype || rootSeen || name != "html") invalidResponse()
                    rootSeen = true
                } else if (name == "html") invalidResponse()
                if (name == "head") {
                    if (headSeen || bodySeen || stack.lastOrNull() != "html") invalidResponse()
                    headSeen = true
                }
                if (name == "body") {
                    if (!headSeen || bodySeen || stack.lastOrNull() != "html") invalidResponse()
                    bodySeen = true
                }
                if (name in setOf("a", "form", "article") && name in stack) invalidResponse()
                val selfClosing = token.trimEnd().endsWith('/')
                if (selfClosing && name !in voidTags && name !in setOf("svg", "math") && "svg" !in stack && "math" !in stack) invalidResponse()
                if (name !in voidTags && !selfClosing) {
                    stack += name
                    if (stack.size > MAX_DEPTH) invalidResponse()
                }
            }
        }
        check()
        if (!doctype || !rootSeen || !headSeen || !bodySeen || stack.isNotEmpty()) invalidResponse()
    }
}
