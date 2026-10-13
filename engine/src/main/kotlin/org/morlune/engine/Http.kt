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
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { s ->
                val decoded = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) GZIPInputStream(s) else s
                readLimited(decoded, maxBytes)
            } ?: ""
            return HttpResult(status, body)
        } finally {
            conn.disconnect()
        }
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
        const val DEFAULT_USER_AGENT = "Morlune/0.1 (open-source Android widget; +https://github.com/deedend/morlune)"
    }
}
