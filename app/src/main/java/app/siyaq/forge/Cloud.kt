package app.siyaq.forge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** يخزّن توكن GitHub مشفّرًا بمفتاح داخل Android Keystore (لا نص صريح). */
object SecureStore {
    private const val ALIAS = "forge_gh_token"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("forge_secure", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        )
        return g.generateKey()
    }
    fun save(ctx: Context, token: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(token.toByteArray())
        prefs(ctx).edit().putString("tok", Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
    }
    fun load(ctx: Context): String? = runCatching {
        val parts = prefs(ctx).getString("tok", null)?.split(":") ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
    }.getOrNull()
    fun clear(ctx: Context) { prefs(ctx).edit().remove("tok").apply() }
}

class GhError(val code: Int, val body: String) : Exception(
    when (code) {
        401 -> "التوكن غير صالح أو منتهي (401)"
        403 -> "ممنوع (403): تأكد من صلاحيتي repo و workflow — ${body.take(120)}"
        404 -> "غير موجود (404) — ${body.take(120)}"
        else -> "HTTP $code: ${body.take(160)}"
    }
)

class GitHub(private val token: String) {
    private fun conn(method: String, url: String, auth: Boolean, body: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = false
        c.connectTimeout = 20_000
        c.readTimeout = 90_000
        c.setRequestProperty("User-Agent", "SiyaqForge")
        if (auth) {
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return c
    }

    fun api(method: String, path: String, body: JSONObject? = null): JSONObject {
        val c = conn(method, "https://api.github.com$path", true, body?.toString())
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) }.orEmpty()
        if (code !in 200..299) throw GhError(code, text)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    /** تنزيل مع تتبّع التحويلات يدويًا: التوكن يُرسل لـ GitHub فقط وليس لخادم التخزين. */
    fun fetch(url: String): ByteArray {
        var u = url
        var auth = true
        repeat(5) {
            val c = conn("GET", u, auth)
            val code = c.responseCode
            if (code in 300..399) {
                u = c.getHeaderField("Location") ?: error("تحويل بلا عنوان")
                auth = false
                return@repeat
            }
            if (code !in 200..299) throw GhError(code, c.errorStream?.use { String(it.readBytes()) }.orEmpty())
            return c.inputStream.use { it.readBytes() }
        }
        error("تحويلات كثيرة")
    }
}

object Installer {
    /** يرجع رسالة إذا احتاج المستخدم لإجراء، أو null إذا فُتحت شاشة التثبيت. */
    fun install(ctx: Context, apk: File): String? {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "فعّل «السماح بالتثبيت» لهذا التطبيق ثم اضغط تثبيت مرة أخرى"
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return null
    }
}

object CloudBuild {
    fun repoNameFor(project: String): String {
        val slug = project.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30)
        val h = Sforge.sha256(project.toByteArray()).take(6)
        return if (slug.isEmpty()) "forge-$h" else "forge-$slug-$h"
    }

    /** المسار الكامل: توليد → رفع إلى GitHub → انتظار Actions → تنزيل APK. */
    suspend fun run(ctx: Context, root: File, token: String, log: (String) -> Unit): File {
        log("① توليد مشروع Android محليًا…")
        val r = Tasks.run(root, "android")
        r.log.forEach { log("   $it") }
        if (!r.ok) error("فشلت مهمة android — صحّح الأخطاء أولًا")
        val src = File(root, "build/android")

        val gh = GitHub(token)
        val me = gh.api("GET", "/user").getString("login")
        val repo = repoNameFor(root.name)
        log("② GitHub: $me/$repo")

        val info = try {
            gh.api("GET", "/repos/$me/$repo")
        } catch (e: GhError) {
            if (e.code != 404) throw e
            log("   إنشاء مستودع خاص جديد…")
            val created = gh.api("POST", "/user/repos", JSONObject().put("name", repo).put("private", true).put("auto_init", true).put("description", "Built by Siyāq Forge"))
            delay(2500)
            created
        }
        val branch = info.optString("default_branch", "main")

        var head: String? = null
        for (i in 1..6) {
            head = try { gh.api("GET", "/repos/$me/$repo/git/ref/heads/$branch").getJSONObject("object").getString("sha") } catch (e: GhError) { if (e.code in listOf(404, 409)) null else throw e }
            if (head != null) break
            delay(2000)
        }
        if (head == null) error("تعذّر قراءة الفرع $branch")

        val files = src.walkTopDown().filter { it.isFile }.toList()
        log("③ رفع ${files.size} ملفًا…")
        val tree = JSONArray()
        files.forEachIndexed { i, f ->
            currentCoroutineContext().ensureActive()
            val blob = gh.api(
                "POST", "/repos/$me/$repo/git/blobs",
                JSONObject().put("content", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)).put("encoding", "base64")
            ).getString("sha")
            tree.put(JSONObject().put("path", f.relativeTo(src).invariantSeparatorsPath).put("mode", "100644").put("type", "blob").put("sha", blob))
            if ((i + 1) % 10 == 0) log("   ${i + 1}/${files.size}")
        }
        val treeSha = gh.api("POST", "/repos/$me/$repo/git/trees", JSONObject().put("tree", tree)).getString("sha")
        val commit = gh.api(
            "POST", "/repos/$me/$repo/git/commits",
            JSONObject().put("message", "Forge build").put("tree", treeSha).put("parents", JSONArray().put(head))
        ).getString("sha")
        gh.api("PATCH", "/repos/$me/$repo/git/refs/heads/$branch", JSONObject().put("sha", commit).put("force", true))
        log("   commit ${commit.take(7)} — بدأ البناء على GitHub")

        log("④ انتظار GitHub Actions…")
        var runId = 0L
        var last = ""
        val t0 = System.currentTimeMillis()
        while (true) {
            currentCoroutineContext().ensureActive()
            val runs = gh.api("GET", "/repos/$me/$repo/actions/runs?head_sha=$commit").getJSONArray("workflow_runs")
            if (runs.length() > 0) {
                val run = runs.getJSONObject(0)
                runId = run.getLong("id")
                val st = run.getString("status")
                if (st != last) { last = st; log("   الحالة: $st") }
                if (st == "completed") {
                    val concl = run.optString("conclusion")
                    if (concl == "success") break
                    explainFailure(gh, me, repo, runId, log)
                    error("فشل البناء على GitHub ($concl)")
                }
            } else if (System.currentTimeMillis() - t0 > 120_000) {
                error("لم يبدأ أي بناء: تأكد أن Actions مفعّل وأن التوكن فيه صلاحية workflow")
            }
            if (System.currentTimeMillis() - t0 > 20 * 60_000) error("انتهت مهلة الانتظار (20 دقيقة)")
            delay(5000)
        }

        log("⑤ تنزيل APK…")
        val arts = gh.api("GET", "/repos/$me/$repo/actions/runs/$runId/artifacts").getJSONArray("artifacts")
        if (arts.length() == 0) error("لا يوجد ملف ناتج في هذا البناء")
        val zip = gh.fetch("https://api.github.com/repos/$me/$repo/actions/artifacts/${arts.getJSONObject(0).getLong("id")}/zip")
        val outDir = File(ctx.cacheDir, "apks").apply { mkdirs() }
        val out = File(outDir, "${root.name}.apk")
        var found = false
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".apk")) { out.writeBytes(z.readBytes()); found = true; break }
            }
        }
        if (!found || out.length() < 10_000) error("ملف APK غير موجود أو تالف داخل الناتج")
        log("   ${out.length() / 1024} KB")
        return out
    }

    private fun explainFailure(gh: GitHub, me: String, repo: String, runId: Long, log: (String) -> Unit) {
        runCatching {
            val jobs = gh.api("GET", "/repos/$me/$repo/actions/runs/$runId/jobs").getJSONArray("jobs")
            for (i in 0 until jobs.length()) {
                val j = jobs.getJSONObject(i)
                if (j.optString("conclusion") != "failure") continue
                val steps = j.getJSONArray("steps")
                for (k in 0 until steps.length()) {
                    val s = steps.getJSONObject(k)
                    if (s.optString("conclusion") == "failure") log("✗ خطوة فاشلة: ${s.getString("name")}")
                }
                val txt = String(gh.fetch("https://api.github.com/repos/$me/$repo/actions/jobs/${j.getLong("id")}/logs"))
                val lines = txt.lines().map { it.replace(Regex("^\\S+Z "), "") }
                val errs = lines.filter { it.contains("error:", true) || it.startsWith("e: ") || it.contains("What went wrong") || it.contains("FAILED") }.take(15)
                (if (errs.isEmpty()) lines.takeLast(15) else errs).forEach { log("   " + it.take(200)) }
            }
        }
    }
}
