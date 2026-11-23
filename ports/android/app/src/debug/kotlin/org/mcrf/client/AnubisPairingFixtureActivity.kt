package org.mcrf.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Operated manually in split screen with Android Settings. No code enters ADB arguments. */
internal class AnubisPairingFixtureActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var active: AnubisPairingNative.Attempt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == APP_ID && !ManagedAppFreezeAdb.supports(this))
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(ScrollView(this).apply { addView(content) })
        fun field(label: String, limit: Int, secret: Boolean = false) = EditText(this).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER or (if (secret) InputType.TYPE_NUMBER_VARIATION_PASSWORD else 0)
            filters = arrayOf(InputFilter.LengthFilter(limit))
            isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            content.addView(this)
        }
        val status = TextView(this).apply {
            text = "Open Wireless debugging and enter the displayed connection and pairing ports. Keep the Android code window open in split screen."
            content.addView(this)
        }
        content.addView(Button(this).apply {
            text = "Developer options"
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        })
        val connect = field("Connection port", 5)
        val pairing = field("Pairing port", 5)
        val code = field("Android pairing code", 6, true)
        val submit = Button(this).apply { text = "Verify system pairing" }
        content.addView(submit)
        submit.setOnClickListener {
            if (active != null) return@setOnClickListener
            val pairPort = pairing.text.toString().toIntOrNull()
            val connectPort = connect.text.toString().toIntOrNull()
            val value = code.text
            if (pairPort == null || connectPort == null || pairPort !in 1..65535 || connectPort !in 1..65535 ||
                pairPort == connectPort || value.length != 6 || value.any { it !in '0'..'9' }) {
                status.text = "Enter both ports and six digits from Android."
                return@setOnClickListener
            }
            val secret = ByteArray(6) { value[it].code.toByte() }
            value.clear()
            val attempt = AnubisPairingNative.Attempt().also { active = it }
            submit.isEnabled = false
            status.text = "Checking Android pairing…"
            Thread({
                var phase = "PAKE"
                val verification = try {
                    val transport = AnubisPairingNative::class.java.getDeclaredMethod("pairTransport",
                        Int::class.javaPrimitiveType, ByteArray::class.java, AnubisPairingNative.Attempt::class.java)
                        .apply { isAccessible = true }
                    val tls = transport.invoke(AnubisPairingNative, pairPort, secret, attempt) as SSLContext
                    phase = "ADB_TLS_CNXN"
                    val open = AnubisLocalAdb::class.java.getDeclaredMethod("connectTls", Socket::class.java,
                        Int::class.javaPrimitiveType, SSLContext::class.java).apply { isAccessible = true }
                    val expire = Runnable { attempt.cancel() }
                    main.postDelayed(expire, 12_000)
                    try {
                        attempt.newSocket().use { socket ->
                            (open.invoke(AnubisLocalAdb, socket, connectPort, tls) as SSLSocket).use { remote ->
                                check(remote.session.protocol == "TLSv1.3")
                            }
                        }
                    } finally {
                        main.removeCallbacks(expire)
                    }
                    "PASS"
                } catch (_: Exception) {
                    "FAIL_$phase"
                } finally {
                    secret.fill(0)
                    attempt.cancel()
                }
                main.post {
                    if (!isDestroyed) status.text = verification
                    result.offer(verification)
                }
            }, "anubis-pairing-fixture").apply { isDaemon = true }.start()
        }
    }

    override fun onDestroy() {
        active?.cancel()
        super.onDestroy()
    }

    companion object {
        const val APP_ID = "org.mcrf.client.anubispairingfixture"
        val result = ArrayBlockingQueue<String>(1)
    }
}
