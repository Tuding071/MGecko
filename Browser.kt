package com.forge.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.*
import java.util.UUID

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  DATA
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

data class Feature(val id: String, val name: String, val icon: String, val code: String)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  BROWSER API  — exposed to every feature via WebView bridge
//  In feature JS:  BrowserAPI.navigate("https://...")
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

class BrowserAPI(
    private val act: MainActivity,
    private val session: GeckoSession,
    private val runtime: GeckoRuntime
) {
    private var sheet: BottomSheetDialog? = null
    fun attachSheet(s: BottomSheetDialog) { sheet = s }

    // ── Navigation ───────────────────────────────────────
    @JavascriptInterface fun navigate(url: String) = act.runOnUiThread { act.loadUrl(url) }
    @JavascriptInterface fun goBack()              = act.runOnUiThread { session.goBack() }
    @JavascriptInterface fun goForward()           = act.runOnUiThread { session.goForward() }
    @JavascriptInterface fun reload()              = act.runOnUiThread { session.reload() }
    @JavascriptInterface fun stop()                = act.runOnUiThread { session.stop() }

    // ── Page info ────────────────────────────────────────
    @JavascriptInterface fun getCurrentUrl(): String = act.currentUrl
    @JavascriptInterface fun getTitle(): String      = act.currentTitle

    // ── History ──────────────────────────────────────────
    @JavascriptInterface fun getHistory(): String = act.historyJson()
    @JavascriptInterface fun clearHistory()       = act.runOnUiThread { act.clearHistory() }

    // ── Storage ──────────────────────────────────────────
    @JavascriptInterface fun clearCookies() = runtime.storageController.clearData(StorageController.ClearFlags.COOKIES)
    @JavascriptInterface fun clearCache()   = runtime.storageController.clearData(StorageController.ClearFlags.NETWORK_CACHE)
    @JavascriptInterface fun clearAll()     = runtime.storageController.clearData(StorageController.ClearFlags.ALL)

    // ── Settings ─────────────────────────────────────────
    @JavascriptInterface fun setDesktopMode(on: Boolean) = act.runOnUiThread {
        session.settings.userAgentMode =
            if (on) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else    GeckoSessionSettings.USER_AGENT_MODE_MOBILE
    }
    @JavascriptInterface fun setTracking(on: Boolean) = act.runOnUiThread {
        session.settings.useTrackingProtection = on
    }

    // ── UI helpers ───────────────────────────────────────
    @JavascriptInterface fun closeFeature()     = act.runOnUiThread { sheet?.dismiss() }
    @JavascriptInterface fun toast(msg: String) = act.runOnUiThread {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
    }

    // ── Feature management (from inside a feature) ───────
    @JavascriptInterface fun getFeatures(): String = act.featureRuntime.toJson()
    @JavascriptInterface fun removeFeature(id: String) = act.runOnUiThread {
        act.featureRuntime.remove(id)
        Toast.makeText(act, "Removed", Toast.LENGTH_SHORT).show()
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  FEATURE RUNTIME  — install / persist / list features
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

class FeatureRuntime(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("forge_features", Context.MODE_PRIVATE)
    val list = mutableListOf<Feature>()

    init { load() }

    private fun load() {
        list.clear()
        val arr = JSONArray(prefs.getString("list", "[]") ?: "[]")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Feature(o.getString("id"), o.getString("name"), o.getString("icon"), o.getString("code")))
        }
    }

    private fun save() {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(JSONObject()
                .put("id", f.id).put("name", f.name)
                .put("icon", f.icon).put("code", f.code))
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun install(code: String) {
        val name = meta(code, "name") ?: "Feature ${list.size + 1}"
        val icon = meta(code, "icon") ?: "⚙️"
        list.add(Feature(UUID.randomUUID().toString(), name, icon, code))
        save()
    }

    fun remove(id: String) { list.removeAll { it.id == id }; save() }

    fun toJson(): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon)) }
        return arr.toString()
    }

    // Parses  // @name  My Feature
    private fun meta(code: String, key: String) =
        code.lines().firstOrNull { it.trimStart().startsWith("// @$key") }
            ?.substringAfter("@$key")?.trim()
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  MAIN ACTIVITY
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

class MainActivity : AppCompatActivity() {

    private lateinit var runtime: GeckoRuntime
    private lateinit var session: GeckoSession
    lateinit var api: BrowserAPI
    lateinit var featureRuntime: FeatureRuntime

    // State — readable by BrowserAPI
    var currentUrl   = ""
    var currentTitle = ""
    private val history = mutableListOf<Pair<String, String>>()   // url to title

    fun historyJson(): String {
        val arr = JSONArray()
        history.take(200).forEach { (u, t) ->
            arr.put(JSONObject().put("url", u).put("title", t))
        }
        return arr.toString()
    }
    fun clearHistory() { history.clear() }

    // Top bar refs (updated by delegates)
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: TextView
    private lateinit var btnForward: TextView

    // ── onCreate ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runtime        = GeckoRuntime.create(this)
        session        = GeckoSession(GeckoSessionSettings.Builder()
                             .useTrackingProtection(true).build())
        session.open(runtime)
        featureRuntime = FeatureRuntime(this)
        api            = BrowserAPI(this, session, runtime)

        setContentView(buildLayout())
        setupDelegates()
        session.loadUri("https://google.com")
    }

    // ── Layout (fully programmatic — no XML) ─────────────────────────────────
    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
        }

        // ── Top chrome bar ──────────────────────────────────────────────────
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            gravity = Gravity.CENTER_VERTICAL
        }

        btnBack    = navBtn("←") { session.goBack() }
        btnForward = navBtn("→") { session.goForward() }
        val btnReload = navBtn("↺") { session.reload() }
        val btnMenu   = navBtn("⋮") { showMenu() }

        btnBack.alpha    = 0.3f
        btnForward.alpha = 0.3f

        urlBar = EditText(this).apply {
            hint = "Search or enter URL"
            setHintTextColor(0xFF555555.toInt())
            setTextColor(Color.WHITE)
            background = null
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> loadUrl(text.toString()); true }
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max        = 100
            visibility = View.INVISIBLE
        }

        topBar.addView(btnBack,    lp(44.dp, 44.dp))
        topBar.addView(btnForward, lp(44.dp, 44.dp))
        topBar.addView(btnReload,  lp(44.dp, 44.dp))
        topBar.addView(urlBar,     LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(btnMenu,    lp(44.dp, 44.dp))

        val gecko = GeckoView(this).apply { setSession(session) }

        root.addView(topBar,       lp(-1, -2))
        root.addView(progressBar,  lp(-1, 4))
        root.addView(gecko,        LinearLayout.LayoutParams(-1, 0, 1f))

        return root
    }

    // ── GeckoView delegates ───────────────────────────────────────────────────
    private fun setupDelegates() {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                s: GeckoSession, url: String?,
                perms: List<ContentPermission>
            ) {
                currentUrl = url ?: ""
                runOnUiThread { urlBar.setText(currentUrl) }
            }
            override fun onCanGoBack(s: GeckoSession, can: Boolean) {
                runOnUiThread { btnBack.alpha = if (can) 1f else 0.3f }
            }
            override fun onCanGoForward(s: GeckoSession, can: Boolean) {
                runOnUiThread { btnForward.alpha = if (can) 1f else 0.3f }
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) =
                runOnUiThread { progressBar.visibility = View.VISIBLE; progressBar.progress = 5 }
            override fun onProgressChange(s: GeckoSession, p: Int) =
                runOnUiThread { progressBar.progress = p }
            override fun onPageStop(s: GeckoSession, ok: Boolean) =
                runOnUiThread { progressBar.visibility = View.INVISIBLE }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(s: GeckoSession, title: String?) {
                currentTitle = title ?: ""
                if (currentUrl.isNotEmpty()) {
                    history.removeAll { it.first == currentUrl }
                    history.add(0, currentUrl to currentTitle)
                    if (history.size > 500) history.removeLast()
                }
            }
            override fun onCrash(s: GeckoSession) { s.open(runtime) }
            override fun onKill(s:  GeckoSession) { s.open(runtime) }
        }

        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                s: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ) = GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT)
        }
    }

    // ── Menu ─────────────────────────────────────────────────────────────────
    private fun showMenu() {
        val features = featureRuntime.list
        val labels   = arrayOf("＋  Add Feature") +
                       features.map { "${it.icon}  ${it.name}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setItems(labels) { _, i ->
                if (i == 0) showAddDialog()
                else        launchFeature(features[i - 1])
            }
            .show()
    }

    private fun showAddDialog() {
        val et = EditText(this).apply {
            hint = "// @name  History\n// @icon  📋\n\nfunction onOpen() {\n  // use BrowserAPI.*\n}"
            setHintTextColor(0xFF444444.toInt())
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            minLines  = 10
            gravity   = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle("Add Feature")
            .setView(et)
            .setPositiveButton("Install") { _, _ ->
                val code = et.text.toString().trim()
                if (code.isNotEmpty()) {
                    featureRuntime.install(code)
                    Toast.makeText(this, "✓ Feature installed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Feature launcher ─────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun launchFeature(feature: Feature) {
        val sheet = BottomSheetDialog(this)
        api.attachSheet(sheet)

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(api, "BrowserAPI")
        }

        // Feature code runs inside this HTML shell
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                  background: #1a1a1a;
                  color: #eeeeee;
                  font-family: sans-serif;
                  font-size: 15px;
                  padding: 16px;
                  min-height: 100vh;
                }
                a    { color: #6ab4ff; cursor: pointer; }
                button, .btn {
                  background: #2e2e2e;
                  color: #fff;
                  border: 1px solid #444;
                  padding: 8px 16px;
                  border-radius: 8px;
                  cursor: pointer;
                  font-size: 14px;
                }
                input, textarea {
                  background: #2a2a2a;
                  color: #fff;
                  border: 1px solid #444;
                  padding: 8px 12px;
                  border-radius: 6px;
                  width: 100%;
                  margin: 4px 0;
                }
                hr  { border-color: #333; margin: 12px 0; }
                h2  { font-size: 16px; margin-bottom: 12px; color: #fff; }
                h3  { font-size: 14px; color: #aaa; }
              </style>
            </head>
            <body>
              <script>
                ${feature.code}
                if (typeof onOpen === 'function') onOpen();
              </script>
            </body>
            </html>
        """.trimIndent()

        wv.loadDataWithBaseURL("https://browser.local/", html, "text/html", "UTF-8", null)
        sheet.setContentView(wv)
        sheet.show()
    }

    // ── URL loading ──────────────────────────────────────────────────────────
    fun loadUrl(input: String) {
        val trimmed = input.trim()
        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ")                  -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        }
        session.loadUri(url)
        hideKeyboard()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    override fun onBackPressed() { session.goBack() }

    override fun onDestroy() {
        super.onDestroy()
        session.close()
        runtime.shutdown()
    }

    private fun hideKeyboard() {
        currentFocus?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun navBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text      = label
        textSize  = 20f
        setTextColor(Color.WHITE)
        gravity   = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density + 0.5f).toInt()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//  FEATURE TEMPLATE — paste this into "Add Feature"
//
//  // @name  History
//  // @icon  📋
//
//  function onOpen() {
//      const items = JSON.parse(BrowserAPI.getHistory())
//      items.forEach(item => {
//          const div = document.createElement('div')
//          div.style = 'padding:12px;border-bottom:1px solid #333;cursor:pointer'
//          div.innerHTML = '<b>' + item.title + '</b><br><small>' + item.url + '</small>'
//          div.onclick = () => { BrowserAPI.navigate(item.url); BrowserAPI.closeFeature() }
//          document.body.appendChild(div)
//      })
//  }
//
//  FULL BrowserAPI SURFACE:
//    navigate(url)        goBack()          goForward()
//    reload()             stop()            getCurrentUrl()
//    getTitle()           getHistory()      clearHistory()
//    clearCookies()       clearCache()      clearAll()
//    setDesktopMode(bool) setTracking(bool)
//    closeFeature()       toast(msg)
//    getFeatures()        removeFeature(id)
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
