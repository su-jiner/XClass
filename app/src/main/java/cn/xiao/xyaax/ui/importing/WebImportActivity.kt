package cn.xiao.xyaax.ui.importing

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cn.xiao.xyaax.R
import cn.xiao.xyaax.data.AppPreferences
import cn.xiao.xyaax.importing.web.WebCompatDelegate
import cn.xiao.xyaax.ui.theme.ThemePaletteProvider
import cn.xiao.xyaax.ui.theme.UiTransitions
import cn.xiao.xyaax.ui.theme.applySystemBarIcons
import cn.xiao.xyaax.ui.theme.applySystemBarPadding

/**
 * 教务网页导入（P3）：WebView 加载教务系统 → 桌面视口模式（移植自 SleepDown WebCompatDelegate）→
 * 登录后在网页课表页点「提取本页课表文本」→ 文本回传导入向导复用统一解析管线（预览 → 确认 → 入库）。
 */
@SuppressLint("SetJavaScriptEnabled")
class WebImportActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var hintText: TextView
    private lateinit var extractButton: Button
    private var delegate: WebCompatDelegate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePaletteProvider.applyNightMode(this)
        setTheme(ThemePaletteProvider.themeStyleFor(AppPreferences.getThemeColor(this)))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_import)
        findViewById<View>(R.id.webImportRoot).applySystemBarPadding()
        val palette = ThemePaletteProvider.fromContext(this)
        window.applySystemBarIcons(palette.isDark)
        UiTransitions.applyEnter(this)

        urlInput = findViewById(R.id.webImportUrl)
        hintText = findViewById(R.id.webImportHint)
        extractButton = findViewById(R.id.webImportExtractBtn)
        webView = findViewById(R.id.webImportView)

        findViewById<View>(R.id.webImportBackBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.webImportGoBtn).setOnClickListener { loadCurrentUrl() }
        urlInput.setOnEditorActionListener { _, _, _ ->
            loadCurrentUrl()
            true
        }
        extractButton.setOnClickListener { extractPageText() }
        extractButton.isEnabled = false

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                delegate?.onPageFinished()
                extractButton.isEnabled = true
                hintText.text = getString(R.string.web_import_ready)
            }
        }
        // 默认桌面视口（教务系统多为 PC 版页面）
        delegate = WebCompatDelegate(webView, desktop = true)

        intent.getStringExtra(EXTRA_INITIAL_URL)?.let { load(it) }
    }

    private fun loadCurrentUrl() {
        val raw = urlInput.text.toString().trim()
        if (raw.isBlank()) return
        load(if (raw.startsWith("http")) raw else "https://$raw")
    }

    private fun load(url: String) {
        urlInput.setText(url)
        hintText.text = getString(R.string.web_import_loading)
        extractButton.isEnabled = false
        delegate?.loadInitialUrl(url)
    }

    /** 提取当前页面可见文本（含表格行），回传导入向导解析。 */
    private fun extractPageText() {
        webView.evaluateJavascript(EXTRACT_SCRIPT) { raw ->
            val text = raw?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
                ?.removePrefix("\"")
                ?.removeSuffix("\"")
                ?.takeIf { it.isNotBlank() }
            if (text.isNullOrBlank()) {
                hintText.text = getString(R.string.web_import_empty)
                return@evaluateJavascript
            }
            val result = Intent().apply {
                putExtra(ImportWizardActivity.EXTRA_PASTE_TEXT, text)
                putExtra(ImportWizardActivity.EXTRA_IMPORTED, -1)
            }
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }

    override fun finish() {
        super.finish()
        delegate?.dispose()
        webView.destroy()
        UiTransitions.applyExit(this)
    }

    companion object {
        const val EXTRA_INITIAL_URL = "extra_initial_url"

        /** 抓取 body 文本：优先表格单元格，保留换行；忽略脚本/样式。 */
        private val EXTRACT_SCRIPT = """
            (function () {
                var tables = document.querySelectorAll('table');
                var parts = [];
                tables.forEach(function (t) {
                    t.querySelectorAll('tr').forEach(function (tr) {
                        var cells = [];
                        tr.querySelectorAll('td,th').forEach(function (c) {
                            var v = (c.innerText || '').trim().replace(/\s+/g, ' ');
                            cells.push(v);
                        });
                        if (cells.length > 1) parts.push(cells.join('\t'));
                    });
                });
                if (parts.length === 0) {
                    var body = document.body ? (document.body.innerText || '') : '';
                    parts.push(body.replace(/\n{3,}/g, '\n\n').trim());
                }
                return parts.join('\n');
            })();
        """.trimIndent()
    }
}
