package app.siyaq.forge

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ToolchainScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var tick by remember { mutableStateOf(0) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val post: (String) -> Unit = { s -> handler.post { log.add(s) } }

    fun launchTask(block: suspend () -> Unit) {
        if (busy) return
        busy = true; log.clear()
        job = scope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { log.add("أُلغي"); throw e }
            catch (e: Exception) { log.add("✗ ${e.message}") }
            finally { busy = false; tick++ }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ رجوع") }
            Text("أدوات البناء", fontSize = 20.sp, color = Color.White)
        }
        Text("تنزيل Android SDK وGradle وJDK داخل التطبيق ثم اختبار تشغيلها فعليًا على جهازك.", fontSize = 12.sp, color = Color(0xFF8B949E), modifier = Modifier.padding(vertical = 8.dp))
        Toolchain.comps().forEach { c ->
            val installed = remember(tick) { Toolchain.isInstalled(ctx, c) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF161B22)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, color = Color.White, fontSize = 14.sp)
                    Text(if (installed) "● مثبّت" else "○ غير مثبّت (~${c.approxMB} MB)", fontSize = 12.sp, color = if (installed) Color(0xFF7EE787) else Color(0xFF8B949E))
                }
                if (installed) TextButton(onClick = { Toolchain.remove(ctx, c); tick++ }) { Text("حذف", color = Color(0xFFFF7B72)) }
                else TextButton(enabled = !busy, onClick = { launchTask { Toolchain.install(ctx, c, post) } }) { Text("تنزيل") }
            }
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { launchTask { Toolchain.selfTest(ctx, post) } }) { Text("اختبار ذاتي") }
            if (busy) OutlinedButton(onClick = { job?.cancel() }) { Text("إلغاء") }
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 380.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF161B22)).verticalScroll(rememberScrollState()).padding(10.dp)) {
            if (log.isEmpty()) Text("السجل فارغ", fontSize = 12.sp, color = Color(0xFF8B949E))
            log.forEach {
                Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = if (it.startsWith("✗")) Color(0xFFFF7B72) else if (it.startsWith("✓")) Color(0xFF7EE787) else Color(0xFFC9D1D9))
            }
        }
    }
}
