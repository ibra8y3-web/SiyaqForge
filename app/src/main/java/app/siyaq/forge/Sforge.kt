package app.siyaq.forge

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** صيغة .sforge: ملف ZIP يحوي ملفات المشروع النصية + manifest.json فيه SHA-256 لكل ملف. */
object Sforge {
    const val VERSION = 1
    private const val MAX_TOTAL = 64L * 1024 * 1024
    private val TEXT_EXT = setOf("html", "htm", "css", "js", "ts", "json", "toml", "md", "txt", "kt", "java", "py", "xml", "svg", "yaml", "yml", "lock", "")

    fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun isEditable(f: File): Boolean =
        f.isFile && f.length() < 2L * 1024 * 1024 && f.extension.lowercase() in TEXT_EXT

    fun listFiles(root: File): List<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

    fun scaffold(root: File, name: String, template: String = "web") {
        File(root, "src").mkdirs()
        File(root, "resources").mkdirs()
        File(root, "project.toml").writeText("[project]\nname = \"$name\"\nformat_version = $VERSION\nentry = \"src/index.html\"\n")
        if (template == "blank") {
            File(root, "src/index.html").writeText("<!DOCTYPE html>\n<html>\n<head><meta charset=\"utf-8\"><title>$name</title></head>\n<body>\n</body>\n</html>\n")
            return
        }
        File(root, "src/index.html").writeText(
            "<!DOCTYPE html>\n<html lang=\"ar\" dir=\"rtl\">\n<head>\n  <meta charset=\"utf-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                "  <title>$name</title>\n  <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n" +
                "  <h1 id=\"title\">مرحبًا من $name</h1>\n  <button id=\"btn\">اضغط</button>\n  <p id=\"out\"></p>\n" +
                "  <script src=\"app.js\"></script>\n</body>\n</html>\n"
        )
        File(root, "src/style.css").writeText("body { font-family: sans-serif; text-align: center; padding: 40px; background: #0e1117; color: #e6edf3; }\nbutton { font-size: 18px; padding: 10px 24px; border-radius: 12px; border: 0; background: #7c9cff; }\n")
        File(root, "src/app.js").writeText("let n = 0;\ndocument.getElementById('btn').addEventListener('click', () => {\n  n++;\n  document.getElementById('out').textContent = 'عدد الضغطات: ' + n;\n});\n")
    }

    fun export(root: File, out: OutputStream) {
        val hashes = JSONObject()
        ZipOutputStream(out).use { zip ->
            for (rel in listFiles(root).filter { !(it.startsWith("build/") && it != "build/pipeline.toml") && !it.startsWith("diagnostics/") }) {
                if (rel == "manifest.json") continue
                val bytes = File(root, rel).readBytes()
                hashes.put(rel, sha256(bytes))
                zip.putNextEntry(ZipEntry(rel)); zip.write(bytes); zip.closeEntry()
            }
            val manifest = JSONObject().put("format_version", VERSION).put("files", hashes)
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(2).toByteArray()); zip.closeEntry()
        }
    }

    /** يتحقق من الحزمة كاملة قبل كتابة أي ملف؛ يرفض التالف والمسارات الخطرة. */
    fun importInto(input: InputStream, projectsDir: File): Result<String> = runCatching {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = e.name
                require(!n.startsWith("/") && !n.contains("..") && !n.contains("\\")) { "مسار غير آمن: $n" }
                val bytes = z.readBytes()
                total += bytes.size
                require(total <= MAX_TOTAL) { "الحزمة كبيرة جدًا" }
                entries[n] = bytes
            }
        }
        val mf = entries["manifest.json"] ?: error("manifest.json مفقود: الملف ليس حزمة .sforge صالحة")
        val m = JSONObject(String(mf))
        require(m.getInt("format_version") <= VERSION) { "إصدار الصيغة أحدث من المدعوم" }
        val hashes = m.getJSONObject("files")
        val names = entries.keys.filter { it != "manifest.json" }.toSet()
        val declared = hashes.keys().asSequence().toSet()
        require(names == declared) { "محتوى الحزمة لا يطابق البيان" }
        for (n in names) require(sha256(entries.getValue(n)) == hashes.getString(n)) { "ملف تالف: $n" }
        val toml = entries["project.toml"] ?: error("project.toml مفقود")
        val raw = Regex("name\\s*=\\s*\"([^\"]+)\"").find(String(toml))?.groupValues?.get(1) ?: "imported"
        val base = raw.replace(Regex("[^\\p{L}\\p{N}_\\-. ]"), "_").take(40).ifBlank { "imported" }
        var final = base
        var i = 2
        while (File(projectsDir, final).exists()) { final = "$base-$i"; i++ }
        val dest = File(projectsDir, final)
        dest.mkdirs()
        for ((n, b) in entries) if (n != "manifest.json") {
            val f = File(dest, n)
            f.parentFile?.mkdirs()
            f.writeBytes(b)
        }
        final
    }
}

class ProjectStore(base: File) {
    val dir = File(base, "projects").apply { mkdirs() }
    fun list(): List<String> = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { it.name }?.sorted() ?: emptyList()
    fun dirOf(name: String) = File(dir, name)
    fun create(name: String, template: String = "web"): Result<String> = runCatching {
        val n = name.trim()
        require(n.matches(Regex("[\\p{L}\\p{N}_\\-. ]{1,40}")) && !n.startsWith(".")) { "اسم غير صالح" }
        val d = dirOf(n)
        require(!d.exists()) { "المشروع موجود مسبقًا" }
        d.mkdirs()
        Sforge.scaffold(d, n, template)
        n
    }
    fun duplicate(name: String): Result<String> = runCatching {
        var n = "$name-copy"
        var i = 2
        while (dirOf(n).exists()) { n = "$name-copy$i"; i++ }
        dirOf(name).copyRecursively(dirOf(n))
        val toml = File(dirOf(n), "project.toml")
        if (toml.exists()) toml.writeText(toml.readText().replace(Regex("name = \"[^\"]*\""), "name = \"" + n + "\""))
        n
    }
    fun delete(name: String) { dirOf(name).deleteRecursively() }
    /** يمنع الخروج من مجلد المشروع. */
    fun resolve(root: File, rel: String): File {
        val f = File(root, rel).canonicalFile
        require(f.path.startsWith(root.canonicalPath + File.separator)) { "مسار غير مسموح" }
        return f
    }
}
