// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

class JsonParseException(message: String) : Exception(message)

/**
 * Small strict JSON parser (no dependencies, same behaviour on JVM and Android).
 * Objects become LinkedHashMap (order kept), arrays List, numbers Double, plus String/Boolean/null.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue(0)
        p.skipWs()
        if (!p.atEnd()) p.fail("unexpected trailing content")
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun fail(msg: String): Nothing = throw JsonParseException("JSON error at offset $i: $msg")

        fun readValue(depth: Int): Any? {
            if (depth > 200) fail("nesting too deep")
            if (atEnd()) fail("unexpected end of input")
            val c = s[i]
            return when {
                c == '{' -> readObject(depth)
                c == '[' -> readArray(depth)
                c == '"' -> readString()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c.isDigit() -> readNumber()
                else -> fail("unexpected character '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("invalid literal")
            i += word.length
            return value
        }

        private fun readNumber(): Double {
            val start = i
            while (i < s.length && s[i] in "+-0123456789.eE") i++
            return s.substring(start, i).toDoubleOrNull() ?: run { i = start; fail("invalid number") }
        }

        private fun readString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad \\u escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad \\u escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipWs()
            if (!atEnd() && s[i] == ']') { i++; return out }
            while (true) {
                skipWs()
                out.add(readValue(depth + 1))
                skipWs()
                if (atEnd()) fail("unterminated array")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> { i--; fail("expected ',' or ']'") }
                }
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') fail("expected string key")
                val key = readString()
                skipWs()
                if (atEnd() || s[i] != ':') fail("expected ':'")
                i++
                skipWs()
                out[key] = readValue(depth + 1)
                skipWs()
                if (atEnd()) fail("unterminated object")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> { i--; fail("expected ',' or '}'") }
                }
            }
        }
    }
}
