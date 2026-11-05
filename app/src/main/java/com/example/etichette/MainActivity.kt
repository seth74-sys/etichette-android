/*
 * © 2026 Stefano Dalan. Applicazione sviluppata con l'ausilio dell'intelligenza artificiale (Claude, Anthropic) da Stefano Dalan. Tutti i diritti riservati.
 */
package com.example.etichette

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Contenitore dell'app etichette: mostra la pagina HTML (assets/index.html) e le offre
 * un ponte "AndroidPrinter" per inviare il ZPL a una stampante Zebra abbinata via Bluetooth Classic (SPP).
 */
@SuppressLint("MissingPermission", "SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    // UUID standard del profilo seriale Bluetooth (SPP)
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val prefs by lazy { getSharedPreferences("etichette", Context.MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            runJs("window.onPermissionResult && window.onPermissionResult()")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFF16839A.toInt()
        window.navigationBarColor = 0xFF0F6477.toInt()

        web = WebView(this)
        setContentView(web)

        // Evita che il contenuto finisca sotto la barra di stato / di navigazione
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "AndroidPrinter")
        web.loadUrl("file:///android_asset/index.html")

        if (!hasPermission()) {
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun runJs(js: String) {
        runOnUiThread { web.evaluateJavascript(js, null) }
    }

    private fun reportResult(ok: Boolean, message: String) {
        runJs("window.onPrintResult(" + ok + ", " + JSONObject.quote(message) + ")")
    }

    private fun sha(x: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(x.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    inner class Bridge {

        /** Elenco delle stampanti Bluetooth gia' abbinate, in formato JSON. */
        @JavascriptInterface
        fun listPrinters(): String {
            val result = JSONObject()
            val ad = adapter()
            val perm = hasPermission()
            result.put("perm", perm)
            result.put("btOn", ad != null && ad.isEnabled)
            if (ad == null) result.put("error", "Questo dispositivo non ha il Bluetooth.")
            val list = JSONArray()
            try {
                if (ad != null && perm && ad.isEnabled) {
                    val all = ad.bondedDevices.toList()
                    // Solo stampanti: classe Bluetooth "imaging/stampante" oppure nome Zebra.
                    val printers = all.filter { d ->
                        val major = d.bluetoothClass?.majorDeviceClass
                        val n = (d.name ?: "").uppercase()
                        major == android.bluetooth.BluetoothClass.Device.Major.IMAGING ||
                            n.contains("ZEBRA") || n.startsWith("ZD") || n.startsWith("ZQ") || n.startsWith("ZT")
                    }
                    // Se il filtro non trova nulla (nome/classe insoliti) mostra tutto, per non bloccare l'uso.
                    val shown = if (printers.isEmpty()) all else printers
                    result.put("filtered", printers.isNotEmpty())
                    for (d in shown) {
                        val item = JSONObject()
                        item.put("name", d.name ?: d.address)
                        item.put("address", d.address)
                        list.put(item)
                    }
                }
            } catch (e: SecurityException) {
                result.put("perm", false)
            }
            result.put("printers", list)
            result.put("selected", prefs.getString("printer", "") ?: "")
            return result.toString()
        }

        @JavascriptInterface
        fun checkPin(pin: String): Boolean = sha(pin) == (prefs.getString("pinHash", null) ?: sha("brunisso"))

        @JavascriptInterface
        fun changePin(old: String, new: String): Boolean {
            if (!checkPin(old) || new.length < 4) return false
            prefs.edit().putString("pinHash", sha(new)).apply()
            return true
        }

        @JavascriptInterface
        fun loadSettings(): String = prefs.getString("settings", "") ?: ""

        @JavascriptInterface
        fun saveSettings(json: String) {
            prefs.edit().putString("settings", json).apply()
        }

        @JavascriptInterface
        fun select(address: String) {
            prefs.edit().putString("printer", address).apply()
        }

        /** Invia il ZPL alla stampante selezionata. L'esito torna alla pagina con window.onPrintResult. */
        @JavascriptInterface
        fun print(zpl: String) {
            val address = prefs.getString("printer", "") ?: ""
            if (address.isEmpty()) {
                reportResult(false, "Nessuna stampante scelta: aprila da Impostazioni.")
                return
            }
            Thread {
                var ok = false
                var message = ""
                try {
                    if (!hasPermission()) {
                        runOnUiThread { permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                        throw IllegalStateException("Permesso Bluetooth mancante: consentilo nella finestra di Android e ripremi Stampa")
                    }
                    val ad = adapter() ?: throw IllegalStateException("Bluetooth non disponibile.")
                    if (!ad.isEnabled) throw IllegalStateException("Bluetooth disattivato.")
                    // Nessuna ricerca in corso (non la avviamo mai): cancelDiscovery() richiederebbe
                    // BLUETOOTH_SCAN su Android 12+ e causava "permesso negato".
                    try { ad.cancelDiscovery() } catch (e: SecurityException) { /* non necessario */ }
                    val device = ad.getRemoteDevice(address)
                    val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                    try {
                        socket.connect()
                        val data = zpl.toByteArray(Charsets.UTF_8)
                        val out = socket.outputStream
                        var offset = 0
                        while (offset < data.size) {
                            val n = minOf(512, data.size - offset)
                            out.write(data, offset, n)
                            out.flush()
                            offset += n
                            Thread.sleep(30)
                        }
                        Thread.sleep(500) // lascia alla stampante il tempo di ricevere tutto prima di chiudere
                        ok = true
                        message = "Inviati " + data.size + " byte alla stampante."
                    } finally {
                        try {
                            socket.close()
                        } catch (e: Exception) {
                            // ignorato
                        }
                    }
                } catch (e: SecurityException) {
                    message = "Permesso Bluetooth negato (" + (e.message ?: "") + ")."
                } catch (e: Exception) {
                    message = "Errore: " + (e.message ?: e.javaClass.simpleName) +
                        ". Controlla che la stampante sia accesa, abbinata e non collegata ad altri dispositivi."
                }
                reportResult(ok, message)
            }.start()
        }
    }
}
