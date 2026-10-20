package app.siyaq.forge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private val BGC = Color(0xFF0E1117)
private val CARD = Color(0xFF161B22)
private val MUTED = Color(0xFF8B949E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppSettings(this)
        setContent {
            CompositionLocalProvider(LocalSettings provides settings) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7C9CFF), background = BGC, surface = CARD)) {
                    Surface(Modifier.fillMaxSize(), color = BGC) { App() }
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val store = remember { ProjectStore(ctx.filesDir) }
    var current by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var toolchain by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = current != null || toolchain) { if (toolchain) toolchain = false else current = null }
    val c = current
    when {
        c != null -> EditorScreen(c, store) { current = null }
        toolchain -> ToolchainScreen { toolchain = false }
        else -> Scaffold(
            containerColor = BGC,
            bottomBar = {
                NavigationBar(containerColor = CARD) {
                    listOf("📁" to "المشاريع", "🧰" to "الأدوات", "⚙" to "الإعدادات").forEachIndexed { i, (icon, label) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Text(icon, fontSize = 20.sp) },
                            label = { Text(label, fontSize = 11.sp, maxLines = 1) }
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> ProjectList(store) { current = it }
                    1 -> ToolsHome { toolchain = true }
                    else -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun InfoCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD)
    val m = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(14.dp)
    Column(m, content = content)
}

@Composable
fun ProjectList(store: ProjectStore, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var projects by remember { mutableStateOf(store.list()) }
    var newName by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("web") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val r = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { Sforge.importInto(it, store.dir).getOrThrow() } }
            projects = store.list()
            Toast.makeText(ctx, if (r.isSuccess) "تم الاستيراد: ${r.getOrNull()}" else "رُفضت الحزمة: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            Text("Siyāq Forge", fontSize = 26.sp, color = Color.White)
            Text("منصة تطوير محلية بصيغة مشروع مفتوحة (.sforge)", fontSize = 13.sp, color = MUTED)
        }
        item {
            OutlinedTextField(newName, { newName = it }, label = { Text("اسم مشروع جديد") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolBtn("قالب: ويب", template == "web") { template = "web" }
                ToolBtn("قالب: فارغ", template == "blank") { template = "blank" }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(modifier = Modifier.weight(1f), onClick = {
                    val r = store.create(newName, template)
                    if (r.isSuccess) { newName = ""; projects = store.list(); onOpen(r.getOrThrow()) }
                    else Toast.makeText(ctx, r.exceptionOrNull()?.message ?: "فشل", Toast.LENGTH_SHORT).show()
                }) { Text("إنشاء", maxLines = 1) }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("استيراد .sforge", maxLines = 1) }
            }
        }
        if (projects.isEmpty()) item { Text("لا توجد مشاريع بعد", color = MUTED) }
        items(projects) { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).clickable { onOpen(p) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(p, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menuFor = p }) { Text("⋮", fontSize = 20.sp) }
                    DropdownMenu(expanded = menuFor == p, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("تكرار") }, onClick = {
                            menuFor = null
                            val r = store.duplicate(p)
                            projects = store.list()
                            Toast.makeText(ctx, if (r.isSuccess) "تم التكرار: ${r.getOrNull()}" else "فشل التكرار", Toast.LENGTH_SHORT).show()
                        })
                        DropdownMenuItem(text = { Text("حذف", color = Color(0xFFFF7B72)) }, onClick = { menuFor = null; confirmDelete = p })
                    }
                }
            }
        }
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("حذف المشروع «$p»؟") },
            text = { Text("سيُحذف المشروع وكل ملفاته من الجهاز. لا يمكن التراجع.") },
            confirmButton = { TextButton(onClick = { store.delete(p); projects = store.list(); confirmDelete = null }) { Text("حذف", color = Color(0xFFFF7B72)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun ToolsHome(onToolchain: () -> Unit) {
    val ctx = LocalContext.current
    var token by remember { mutableStateOf(SecureStore.load(ctx)) }
    var tokenInput by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    val cacheMb = remember(tick) { File(ctx.cacheDir, "apks").walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576 }
    val toolMb = remember(tick) { Toolchain.base(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("الأدوات", fontSize = 24.sp, color = Color.White)
        InfoCard(onClick = onToolchain) {
            Text("🧱 أدوات البناء", color = Color.White, fontSize = 16.sp)
            Text("Android SDK · Gradle · JDK — تنزيل واختبار تشغيلها على جهازك", color = MUTED, fontSize = 12.sp)
        }
        InfoCard {
            Text("☁ حساب GitHub (للبناء السحابي)", color = Color.White, fontSize = 16.sp)
            if (token != null) {
                Text("● متصل — التوكن مخزّن مشفّرًا", color = Color(0xFF7EE787), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                ToolBtn("تسجيل الخروج") { SecureStore.clear(ctx); token = null }
            } else {
                Text("توكن Classic بصلاحيتي repo و workflow.", color = MUTED, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolBtn("إنشاء توكن") {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/tokens/new?scopes=repo,workflow&description=Siyaq%20Forge")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                OutlinedTextField(tokenInput, { tokenInput = it }, label = { Text("الصق التوكن") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Box(Modifier.padding(top = 6.dp)) {
                    ToolBtn("حفظ") {
                        val t = tokenInput.trim()
                        if (t.length >= 20) { SecureStore.save(ctx, t); token = t; tokenInput = "" }
                        else Toast.makeText(ctx, "التوكن قصير جدًا", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        InfoCard {
            Text("🧹 التخزين", color = Color.White, fontSize = 16.sp)
            Text("ملفات APK المؤقتة: $cacheMb MB", color = MUTED, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text("أدوات البناء المنزّلة: $toolMb MB", color = MUTED, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            ToolBtn("مسح ملفات APK المؤقتة") { File(ctx.cacheDir, "apks").deleteRecursively(); tick++ }
        }
    }
}

@Composable
fun SettingsScreen() {
    val s = LocalSettings.current
    val layout = rememberLayoutInfo()
    val shown = layout.editorFont(s.fontSize)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("الإعدادات", fontSize = 24.sp, color = Color.White)
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("حجم خط المحرر تلقائي حسب الشاشة", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = s.fontSize == 0, onCheckedChange = { s.fontSize = if (it) 0 else shown; s.save() })
            }
            if (s.fontSize > 0) {
                Slider(value = s.fontSize.toFloat(), onValueChange = { s.fontSize = it.toInt() }, onValueChangeFinished = { s.save() }, valueRange = 10f..22f)
                Text("${s.fontSize} sp", color = MUTED, fontSize = 12.sp)
            }
            Text("function hello() { return 42; }", fontFamily = FontFamily.Monospace, fontSize = shown.sp, color = Color(0xFFC3E88D), modifier = Modifier.padding(top = 8.dp))
        }
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("إزاحة تلقائية بعد Enter", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = s.autoIndent, onCheckedChange = { s.autoIndent = it; s.save() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("إغلاق الأقواس والاقتباسات تلقائيًا", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = s.autoClose, onCheckedChange = { s.autoClose = it; s.save() })
            }
        }
        InfoCard {
            Text("Siyāq Forge 0.1", color = Color.White)
            Text("الشاشة الحالية: ${layout.widthDp}×${layout.heightDp} dp — كل الأحجام تُشتق منها تلقائيًا.", color = MUTED, fontSize = 12.sp)
        }
    }
}
