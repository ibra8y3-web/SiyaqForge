package app.siyaq.forge

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** مدير أدوات البناء: تنزيل + تحقق + فك + اختبار تشغيل حقيقي على هذا الجهاز. */
object Toolchain {
    data class Comp(val id: String, val dir: String, val title: String, val url: String, val tgz: Boolean, val approxMB: Int, val sha256Url: String?)
    data class ExecResult(val code: Int?, val output: String, val error: String?)

    fun base(ctx: Context) = File(ctx.filesDir, "toolchain").apply { mkdirs() }

    private fun jdkArch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "x86_64" -> "x64"
        "armeabi-v7a" -> "arm"
        else -> null
    }

    fun comps(): List<Comp> = listOfNotNull(
        Comp("gradle", "gradle", "Gradle 8.7", "https://services.gradle.org/distributions/gradle-8.7-bin.zip", false, 135,
            "https://services.gradle.org/distributions/gradle-8.7-bin.zip.sha256"),
        Comp("cmdline-tools", "android-sdk", "Android SDK Command-line Tools", "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip", false, 150, null),
        jdkArch()?.let {
            Comp("jdk", "jdk", "JDK 17 (Temurin $it)", "https://api.adoptium.net/v3/binary/latest/17/ga/linux/$it/jdk/hotspot/normal/eclipse", true, 190, null)
        }
    )

    fun isInstalled(ctx: Context, c: Comp) = File(base(ctx), "${c.dir}/.installed").exists()

    fun remove(ctx: Context, c: Comp) { File(base(ctx), c.dir).deleteRecursively() }

    suspend fun install(ctx: Context, c: Comp, log: (String) -> Unit) {
        val base = base(ctx)
        val need = c.approxMB * 5L / 2 * 1024 * 1024
        if (base.usableSpace < need) error("المساحة غير كافية: يلزم ~${need / 1048576} MB والمتاح ${base.usableSpace / 1048576} MB")
        val dl = File(base, "downloads").apply { mkdirs() }
        val archive = File(dl, c.id + if (c.tgz) ".tar.gz" else ".zip")
        log("↓ تنزيل ${c.title} (~${c.approxMB} MB)")
        val sha = download(c.url, archive, log)
        if (c.sha256Url != null) {
            val expected = String(URL(c.sha256Url).readBytes()).trim().split(Regex("\\s+"))[0].lowercase()
            if (expected != sha) { archive.delete(); error("SHA-256 لا يطابق القيمة المنشورة — أُلغي التثبيت") }
            log("✓ SHA-256 مطابق للقيمة الرسمية")
        } else {
            log("SHA-256: ${sha.take(16)}… (لا توجد قيمة رسمية للمقارنة؛ سُجّلت فقط)")
        }
        log("فك الضغط…")
        val finalDir = File(base, c.dir)
        finalDir.deleteRecursively()
        if (c.id == "cmdline-tools") {
            val tmp = File(base, "_tmp-${c.id}"); tmp.deleteRecursively(); tmp.mkdirs()
            unzip(archive, tmp)
            val latest = File(finalDir, "cmdline-tools/latest")
            latest.parentFile?.mkdirs()
            if (!File(tmp, "cmdline-tools").renameTo(latest)) error("تعذّر ترتيب مجلد cmdline-tools")
            tmp.deleteRecursively()
        } else {
            finalDir.mkdirs()
            if (c.tgz) untar(archive, finalDir) else unzip(archive, finalDir)
        }
        File(finalDir, ".installed").writeText(sha)
        archive.delete()
        log("✓ ${c.title} مثبّت")
    }

    private suspend fun download(url: String, out: File, log: (String) -> Unit): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "SiyaqForge")
        val code = c.responseCode
        if (code !in 200..299) error("فشل التنزيل: HTTP $code")
        val total = c.contentLengthLong
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var lastLog = 0L
        c.inputStream.use { inp ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = inp.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (done - lastLog >= 10L * 1024 * 1024) {
                        lastLog = done
                        log("   ${done / 1048576} / ${if (total > 0) (total / 1048576).toString() else "?"} MB")
                    }
                }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unzip(zip: File, dest: File) {
        val root = dest.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(dest, e.name).canonicalFile
                require(f.path.startsWith(root.path + File.separator) || f == root) { "مسار غير آمن في الأرشيف" }
                if (e.isDirectory) f.mkdirs()
                else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { z.copyTo(it) }
                    if (e.name.contains("/bin/")) f.setExecutable(true, false)
                }
            }
        }
    }

    private fun untar(tgz: File, dest: File) {
        val p = ProcessBuilder("/system/bin/tar", "-xzf", tgz.path, "-C", dest.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val rc = p.waitFor()
        if (rc != 0) error("فشل فك tar ($rc): ${out.take(200)}")
    }

    fun findJava(ctx: Context): File? =
        File(base(ctx), "jdk").listFiles()?.firstOrNull { File(it, "bin/java").isFile }?.let { File(it, "bin/java") }

    fun findGradle(ctx: Context): File? =
        File(base(ctx), "gradle").listFiles()?.firstOrNull { File(it, "bin/gradle").isFile }?.let { File(it, "bin/gradle") }

    private suspend fun exec(cmd: List<String>, env: Map<String, String>, timeoutS: Long): ExecResult = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            val rc = withTimeoutOrNull(timeoutS * 1000) { runInterruptible { p.waitFor() } }
            if (rc == null) { p.destroy(); ExecResult(null, "", "انتهت المهلة") }
            else ExecResult(rc, p.inputStream.bufferedReader().readText(), null)
        } catch (e: IOException) {
            ExecResult(null, "", e.message)
        }
    }

    private fun explain(ctx: Context, name: String, r: ExecResult): String {
        val err = r.error.orEmpty()
        return when {
            err.contains("error=13") ->
                "✗ $name: أندرويد رفض التشغيل (Permission denied). المتوقع أن السبب قيد منع تشغيل الملفات من مساحة التطبيق؛ targetSdk الحالي = ${ctx.applicationInfo.targetSdkVersion} والمطلوب ≤ 28، وبعض الأجهزة/الإصدارات تفرض القيد رغم ذلك."
            err.contains("error=2") ->
                "✗ $name: الملف موجود لكن لا يعمل — غالبًا لأنه مبني لـ glibc (لينكس) بينما أندرويد يستخدم bionic، فلا يجد المفسّر ld-linux. نحتاج JDK مبنيًا لأندرويد أو بيئة proot."
            err.isNotEmpty() -> "✗ $name: $err"
            r.code != 0 -> "✗ $name: خرج برمز ${r.code}\n" + r.output.lines().take(6).joinToString("\n")
            else -> "✓ $name يعمل:\n" + r.output.lines().take(4).joinToString("\n")
        }
    }

    suspend fun selfTest(ctx: Context, log: (String) -> Unit) {
        val base = base(ctx)
        log("الجهاز: ABI=${Build.SUPPORTED_ABIS.joinToString()} · API ${Build.VERSION.SDK_INT} · targetSdk=${ctx.applicationInfo.targetSdkVersion}")
        log("المساحة الحرة: ${base.usableSpace / 1048576} MB")
        val report = StringBuilder()
        val java = findJava(ctx)
        if (java == null) log("✗ JDK غير مثبت") else {
            val r = exec(listOf(java.path, "-version"), emptyMap(), 25)
            val msg = explain(ctx, "java", r); log(msg); report.appendLine(msg)
            if (r.code == 0) {
                val gradle = findGradle(ctx)
                if (gradle == null) log("✗ Gradle غير مثبت") else {
                    val env = mapOf("JAVA_HOME" to java.parentFile!!.parentFile!!.path, "GRADLE_USER_HOME" to File(base, "gradle-home").path)
                    val g = exec(listOf("/system/bin/sh", gradle.path, "--version"), env, 90)
                    val m2 = explain(ctx, "gradle", g); log(m2); report.appendLine(m2)
                }
            } else log("تخطّي اختبار Gradle لأن Java لا تعمل")
        }
        runCatching { File(base, "selftest.txt").writeText(report.toString()) }
    }
}
