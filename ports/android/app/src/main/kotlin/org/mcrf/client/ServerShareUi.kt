package org.mcrf.client

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.view.WindowManager
import android.widget.ImageView

/** All four exports use the latest committed profile and explicit sensitive clipboard writes. */
internal object ServerShareUi {
    fun show(activity:MainActivity,id:String) {
        val body=EditorLayout.body(activity)
        val dialog=ThemedDialogBuilder(activity).setTitle("Поделиться подключением")
            .setView(CompactUi.scroll(activity,body)).setNegativeButton("Закрыть",null).create()
        val originalCsqtt=activity.catalogue.entries().firstOrNull {it.id==id}?.protocol=="csqtt"
        ServerShareOptions.values().forEach {option->
            val title=if(originalCsqtt&&option==ServerShareOptions.LINK)"Копировать ссылку CSQTT"else option.title
            body.addView(activity.button(title,false).apply {
                gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                minHeight=activity.dp(56);textSize=13f
                setCompoundDrawablesRelativeWithIntrinsicBounds(activity.icon(if(option.qr)"qr"else"copy",Loft.ACCENT),null,activity.icon("arrow",Loft.MUTED),null)
                compoundDrawablePadding=activity.dp(12)
                setOnClickListener {
                dialog.dismiss()
                var payload:ServerSharePayload?=null
                activity.run(MainActivity.Operation {
                    payload=synchronized(ProfileStore.storageLock){
                        ServerSharePayload.current(ConnectionCatalogue(ProfileStore(activity,"connections.enc").read()),id,originalCsqtt=option==ServerShareOptions.LINK||option==ServerShareOptions.QR)
                    }
                    ""
                },false,Runnable {
                    val captured=payload?:return@Runnable
                    payload=null
                    if(option.encrypted)CompatibilityImportUi.shareEncrypted(activity,captured.source){value->
                        if(option.qr)qr(activity,captured.name,value)else copy(activity,value)
                    }else if(option.qr)qr(activity,captured.name,captured.source)else copy(activity,captured.source)
                })
                }
            },activity.space(0,8))
        }
        dialog.show()
    }
    private fun copy(activity:MainActivity,value:String) {
        try {
            activity.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(AndroidDeployClipboard.sensitive(value,"Подключение MCRF"))
            activity.toast("Ссылка скопирована")
        }catch(_:Exception){activity.alert("Поделиться","Не удалось скопировать ссылку.")}
    }
    private fun qr(activity:MainActivity,name:String,value:String) {
        var bitmap:Bitmap?=null
        try {
            val matrix=QrCodec.encode(value,512)
            val image=Bitmap.createBitmap(IntArray(512*512){i->if(matrix[i%512,i/512])Color.BLACK else Color.WHITE},512,512,Bitmap.Config.ARGB_8888)
            bitmap=image
            val view=ImageView(activity).apply {
                setImageBitmap(image);adjustViewBounds=true
                setPadding(activity.dp(16),activity.dp(16),activity.dp(16),activity.dp(16))
                contentDescription="QR-код подключения"
            }
            val dialog=ThemedDialogBuilder(activity).setTitle(name).setView(view)
                .setNegativeButton("Закрыть",null).setPositiveButton("Копировать ссылку"){_,_->copy(activity,value)}.create()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.setOnDismissListener {view.setImageDrawable(null);image.recycle()}
            dialog.show()
        }catch(_:Exception){bitmap?.recycle();activity.alert("QR-код","Ссылка слишком длинная для QR-кода. Скопируй ссылку.")}
    }
}
