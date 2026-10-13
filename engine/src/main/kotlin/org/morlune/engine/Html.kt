// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

/** Thin abstraction over an HTML DOM so the engine core has no hard dependency on a parser. */
interface HtmlNode {
    fun select(css: String): List<HtmlNode>
    fun text(): String
    fun attr(name: String): String
}

fun interface HtmlParser {
    fun parse(html: String): HtmlNode
}

/** Jsoup-backed implementation (Jsoup runs fine on Android). */
class JsoupHtmlParser : HtmlParser {
    override fun parse(html: String): HtmlNode = Node(org.jsoup.Jsoup.parse(html))

    private class Node(private val el: org.jsoup.nodes.Element) : HtmlNode {
        override fun select(css: String): List<HtmlNode> = try {
            el.select(css).map { Node(it) }
        } catch (e: org.jsoup.select.Selector.SelectorParseException) {
            throw ProviderException("bad CSS selector '$css': ${e.message}", e)
        }
        override fun text(): String = el.text().trim()
        override fun attr(name: String): String = el.attr(name).trim()
    }
}
