package com.everettpenne.thumper.data.feed

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/** OPML: the list of subscriptions every feed reader and podcast app can import and export. */
object Opml {
    data class Entry(val title: String, val feedUrl: String, val siteUrl: String? = null)

    /** Every feed in an OPML document (nested categories flattened), in order, without repeats. */
    fun parse(input: InputStream): List<Entry> {
        val p = XmlPullParserFactory.newInstance().newPullParser(); p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); p.setInput(input, null)
        val out = LinkedHashMap<String, Entry>(); var sawOpml = false
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                if (p.name.equals("opml", true)) sawOpml = true
                if (p.name.equals("outline", true)) {
                    val xml = attr(p, "xmlUrl")
                    if (!xml.isNullOrBlank()) out.putIfAbsent(xml.trim(), Entry(attr(p, "title")?.ifBlank { null } ?: attr(p, "text") ?: xml, xml.trim(), attr(p, "htmlUrl")))
                }
            }
            e = p.next()
        }
        if (!sawOpml) throw IllegalArgumentException("That isn't an OPML file.")
        return out.values.toList()
    }

    private fun attr(p: XmlPullParser, name: String): String? { for (i in 0 until p.attributeCount) if (p.getAttributeName(i).equals(name, true)) return p.getAttributeValue(i); return null }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun write(entries: List<Entry>, title: String = "Thumper subscriptions"): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n  <head><title>${esc(title)}</title></head>\n  <body>\n")
        for (e in entries) append("    <outline type=\"rss\" text=\"${esc(e.title)}\" title=\"${esc(e.title)}\" xmlUrl=\"${esc(e.feedUrl)}\"${e.siteUrl?.let { " htmlUrl=\"${esc(it)}\"" } ?: ""}/>\n")
        append("  </body>\n</opml>\n")
    }
}
