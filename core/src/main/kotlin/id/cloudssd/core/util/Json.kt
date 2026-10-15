package id.cloudssd.core.util

/**
 * Parser/serializer JSON kecil tanpa dependensi.
 * Angka diparse menjadi Long bila bulat, selain itu Double.
 */
object Json {
    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v.isFinite()) sb.append(v) else sb.append("null")
            is Float -> if (v.isFinite()) sb.append(v) else sb.append("null")
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.asList())
            is JsonValue -> write(sb, v.toJson())
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c") // aman disisipkan ke HTML
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("Karakter sisa pada posisi ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(depth: Int): Any? {
            if (depth > 64) throw IllegalArgumentException("JSON terlalu dalam")
            if (i >= s.length) throw IllegalArgumentException("JSON terpotong")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Karakter tak terduga '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("Literal tidak valid")
            i += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (peek() == '}') { i++; return m }
            while (true) {
                ws()
                if (peek() != '"') throw IllegalArgumentException("Kunci harus string")
                val k = str()
                ws()
                expect(':')
                ws()
                m[k] = value(depth + 1)
                ws()
                when (next()) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw IllegalArgumentException("Diharapkan , atau }")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (peek() == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value(depth + 1))
                ws()
                when (next()) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw IllegalArgumentException("Diharapkan , atau ]")
                }
            }
        }

        fun str(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("String tidak ditutup")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("Escape terpotong")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("Escape unicode terpotong")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Escape tidak valid \\$e")
                        }
                    }
                    c < ' ' -> throw IllegalArgumentException("Karakter kontrol dalam string")
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Number {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw IllegalArgumentException("Angka tidak valid")
        }

        fun peek(): Char? = if (i < s.length) s[i] else null
        fun next(): Char? = if (i < s.length) s[i++] else null
        fun expect(c: Char) {
            if (next() != c) throw IllegalArgumentException("Diharapkan '$c'")
        }
    }
}

interface JsonValue {
    fun toJson(): Map<String, Any?>
}

/** Helper akses aman untuk Map hasil parse. */
fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> if (v % 1.0 == 0.0) v.toLong() else null
    else -> null
}
fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<Any?>
