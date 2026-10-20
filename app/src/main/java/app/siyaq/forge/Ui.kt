package app.siyaq.forge

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** إعدادات المستخدم (محفوظة محليًا). fontSize = 0 يعني تلقائي حسب عرض الشاشة. */
class AppSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("forge_settings", Context.MODE_PRIVATE)
    var fontSize by mutableStateOf(p.getInt("font", 0))
    var autoIndent by mutableStateOf(p.getBoolean("indent", true))
    var autoClose by mutableStateOf(p.getBoolean("close", true))
    fun save() {
        p.edit().putInt("font", fontSize).putBoolean("indent", autoIndent).putBoolean("close", autoClose).apply()
    }
}

val LocalSettings = staticCompositionLocalOf<AppSettings> { error("AppSettings غير موفّرة") }

/** مقاسات الشاشة الحالية؛ كل الأحجام المتجاوبة تُشتق منها بدل أرقام ثابتة. */
data class LayoutInfo(val widthDp: Int, val heightDp: Int) {
    val landscape: Boolean get() = widthDp > heightDp
    val panelMax: Dp get() = (heightDp * (if (landscape) 0.45f else 0.34f)).dp
    val logMax: Dp get() = (heightDp * 0.15f).dp
    fun editorFont(user: Int): Int = if (user > 0) user else (widthDp / 28).coerceIn(12, 16)
}

@Composable
fun rememberLayoutInfo(): LayoutInfo {
    val c = LocalConfiguration.current
    return LayoutInfo(c.screenWidthDp, c.screenHeightDp)
}
