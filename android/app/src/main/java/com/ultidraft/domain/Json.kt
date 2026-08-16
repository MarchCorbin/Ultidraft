package com.ultidraft.domain

/**
 * A small JSON reader/writer.
 *
 * The sidecar schema is fixed and tiny, and the domain layer stays dependency-free so
 * it runs unchanged in plain JVM unit tests (`org.json` is a stub there, and pulling in
 * a serialization library would put a codegen plugin in the way of the parser tests).
 */
sealed class JsonValue {
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    /** Field lookup that never throws; missing or wrong-typed fields read as empty. */
    operator fun get(key: String): JsonValue? = (this as? Obj)?.fields?.get(key)

    fun string(key: String, fallback: String = ""): String =
        when (val field = get(key)) {
            is Str -> field.value
            is Num -> field.value.asWholeOrDecimalString()
            is Bool -> field.value.toString()
            else -> fallback
        }

    fun int(key: String, fallback: Int = 0): Int =
        when (val field = get(key)) {
            is Num -> field.value.toInt()
            is Str -> field.value.trim().toDoubleOrNull()?.toInt() ?: fallback
            else -> fallback
        }

    fun array(key: String): List<JsonValue> = (get(key) as? Arr)?.items ?: emptyList()

    fun objectAt(key: String): JsonValue = (get(key) as? Obj) ?: Obj(emptyMap())

    fun toJsonString(indent: Int = 2): String {
        val out = StringBuilder()
        write(out, this, indent, 0)
        return out.toString()
    }

    companion object {
        fun str(value: String): JsonValue = Str(value)
        fun num(value: Int): JsonValue = Num(value.toDouble())
        fun arr(items: List<JsonValue>): JsonValue = Arr(items)
        fun obj(vararg fields: Pair<String, JsonValue>): JsonValue = Obj(linkedMapOf(*fields))

        fun parse(text: String): JsonValue? = try {
            val reader = JsonReader(text)
            val value = reader.readValue()
            reader.skipWhitespace()
            if (reader.done()) value else null
        } catch (_: Exception) {
            null
        }

        private fun write(out: StringBuilder, value: JsonValue, indent: Int, depth: Int) {
            val pad = " ".repeat(indent * depth)
            val childPad = " ".repeat(indent * (depth + 1))
            when (value) {
                is Str -> out.append(quote(value.value))
                is Num -> out.append(value.value.asWholeOrDecimalString())
                is Bool -> out.append(if (value.value) "true" else "false")
                is Null -> out.append("null")
                is Arr -> {
                    if (value.items.isEmpty()) {
                        out.append("[]")
                        return
                    }
                    out.append("[\n")
                    value.items.forEachIndexed { i, item ->
                        out.append(childPad)
                        write(out, item, indent, depth + 1)
                        if (i < value.items.size - 1) out.append(',')
                        out.append('\n')
                    }
                    out.append(pad).append(']')
                }

                is Obj -> {
                    if (value.fields.isEmpty()) {
                        out.append("{}")
                        return
                    }
                    out.append("{\n")
                    val entries = value.fields.entries.toList()
                    entries.forEachIndexed { i, entry ->
                        out.append(childPad).append(quote(entry.key)).append(": ")
                        write(out, entry.value, indent, depth + 1)
                        if (i < entries.size - 1) out.append(',')
                        out.append('\n')
                    }
                    out.append(pad).append('}')
                }
            }
        }

        private fun quote(text: String): String {
            val out = StringBuilder(text.length + 2)
            out.append('"')
            for (char in text) {
                when (char) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    else ->
                        // Keep real UTF-8 through, like the desktop's ensure_ascii=False.
                        if (char < ' ') out.append("\\u%04x".format(char.code)) else out.append(char)
                }
            }
            out.append('"')
            return out.toString()
        }
    }
}

private fun Double.asWholeOrDecimalString(): String =
    if (this == Math.floor(this) && !this.isInfinite() && Math.abs(this) < 1e15) {
        this.toLong().toString()
    } else {
        this.toString()
    }

private class JsonReader(private val text: String) {
    private var i = 0

    fun done(): Boolean = i >= text.length

    fun skipWhitespace() {
        while (i < text.length && text[i].isWhitespace()) i += 1
    }

    fun readValue(): JsonValue {
        skipWhitespace()
        if (done()) error("unexpected end of json")
        return when (val char = text[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't' -> readLiteral("true", JsonValue.Bool(true))
            'f' -> readLiteral("false", JsonValue.Bool(false))
            'n' -> readLiteral("null", JsonValue.Null)
            else ->
                if (char == '-' || char.isDigit()) readNumber()
                else error("unexpected character '$char' at $i")
        }
    }

    private fun readLiteral(word: String, value: JsonValue): JsonValue {
        require(text.startsWith(word, i)) { "bad literal at $i" }
        i += word.length
        return value
    }

    private fun readNumber(): JsonValue {
        val start = i
        if (i < text.length && text[i] == '-') i += 1
        while (i < text.length && (text[i].isDigit() || text[i] in ".eE+-")) i += 1
        val slice = text.substring(start, i)
        return JsonValue.Num(slice.toDoubleOrNull() ?: error("bad number '$slice'"))
    }

    private fun readString(): String {
        require(text[i] == '"') { "expected string at $i" }
        i += 1
        val out = StringBuilder()
        while (i < text.length) {
            when (val char = text[i]) {
                '"' -> {
                    i += 1
                    return out.toString()
                }

                '\\' -> {
                    i += 1
                    require(i < text.length) { "dangling escape" }
                    when (val escape = text[i]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(i + 4 < text.length) { "short unicode escape" }
                            val code = text.substring(i + 1, i + 5).toInt(16)
                            out.append(code.toChar())
                            i += 4
                        }

                        else -> error("bad escape '\\$escape'")
                    }
                    i += 1
                }

                else -> {
                    out.append(char)
                    i += 1
                }
            }
        }
        error("unterminated string")
    }

    private fun readArray(): JsonValue {
        i += 1
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (i < text.length && text[i] == ']') {
            i += 1
            return JsonValue.Arr(items)
        }
        while (true) {
            items.add(readValue())
            skipWhitespace()
            require(i < text.length) { "unterminated array" }
            when (text[i]) {
                ',' -> i += 1
                ']' -> {
                    i += 1
                    return JsonValue.Arr(items)
                }

                else -> error("expected , or ] at $i")
            }
        }
    }

    private fun readObject(): JsonValue {
        i += 1
        val fields = linkedMapOf<String, JsonValue>()
        skipWhitespace()
        if (i < text.length && text[i] == '}') {
            i += 1
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            require(i < text.length && text[i] == ':') { "expected : at $i" }
            i += 1
            fields[key] = readValue()
            skipWhitespace()
            require(i < text.length) { "unterminated object" }
            when (text[i]) {
                ',' -> i += 1
                '}' -> {
                    i += 1
                    return JsonValue.Obj(fields)
                }

                else -> error("expected , or } at $i")
            }
        }
    }
}
