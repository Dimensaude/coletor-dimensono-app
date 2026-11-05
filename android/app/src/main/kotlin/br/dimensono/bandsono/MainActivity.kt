package br.dimensono.bandsono

import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var channel: BandChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyLink(intent)
    }

    /**
     * https://dimensaude.com.br/actigrafia/p/DMS-...: professional code (prefill only).
     * https://dimensaude.com.br/actigrafia/c/<CODE>: legacy participant code (ignored on fresh installs).
     */
    private fun applyLink(intent: Intent?) {
        if (intent == null) return
        val uri = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        if (uri.scheme != "https" || uri.host != "dimensaude.com.br") return
        val segments = uri.pathSegments
        if (segments.size != 3 || segments[0] != "actigrafia") return
        if (segments[1] != "c" && segments[1] != "p") return
        // Consume the intent so a rotation or recreation does not apply it again
        intent.data = null
        Settings.provisionDefaults(applicationContext)
        if (segments[1] == "p") {
            // Professional link: only prefills the professional code in the entry flow, never the participant code
            Settings.applyProfessionalLink(applicationContext, segments[2])
        } else {
            Settings.applyLink(applicationContext, segments[2])
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = BandChannel(this, flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        channel?.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onDestroy() {
        channel?.dispose()
        channel = null
        super.onDestroy()
    }
}
