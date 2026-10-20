package app.siyaq.forge

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import org.json.JSONArray
import org.json.JSONObject

class Highlight(path: String) : VisualTransformation {
    private val rules: List<Pair<Regex, Color>>
    init {
        val kwC = Color(0xFFC792EA); val strC = Color(0xFFC3E88D); val comC = Color(0xFF6A7A99)
        val numC = Color(0xFFF78C6C); val tagC = Color(0xFF82AAFF)
        val str = Regex("\"(\\\\.|[^\"\\\\\\n])*\"|'(\\\\.|[^'\\\\\\n])*'")
        val num = Regex("\\b\\d+(\\.\\d+)?\\b")
        val kw = Regex("\\b(function|const|let|var|return|if|else|for|while|class|import|export|from|new|this|true|false|null|undefined|async|await|fun|val|object|when|is|in|package|override|private|public|static|void|int|def|try|catch|throw|switch|case|break|continue)\\b")
        rules = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm", "xml", "svg" -> listOf(Regex("</?[a-zA-Z][\\w-]*") to tagC, str to strC, Regex("<!--[\\s\\S]*?-->") to comC)
            "css" -> listOf(Regex("[a-zA-Z-]+(?=\\s*:)") to tagC, Regex("\\b\\d+(\\.\\d+)?(px|em|rem|%|vh|vw|s)?") to numC, str to strC, Regex("/\\*[\\s\\S]*?\\*/") to comC)
            "js", "ts", "kt", "java" -> listOf(kw to kwC, num to numC, str to strC, Regex("//.*|/\\*[\\s\\S]*?\\*/") to comC)
            "py" -> listOf(kw to kwC, num to numC, str to strC, Regex("#.*") to comC)
            "json" -> listOf(num to numC, Regex("\\b(true|false|null)\\b") to kwC, str to strC)
            else -> emptyList()
        }
    }
    override fun filter(text: AnnotatedString): TransformedText {
        if (rules.isEmpty() || text.length > 200_000) return TransformedText(text, OffsetMapping.Identity)
        val b = AnnotatedString.Builder(text)
        for ((r, c) in rules) for (m in r.findAll(text.text)) b.addStyle(SpanStyle(color = c), m.range.first, m.range.last + 1)
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

object Diagnostics {
    data class Issue(val line: Int, val message: String)

    fun check(path: String, text: String): List<Issue> {
        if (text.length > 400_000) return emptyList()
        return when (path.substringAfterLast('.', "").lowercase()) {
            "json" -> json(text)
            "js", "ts", "kt", "java" -> brackets(text, false)
            "css" -> brackets(text, true)
            else -> emptyList()
        }
    }

    private fun json(t: String): List<Issue> {
        if (t.isBlank()) return emptyList()
        return try {
            if (t.trimStart().startsWith("[")) JSONArray(t) else JSONObject(t)
            emptyList()
        } catch (e: Exception) {
            val pos = Regex("character (\\d+)").find(e.message ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val line = t.take(pos.coerceAtMost(t.length)).count { it == '\n' } + 1
            listOf(Issue(line, "JSON غير صالح: " + (e.message ?: "").substringBefore(" at character").take(120)))
        }
    }

    private fun brackets(t: String, css: Boolean): List<Issue> {
        val st = ArrayDeque<Pair<Char, Int>>()
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        var line = 1
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\n') line++
            else if (c == '/' && i + 1 < t.length && t[i + 1] == '/' && !css) {
                while (i < t.length && t[i] != '\n') i++
                continue
            } else if (c == '/' && i + 1 < t.length && t[i + 1] == '*') {
                val e = t.indexOf("*/", i + 2)
                val end = if (e < 0) t.length else e + 2
                line += t.substring(i, end).count { it == '\n' }
                i = end
                continue
            } else if (c == '"' || c == '\'') {
                i++
                while (i < t.length && t[i] != c && t[i] != '\n') { if (t[i] == '\\') i++; i++ }
            } else if (c == '(' || c == '[' || c == '{') st.addLast(c to line)
            else if (c == ')' || c == ']' || c == '}') {
                val top = st.removeLastOrNull()
                if (top == null || top.first != pairs[c]) return listOf(Issue(line, "القوس '$c' بلا مطابق"))
            }
            i++
        }
        return st.lastOrNull()?.let { listOf(Issue(it.second, "القوس '${it.first}' لم يُغلق")) } ?: emptyList()
    }
}


/** مساعدات الكتابة: إزاحة تلقائية بعد Enter، وإغلاق الأقواس والاقتباسات، وتخطّي المغلِق الموجود. */
object Typing {
    private fun closerFor(c: Char?): Char? = when (c) { '{' -> '}'; '(' -> ')'; '[' -> ']'; else -> null }

    fun transform(old: TextFieldValue, v: TextFieldValue, indent: Boolean, close: Boolean): TextFieldValue {
        if (v.composition != null) return v
        if (v.text.length != old.text.length + 1) return v
        if (!v.selection.collapsed || !old.selection.collapsed || v.selection.start != old.selection.start + 1) return v
        val t = v.text
        val c = v.selection.start
        val ch = t[c - 1]
        val next = t.getOrNull(c)
        if (close && next == ch && (ch == ')' || ch == ']' || ch == '}' || ch == '"' || ch == '\'')) {
            return TextFieldValue(t.removeRange(c - 1, c), TextRange(c))
        }
        if (ch == '\n' && indent) {
            val lineStart = t.lastIndexOf('\n', c - 2) + 1
            val prevLine = t.substring(lineStart, c - 1)
            val ws = prevLine.takeWhile { it == ' ' || it == '\t' }
            val last = prevLine.trimEnd().lastOrNull()
            val opener = last != null && (last == '{' || last == '(' || last == '[')
            val extra = if (opener) "  " else ""
            val between = opener && next != null && next == closerFor(last)
            return if (between) {
                val ins = ws + extra + "\n" + ws
                TextFieldValue(t.substring(0, c) + ins + t.substring(c), TextRange(c + ws.length + extra.length))
            } else {
                val ins = ws + extra
                TextFieldValue(t.substring(0, c) + ins + t.substring(c), TextRange(c + ins.length))
            }
        }
        if (close) {
            val pair: Char? = when (ch) { '(' -> ')'; '[' -> ']'; '{' -> '}'; '"' -> '"'; '\'' -> '\''; else -> null }
            if (pair != null) {
                val quote = ch == '"' || ch == '\''
                val prev = t.getOrNull(c - 2)
                val prevOk = !quote || prev == null || !prev.isLetterOrDigit()
                val nextOk = next == null || next.isWhitespace() || next == ')' || next == ']' || next == '}' || next == ';' || next == ',' || next == '.'
                if (prevOk && nextOk) return TextFieldValue(t.substring(0, c) + pair + t.substring(c), TextRange(c))
            }
        }
        return v
    }
}
