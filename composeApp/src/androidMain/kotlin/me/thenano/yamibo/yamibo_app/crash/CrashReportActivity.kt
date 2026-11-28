package me.thenano.yamibo.yamibo_app.crash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import me.thenano.yamibo.yamibo_app.components.theme.ensureContrastOn
import me.thenano.yamibo.yamibo_app.repository.scheme.YamiboColorScheme
import me.thenano.yamibo.yamibo_app.repository.settings.AppSettingsRepository
import me.thenano.yamibo.yamibo_app.store.settings.AndroidSettingsStore
import kotlin.math.roundToInt

/** A native dialog in a separate process, independent of Reader and its Compose tree. */
class CrashReportActivity : Activity() {
    private var expanded = false
    private var report: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = AndroidCrashReport.readPending(this)
        if (text == null) {
            finish()
            return
        }
        report = text
        expanded = savedInstanceState?.getBoolean("expanded") == true
        val scheme = runCatching {
            AppSettingsRepository(AndroidSettingsStore(this)).themeScheme.getValue().toScheme()
        }.getOrDefault(YamiboColorScheme.Default)
        val surface = Color(scheme.creamSurface)
        val inkColor = Color(scheme.textDark).ensureContrastOn(surface, 4.5)
        val ink = inkColor.toArgb()
        val actionColor = Color(scheme.brownPrimary).ensureContrastOn(surface, 4.5).toArgb()
        fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(10))
        }
        fun label(value: String, size: Float) = TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(ink)
            content.addView(this)
        }
        fun action(value: String, click: (() -> Unit)? = null) =
            Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                this.text = value
                isAllCaps = false
                isEnabled = click != null
                setTextColor(if (isEnabled) actionColor else inkColor.copy(alpha = .5f).toArgb())
                if (click != null) setOnClickListener { click() }
            }
        label("App 意外關閉", 21f).setTypeface(null, Typeface.BOLD)
        label("可以複製這次的日誌，協助開發者找出原因。", 15f)
            .setPadding(0, dp(8), 0, 0)
        label(text.substringAfter("\n\n").lineSequence().firstOrNull().orEmpty().take(180), 15f)
            .setPadding(0, dp(10), 0, dp(4))
        lateinit var details: ScrollView
        lateinit var toggle: Button
        fun showDetails() {
            toggle.text = if (expanded) "收起日誌" else "查看日誌"
            details.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        toggle = action("查看日誌") {
            expanded = !expanded
            showDetails()
        }
        content.addView(toggle)
        details = ScrollView(this).apply {
            setBackgroundColor(scheme.creamBackground.toInt())
            addView(TextView(this@CrashReportActivity).apply {
                this.text = text
                textSize = 12f
                setTextColor(ink)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            })
        }
        content.addView(details, -1, minOf(dp(230), resources.displayMetrics.heightPixels / 4))
        showDetails()
        content.addView(action("上傳給開發者"))
        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        val copy = action("複製日誌") {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Yamibo 崩潰日誌", text))
            if (android.os.Build.VERSION.SDK_INT < 33) {
                Toast.makeText(this, "已複製日誌", Toast.LENGTH_SHORT).show()
            }
        }
        actions.addView(copy)
        actions.addView(action("關閉") { finish() })
        content.addView(actions)
        setContentView(ScrollView(this).apply { addView(content) })
        window.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(surface.toArgb())
        })
        val width = (resources.displayMetrics.widthPixels - dp(40)).coerceIn(dp(200), dp(440))
        window.setLayout(width, WRAP_CONTENT)
        setFinishOnTouchOutside(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("expanded", expanded)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recreate()
    }

    override fun finish() {
        report?.let { AndroidCrashReport.markViewed(this, it) }
        super.finish()
    }
}
