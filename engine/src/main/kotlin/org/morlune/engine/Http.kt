// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class HttpResult(val status: Int, val body: String)

fun interface HttpFetcher {
    fun get(url: String, headers: Map<String, String>): HttpResult
}

/** Plain HttpURLConnection fetcher: works on JVM and Android, no extra dependency. */
class UrlConnectionFetcher(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val timeoutMs: Int = 10_000,
    private val maxBytes: Int = 2_000_000,
) : HttpFetcher {

    override fun get(url: String, headers: Map<String, String>): HttpResult {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) { hop ->
            require(current.protocol.equals("https", ignoreCase = true)) { "HTTPS is required for weather requests" }
            val conn = current.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept-Encoding", "gzip")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val status = conn.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    if (hop == MAX_REDIRECTS) throw java.io.IOException("too many HTTP redirects")
                    val location = conn.getHeaderField("Location")
                        ?: throw java.io.IOException("redirect without Location")
                    val next = URL(current, location)
                    if (!next.protocol.equals("https", ignoreCase = true))
                        throw java.io.IOException("redirect to non-HTTPS URL blocked")
                    current = next
                } else {
                    val stream = if (status >= 400) conn.errorStream else conn.inputStream
                    val body = stream?.use { raw ->
                        val decoded = if ("gzip".equals(conn.contentEncoding, ignoreCase = true))
                            GZIPInputStream(raw) else raw
                        decoded.use { readLimited(it, maxBytes) }
                    } ?: ""
                    return HttpResult(status, body)
                }
            } finally {
                conn.disconnect()
            }
        }
        throw java.io.IOException("too many HTTP redirects")
    }

    private fun readLimited(input: InputStream, limit: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw java.io.IOException("response larger than $limit bytes")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        const val DEFAULT_USER_AGENT = "Morlune/0.1 (open-source Android widget; +https://github.com/deedend/morlune)"
    }
}
