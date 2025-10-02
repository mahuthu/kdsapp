package com.passoassist.app

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabRefresh: FloatingActionButton
    private lateinit var fabScan: FloatingActionButton
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var toggle: ActionBarDrawerToggle

    private var baseUrl: String = ""
    private var protocol: String = "https"
    private var domain: String = ""
    private var port: String = ""

    companion object {
        private const val PREFS_NAME = "PassoAssistPrefs"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_PORT = "port"
        private const val KEY_BASE_URL = "base_url"
    }

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
        } else {
            handleScannedUrl(result.contents)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startQRScanner()
        } else {
            Toast.makeText(this, "Camera permission required for QR scanning", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupSharedPreferences()
        setupToolbarAndDrawer()
        setupWebView()
        setupFABs()
        loadSavedUrl()
    }

    private fun initializeViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progress_bar)
        fabRefresh = findViewById(R.id.fab_refresh)
        fabScan = findViewById(R.id.fab_scan)

        val navigationView: NavigationView = findViewById(R.id.nav_view)
        navigationView.setNavigationItemSelectedListener(this)
    }

    private fun setupSharedPreferences() {
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        protocol = sharedPreferences.getString(KEY_PROTOCOL, "https") ?: "https"
        domain = sharedPreferences.getString(KEY_DOMAIN, "") ?: ""
        port = sharedPreferences.getString(KEY_PORT, "") ?: ""
        baseUrl = sharedPreferences.getString(KEY_BASE_URL, "") ?: ""
    }

    private fun setupToolbarAndDrawer() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.app_name)

        toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                progressBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Error loading page: ${error?.description}", Toast.LENGTH_SHORT).show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                if (newProgress == 100) {
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun setupFABs() {
        fabRefresh.setOnClickListener {
            if (webView.url != null) {
                webView.reload()
            } else if (baseUrl.isNotEmpty()) {
                webView.loadUrl(baseUrl)
            } else {
                Toast.makeText(this, "No URL to refresh. Please scan a QR code first.", Toast.LENGTH_SHORT).show()
            }
        }

        fabScan.setOnClickListener {
            checkCameraPermissionAndScan()
        }
    }

    private fun checkCameraPermissionAndScan() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startQRScanner()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startQRScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan QR Code for URL")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(true)
            setOrientationLocked(false)
        }
        barcodeLauncher.launch(options)
    }

    private fun handleScannedUrl(scannedContent: String) {
        try {
            val uri = Uri.parse(scannedContent)

            if (uri.scheme != null && (uri.scheme == "http" || uri.scheme == "https")) {
                protocol = uri.scheme ?: "https"
                domain = uri.host ?: ""
                port = if (uri.port != -1) uri.port.toString() else ""

                baseUrl = buildUrl(protocol, domain, port)

                saveUrlComponents(protocol, domain, port, baseUrl)

                webView.loadUrl(scannedContent)

                Toast.makeText(this, "Base URL saved: $baseUrl", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Invalid URL format in QR code", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error parsing QR code: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildUrl(protocol: String, domain: String, port: String): String {
        return if (port.isNotEmpty() && port != "80" && port != "443") {
            "$protocol://$domain:$port"
        } else {
            "$protocol://$domain"
        }
    }

    private fun saveUrlComponents(protocol: String, domain: String, port: String, baseUrl: String) {
        sharedPreferences.edit().apply {
            putString(KEY_PROTOCOL, protocol)
            putString(KEY_DOMAIN, domain)
            putString(KEY_PORT, port)
            putString(KEY_BASE_URL, baseUrl)
            apply()
        }
    }

    private fun loadSavedUrl() {
        if (baseUrl.isNotEmpty()) {
            webView.loadUrl(baseUrl)
        } else {
            webView.loadUrl("file:///android_asset/welcome.html")
        }
    }

    fun reloadFromPreferences() {
        setupSharedPreferences()
        if (baseUrl.isNotEmpty()) {
            webView.loadUrl(baseUrl)
        } else {
            webView.loadUrl("file:///android_asset/welcome.html")
        }
        showMainContent()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_settings -> {
                loadFragment(SettingsFragment())
            }
            R.id.nav_license -> {
                loadFragment(LicenseFragment())
            }
            R.id.nav_support -> {
                webView.loadUrl("https://passo.co.ke/support")
                showMainContent()
            }
            R.id.nav_exit -> {
                showExitDialog()
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

        webView.visibility = View.GONE
        findViewById<View>(R.id.fragment_container).visibility = View.VISIBLE
        fabRefresh.hide()
        fabScan.hide()
    }

    private fun showMainContent() {
        webView.visibility = View.VISIBLE
        findViewById<View>(R.id.fragment_container).visibility = View.GONE
        fabRefresh.show()
        fabScan.show()
        supportFragmentManager.popBackStack()
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit App")
            .setMessage("Are you sure you want to exit PassoAssist?")
            .setPositiveButton("Exit") { _, _ -> finish() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        when {
            drawerLayout.isDrawerOpen(GravityCompat.START) -> {
                drawerLayout.closeDrawer(GravityCompat.START)
            }
            findViewById<View>(R.id.fragment_container).visibility == View.VISIBLE -> {
                showMainContent()
            }
            webView.canGoBack() -> {
                webView.goBack()
            }
            else -> {
                super.onBackPressed()
            }
        }
    }
}





