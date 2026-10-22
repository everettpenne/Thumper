package com.everettpenne.thumper.data.feed

import com.everettpenne.thumper.util.Html
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

/**
 * Finds the article in a web page, for feeds that send only a teaser: the page is fetched (from the site the feed points at, which the person
 * chose by subscribing) and its main text is lifted out of the menus, sidebars, share buttons and comments around it, the way a browser's reader
 * view does. Pure (jsoup, no Android), so it is unit-tested on real-shaped pages.
 */
object ArticleText {
    /** Where sites put the article itself, most specific first. */
    private val CONTAINERS = listOf("[itemprop=articleBody]", "article .entry-content", "article .post-content", ".entry-content", ".post-content", ".article-body",
        ".article-content", ".post-body", ".story-body", "article", "main", "[role=main]", "#content")
    /** What is never part of an article. */
    private const val CLUTTER = "script, style, noscript, iframe, svg, form, button, input, select, nav, header, footer, aside, [role=navigation], [role=banner], " +
        "[role=contentinfo], [aria-hidden=true], .share, .sharing, .social, .related, .comments, #comments, .newsletter, .subscribe, .advert, .ad, .ads, " +
        ".sidebar, .breadcrumbs, .author-bio, .tags, .post-meta, .skip-link, .screen-reader-text, .wp-block-buttons, figure figcaption:empty"

    /** Teaser text: little of it, or cut off ("…", "Read more"), so the full article is worth fetching. */
    fun isTeaser(contentHtml: String): Boolean {
        val plain = Html.plain(contentHtml).trim()
        if (plain.length < 600) return true
        val tail = plain.takeLast(80).lowercase()
        return plain.endsWith("…") || plain.endsWith("...") || listOf("read more", "continue reading", "read the full", "read full", "full article", "full story", "keep reading", "read the rest", "the post ", "appeared first on").any { it in tail }
    }

    /** The article's HTML (paragraphs, headings, lists, quotes, pictures and links, with absolute addresses), or null when no article was found. */
    fun extract(pageHtml: String, pageUrl: String): String? {
        val doc = Jsoup.parse(pageHtml, pageUrl)
        doc.select(CLUTTER).remove()
        lazyImages(doc)
        val body = CONTAINERS.firstNotNullOfOrNull { sel -> doc.select(sel).maxByOrNull { textLength(it) }?.takeIf { textLength(it) >= 400 } }
            ?: densest(doc) ?: return null
        dropRepeats(body)
        val clean = Jsoup.clean(body.html(), pageUrl, Safelist.relaxed().removeTags("table", "thead", "tbody", "tfoot", "tr", "td", "th", "col", "colgroup", "caption")
            .addAttributes("img", "srcset"), Document.OutputSettings().prettyPrint(false))
        return clean.takeIf { Html.plain(it).length >= 300 }
    }

    /**
     * Sites often print a picture's caption twice (once beside it, once for small screens, with the credit run on): a paragraph or caption that
     * begins with the whole of one already seen is dropped.
     */
    private fun dropRepeats(body: Element) {
        val seen = ArrayList<String>()
        for (e in body.select("p, figcaption")) {
            val t = e.text().trim()
            if (t.length < 30) continue
            // Only a longer copy (the caption with its credit run on) or a second identical caption goes; repeated paragraphs of text stay.
            if (seen.any { (t.length > it.length && t.startsWith(it)) || (e.tagName() == "figcaption" && t == it) }) e.remove() else seen += t
        }
    }

    /** Pictures that load as they scroll keep their real address in data-src; it becomes the src. */
    private fun lazyImages(doc: Document) {
        for (img in doc.select("img")) {
            val real = listOf("data-src", "data-lazy-src", "data-original").firstNotNullOfOrNull { img.attr(it).takeIf { v -> v.isNotBlank() } }
            if (real != null && (img.attr("src").isBlank() || img.attr("src").startsWith("data:"))) img.attr("src", real)
        }
    }

    /** The text of an element's paragraphs, not counting link text (a list of links is not an article). */
    private fun textLength(e: Element): Int {
        val paragraphs = e.select("p, li, blockquote, h2, h3, pre")
        val text = paragraphs.sumOf { it.ownText().length + it.select("> b, > strong, > i, > em, > span, > code").sumOf { s -> s.text().length } }
        val links = e.select("a").sumOf { it.text().length }
        val total = e.text().length.coerceAtLeast(1)
        return if (links.toFloat() / total > .5f) 0 else text
    }

    /** No container found: the element whose own paragraphs hold the most text wins. */
    private fun densest(doc: Document): Element? {
        val scores = HashMap<Element, Int>()
        for (p in doc.select("p")) {
            // Only the paragraph's own words count: a paragraph of links is a list of posts, not writing.
            val len = p.text().length - p.select("a").sumOf { it.text().length }
            if (len < 40) continue
            p.parent()?.let { scores.merge(it, len, Int::plus) }
            p.parent()?.parent()?.let { scores.merge(it, len / 2, Int::plus) }
        }
        return scores.maxByOrNull { it.value }?.takeIf { it.value >= 400 }?.key
    }

    /** Words to minutes at an easy reading pace; at least a minute. */
    fun readingMinutes(html: String): Int = (Html.plain(html).split(Regex("\\s+")).count { it.isNotBlank() } / 230.0).let { kotlin.math.ceil(it).toInt().coerceAtLeast(1) }
}
