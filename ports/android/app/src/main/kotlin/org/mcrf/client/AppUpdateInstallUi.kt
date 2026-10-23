package org.mcrf.client

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Application update download stays in MCRF; only consent/installation belongs to Android. */
internal object AppUpdateInstallUi {
    private var owner:MainActivity?=null
    private var dialog:AlertDialog?=null
    fun isShowing()=dialog?.isShowing==true
    fun close(activity:MainActivity){if(owner===activity)dialog?.dismiss()}
    fun show(activity:MainActivity,release:AppRelease) {
        if(isShowing())return
        val cancelled=AtomicBoolean(false)
        var ready:File?=null;var handedOff=false
        val body=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(activity.dp(20),activity.dp(12),activity.dp(20),activity.dp(12))}
        val status=activity.text("Подготовка загрузки…",14,Loft.INK)
        val progress=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply{
            max=100;isIndeterminate=false;progressTintList=android.content.res.ColorStateList.valueOf(Loft.ACCENT)
        }
        body.addView(status);body.addView(progress,LinearLayout.LayoutParams(-1,activity.dp(16)).apply{topMargin=activity.dp(16)})
        body.addView(activity.text("APK проверяется перед установкой. Android попросит подтверждение; установка перезапустит MCRF.",12,Loft.MUTED),activity.space(12,0))
        val window=ThemedDialogBuilder(activity).setTitle("Обновление MCRF").setView(body).setNegativeButton("Отмена",null).setPositiveButton("Установить",null).create()
        owner=activity;dialog=window
        fun live()=!cancelled.get()&&!activity.isFinishing&&!activity.isDestroyed&&window.isShowing
        fun ui(action:()->Unit){activity.runOnUiThread{if(live())action()}}
        fun failure(e:Exception){status.text="Обновление не установлено. "+(e.message?.substringBefore('?')?.take(180)?:e.javaClass.simpleName);progress.visibility=View.GONE;window.getButton(AlertDialog.BUTTON_POSITIVE).apply{isEnabled=true;text=if(ready==null)"Повторить"else"Установить"}}
        fun download(){
            ready=null;progress.visibility=View.VISIBLE;progress.progress=0;status.text="Скачивание ${AppUpdatePresentation.buildLabel(release.asset.version)}…"
            window.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
            Thread({try{
                var last=-1
                val file=AppUpdateDownload.download(activity.applicationContext,release.asset,cancelled::get){received->
                    val percent=(received*100/release.asset.bytes).toInt()
                    if(percent!=last){last=percent;ui{progress.progress=percent;status.text="Скачивание: $percent% · ${TrafficState.bytes(received)} / ${TrafficState.bytes(release.asset.bytes)}"}}
                }
                if(cancelled.get()){file.delete();return@Thread}
                activity.runOnUiThread{if(live()){ready=file;status.text="APK скачан. Целостность, подпись и версия проверены.";window.getButton(AlertDialog.BUTTON_POSITIVE).apply{isEnabled=true;text="Установить"}}else file.delete()}
            }catch(e:Exception){ui{failure(e)}}},"mcrf-app-download").start()
        }
        window.setOnDismissListener {
            cancelled.set(true);if(!handedOff)ready?.delete()
            if(dialog===window){dialog=null;owner=null}
        }
        window.show()
        window.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val file=ready
            if(file==null){download();return@setOnClickListener}
            if(!activity.packageManager.canRequestPackageInstalls()){
                status.text="Разрешите установку из MCRF в настройках Android, затем нажмите «Установить» ещё раз."
                try{activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${activity.packageName}")))}catch(e:Exception){failure(e)}
                return@setOnClickListener
            }
            window.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false;status.text="Повторная проверка APK…"
            Thread({try{
                AppUpdateDownload.verify(activity.applicationContext,file,release.asset)
                ui{
                    try{
                        @Suppress("DEPRECATION") val intent=Intent(Intent.ACTION_INSTALL_PACKAGE)
                            .setDataAndType(Uri.parse("content://${activity.packageName}.engine-package/${file.name}"),"application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        activity.startActivity(intent);handedOff=true;window.dismiss()
                    }catch(e:Exception){failure(e)}
                }
            }catch(e:Exception){ui{failure(e)}}},"mcrf-app-install-check").start()
        }
        download()
    }
}
