package com.awacker.billsnotifier.domain.sync

import java.math.BigDecimal

/**
 * A minimal JSON writer.
 *
 * The sync payload is a fixed, flat shape that this module already produces as
 * [SheetValue]s, so a serialization library would add a compiler plugin and a dependency to
 * earn very little. Writing it here instead keeps the domain module dependency-free and —
 * more usefully — puts the exact bytes that go over the wire under unit test.
 *
 * Reading JSON is a different matter; responses are parsed on the Android side, where a
 * real parser is available.
 */
sealed interface Json {

    data class Str(val value: String) : Json
    data class Num(val value: BigDecimal) : Json
    data class Bool(val value: Boolean) : Json
    data class Arr(val items: List<Json>) : Json
    data class Obj(val entries: List<Pair<String, Json>>) : Json

    fun encode(): String = when (this) {
        is Str -> encodeString(value)
        is Num -> value.toPlainString()
        is Bool -> if (value) "true" else "false"
        is Arr -> items.joinToString(",", "[", "]") { it.encode() }
        is Obj -> entries.joinToString(",", "{", "}") { (key, value) ->
            encodeString(key) + ":" + value.encode()
        }
    }

    companion object {

        fun obj(vararg entries: Pair<String, Json>): Obj = Obj(entries.toList())

        /** Preserves the given column order, which is what the sheet's header row expects. */
        fun row(columns: List<String>, values: Map<String, SheetValue>): Obj =
            Obj(columns.map { column -> column to (values[column]?.toJson() ?: Str("")) })

        private fun SheetValue.toJson(): Json = when (this) {
            is SheetValue.Text -> Str(value)
            is SheetValue.Number -> Num(value)
            is SheetValue.Flag -> Bool(value)
        }

        private fun encodeString(value: String): String {
            val out = StringBuilder(value.length + 2)
            out.append('"')
            for (character in value) {
                when (character) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    else ->
                        // Control characters are illegal raw in JSON strings. Everything else,
                        // including non-ASCII, goes through as UTF-8.
                        if (character < ' ') {
                            out.append("\\u").append("%04x".format(character.code))
                        } else {
                            out.append(character)
                        }
                }
            }
            out.append('"')
            return out.toString()
        }
    }
}
