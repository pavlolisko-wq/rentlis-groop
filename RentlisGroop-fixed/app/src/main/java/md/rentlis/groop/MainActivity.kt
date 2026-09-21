package md.rentlis.groop

import android.Manifest
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooser: ActivityResultLauncher<Intent>
    private lateinit var root: FrameLayout
    private var insetsJs: String = ""

    // App lock state
    private var stoppedAt = 0L
    private var pickerOpen = false
    private var relockPending = false
    private var bioShowing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: the app draws behind the status bar / cutout / nav bar,
        // and the page receives the real insets (works with or without a notch).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        // Receives the picked image and hands it back to the WebView <input type="file">
        fileChooser = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = filePathCallback
            filePathCallback = null
            if (cb != null) {
                cb.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                )
            }
        }

        // Android 13+: ask for notification permission (payment day reminders)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        web = WebView(this)
        root = FrameLayout(this)
        root.setBackgroundColor(0xFF082B23.toInt())
        root.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val d = resources.displayMetrics.density
            // keyboard open: shrink the WebView, page needs no extra bottom inset
            v.setPadding(0, 0, 0, ime.bottom)
            val bottom = if (ime.bottom > 0) 0 else Math.round(bars.bottom / d)
            insetsJs = "window.setInsets&&window.setInsets(" + Math.round(bars.top / d) + "," +
                Math.round(bars.right / d) + "," + bottom + "," + Math.round(bars.left / d) + ")"
            web.evaluateJavascript(insetsJs, null)
            WindowInsetsCompat.CONSUMED
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (insetsJs.isNotEmpty()) view?.evaluateJavascript(insetsJs, null)
            }
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = false
            allowContentAccess = false
        }
        web.setBackgroundColor(0xFF0C0D10.toInt())

        // Enable file/photo picker inside the WebView
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                return try {
                    pickerOpen = true
                    fileChooser.launch(intent)
                    true
                } catch (e: Exception) {
                    pickerOpen = false
                    filePathCallback = null
                    false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "RentlisNative")
        Reminders.schedule(this)
        web.loadUrl("file:///android_asset/rentmaster.html")

        // System back: let the page close sheet / detail / go to the previous page first
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.appBack&&window.appBack())?'1':'0'") { r ->
                    if (r != null && r.contains("1")) return@evaluateJavascript
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Opened from a payment notification: let the page show the confirmation sheet
        web.evaluateJavascript("window.checkDue && window.checkDue()", null)
    }

    // ---------------------------------------------------------------- app lock

    override fun onPause() {
        super.onPause()
        // hide the page while the app is not in front (recent-apps thumbnail, no flash on return)
        if (lockEnabled()) web.visibility = View.INVISIBLE
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (pickerOpen) {           // returning from the photo picker: do not lock
            pickerOpen = false
            return
        }
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > 15_000 && lockEnabled()) {
            relockPending = true
            web.evaluateJavascript("window.lockNow&&window.lockNow()") { showWeb() }
            web.postDelayed({ showWeb() }, 1200)   // safety net
        }
    }

    override fun onResume() {
        super.onResume()
        if (!relockPending) showWeb()
    }

    private fun showWeb() {
        relockPending = false
        web.visibility = View.VISIBLE
    }

    private fun lockPrefs() = applicationContext.getSharedPreferences("lock", MODE_PRIVATE)

    private fun lockEnabled(): Boolean = !lockPrefs().getString("hash", null).isNullOrEmpty()

    private fun bioAvail(): Boolean = try {
        BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    } catch (e: Exception) {
        false
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 20000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** Checks the PIN; 5 wrong tries in a row start a growing 30 s * n delay. */
    private fun verifyPin(pin: String): Boolean {
        val p = lockPrefs()
        val now = System.currentTimeMillis()
        if (now < p.getLong("until", 0L)) return false
        val h = p.getString("hash", null) ?: return false
        val s = p.getString("salt", null) ?: return false
        val ok = MessageDigest.isEqual(
            hashPin(pin, Base64.decode(s, Base64.NO_WRAP)),
            Base64.decode(h, Base64.NO_WRAP)
        )
        val e = p.edit()
        if (ok) {
            e.putInt("fails", 0).putLong("until", 0L)
        } else {
            val f = p.getInt("fails", 0) + 1
            e.putInt("fails", f)
            if (f >= 5) e.putLong("until", now + 30_000L * (f - 4))
        }
        e.apply()
        return ok
    }

    private fun showBio(title: String, cancel: String) {
        if (bioShowing || !bioAvail()) return
        bioShowing = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    bioShowing = false
                    lockPrefs().edit().putInt("fails", 0).putLong("until", 0L).apply()
                    web.evaluateJavascript("window.lockOk&&window.lockOk()", null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    bioShowing = false   // user cancelled / chose the PIN pad
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(cancel)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            bioShowing = false
        }
    }

    /** Called from rentmaster.html to hand over reminder data. */
    inner class Bridge {
        /** Full app-data backup, in case WebView storage is ever cleared. */
        @JavascriptInterface
        fun backup(json: String) {
            applicationContext.getSharedPreferences("backup", MODE_PRIVATE).edit().putString("data", json).apply()
        }

        @JavascriptInterface
        fun restore(): String {
            return applicationContext.getSharedPreferences("backup", MODE_PRIVATE).getString("data", "") ?: ""
        }

        /** Dark theme -> light status/navigation icons, light theme -> dark icons. */
        @JavascriptInterface
        fun bars(dark: Boolean) {
            runOnUiThread {
                val c = WindowCompat.getInsetsController(window, web)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
                root.setBackgroundColor(if (dark) 0xFF082B23.toInt() else 0xFFF6F1E7.toInt())
            }
        }

        @JavascriptInterface
        fun sync(json: String) {
            Reminders.save(applicationContext, json)
            Reminders.schedule(applicationContext)
        }

        // ---- app lock (PIN is stored only as a salted PBKDF2 hash, never in the exported data) ----

        /** "off" | "pin" | "pin+bio" */
        @JavascriptInterface
        fun lockState(): String {
            if (!lockEnabled()) return "off"
            return if (lockPrefs().getBoolean("bio", false) && bioAvail()) "pin+bio" else "pin"
        }

        @JavascriptInterface
        fun bioAvailable(): Boolean = bioAvail()

        @JavascriptInterface
        fun checkPin(pin: String): Boolean = verifyPin(pin)

        /** Seconds left of the "too many attempts" delay, 0 when none. */
        @JavascriptInterface
        fun lockWait(): Int {
            val ms = lockPrefs().getLong("until", 0L) - System.currentTimeMillis()
            return if (ms > 0) ((ms + 999) / 1000).toInt() else 0
        }

        /** Sets or changes the PIN. oldPin is required (and checked) when a PIN already exists. */
        @JavascriptInterface
        fun setPin(oldPin: String, newPin: String): Boolean {
            if (!Regex("[0-9]{4}").matches(newPin)) return false
            val had = lockEnabled()
            if (had && !verifyPin(oldPin)) return false
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)
            val e = lockPrefs().edit()
            e.putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            e.putString("hash", Base64.encodeToString(hashPin(newPin, salt), Base64.NO_WRAP))
            e.putInt("fails", 0).putLong("until", 0L)
            if (!had) e.putBoolean("bio", bioAvail())
            e.apply()
            return true
        }

        @JavascriptInterface
        fun clearLock(pin: String): Boolean {
            if (!lockEnabled() || !verifyPin(pin)) return false
            lockPrefs().edit().clear().apply()
            return true
        }

        @JavascriptInterface
        fun setBio(on: Boolean) {
            if (lockEnabled()) lockPrefs().edit().putBoolean("bio", on && bioAvail()).apply()
        }

        @JavascriptInterface
        fun promptBio(title: String, cancel: String) {
            runOnUiThread { showBio(title, cancel) }
        }
    }
}
