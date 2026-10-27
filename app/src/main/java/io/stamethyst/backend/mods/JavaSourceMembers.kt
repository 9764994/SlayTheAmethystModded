package io.stamethyst.backend.mods

/**
 * Splits CFR's Java output into the members of its outermost type so a huge decompiled class can be
 * returned as a signature skeleton plus one requested member instead of a truncated prefix.
 *
 * This is a scanner, not a Java parser. It tracks string/char literals and comments so braces inside
 * them never unbalance a range, and it relies only on the regular member layout CFR emits. Anything
 * it cannot recognise is left untouched, and callers fall back to the raw source.
 */
internal object JavaSourceMembers {
    data class Member(
        val name: String,
        val kind: String,
        val header: String,
        val start: Int,
        val bodyStart: Int,
        val bodyEnd: Int,
        val end: Int,
    )

    /** Members of the outermost type, in source order. Empty when the file has no type body. */
    fun members(source: String): List<Member> {
        val outer = outerBody(source) ?: return emptyList()
        val typeName = outerTypeName(source, outer.first)
        return parseBody(source, outer.first + 1, outer.second, typeName)
    }

    /**
     * Renders the file with every method/constructor/initializer body collapsed to `{ … }` and
     * nested types kept as skeletons. Returns null when the type body cannot be located.
     */
    fun skeleton(source: String): String? {
        val outer = outerBody(source) ?: return null
        val typeName = outerTypeName(source, outer.first)
        val members = parseBody(source, outer.first + 1, outer.second, typeName)
        if (members.isEmpty()) return null
        return buildString {
            append(source, 0, outer.first + 1)
            members.forEach { member ->
                append('\n').append(render(source, member, 1, typeName))
            }
            append('\n').append(source, outer.second, source.length)
        }
    }

    /** Members whose name matches [name]; exact matches win over case-insensitive ones. */
    fun select(members: List<Member>, name: String, limit: Int): List<Member> {
        val needle = name.trim().substringAfterLast('.').substringAfterLast('#')
        if (needle.isEmpty()) return emptyList()
        val exact = members.filter { it.name == needle }
        val chosen = exact.ifEmpty { members.filter { it.name.equals(needle, ignoreCase = true) } }
        return chosen.take(limit.coerceAtLeast(1))
    }

    /** Source text of one member, exactly as CFR emitted it. */
    fun text(source: String, member: Member): String =
        source.substring(member.start, member.end).trim()

    /** Declaration line for the member index; long parameter lists stay on one line. */
    fun declaration(member: Member): String = member.header.trim().replace(WHITESPACE, " ")

    private fun render(source: String, member: Member, depth: Int, typeName: String): String {
        val indent = "    ".repeat(depth)
        return when {
            member.kind == "type" -> {
                val children = parseBody(source, member.bodyStart + 1, member.bodyEnd, member.name)
                buildString {
                    append(indent).append(declaration(member)).append(" {\n")
                    children.forEach { child ->
                        append(render(source, child, depth + 1, member.name)).append('\n')
                    }
                    append(indent).append('}')
                }
            }
            member.bodyStart >= 0 -> {
                val terminator = if (member.kind == "field") ";" else ""
                "$indent${declaration(member)} { /* … */ }$terminator"
            }
            else -> indent + declaration(member)
        }
    }

    private fun parseBody(source: String, from: Int, to: Int, typeName: String): List<Member> {
        val members = ArrayList<Member>()
        var index = from
        while (index < to) {
            // A leading comma appears between enum constants whose bodies were parsed as members.
            while (index < to &&
                (source[index].isWhitespace() || source[index] == ';' || source[index] == ',')
            ) {
                index++
            }
            if (index >= to) break
            val start = index
            var paren = 0
            var cursor = index
            var terminator = -1
            var brace = false
            while (cursor < to) {
                val char = source[cursor]
                if (char == '"' || char == '\'') {
                    cursor = skipLiteral(source, cursor, to)
                    continue
                }
                if (char == '/' && cursor + 1 < to && (source[cursor + 1] == '/' || source[cursor + 1] == '*')) {
                    cursor = skipComment(source, cursor, to)
                    continue
                }
                when (char) {
                    '(' -> paren++
                    ')' -> if (paren > 0) paren--
                    ';' -> if (paren == 0) {
                        terminator = cursor
                        break
                    }
                    '{' -> if (paren == 0) {
                        terminator = cursor
                        brace = true
                        break
                    }
                }
                cursor++
            }
            if (terminator < 0) break
            if (!brace) {
                val header = source.substring(start, terminator + 1).trim()
                members += Member(
                    name = fieldName(header),
                    kind = "field",
                    header = header,
                    start = start,
                    bodyStart = -1,
                    bodyEnd = -1,
                    end = terminator + 1,
                )
                index = terminator + 1
                continue
            }

            val rawHeader = source.substring(start, terminator).trim()
            val header = stripAnnotations(rawHeader)
            val close = matchingBrace(source, terminator, to)
            if (close < 0) break
            val isType = TYPE_KEYWORD.containsMatchIn(header)
            val hasAssign = header.contains('=')
            if (!isType && hasAssign) {
                // Field initializer: `int[] x = {1, 2}`, an anonymous class, or a lambda body. The
                // brace belongs to the value, so keep scanning for the declaration's `;`.
                var next = close + 1
                var depth = 0
                var end = -1
                while (next < to) {
                    val char = source[next]
                    if (char == '"' || char == '\'') {
                        next = skipLiteral(source, next, to)
                        continue
                    }
                    if (char == '/' && next + 1 < to && (source[next + 1] == '/' || source[next + 1] == '*')) {
                        next = skipComment(source, next, to)
                        continue
                    }
                    when (char) {
                        '(' -> depth++
                        ')' -> if (depth > 0) depth--
                        '{' -> {
                            val nested = matchingBrace(source, next, to)
                            if (nested < 0) break
                            next = nested
                        }
                        ';' -> if (depth == 0) {
                            end = next
                            break
                        }
                    }
                    next++
                }
                val memberEnd = if (end >= 0) end + 1 else close + 1
                members += Member(
                    name = fieldName(source.substring(start, memberEnd)),
                    kind = "field",
                    header = header,
                    start = start,
                    bodyStart = terminator,
                    bodyEnd = close,
                    end = memberEnd,
                )
                index = memberEnd
                continue
            }

            val hasCall = header.contains('(')
            val name = when {
                isType -> typeNameOf(header)
                hasCall -> callName(header)
                else -> ""
            }
            val kind = when {
                isType -> "type"
                hasCall && name == typeName -> "constructor"
                hasCall -> "method"
                else -> "initializer"
            }
            members += Member(
                name = name,
                kind = kind,
                header = header,
                start = start,
                bodyStart = terminator,
                bodyEnd = close,
                end = close + 1,
            )
            index = close + 1
        }
        return members
    }

    /**
     * Finds the outermost type body by anchoring on the type declaration, not on the first brace in
     * the file: CFR emits `@SuppressWarnings({"unchecked"})` on classes, and that annotation's braces
     * must not be mistaken for the class body.
     */
    private fun outerBody(source: String): Pair<Int, Int>? {
        val declaration = TYPE_DECLARATION.find(source) ?: return null
        var index = declaration.range.last + 1
        while (index < source.length) {
            val char = source[index]
            if (char == '"' || char == '\'') {
                index = skipLiteral(source, index, source.length)
                continue
            }
            if (char == '/' && index + 1 < source.length &&
                (source[index + 1] == '/' || source[index + 1] == '*')
            ) {
                index = skipComment(source, index, source.length)
                continue
            }
            when (char) {
                // A declaration without a body (annotation type member) has nothing to slice.
                ';' -> return null
                '{' -> {
                    val close = matchingBrace(source, index, source.length)
                    return if (close < 0) null else index to close
                }
            }
            index++
        }
        return null
    }

    private fun matchingBrace(source: String, open: Int, limit: Int): Int {
        var depth = 0
        var index = open
        while (index < limit) {
            val char = source[index]
            if (char == '"' || char == '\'') {
                index = skipLiteral(source, index, limit)
                continue
            }
            if (char == '/' && index + 1 < limit &&
                (source[index + 1] == '/' || source[index + 1] == '*')
            ) {
                index = skipComment(source, index, limit)
                continue
            }
            when (char) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }

    private fun skipLiteral(source: String, start: Int, limit: Int): Int {
        val quote = source[start]
        var index = start + 1
        while (index < limit) {
            val char = source[index]
            when {
                char == '\\' -> index += 2
                char == quote -> return index + 1
                else -> index++
            }
        }
        return limit
    }

    private fun skipComment(source: String, start: Int, limit: Int): Int {
        if (source[start + 1] == '/') {
            var index = start + 2
            while (index < limit && source[index] != '\n') index++
            return index
        }
        val end = source.indexOf("*/", start + 2)
        return if (end < 0 || end + 2 > limit) limit else end + 2
    }

    private fun outerTypeName(source: String, bodyStart: Int): String {
        val prefix = source.substring(0, bodyStart)
        val match = TYPE_DECLARATION.find(prefix) ?: return ""
        return match.groupValues[1]
    }

    private fun typeNameOf(header: String): String =
        TYPE_DECLARATION.find(header)?.groupValues?.get(1).orEmpty()

    /** Name of the callable: the identifier immediately before the last top-level `(`. */
    private fun callName(header: String): String {
        val open = header.lastIndexOf('(')
        if (open <= 0) return ""
        var end = open
        while (end > 0 && header[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && isIdentifierChar(header[start - 1])) start--
        return header.substring(start, end)
    }

    /** Last identifier of a field declaration, ignoring modifiers, annotations and initializers. */
    private fun fieldName(header: String): String {
        val declaration = stripAnnotations(header)
            .substringBefore('=')
            .trimEnd()
            .removeSuffix(";")
            .trimEnd()
        val match = IDENTIFIER.findAll(declaration).lastOrNull() ?: return ""
        return match.value
    }

    /** Drops leading `@Annotation` / `@Annotation(...)` runs so modifiers stay recognisable. */
    private fun stripAnnotations(header: String): String {
        var index = 0
        while (true) {
            while (index < header.length && header[index].isWhitespace()) index++
            if (index >= header.length || header[index] != '@') break
            index++
            while (index < header.length && isIdentifierChar(header[index])) index++
            while (index < header.length && header[index] == '.') {
                index++
                while (index < header.length && isIdentifierChar(header[index])) index++
            }
            var lookahead = index
            while (lookahead < header.length && header[lookahead].isWhitespace()) lookahead++
            if (lookahead < header.length && header[lookahead] == '(') {
                var depth = 0
                var cursor = lookahead
                while (cursor < header.length) {
                    val char = header[cursor]
                    if (char == '"' || char == '\'') {
                        cursor = skipLiteral(header, cursor, header.length)
                        continue
                    }
                    when (char) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) {
                                cursor++
                                break
                            }
                        }
                    }
                    cursor++
                }
                index = cursor
            }
        }
        return header.substring(index).trimStart()
    }

    private fun isIdentifierChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '$'

    private val TYPE_KEYWORD = Regex("\\b(class|interface|enum|record)\\b")
    private val TYPE_DECLARATION = Regex("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
    private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    private val WHITESPACE = Regex("\\s+")
}
