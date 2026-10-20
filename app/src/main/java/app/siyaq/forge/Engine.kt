package app.siyaq.forge

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProjectLayout {
    private const val PIPELINE = """[tasks.check]
description = "فحص كل الملفات وروابط المشروع"

[tasks.graph]
description = "بناء خريطة الاعتماديات"

[tasks.package]
description = "فحص ثم تغليف .sforge والتحقق منه"

[tasks.android]
description = "توليد مشروع Android (WebView) جاهز للبناء عبر GitHub Actions"
"""
    fun ensure(root: File) {
        listOf("graph", "plugins", "build", "diagnostics").forEach { File(root, it).mkdirs() }
        val lock = File(root, "plugins.lock"); if (!lock.exists()) lock.writeText("")
        val pl = File(root, "build/pipeline.toml"); if (!pl.exists()) pl.writeText(PIPELINE)
    }
    fun isGenerated(rel: String) = rel.startsWith("build/") || rel.startsWith("diagnostics/") || rel.startsWith("graph/") || rel.startsWith("plugins/")
}

/** الإضافات: وصفية فقط (كلمات مفتاحية + قصاصات). أي صلاحية خارج القائمة المسموحة تُرفض. */
object Plugins {
    val ALLOWED = setOf("snippets", "keywords")
    data class Snippet(val trigger: String, val ext: String, val body: String)
    data class Plugin(val id: String, val version: String, val keywords: Map<String, List<String>>, val snippets: List<Snippet>, val active: Boolean, val note: String)

    fun builtin() = Plugin(
        "builtin-web", "1.0",
        mapOf(
            "js" to listOf("addEventListener", "querySelector", "getElementById", "textContent", "innerHTML", "setTimeout", "localStorage"),
            "html" to listOf("DOCTYPE", "viewport", "stylesheet", "charset"),
            "css" to listOf("display", "flex", "grid", "background", "border-radius", "padding", "margin", "font-size")
        ),
        listOf(
            Snippet("log", "js", "console.log();"),
            Snippet("func", "js", "function name() {\n  \n}"),
            Snippet("html5", "html", "<!DOCTYPE html>\n<html>\n<head>\n  <meta charset=\"utf-8\">\n  <title></title>\n</head>\n<body>\n  \n</body>\n</html>")
        ),
        true, "مدمجة"
    )

    private fun lock(root: File): Map<String, String> =
        File(root, "plugins.lock").takeIf { it.exists() }?.readLines()
            ?.mapNotNull { val p = it.trim().split(" "); if (p.size == 2) p[0] to p[1] else null }?.toMap() ?: emptyMap()

    fun parse(json: String): Plugin {
        val o = JSONObject(json)
        val id = o.getString("id")
        require(id.matches(Regex("[a-z0-9-]{1,30}"))) { "معرّف الإضافة غير صالح" }
        val perms = o.optJSONArray("permissions")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val denied = perms.filter { it !in ALLOWED }
        require(denied.isEmpty()) { "صلاحيات غير ممنوحة: ${denied.joinToString()}" }
        val kw = HashMap<String, List<String>>()
        o.optJSONObject("keywords")?.let { k ->
            for (key in k.keys()) { val a = k.getJSONArray(key); kw[key] = (0 until minOf(a.length(), 500)).map { a.getString(it) } }
        }
        val sn = ArrayList<Snippet>()
        o.optJSONArray("snippets")?.let { a ->
            for (i in 0 until minOf(a.length(), 100)) {
                val s = a.getJSONObject(i)
                val body = s.getString("body"); require(body.length <= 2000) { "قصاصة كبيرة" }
                sn.add(Snippet(s.getString("trigger"), s.optString("ext", "*"), body))
            }
        }
        return Plugin(id, o.optString("version", "?"), kw, sn, true, "مثبّتة")
    }

    fun install(root: File, json: String): Result<String> = runCatching {
        val p = parse(json)
        val dir = File(root, "plugins/${p.id}"); dir.mkdirs()
        File(dir, "plugin.json").writeText(json)
        val kept = File(root, "plugins.lock").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() && !it.startsWith(p.id + " ") } ?: emptyList()
        File(root, "plugins.lock").writeText((kept + "${p.id} ${Sforge.sha256(json.toByteArray())}").joinToString("\n") + "\n")
        p.id
    }

    fun load(root: File): List<Plugin> {
        val out = arrayListOf(builtin())
        val lk = lock(root)
        File(root, "plugins").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val f = File(d, "plugin.json")
            if (!f.isFile) return@forEach
            val raw = f.readText()
            val bad = { msg: String -> Plugin(d.name, "?", emptyMap(), emptyList(), false, msg) }
            if (lk[d.name] != Sforge.sha256(raw.toByteArray())) { out.add(bad("غير موثوقة: لا تطابق plugins.lock")); return@forEach }
            out.add(runCatching { parse(raw) }.getOrElse { bad("مرفوضة: ${it.message}") })
        }
        return out
    }
}

object Completion {
    data class Sug(val label: String, val insert: String)
    private val BASE = mapOf(
        "js" to listOf("function", "const", "let", "return", "await", "async", "document", "window", "import", "export", "class", "console", "switch", "while"),
        "kt" to listOf("fun", "val", "var", "class", "object", "when", "return", "override", "private", "suspend"),
        "py" to listOf("def", "class", "import", "return", "lambda", "print", "range", "while", "except", "finally"),
        "css" to listOf("color", "width", "height", "position", "transition", "overflow", "opacity"),
        "html" to listOf("div", "span", "script", "style", "button", "input", "section", "header", "footer", "canvas")
    )
    fun prefixStart(t: String, c: Int): Int { var i = c; while (i > 0 && (t[i - 1].isLetterOrDigit() || t[i - 1] == '_')) i--; return i }
    fun suggest(path: String, t: String, c: Int, plugins: List<Plugins.Plugin>): List<Sug> {
        if (t.length > 200_000) return emptyList()
        val cc = c.coerceIn(0, t.length)
        val st = prefixStart(t, cc)
        val pre = t.substring(st, cc)
        if (pre.length < 2) return emptyList()
        var ext = path.substringAfterLast('.', "").lowercase()
        if (ext == "htm") ext = "html"; if (ext == "ts") ext = "js"
        val act = plugins.filter { it.active }
        val out = LinkedHashMap<String, Sug>()
        for (p in act) for (s in p.snippets) if ((s.ext == ext || s.ext == "*") && s.trigger.startsWith(pre, true)) out["s:" + s.trigger] = Sug("⌘ " + s.trigger, s.body)
        for (k in (BASE[ext] ?: emptyList()) + act.flatMap { it.keywords[ext] ?: emptyList() }) if (k.startsWith(pre, true) && k != pre) out.putIfAbsent("k:$k", Sug(k, k))
        for (m in Regex("[\\p{L}_][\\p{L}\\p{N}_]{2,}").findAll(t)) { val w = m.value; if (w.startsWith(pre, true) && w != pre) out.putIfAbsent("w:$w", Sug(w, w)) }
        return out.values.take(8)
    }
}

object CodeFormatter {
    fun format(path: String, text: String): Result<String> = runCatching {
        when (path.substringAfterLast('.', "").lowercase()) {
            "json" -> if (text.trimStart().startsWith("[")) JSONArray(text).toString(2) else JSONObject(text).toString(2)
            "js", "ts", "kt", "java", "css" -> {
                var depth = 0
                text.lines().joinToString("\n") { raw ->
                    val l = raw.trim()
                    if (l.isEmpty()) "" else {
                        val lead = l.takeWhile { it == '}' || it == ')' || it == ']' }.length
                        val ind = maxOf(depth - lead, 0)
                        depth = maxOf(depth + l.count { it == '{' || it == '(' || it == '[' } - l.count { it == '}' || it == ')' || it == ']' }, 0)
                        "  ".repeat(ind) + l
                    }
                }
            }
            else -> text.lines().joinToString("\n") { it.trimEnd() }
        }
    }
}

object Graph {
    data class Edge(val from: String, val to: String, val exists: Boolean)
    private val SCAN = setOf("html", "htm", "css", "js", "ts")

    fun build(root: File): List<Edge> {
        val rc = root.canonicalFile
        val out = ArrayList<Edge>()
        for (rel in Sforge.listFiles(root)) {
            if (ProjectLayout.isGenerated(rel)) continue
            val ext = rel.substringAfterLast('.', "").lowercase()
            val f = File(root, rel)
            if (ext !in SCAN || !Sforge.isEditable(f)) continue
            val t = f.readText()
            val refs = when (ext) {
                "html", "htm" -> Regex("(?:src|href)\\s*=\\s*[\"']([^\"'#?]+)").findAll(t).map { it.groupValues[1] }.toList()
                "css" -> Regex("@import\\s+(?:url\\()?[\"']?([^\"')\\s;]+)").findAll(t).map { it.groupValues[1] }.toList()
                else -> Regex("(?:from|import|require\\()\\s*[\"'](\\.{1,2}/[^\"']+)[\"']").findAll(t).map { it.groupValues[1] }.toList()
            }
            val dir = f.parentFile ?: root
            for (r in refs) {
                if (r.contains("://") || r.startsWith("data:") || r.startsWith("mailto:") || r.startsWith("//") || r.startsWith("javascript:")) continue
                var target = File(dir, r).canonicalFile
                if (!target.exists() && (ext == "js" || ext == "ts")) {
                    target = listOf(".js", ".ts").map { File(target.path + it) }.firstOrNull { it.exists() } ?: target
                }
                val rt = if (target.path.startsWith(rc.path + File.separator)) target.relativeTo(rc).invariantSeparatorsPath else r
                out.add(Edge(rel, rt, target.exists()))
            }
        }
        return out
    }

    fun write(root: File, edges: List<Edge>) {
        File(root, "graph").mkdirs()
        val comps = JSONArray()
        Sforge.listFiles(root).filter { !ProjectLayout.isGenerated(it) }.forEach {
            comps.put(JSONObject().put("path", it).put("type", it.substringAfterLast('.', "")))
        }
        val deps = JSONArray()
        edges.forEach { deps.put(JSONObject().put("from", it.from).put("to", it.to).put("exists", it.exists)) }
        File(root, "graph/components.json").writeText(comps.toString(2))
        File(root, "graph/dependencies.json").writeText(deps.toString(2))
    }
}

data class TaskResult(val ok: Boolean, val log: List<String>)

object Tasks {
    fun names(root: File): List<String> =
        Regex("\\[tasks\\.([a-z0-9_-]+)\\]").findAll(File(root, "build/pipeline.toml").takeIf { it.exists() }?.readText().orEmpty()).map { it.groupValues[1] }.toList()

    fun run(root: File, name: String): TaskResult {
        ProjectLayout.ensure(root)
        val log = ArrayList<String>()
        val ok = try {
            when (name) {
                "check" -> check(root, log)
                "graph" -> { val e = Graph.build(root); Graph.write(root, e); log.add("✓ ${e.size} اعتمادية كُتبت في graph/dependencies.json"); true }
                "package" -> pack(root, log)
                "android" -> android(root, log)
                else -> { log.add("✗ مهمة غير معرّفة: $name"); false }
            }
        } catch (e: Exception) { log.add("✗ استثناء: ${e.message}"); false }
        runCatching { File(root, "diagnostics/last-run.txt").writeText("task=$name ok=$ok\n" + log.joinToString("\n")) }
        return TaskResult(ok, log)
    }

    private fun check(root: File, log: MutableList<String>): Boolean {
        var ok = true; var n = 0
        for (rel in Sforge.listFiles(root)) {
            if (ProjectLayout.isGenerated(rel)) continue
            val f = File(root, rel)
            if (!Sforge.isEditable(f)) continue
            n++
            for (i in Diagnostics.check(rel, f.readText())) { ok = false; log.add("✗ $rel:${i.line} ${i.message}") }
        }
        for (e in Graph.build(root)) if (!e.exists) { ok = false; log.add("✗ ${e.from}: مرجع مفقود ${e.to}") }
        log.add(if (ok) "✓ فُحص $n ملفًا بلا مشاكل" else "فشل الفحص")
        return ok
    }

    private fun pack(root: File, log: MutableList<String>): Boolean {
        if (!check(root, log)) return false
        val out = File(root, "build/${root.name}.sforge")
        out.outputStream().use { Sforge.export(root, it) }
        val tmp = File(root.parentFile, ".verify-${System.nanoTime()}"); tmp.mkdirs()
        val r = out.inputStream().use { Sforge.importInto(it, tmp) }
        tmp.deleteRecursively()
        if (r.isFailure) { log.add("✗ فشل التحقق من الحزمة: ${r.exceptionOrNull()?.message}"); return false }
        log.add("✓ build/${root.name}.sforge (${out.length()} بايت) — أُعيد استيرادها وتحقق منها")
        return true
    }

    private fun android(root: File, log: MutableList<String>): Boolean {
        val entry = Regex("entry\\s*=\\s*\"([^\"]+)\"").find(File(root, "project.toml").readText())?.groupValues?.get(1) ?: "src/index.html"
        if (!File(root, entry).isFile) { log.add("✗ ملف الدخول غير موجود: $entry"); return false }
        if (!check(root, log)) return false
        val out = File(root, "build/android"); out.deleteRecursively()
        val pkg = "app.siyaq.gen.p" + Sforge.sha256(root.name.toByteArray()).take(8)
        fun w(path: String, text: String) { val f = File(out, path); f.parentFile?.mkdirs(); f.writeText(text) }
        w("settings.gradle.kts", "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { google(); mavenCentral() } }\nrootProject.name = \"WebApp\"\ninclude(\":app\")\n")
        w("build.gradle.kts", "plugins { id(\"com.android.application\") version \"8.5.2\" apply false }\n")
        w("gradle.properties", "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n")
        w("app/build.gradle.kts", """
            plugins { id("com.android.application") }
            android {
                namespace = "PKG"
                compileSdk = 34
                defaultConfig { applicationId = "PKG"; minSdk = 24; targetSdk = 34; versionCode = 1; versionName = "1.0" }
                compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
            }
        """.trimIndent().replace("PKG", pkg) + "\n")
        w("app/src/main/AndroidManifest.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET" />
                <application android:label="APPNAME" android:theme="@android:style/Theme.Material.NoActionBar">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent().replace("APPNAME", root.name) + "\n")
        w("app/src/main/java/" + pkg.replace('.', '/') + "/MainActivity.java", """
            package PKG;
            import android.app.Activity;
            import android.os.Bundle;
            import android.webkit.WebView;
            import android.webkit.WebViewClient;
            public class MainActivity extends Activity {
                private WebView web;
                @Override protected void onCreate(Bundle b) {
                    super.onCreate(b);
                    web = new WebView(this);
                    web.getSettings().setJavaScriptEnabled(true);
                    web.getSettings().setDomStorageEnabled(true);
                    web.setWebViewClient(new WebViewClient());
                    setContentView(web);
                    web.loadUrl("file:///android_asset/www/ENTRY");
                }
                @Override public void onBackPressed() {
                    if (web.canGoBack()) web.goBack(); else super.onBackPressed();
                }
            }
        """.trimIndent().replace("PKG", pkg).replace("ENTRY", entry.removePrefix("src/")) + "\n")
        w(".github/workflows/build.yml", """
            name: Build APK
            on: [push, workflow_dispatch]
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - uses: actions/setup-java@v4
                    with: { distribution: temurin, java-version: 17 }
                  - uses: gradle/actions/setup-gradle@v4
                    with: { gradle-version: 8.7 }
                  - run: gradle assembleDebug --no-daemon
                  - uses: actions/upload-artifact@v4
                    with: { name: app-debug, path: app/build/outputs/apk/debug/*.apk }
        """.trimIndent() + "\n")
        val www = File(out, "app/src/main/assets/www")
        File(root, "src").copyRecursively(www, true)
        File(root, "resources").takeIf { it.exists() }?.copyRecursively(File(www, "resources"), true)
        if (!File(www, entry.removePrefix("src/")).isFile) { log.add("✗ ملف الدخول لم يُنسخ إلى الأصول"); return false }
        val zip = File(root, "build/${root.name}-android.zip")
        var count = 0
        ZipOutputStream(zip.outputStream()).use { z ->
            out.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(out).invariantSeparatorsPath)); z.write(f.readBytes()); z.closeEntry(); count++
            }
        }
        if (count < 7) { log.add("✗ الحزمة ناقصة"); return false }
        log.add("✓ build/${root.name}-android.zip ($count ملفًا) — ارفعه إلى GitHub وسيبني الـ APK تلقائيًا")
        log.add("ملاحظة: هذا مشروع جاهز للبناء وليس APK؛ بناء APK نفسه يتم في GitHub Actions.")
        return true
    }
}
