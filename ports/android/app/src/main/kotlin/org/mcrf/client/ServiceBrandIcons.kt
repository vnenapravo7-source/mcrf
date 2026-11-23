package org.mcrf.client

/** Bundled brand vectors and deterministic fallback badges always work offline. */
internal object ServiceBrandIcons {
    private val resources=mapOf(
        "discord" to R.drawable.service_brand_discord,
        "telegram" to R.drawable.service_brand_telegram,
        "signal" to R.drawable.service_brand_signal,
        "viber" to R.drawable.service_brand_viber,
        "whatsapp" to R.drawable.service_brand_whatsapp,
        "youtube" to R.drawable.service_brand_youtube,
        "twitch" to R.drawable.service_brand_twitch,
        "kick" to R.drawable.service_brand_kick,
        "spotify" to R.drawable.service_brand_spotify,
        "tiktok" to R.drawable.service_brand_tiktok,
        "soundcloud" to R.drawable.service_brand_soundcloud,
        "tmdb" to R.drawable.service_brand_themoviedatabase,
        "netflix" to R.drawable.service_brand_netflix,
        "vimeo" to R.drawable.service_brand_vimeo,
        "crunchyroll" to R.drawable.service_brand_crunchyroll,
        "instagram" to R.drawable.service_brand_instagram,
        "facebook" to R.drawable.service_brand_facebook,
        "twitter" to R.drawable.service_brand_x,
        "linkedin" to R.drawable.service_brand_linkedin,
        "reddit" to R.drawable.service_brand_reddit,
        "bluesky" to R.drawable.service_brand_bluesky,
        "pinterest" to R.drawable.service_brand_pinterest,
        "roblox" to R.drawable.service_brand_roblox,
        "steam" to R.drawable.service_brand_steam,
        "riot" to R.drawable.service_brand_riotgames,
        "epic" to R.drawable.service_brand_epicgames,
        "ubisoft" to R.drawable.service_brand_ubisoft,
        "itch" to R.drawable.service_brand_itchdotio,
        "battlenet" to R.drawable.service_brand_battledotnet,
        "playstation" to R.drawable.service_brand_playstation,
        "nintendo" to R.drawable.service_brand_nintendo,
        "ea" to R.drawable.service_brand_ea,
        "gog" to R.drawable.service_brand_gogdotcom,
        "rockstar" to R.drawable.service_brand_rockstargames,
        "chatgpt" to R.drawable.service_brand_openai,
        "gemini" to R.drawable.service_brand_googlegemini,
        "perplexity" to R.drawable.service_brand_perplexity,
        "grok" to R.drawable.service_brand_x,
        "huggingface" to R.drawable.service_brand_huggingface,
        "claude" to R.drawable.service_brand_claude,
        "notion" to R.drawable.service_brand_notion,
        "obsidian" to R.drawable.service_brand_obsidian,
        "fandom" to R.drawable.service_brand_fandom,
        "figma" to R.drawable.service_brand_figma,
        "miro" to R.drawable.service_brand_miro,
        "slack" to R.drawable.service_brand_slack,
        "zoom" to R.drawable.service_brand_zoom,
        "atlassian" to R.drawable.service_brand_atlassian,
        "dropbox" to R.drawable.service_brand_dropbox,
        "proton" to R.drawable.service_brand_proton,
        "github" to R.drawable.service_brand_github,
        "stackoverflow" to R.drawable.service_brand_stackoverflow,
        "npm" to R.drawable.service_brand_npm,
        "pypi" to R.drawable.service_brand_pypi,
        "docker" to R.drawable.service_brand_docker,
        "jetbrains" to R.drawable.service_brand_jetbrains,
        "virustotal" to R.drawable.service_brand_virustotal,
        "wikipedia" to R.drawable.service_brand_wikipedia,
        "archive" to R.drawable.service_brand_internetarchive,
        "medium" to R.drawable.service_brand_medium,
        "google" to R.drawable.service_brand_google,
        "cloudflare" to R.drawable.service_brand_cloudflare,
        "amazon" to R.drawable.service_brand_amazon,
        "apple" to R.drawable.service_brand_apple,
        "speedtest" to R.drawable.service_brand_speedtest,
        "recaptcha" to R.drawable.service_brand_google,
        "turnstile" to R.drawable.service_brand_cloudflare
    )
    /** A probe part (voice, DNS, pictures) keeps its parent service's artwork. */
    internal fun resourceFor(id:String):Int? {
        if(id.contains("discord",ignoreCase=true))return R.drawable.service_brand_discord
        val parent=ServiceTestCatalog.serviceFor(id)
        return resources[id] ?: parent?.let(::resourceFor)
    }
    internal fun resourceFor(service:ServiceTestService):Int? =
        if(service.id.contains("discord",ignoreCase=true)||service.title.contains("discord",ignoreCase=true))
            R.drawable.service_brand_discord else resources[service.id]
    fun drawable(activity:MainActivity,service:ServiceTestService):android.graphics.drawable.Drawable {
        val resource=resourceFor(service)?:resourceFor(service.id)
        val color=when {
            resource==R.drawable.service_brand_discord->if(Loft.dark)0xffa6acff.toInt()else 0xff5865f2.toInt()
            service.id=="whatsapp"->if(Loft.dark)0xff70df9d.toInt()else 0xff128c4a.toInt()
            else->Loft.INK
        }
        return if(resource==null)monogram(service)
        else SettingsSectionUi.read({activity.getDrawable(resource)?.mutate()?.apply {setTint(color)}},{null})
            ?: monogram(service)
    }
    /** A distinct offline badge for every service even when no bundled brand vector exists. */
    private fun monogram(service:ServiceTestService)=object:android.graphics.drawable.Drawable() {
        private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign=android.graphics.Paint.Align.CENTER;typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD)
        }
        override fun draw(canvas:android.graphics.Canvas) {
            val box=android.graphics.RectF(bounds);paint.color=Loft.selection();canvas.drawRoundRect(box,box.width()*.2f,box.height()*.2f,paint)
            val label=ServiceTestCatalog.iconLabel(service).ifBlank {service.id.take(2).uppercase(java.util.Locale.ROOT)}.take(3)
            paint.color=Loft.INK;paint.textSize=box.height()*(if(label.length>2).34f else .44f)
            canvas.drawText(label,box.centerX(),box.centerY()-(paint.ascent()+paint.descent())/2,paint)
        }
        override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:android.graphics.ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
        override fun getIntrinsicWidth()=24
        override fun getIntrinsicHeight()=24
        @Deprecated("Drawable contract") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
    }
}
