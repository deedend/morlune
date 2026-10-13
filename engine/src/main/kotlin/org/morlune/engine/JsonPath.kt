// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

/**
 * Minimal JSONPath: `$`, `.key`, `['key']`, `[0]`, `[-1]`, `[*]`, `.*`, `..key`.
 * No filters or slices on purpose: keeps provider files easy to read and the engine easy to audit.
 */
class JsonPath private constructor(val expression: String, private val steps: List<Step>) {

    private sealed class Step {
        data class Key(val name: String) : Step()
        data class Index(val index: Int) : Step()
        object Wildcard : Step()
        data class Descend(val name: String) : Step()
    }

    /** All matching nodes (a matched JSON null is included as null). */
    fun select(root: Any?): List<Any?> {
        var nodes: List<Any?> = listOf(root)
        for (step in steps) {
            val next = ArrayList<Any?>()
            for (n in nodes) {
                when (step) {
                    is Step.Key -> if (n is Map<*, *> && n.containsKey(step.name)) next.add(n[step.name])
                    is Step.Index -> if (n is List<*>) {
                        val idx = if (step.index < 0) n.size + step.index else step.index
                        if (idx in n.indices) next.add(n[idx])
                    }
                    Step.Wildcard -> when (n) {
                        is Map<*, *> -> next.addAll(n.values)
                        is List<*> -> next.addAll(n)
                        else -> {}
                    }
                    is Step.Descend -> descend(n, step.name, next)
                }
            }
            nodes = next
        }
        return nodes
    }

    fun first(root: Any?): Any? = select(root).firstOrNull()

    private fun descend(n: Any?, name: String, out: MutableList<Any?>) {
        when (n) {
            is Map<*, *> -> {
                if (n.containsKey(name)) out.add(n[name])
                n.values.forEach { descend(it, name, out) }
            }
            is List<*> -> n.forEach { descend(it, name, out) }
            else -> {}
        }
    }

    companion object {
        fun compile(expr: String): JsonPath {
            val e = expr.trim()
            require(e.startsWith("$")) { "JSON path must start with '\$': $expr" }
            val steps = ArrayList<Step>()
            var i = 1
            while (i < e.length) {
                when (e[i]) {
                    '.' -> {
                        val descend = i + 1 < e.length && e[i + 1] == '.'
                        i += if (descend) 2 else 1
                        val start = i
                        while (i < e.length && e[i] != '.' && e[i] != '[') i++
                        val name = e.substring(start, i)
                        require(name.isNotEmpty()) { "empty name in JSON path: $expr" }
                        steps += when {
                            descend -> Step.Descend(name)
                            name == "*" -> Step.Wildcard
                            else -> Step.Key(name)
                        }
                    }
                    '[' -> {
                        val end = e.indexOf(']', i)
                        require(end > 0) { "missing ']' in JSON path: $expr" }
                        val inner = e.substring(i + 1, end).trim()
                        steps += when {
                            inner == "*" -> Step.Wildcard
                            inner.length >= 2 && (inner[0] == '\'' || inner[0] == '"') && inner.last() == inner[0] ->
                                Step.Key(inner.substring(1, inner.length - 1))
                            else -> Step.Index(
                                inner.toIntOrNull() ?: throw IllegalArgumentException("bad index '[$inner]' in JSON path: $expr")
                            )
                        }
                        i = end + 1
                    }
                    else -> throw IllegalArgumentException("unexpected '${e[i]}' at position $i in JSON path: $expr")
                }
            }
            return JsonPath(e, steps)
        }
    }
}
