package org.mcrf.client

/** Linear bounded lexical preflight before Android's recursive, lenient JSONTokener.
 * Not a complete JSON grammar validator. Reject lenient comment/quote/literal
 * forms so they cannot make this depth scanner disagree with the parser. */
internal object OpenFluxJsonNesting {
    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    fun bounded(text: String): Boolean {
        val stack = CharArray(16); var depth = 0; var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                ' ', '\t', '\r', '\n', ':', ',' -> i++
                '{', '[' -> { if (depth == stack.size) return false; stack[depth++] = c; i++ }
                '}', ']' -> { if (depth == 0 || stack[--depth] != if (c == '}') '{' else '[') return false; i++ }
                '"' -> {
                    i++; var ended = false
                    while (i < text.length) {
                        val ch = text[i++]
                        if (ch == '"') { ended = true; break }
                        if (ch.code < 0x20) return false
                        if (ch == '\\') {
                            if (i == text.length) return false
                            when (text[i++]) {
                                '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                                'u' -> {
                                    if (i + 4 > text.length) return false
                                    repeat(4) { if (text[i++] !in "0123456789abcdefABCDEF") return false }
                                }
                                else -> return false
                            }
                        }
                    }
                    if (!ended) return false
                }
                '-', in '0'..'9' -> {
                    val start = i++
                    while (i < text.length && text[i] in "0123456789+-.eE") i++
                    if (!number.matches(text.substring(start, i))) return false
                    if (i < text.length && text[i] !in " \t\r\n,]}") return false
                }
                't', 'f', 'n' -> {
                    val literal = when (c) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                    if (!text.startsWith(literal, i)) return false
                    i += literal.length
                    if (i < text.length && text[i] !in " \t\r\n,]}") return false
                }
                else -> return false
            }
        }
        return depth == 0
    }
}
