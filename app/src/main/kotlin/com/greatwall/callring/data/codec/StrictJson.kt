package com.greatwall.callring.data.codec

/**
 * Android's org.json accepts comments, unquoted keys and duplicate keys. Import files must be
 * strict JSON.
 */
internal object StrictJson {
    fun validate(text: String) {
        Parser(text).parse()
    }

    private class Parser(val text: String) {
        var index = 0

        fun parse() {
            value(0)
            whitespace()
            require(index == text.length)
        }

        fun whitespace() {
            while (index < text.length && text[index] in " \t\r\n") index++
        }

        fun consume(char: Char): Boolean {
            whitespace()
            return if (index < text.length && text[index] == char) {
                index++
                true
            } else false
        }

        fun expect(char: Char) {
            require(consume(char))
        }

        fun value(depth: Int) {
            require(depth < 64)
            whitespace()
            require(index < text.length)
            when (text[index]) {
                '{' -> {
                    index++
                    val keys = hashSetOf<String>()
                    if (consume('}')) return
                    do {
                        whitespace()
                        require(keys.add(string()))
                        expect(':')
                        value(depth + 1)
                    } while (consume(','))
                    expect('}')
                }
                '[' -> {
                    index++
                    if (consume(']')) return
                    do {
                        value(depth + 1)
                    } while (consume(','))
                    expect(']')
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }

        fun literal(value: String) {
            require(text.startsWith(value, index))
            index += value.length
        }

        fun string(): String {
            require(index < text.length && text[index++] == '"')
            val result = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                if (char == '"') return result.toString()
                require(char.code >= 32)
                if (char != '\\') result.append(char)
                else {
                    require(index < text.length)
                    when (val escaped = text[index++]) {
                        '"',
                        '\\',
                        '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(index + 4 <= text.length)
                            val digits = text.substring(index, index + 4)
                            require(digits.all { it in "0123456789abcdefABCDEF" })
                            result.append(digits.toInt(16).toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException()
                    }
                }
            }
            throw IllegalArgumentException()
        }

        fun number() {
            if (index < text.length && text[index] == '-') index++
            require(index < text.length)
            if (text[index] == '0') index++
            else {
                require(text[index] in '1'..'9')
                digits()
            }
            if (index < text.length && text[index] == '.') {
                index++
                digits()
            }
            if (index < text.length && text[index] in "eE") {
                index++
                if (index < text.length && text[index] in "+-") index++
                digits()
            }
        }

        fun digits() {
            val start = index
            while (index < text.length && text[index] in '0'..'9') index++
            require(index > start)
        }
    }
}
