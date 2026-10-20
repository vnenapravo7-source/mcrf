package org.mcrf.client

import android.content.*
import android.net.Uri
import android.os.*

/** IPC keeps the Telegram physical-network binding out of the VPN/UI process. */
class TelegramBridge(private val context: Context, private val changed: () -> Unit) {
    data class State(val wanted:Boolean=false,val running:Boolean=false,val message:String="Выключен",val port:Int=DEFAULT_PORT,val transport:String="")
    var state=State(); private set
    private var bound=false
    private var remote:Messenger?=null
    private val inbox=Messenger(Handler(Looper.getMainLooper()) { message ->
        if(message.what==STATE) {
            state=State(message.data.getBoolean("wanted"),message.data.getBoolean("running"),message.data.getString("message").orEmpty(),message.data.getInt("port",DEFAULT_PORT),message.data.getString("transport").orEmpty())
            changed()
        };true
    })
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,service:IBinder) {remote=Messenger(service);send(SUBSCRIBE)}
        override fun onServiceDisconnected(name:ComponentName) {remote=null;state=State();changed()}
    }
    fun bind(){if(!bound)bound=context.bindService(Intent(context,TelegramProxyService::class.java),connection,Context.BIND_AUTO_CREATE)}
    fun unbind(){if(bound){send(UNSUBSCRIBE);context.unbindService(connection);bound=false;remote=null}}
    private fun send(what:Int){try{remote?.send(Message.obtain(null,what).apply {replyTo=inbox})}catch(_:RemoteException){}}
    fun setEnabled(enabled:Boolean) {
        if(enabled&&!EngineUpdateTelegram.available(context)) {
            (context as? MainActivity)?.let(EngineUpdateTelegram::requestDownload)
            return
        }
        // Disabling is always possible, including while waiting for the network.
        context.startForegroundService(Intent(context,TelegramProxyService::class.java).setAction(if(enabled)START else STOP))
    }
    fun openTelegram() {
        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(setupLink(state.port))))
    }
    companion object {
        const val DEFAULT_PORT=19071
        const val START="org.mcrf.telegram.START"
        const val STOP="org.mcrf.telegram.STOP"
        const val SUBSCRIBE=1;const val UNSUBSCRIBE=2;const val STATE=3
        fun setupLink(port:Int):String {require(port in 1024..65535);return "https://t.me/socks?server=127.0.0.1&port=$port"}
    }
}
