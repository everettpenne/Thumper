package com.everettpenne.thumper.util

/**
 * A small, forgiving reader for the HTML that feeds carry (show notes, article bodies): it does not build a DOM, it walks the tags once and
 * produces blocks (paragraphs, headings, list items, quotes, code, images) made of styled runs, which is all the reader screens draw. Unknown tags
 * are ignored, unclosed ones don't matter, and scripts, styles and iframes are dropped with their contents.
 */
object Html {
    data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val href: String? = null)

    sealed interface Block {
        data class Paragraph(val runs: List<Run>) : Block
        data class Heading(val level: Int, val runs: List<Run>) : Block
        data class ListItem(val marker: String, val runs: List<Run>) : Block
        data class Quote(val runs: List<Run>) : Block
        data class Code(val text: String) : Block
        data class Image(val url: String, val alt: String) : Block
        data object Rule : Block
    }

    private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "copy" to "©", "reg" to "®", "trade" to "™", "bull" to "•", "middot" to "·", "euro" to "€", "pound" to "£")

    fun decode(text: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(text) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() } ?: m.value
            else -> ENTITIES[e] ?: m.value
        }
    }

    /** The text of some HTML with the markup gone and the whitespace tidied: for previews, and for places that can't draw styled text. */
    fun plain(html: String): String = blocks(html).joinToString("\n") { b ->
        when (b) {
            is Block.Paragraph -> b.runs.joinToString("") { it.text }; is Block.Heading -> b.runs.joinToString("") { it.text }
            is Block.ListItem -> b.marker + " " + b.runs.joinToString("") { it.text }; is Block.Quote -> b.runs.joinToString("") { it.text }
            is Block.Code -> b.text; is Block.Image -> ""; Block.Rule -> ""
        }
    }.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    private val TAG = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>""")
    private val DROPPED = setOf("script", "style", "iframe", "svg", "noscript", "head", "template")
    private val BLOCK_TAGS = setOf("p", "div", "section", "article", "header", "footer", "figure", "figcaption", "table", "tr", "ul", "ol", "dl", "dt", "dd", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "img")

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""", RegexOption.IGNORE_CASE).find(attrs)?.let { m -> decode(m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: "") }

    /** [base] is the page the HTML came from: relative links and pictures (common in feeds) are resolved against it. */
    fun blocks(html: String, base: String? = null): List<Block> {
        val baseUri = base?.let { runCatching { java.net.URI(it) }.getOrNull() }
        fun resolve(u: String): String = if (baseUri == null || u.contains("://") || u.startsWith("mailto:") || u.startsWith("tel:")) u
            else runCatching { baseUri.resolve(u.replace(" ", "%20")).toString() }.getOrDefault(u)
        val out = ArrayList<Block>(); val runs = ArrayList<Run>()
        var bold = 0; var italic = 0; var code = 0; var href: String? = null; var quote = 0; var pre = 0; var heading = 0; var dropping: String? = null
        val lists = ArrayList<IntArray>(); var pendingMarker: String? = null; val preText = StringBuilder()

        fun flush() {
            if (pre > 0) return
            // collapse whitespace across runs, trim the edges of the block
            val cleaned = ArrayList<Run>()
            for (r in runs) { val t = r.text.replace(Regex("\\s+"), " "); if (t.isNotEmpty()) cleaned += r.copy(text = t) }
            runs.clear()
            if (cleaned.isEmpty()) { pendingMarker = pendingMarker; return }
            cleaned[0] = cleaned[0].copy(text = cleaned[0].text.trimStart()); cleaned[cleaned.lastIndex] = cleaned.last().copy(text = cleaned.last().text.trimEnd())
            cleaned.removeAll { it.text.isEmpty() }
            if (cleaned.isEmpty()) return
            out += when {
                heading > 0 -> Block.Heading(heading, cleaned)
                pendingMarker != null -> Block.ListItem(pendingMarker!!, cleaned).also { pendingMarker = null }
                quote > 0 -> Block.Quote(cleaned)
                else -> Block.Paragraph(cleaned)
            }
        }
        fun text(raw: String) {
            if (dropping != null || raw.isEmpty()) return
            val t = decode(raw)
            if (pre > 0) preText.append(t) else runs += Run(t, bold > 0, italic > 0, code > 0, href)
        }

        var pos = 0
        for (m in TAG.findAll(html)) {
            text(html.substring(pos, m.range.first)); pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"; val tag = m.groupValues[2].lowercase(); val attrs = m.groupValues[3]
            if (dropping != null) { if (closing && tag == dropping) dropping = null; continue }
            if (!closing && tag in DROPPED) { if (!attrs.trimEnd().endsWith("/")) dropping = tag; continue }
            if (tag in BLOCK_TAGS && tag != "img") flush()
            when (tag) {
                "b", "strong" -> bold = (bold + if (closing) -1 else 1).coerceAtLeast(0)
                "i", "em", "cite" -> italic = (italic + if (closing) -1 else 1).coerceAtLeast(0)
                "code", "kbd", "tt" -> code = (code + if (closing) -1 else 1).coerceAtLeast(0)
                "a" -> href = if (closing) null else attr(attrs, "href")?.let(::resolve)
                "h1", "h2", "h3", "h4", "h5", "h6" -> heading = if (closing) 0 else tag[1] - '0'
                "blockquote" -> quote = (quote + if (closing) -1 else 1).coerceAtLeast(0)
                "ul", "ol" -> if (closing) { if (lists.isNotEmpty()) lists.removeAt(lists.lastIndex) } else lists += intArrayOf(if (tag == "ol") 1 else 0)
                "li" -> if (!closing) { val l = lists.lastOrNull(); pendingMarker = if (l == null || l[0] == 0) "•" else "${l[0]++}." }
                "pre" -> if (closing) { pre = 0; val t = preText.toString().trim('\n'); preText.clear(); if (t.isNotBlank()) out += Block.Code(t) } else { pre = 1; preText.clear() }
                "hr" -> out += Block.Rule
                "br" -> if (pre > 0) preText.append('\n') else flush()
                "img" -> if (!closing) attr(attrs, "src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { flush(); out += Block.Image(resolve(it), attr(attrs, "alt").orEmpty()) }
            }
        }
        text(html.substring(pos)); flush()
        return out
    }
}
