package app.siyaq.forge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val BG = Color(0xFF0E1117)
private val PANEL = Color(0xFF161B22)
private val ACCENT = Color(0xFF7C9CFF)
private val MONO = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFFE6EDF3))

@Composable
fun ToolBtn(label: String, highlight: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (highlight) ACCENT.copy(alpha = .25f) else PANEL)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    ) { Text(label, fontSize = 13.sp, color = Color.White) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(name: String, store: ProjectStore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val root = remember(name) { store.dirOf(name) }
    val prefs = remember { ctx.getSharedPreferences("forge", Context.MODE_PRIVATE) }
    var files by remember { mutableStateOf(Sforge.listFiles(root)) }
    val tabs = remember { mutableStateListOf<String>() }
    val docs = remember { mutableStateMapOf<String, TextFieldValue>() }
    val undoS = remember { HashMap<String, ArrayList<String>>() }
    val redoS = remember { HashMap<String, ArrayList<String>>() }
    val lastPush = remember { HashMap<String, Long>() }
    var plugins by remember { mutableStateOf(Plugins.load(root)) }
    var edges by remember { mutableStateOf(listOf<Graph.Edge>()) }
    val taskLog = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val settings = LocalSettings.current
    val layout = rememberLayoutInfo()
    val fs = layout.editorFont(settings.fontSize)
    val monoStyle = remember(fs) { TextStyle(fontFamily = FontFamily.Monospace, fontSize = fs.sp, lineHeight = (fs * 1.45f).sp, color = Color(0xFFE6EDF3)) }
    var cat by remember { mutableStateOf("تحرير") }
    var toolsOpen by remember { mutableStateOf(true) }
    var renameDialog by remember { mutableStateOf(false) }
    var renameTo by remember { mutableStateOf("") }
    var gotoDialog by remember { mutableStateOf(false) }
    var gotoText by remember { mutableStateOf("") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var token by remember { mutableStateOf(SecureStore.load(ctx)) }
    var tokenInput by remember { mutableStateOf("") }
    val buildLog = remember { mutableStateListOf<String>() }
    var building by remember { mutableStateOf(false) }
    var buildJob by remember { mutableStateOf<Job?>(null) }
    var apk by remember { mutableStateOf(File(ctx.cacheDir, "apks/$name.apk").takeIf { it.exists() }) }
    val vs = rememberScrollState()
    var active by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<Triple<String, Int, String>>()) }
    var issues by remember { mutableStateOf(listOf<Diagnostics.Issue>()) }
    var newFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("src/new.js") }
    var deleteDialog by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    val logs = remember { mutableStateListOf<String>() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = panel.isNotEmpty() || drawer.isOpen) {
        if (drawer.isOpen) scope.launch { drawer.close() } else panel = ""
    }

    fun flush() {
        docs.forEach { (p, t) -> runCatching { store.resolve(root, p).writeText(t.text) } }
        prefs.edit().putString("tabs_$name", tabs.joinToString("\n")).putString("active_$name", active ?: "").apply()
    }
    fun openFile(p: String) {
        val f = runCatching { store.resolve(root, p) }.getOrNull()
        if (f == null || !Sforge.isEditable(f)) {
            Toast.makeText(ctx, "نوع الملف غير مدعوم للتحرير", Toast.LENGTH_SHORT).show(); return
        }
        if (!docs.containsKey(p)) docs[p] = TextFieldValue(f.readText())
        if (p !in tabs) tabs.add(p)
        active = p
        panel = ""
    }

    fun pushUndo(p: String, old: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - (lastPush[p] ?: 0L) > 700) {
            val st = undoS.getOrPut(p) { ArrayList() }
            st.add(old); if (st.size > 100) st.removeAt(0)
            lastPush[p] = now
        }
        redoS[p]?.clear()
    }
    fun setText(p: String, t: String) {
        val old = docs[p]?.text ?: return
        pushUndo(p, old, true)
        docs[p] = TextFieldValue(t, TextRange(t.length))
    }
    fun undo() {
        val a = active ?: return
        val st = undoS[a]; if (st.isNullOrEmpty()) return
        val c = docs[a] ?: return
        redoS.getOrPut(a) { ArrayList() }.add(c.text)
        val t = st.removeAt(st.size - 1)
        docs[a] = TextFieldValue(t, TextRange(c.selection.start.coerceAtMost(t.length)))
    }
    fun redo() {
        val a = active ?: return
        val st = redoS[a]; if (st.isNullOrEmpty()) return
        val c = docs[a] ?: return
        undoS.getOrPut(a) { ArrayList() }.add(c.text)
        val t = st.removeAt(st.size - 1)
        docs[a] = TextFieldValue(t, TextRange(c.selection.start.coerceAtMost(t.length)))
    }
    fun jumpTo(p: String, line: Int) {
        openFile(p)
        val tv = docs[p] ?: return
        var off = 0; var l = 1
        while (l < line && off < tv.text.length) { if (tv.text[off] == '\n') l++; off++ }
        docs[p] = TextFieldValue(tv.text, TextRange(off))
        jump = line
    }
    fun runTask(t: String) {
        flush(); taskLog.clear(); taskLog.add("▶ $t …"); busy = true
        scope.launch {
            val r = withContext(Dispatchers.Default) { Tasks.run(root, t) }
            taskLog.clear(); taskLog.addAll(r.log)
            taskLog.add(if (r.ok) "✓ نجحت المهمة" else "✗ فشلت المهمة")
            busy = false
            files = Sforge.listFiles(root)
        }
    }

    fun startBuild() {
        flush(); buildLog.clear(); building = true
        val tk = token ?: return
        buildJob = scope.launch {
            try {
                val f = withContext(Dispatchers.IO) { CloudBuild.run(ctx, root, tk) { s -> mainHandler.post { buildLog.add(s) } } }
                apk = f
                buildLog.add("✓ جاهز — شاشة التثبيت ستفتح")
                buildLog.add("ملاحظة: لو فشل التثبيت لوجود نسخة سابقة موقّعة بمفتاح مختلف، احذفها أولًا.")
                Installer.install(ctx, f)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
            } catch (e: CancellationException) {
                buildLog.add("أُلغي البناء"); throw e
            } catch (e: Exception) {
                buildLog.add("✗ ${e.message}")
            } finally { building = false }
        }
    }

    LaunchedEffect(jump) {
        val j = jump
        if (j != null) { delay(80); vs.scrollTo(with(density) { ((j - 3).coerceAtLeast(0) * (fs * 1.45f).sp.toPx()).toInt() }); jump = null }
    }
    LaunchedEffect(panel) {
        if (panel == "graph") edges = withContext(Dispatchers.Default) { Graph.build(root) }
    }

    LaunchedEffect(name) {
        ProjectLayout.ensure(root)
        files = Sforge.listFiles(root)
        val saved = prefs.getString("tabs_$name", "").orEmpty().split("\n").filter { it.isNotBlank() && File(root, it).isFile }
        saved.forEach { openFile(it) }
        val a = prefs.getString("active_$name", "").orEmpty()
        if (a in tabs) active = a
        if (tabs.isEmpty()) openFile("src/index.html")
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) flush() }
        owner.lifecycle.addObserver(ob)
        onDispose { flush(); owner.lifecycle.removeObserver(ob) }
    }

    val cur = active?.let { docs[it]?.text }
    LaunchedEffect(active, cur) {
        val a = active
        if (a != null && cur != null) {
            delay(400)
            runCatching { store.resolve(root, a).writeText(cur) }
            issues = withContext(Dispatchers.Default) { Diagnostics.check(a, cur) }
        } else issues = emptyList()
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            flush()
            val r = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { Sforge.export(root, it) } }
            Toast.makeText(ctx, if (r.isSuccess) "تم التصدير" else "فشل التصدير: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }

    val pluginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val r = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) } }
                .mapCatching { Plugins.install(root, it).getOrThrow() }
            plugins = Plugins.load(root); files = Sforge.listFiles(root)
            Toast.makeText(ctx, if (r.isSuccess) "ثُبّتت الإضافة: ${r.getOrNull()}" else "رُفضت الإضافة: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Text("الملفات", Modifier.padding(16.dp), fontSize = 18.sp)
                LazyColumn {
                    items(files) { p ->
                        Text(p, Modifier.fillMaxWidth().clickable { openFile(p); scope.launch { drawer.close() } }.padding(horizontal = 16.dp, vertical = 12.dp),
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
            }
        }
    ) {
        Column(Modifier.fillMaxSize().background(BG).statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { flush(); onBack() }) { Text("‹ رجوع", maxLines = 1) }
                Text(name, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val cats = listOf("ملفات", "تحرير", "فحص", "تشغيل", "أدوات")
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(cats) { c ->
                    ToolBtn(c, toolsOpen && cat == c) { if (cat == c) toolsOpen = !toolsOpen else { cat = c; toolsOpen = true } }
                }
            }
            if (toolsOpen) FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (cat) {
                    "ملفات" -> {
                        ToolBtn("قائمة الملفات") { scope.launch { drawer.open() } }
                        ToolBtn("جديد") { newFileDialog = true }
                        ToolBtn("إعادة تسمية") { val a = active; if (a != null) { renameTo = a; renameDialog = true } }
                        ToolBtn("حذف") { if (active != null) deleteDialog = true }
                        ToolBtn("تصدير .sforge") { exportLauncher.launch("$name.sforge") }
                    }
                    "تحرير" -> {
                        ToolBtn("↶ تراجع") { undo() }
                        ToolBtn("↷ إعادة") { redo() }
                        ToolBtn("تنسيق") {
                            val a = active
                            if (a != null) CodeFormatter.format(a, docs.getValue(a).text)
                                .onSuccess { setText(a, it) }
                                .onFailure { Toast.makeText(ctx, "تعذّر التنسيق: ${it.message}", Toast.LENGTH_SHORT).show() }
                        }
                        ToolBtn("بحث", panel == "search") { panel = if (panel == "search") "" else "search" }
                        ToolBtn("اذهب لسطر") { gotoText = ""; gotoDialog = true }
                    }
                    "فحص" -> {
                        ToolBtn("المشاكل (${issues.size})", panel == "problems") { panel = if (panel == "problems") "" else "problems" }
                        ToolBtn("الخريطة", panel == "graph") { panel = if (panel == "graph") "" else "graph" }
                        ToolBtn("المهام", panel == "tasks") { panel = if (panel == "tasks") "" else "tasks" }
                    }
                    "تشغيل" -> {
                        ToolBtn("تشغيل ▶", panel == "preview") { flush(); logs.clear(); reloadTick++; panel = "preview" }
                        ToolBtn("بناء APK ☁", panel == "build") { panel = if (panel == "build") "" else "build" }
                    }
                    else -> {
                        ToolBtn("الإضافات", panel == "plugins") { panel = if (panel == "plugins") "" else "plugins" }
                        ToolBtn("A−") { settings.fontSize = (fs - 1).coerceAtLeast(10); settings.save() }
                        ToolBtn("A+") { settings.fontSize = (fs + 1).coerceAtMost(24); settings.save() }
                        ToolBtn("خط تلقائي") { settings.fontSize = 0; settings.save() }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(tabs.toList()) { p ->
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(if (p == active) PANEL else Color.Transparent)
                            .border(1.dp, if (p == active) ACCENT else Color(0xFF30363D), RoundedCornerShape(8.dp))
                            .clickable { active = p; panel = "" }.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.substringAfterLast('/'), color = Color.White, fontSize = 12.sp)
                        Text("×", color = Color(0xFF8B949E), fontSize = 16.sp, modifier = Modifier.clickable {
                            runCatching { store.resolve(root, p).writeText(docs[p]?.text.orEmpty()) }
                            tabs.remove(p); docs.remove(p)
                            if (active == p) active = tabs.lastOrNull()
                        }.padding(horizontal = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            if (panel == "preview") {
                val a = active
                val entry = if (a != null && a.endsWith(".html")) File(root, a) else File(root, "src/index.html")
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolBtn("إعادة تحميل") { flush(); logs.clear(); reloadTick++ }
                    ToolBtn("العودة للمحرر") { panel = "" }
                }
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            this.settings.javaScriptEnabled = true
                            this.settings.allowFileAccess = true
                            webViewClient = WebViewClient()
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                    logs.add("${m.messageLevel()}: ${m.message()} (سطر ${m.lineNumber()})"); return true
                                }
                            }
                        }
                    },
                    update = { w ->
                        val key = entry.path + "#" + reloadTick
                        if (w.tag != key) { w.tag = key; w.loadUrl("file://" + entry.absolutePath) }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)
                )
                Column(Modifier.fillMaxWidth().heightIn(max = layout.logMax).background(PANEL).verticalScroll(rememberScrollState()).padding(8.dp)) {
                    if (logs.isEmpty()) Text("سجل وحدة التحكم فارغ", color = Color(0xFF8B949E), fontSize = 12.sp)
                    logs.forEach { Text(it, color = Color(0xFFFFB86C), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                }
            } else {
                if (panel == "search") {
                    Column(Modifier.fillMaxWidth().heightIn(max = layout.panelMax).background(PANEL).padding(8.dp)) {
                        OutlinedTextField(query, { query = it }, label = { Text("بحث") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(replacement, { replacement = it }, label = { Text("استبدال بـ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                            ToolBtn("بحث في المشروع") {
                                results = if (query.isEmpty()) emptyList() else buildList {
                                    for (p in files) {
                                        val f = File(root, p)
                                        val t = docs[p]?.text ?: (if (Sforge.isEditable(f)) f.readText() else continue)
                                        t.lines().forEachIndexed { i, l -> if (l.contains(query)) add(Triple(p, i + 1, l.trim().take(80))) }
                                    }
                                }
                            }
                            ToolBtn("استبدال الكل (الملف الحالي)") {
                                val a = active
                                if (a != null && query.isNotEmpty()) setText(a, docs.getValue(a).text.replace(query, replacement))
                            }
                        }
                        LazyColumn {
                            items(results) { (p, line, snip) ->
                                Text("$p:$line  $snip", Modifier.fillMaxWidth().clickable { jumpTo(p, line) }.padding(vertical = 5.dp),
                                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFC9D1D9))
                            }
                        }
                    }
                }
                if (panel == "problems") {
                    Column(Modifier.fillMaxWidth().heightIn(max = layout.panelMax).background(PANEL).verticalScroll(rememberScrollState()).padding(10.dp)) {
                        if (issues.isEmpty()) Text("لا توجد مشاكل في الملف الحالي", color = Color(0xFF7EE787), fontSize = 13.sp)
                        issues.forEach { i -> Text("سطر ${i.line}: ${i.message}", color = Color(0xFFFF7B72), fontSize = 13.sp, modifier = Modifier.fillMaxWidth().clickable { active?.let { jumpTo(it, i.line) } }.padding(vertical = 4.dp)) }
                    }
                }
                if (panel == "build") {
                    Column(Modifier.fillMaxWidth().heightIn(max = layout.panelMax).background(PANEL).padding(8.dp)) {
                        if (token == null) {
                            Text("للبناء السحابي نحتاج توكن GitHub (Classic) بصلاحيتي repo و workflow. يُخزَّن مشفّرًا على جهازك.", fontSize = 12.sp, color = Color(0xFF8B949E))
                            Row(Modifier.padding(vertical = 6.dp)) {
                                ToolBtn("إنشاء توكن") {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/tokens/new?scopes=repo,workflow&description=Siyaq%20Forge")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                            OutlinedTextField(tokenInput, { tokenInput = it }, label = { Text("الصق التوكن") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            Row(Modifier.padding(top = 6.dp)) {
                                ToolBtn("حفظ") {
                                    val t = tokenInput.trim()
                                    if (t.length >= 20) { SecureStore.save(ctx, t); token = t; tokenInput = "" }
                                    else Toast.makeText(ctx, "التوكن قصير جدًا", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item { ToolBtn(if (building) "جارٍ البناء…" else "ابنِ وثبّت ☁") { if (!building) startBuild() } }
                                if (building) item { ToolBtn("إلغاء") { buildJob?.cancel() } }
                                apk?.let { f -> item { ToolBtn("تثبيت آخر APK") { Installer.install(ctx, f)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } } } }
                                item { ToolBtn("خروج GitHub") { SecureStore.clear(ctx); token = null } }
                            }
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                                if (buildLog.isEmpty()) Text("يولّد مشروع Android، يرفعه إلى مستودع خاص على حسابك، ينتظر Actions، ينزّل الـ APK ويفتح التثبيت.", fontSize = 12.sp, color = Color(0xFF8B949E))
                                buildLog.forEach { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (it.startsWith("✗")) Color(0xFFFF7B72) else if (it.startsWith("✓")) Color(0xFF7EE787) else Color(0xFFC9D1D9)) }
                            }
                        }
                    }
                }
                if (panel == "tasks") {
                    Column(Modifier.fillMaxWidth().heightIn(max = layout.panelMax).background(PANEL).padding(8.dp)) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(Tasks.names(root)) { t -> ToolBtn(if (busy) "…" else t) { if (!busy) runTask(t) } }
                        }
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                            if (taskLog.isEmpty()) Text("اختر مهمة من build/pipeline.toml", color = Color(0xFF8B949E), fontSize = 12.sp)
                            taskLog.forEach { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (it.startsWith("✗")) Color(0xFFFF7B72) else if (it.startsWith("✓")) Color(0xFF7EE787) else Color(0xFFC9D1D9)) }
                        }
                    }
                }
                if (panel == "graph") {
                    Column(Modifier.fillMaxWidth().heightIn(max = layout.panelMax).background(PANEL).verticalScroll(rememberScrollState()).padding(10.dp)) {
                        if (edges.isEmpty()) Text("لا توجد اعتماديات بين الملفات", color = Color(0xFF8B949E), fontSize = 12.sp)
                        edges.forEach { e ->
                            Text("${e.from}  →  ${e.to}" + if (e.exists) "" else "   (مفقود)",
                                Modifier.fillMaxWidth().clickable { openFile(e.from) }.padding(vertical = 4.dp),
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = if (e.exists) Color(0xFFC9D1D9) else Color(0xFFFF7B72))
                        }
                    }
                }
                if (panel == "plugins") {
                    Column(Modifier.fillMaxWidth().heightIn(max = layout.panelMax).background(PANEL).verticalScroll(rememberScrollState()).padding(10.dp)) {
                        ToolBtn("تثبيت إضافة (.json)") { pluginLauncher.launch(arrayOf("*/*")) }
                        Text("الصلاحيات المسموحة: ${Plugins.ALLOWED.joinToString()} — أي صلاحية أخرى (ملفات/شبكة/تنفيذ) تُرفض.", fontSize = 11.sp, color = Color(0xFF8B949E), modifier = Modifier.padding(vertical = 6.dp))
                        plugins.forEach { p ->
                            Text("${if (p.active) "●" else "○"} ${p.id} ${p.version} — ${p.note}", fontSize = 13.sp, color = if (p.active) Color(0xFF7EE787) else Color(0xFFFF7B72), modifier = Modifier.padding(vertical = 3.dp))
                        }
                    }
                }
                val a = active
                if (a != null && docs.containsKey(a)) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        val tv = docs.getValue(a)
                        val text = tv.text
                        val hs = rememberScrollState()
                        val lineCount = text.count { it == '\n' } + 1
                        val gutterW = with(density) { (lineCount.toString().length.coerceAtLeast(2) * fs * 0.62f + 14f).sp.toDp() }
                        val highlight = remember(a) { Highlight(a) }
                        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vs)) {
                            Text(
                                (1..(text.count { it == '\n' } + 1)).joinToString("\n"),
                                style = monoStyle.copy(color = Color(0xFF6E7681), textAlign = TextAlign.End),
                                modifier = Modifier.width(gutterW).padding(end = 8.dp, top = 8.dp)
                            )
                            Box(Modifier.weight(1f).horizontalScroll(hs)) {
                                BasicTextField(
                                    value = tv,
                                    onValueChange = { v ->
                                        val old = docs.getValue(a)
                                        val nv = Typing.transform(old, v, settings.autoIndent, settings.autoClose)
                                        if (nv.text != old.text) pushUndo(a, old.text)
                                        docs[a] = nv
                                    },
                                    textStyle = monoStyle, cursorBrush = SolidColor(ACCENT),
                                    visualTransformation = highlight,
                                    modifier = Modifier.widthIn(min = (layout.widthDp - 90).coerceAtLeast(120).dp).padding(top = 8.dp, end = 16.dp, bottom = 48.dp)
                                )
                            }
                        }
                        val sugs = Completion.suggest(a, text, tv.selection.start, plugins)
                        if (sugs.isNotEmpty()) LazyRow(Modifier.fillMaxWidth().background(PANEL).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(sugs) { sg ->
                                ToolBtn(sg.label) {
                                    val c = docs.getValue(a)
                                    val cur = c.selection.start.coerceIn(0, c.text.length)
                                    val st = Completion.prefixStart(c.text, cur)
                                    pushUndo(a, c.text, true)
                                    val nt = c.text.substring(0, st) + sg.insert + c.text.substring(cur)
                                    docs[a] = TextFieldValue(nt, TextRange(st + sg.insert.length))
                                }
                            }
                        }
                        val before = text.substring(0, tv.selection.start.coerceIn(0, text.length))
                        val ln = before.count { it == '\n' } + 1
                        val col = before.length - (before.lastIndexOf('\n') + 1) + 1
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                            Text(
                                "سطر $ln، عمود $col · ${text.length} حرف · ${a.substringAfterLast('.', "txt").uppercase()}",
                                fontSize = 11.sp, color = Color(0xFF8B949E), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().background(PANEL).padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("افتح ملفًا من «الملفات»", color = Color(0xFF8B949E))
                    }
                }
            }
        }
    }

    if (newFileDialog) AlertDialog(
        onDismissRequest = { newFileDialog = false },
        title = { Text("ملف جديد") },
        text = { OutlinedTextField(newFileName, { newFileName = it }, singleLine = true, label = { Text("المسار داخل المشروع") }) },
        confirmButton = {
            TextButton(onClick = {
                val r = runCatching {
                    val f = store.resolve(root, newFileName.trim())
                    require(!f.exists()) { "الملف موجود" }
                    f.parentFile?.mkdirs(); f.createNewFile()
                }
                if (r.isSuccess) { files = Sforge.listFiles(root); openFile(newFileName.trim()); newFileDialog = false }
                else Toast.makeText(ctx, r.exceptionOrNull()?.message ?: "فشل", Toast.LENGTH_SHORT).show()
            }) { Text("إنشاء") }
        },
        dismissButton = { TextButton(onClick = { newFileDialog = false }) { Text("إلغاء") } }
    )

    if (deleteDialog) AlertDialog(
        onDismissRequest = { deleteDialog = false },
        title = { Text("حذف ${active.orEmpty()}؟") },
        text = { Text("لا يمكن التراجع عن الحذف.") },
        confirmButton = {
            TextButton(onClick = {
                val a = active
                if (a != null) {
                    runCatching { store.resolve(root, a).delete() }
                    tabs.remove(a); docs.remove(a); active = tabs.lastOrNull()
                    files = Sforge.listFiles(root)
                }
                deleteDialog = false
            }) { Text("حذف") }
        },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("إلغاء") } }
    )

    if (renameDialog) AlertDialog(
        onDismissRequest = { renameDialog = false },
        title = { Text("إعادة تسمية الملف") },
        text = { OutlinedTextField(renameTo, { renameTo = it }, singleLine = true, label = { Text("المسار الجديد") }) },
        confirmButton = {
            TextButton(onClick = {
                val a = active
                val nt = renameTo.trim()
                if (a != null && nt.isNotEmpty() && nt != a) {
                    val r = runCatching {
                        flush()
                        val from = store.resolve(root, a)
                        val to = store.resolve(root, nt)
                        require(!to.exists()) { "الاسم مستخدم" }
                        to.parentFile?.mkdirs()
                        require(from.renameTo(to)) { "فشلت إعادة التسمية" }
                    }
                    if (r.isSuccess) {
                        val idx = tabs.indexOf(a)
                        val d = docs.remove(a)
                        if (idx >= 0) tabs[idx] = nt else tabs.add(nt)
                        if (d != null) docs[nt] = d
                        undoS.remove(a)?.let { undoS[nt] = it }
                        redoS.remove(a)?.let { redoS[nt] = it }
                        active = nt
                        files = Sforge.listFiles(root)
                        renameDialog = false
                    } else Toast.makeText(ctx, r.exceptionOrNull()?.message ?: "فشل", Toast.LENGTH_SHORT).show()
                } else renameDialog = false
            }) { Text("تم") }
        },
        dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("إلغاء") } }
    )

    if (gotoDialog) AlertDialog(
        onDismissRequest = { gotoDialog = false },
        title = { Text("اذهب إلى سطر") },
        text = { OutlinedTextField(gotoText, { gotoText = it.filter { c -> c.isDigit() } }, singleLine = true, label = { Text("رقم السطر") }) },
        confirmButton = {
            TextButton(onClick = {
                val n = gotoText.toIntOrNull()
                val a = active
                if (n != null && a != null) jumpTo(a, n.coerceAtLeast(1))
                gotoDialog = false
            }) { Text("اذهب") }
        },
        dismissButton = { TextButton(onClick = { gotoDialog = false }) { Text("إلغاء") } }
    )
}
