package org.mcrf.client

import java.net.URI

/** Immutable, allowlisted public probes. No user URL or Windows recipe input. */
data class ServiceTestTarget(val id: String, val title: String, val host: String = "", val check: DiagnosticCheck = DiagnosticCheck.HTTPS) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,95}")))
        require(check == DiagnosticCheck.UNSUPPORTED || host.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*\\.[a-z]{2,}")))
    }
    val endpoint: URI? get() = if (check == DiagnosticCheck.HTTPS) URI("https", null, host, 443, "/", null, null) else null
    // Do not expose endpoint strings from generated toString in diagnostics.
    override fun toString() = "ServiceTestTarget($id, $check)"
}
data class ServiceTestService(val id: String, val title: String, val parts: List<ServiceTestTarget>)
data class ServiceTestCategory(val id: String, val title: String, val services: List<ServiceTestService>)

/** Names/parts based on official NineUPFLOW/Zephyron ServiceCatalog.cs, revision
 * 6f8028ff4fb02566d027f37804cf924f9aeac62f (2026-10-31).
 * https://github.com/NineUPFLOW/Zephyron/blob/6f8028ff4fb02566d027f37804cf924f9aeac62f/src/Zephyron.Core/Services/ServiceCatalog.cs
 * Taxonomy only: all probes below are independently chosen Android DNS/HTTPS.
 * HTTPS HEAD checks a public origin, not playback, login, voice, UDP or a game.
 * Address-only and bulk lists deliberately have no synthetic substitute. */
object ServiceTestCatalog {
    /** Local installed-app artwork only; never fetch favicons or transmit the installed-app list. */
    fun iconPackages(id:String):List<String> = when(id){
        "discord"->listOf("com.discord");"telegram"->listOf("org.telegram.messenger","org.thunderdog.challegram")
        "signal"->listOf("org.thoughtcrime.securesms");"viber"->listOf("com.viber.voip");"whatsapp"->listOf("com.whatsapp")
        "youtube"->listOf("com.google.android.youtube");"twitch"->listOf("tv.twitch.android.app");"kick"->listOf("com.kick.mobile")
        "spotify"->listOf("com.spotify.music");"tiktok"->listOf("com.zhiliaoapp.musically");"soundcloud"->listOf("com.soundcloud.android")
        "netflix"->listOf("com.netflix.mediaclient");"crunchyroll"->listOf("com.crunchyroll.crunchyroid")
        "instagram"->listOf("com.instagram.android");"facebook"->listOf("com.facebook.katana");"twitter"->listOf("com.twitter.android")
        "linkedin"->listOf("com.linkedin.android");"reddit"->listOf("com.reddit.frontpage");"pinterest"->listOf("com.pinterest")
        "bluesky"->listOf("xyz.blueskyweb.app");"roblox"->listOf("com.roblox.client");"steam"->listOf("com.valvesoftware.android.steam.community")
        "chatgpt"->listOf("com.openai.chatgpt");"gemini"->listOf("com.google.android.apps.bard");"claude"->listOf("com.anthropic.claude")
        "deepseek"->listOf("com.deepseek.chat");"perplexity"->listOf("ai.perplexity.app.android")
        "notion"->listOf("notion.id");"obsidian"->listOf("md.obsidian");"slack"->listOf("com.Slack");"zoom"->listOf("us.zoom.videomeetings")
        "dropbox"->listOf("com.dropbox.android");"proton"->listOf("ch.protonmail.android");"github"->listOf("com.github.android")
        "wikipedia"->listOf("org.wikipedia");"google"->listOf("com.google.android.googlequicksearchbox")
        else->emptyList()
    }
    fun iconLabel(service:ServiceTestService):String=when(service.id){
        "youtube"->"▶";"telegram"->"TG";"discord"->"D";"github"->"GH";"chatgpt"->"GPT"
        "twitter"->"X";"cloudflare"->"CF";"wikipedia"->"W";"playstation"->"PS";"microsoft-store"->"MS"
        else->service.title.split(' ','.',':','/').filter {it.isNotBlank()}.take(2).joinToString(""){it.take(1)}.uppercase(java.util.Locale.ROOT)
    }
    const val SOURCE_REVISION = "6f8028ff4fb02566d027f37804cf924f9aeac62f"
    private fun p(id: String, title: String, host: String) = ServiceTestTarget(id, title, host)
    private fun dns(id: String, title: String, host: String) = ServiceTestTarget(id, title, host, DiagnosticCheck.DNS)
    private fun unsupported(id: String, title: String) = ServiceTestTarget(id, title, check = DiagnosticCheck.UNSUPPORTED)
    private fun s(id: String, title: String, vararg parts: ServiceTestTarget) = ServiceTestService(id, title, parts.toList())
    private fun site(id: String, title: String, host: String) = s(id, title, p("$id-site", "Сайт", host))
    private fun category(id: String, title: String, vararg services: ServiceTestService) = ServiceTestCategory(id, title, services.toList())
    val categories: List<ServiceTestCategory> = listOf(
        category("messaging", "Мессенджеры",
            s("discord", "Discord", dns("discord-voice", "Голос · только DNS", "discord.media"),
                unsupported("discord-voice-ip", "Звук голоса (адреса)"), p("discord-text", "Сайт и переписка", "discord.com"),
                p("discord-images", "Картинки · HTTPS узла", "cdn.discordapp.com"), p("discord-updates", "Обновления · HTTPS узла", "updates.discord.com"),
                p("discord-vencord", "Vencord", "vencord.dev"), unsupported("discord-addresses", "Запасной путь по адресам")),
            s("telegram", "Telegram", unsupported("telegram-app", "Приложение · MTProto"), p("telegram-web", "Сайт и веб-версия", "web.telegram.org")),
            site("signal", "Signal", "signal.org"), site("viber", "Viber", "www.viber.com"), site("whatsapp", "WhatsApp", "www.whatsapp.com")),
        category("media", "Видео и музыка",
            s("youtube", "YouTube", unsupported("youtube-stream", "Видеопоток · нужен сеанс плеера"), p("youtube-ui", "Интерфейс", "www.youtube.com"),
                p("youtube-thumbnails", "Превью · HTTPS узла", "i.ytimg.com"), unsupported("youtube-quic", "QUIC по адресам")),
            s("twitch", "Twitch", p("twitch-site", "Сайт и чат", "www.twitch.tv"), dns("twitch-stream", "Эфиры · только DNS", "usher.ttvnw.net"),
                unsupported("twitch-ingest", "Приём трансляции (Amazon IVS)")),
            s("kick", "Kick", p("kick-site", "Сайт и чат", "kick.com"), unsupported("kick-video", "Видео (Amazon IVS)")),
            site("donationalerts", "DonationAlerts", "www.donationalerts.com"),
            s("spotify", "Spotify", p("spotify-site", "Сайт и вход", "open.spotify.com"), p("spotify-media", "Музыка и обложки · HTTPS узла", "i.scdn.co")),
            site("tiktok", "TikTok", "www.tiktok.com"), site("soundcloud", "SoundCloud", "soundcloud.com")),
        category("cinema", "Кино и сериалы", site("tmdb", "TMDB: постеры и описания", "www.themoviedb.org"),
            site("netflix", "Netflix", "www.netflix.com"), site("vimeo", "Vimeo", "vimeo.com"),
            site("crunchyroll", "Crunchyroll", "www.crunchyroll.com"), site("seriesgraph", "SeriesGraph", "seriesgraph.com")),
        category("social", "Соцсети", site("instagram", "Instagram", "www.instagram.com"), site("facebook", "Facebook", "www.facebook.com"),
            s("twitter", "Twitter / X", p("twitter-site", "Сайт", "x.com"), unsupported("twitter-addresses", "Twitter / X по адресам")),
            site("linkedin", "LinkedIn", "www.linkedin.com"), site("reddit", "Reddit", "www.reddit.com"),
            s("bluesky", "Bluesky", p("bluesky-app", "Приложение", "bsky.app"), p("bluesky-media", "Картинки и видео · HTTPS узла", "cdn.bsky.app")),
            site("pinterest", "Pinterest", "www.pinterest.com")),
        category("games", "Игры",
            s("roblox", "Roblox", p("roblox-site", "Игра и сайт", "www.roblox.com"), p("roblox-images", "Превью предметов · HTTPS узла", "thumbnails.roblox.com"),
                unsupported("roblox-servers", "Игровые серверы (адреса)")),
            s("steam", "Steam", p("steam-community", "Сайт и сообщество", "steamcommunity.com"), p("steam-download", "Загрузки и всё прочее · HTTPS узла", "store.steampowered.com")),
            site("valheim", "Valheim", "www.valheimgame.com"),
            s("riot", "Riot и Valorant", p("riot-site", "Вход и клиент", "www.riotgames.com"), unsupported("riot-udp", "Игровой UDP")),
            s("photon", "Photon: R.E.P.O. и другие", dns("photon-lobby", "Сервер и лобби · только DNS", "ns.photonengine.io"), unsupported("photon-servers", "Игровые серверы (адреса)")),
            s("arc-raiders", "ARC Raiders", p("arc-site", "Сервисы Embark · публичный сайт", "www.embark-studios.com"),
                unsupported("arc-voice", "Голос"), unsupported("arc-servers", "Игровые серверы (адреса)")),
            site("epic", "Epic Games и Fortnite", "www.epicgames.com"), site("ubisoft", "Ubisoft", "www.ubisoft.com"),
            site("wardogs", "Wardogs", "www.bulkhead.com"), site("itch", "itch.io", "itch.io"), site("battlenet", "Battle.net и Blizzard", "www.blizzard.com"),
            site("playstation", "PlayStation Network", "www.playstation.com"), site("xbox", "Xbox Live", "www.xbox.com"), site("nintendo", "Nintendo", "www.nintendo.com"),
            site("ea", "EA и Origin", "www.ea.com"), site("gog", "GOG", "www.gog.com"), site("rockstar", "Rockstar", "www.rockstargames.com"),
            site("minecraft", "Minecraft", "www.minecraft.net"), site("hoyoverse", "HoYoverse: Genshin и Honkai", "www.hoyoverse.com")),
        category("ai", "Нейросети", site("chatgpt", "ChatGPT", "chatgpt.com"), site("gemini", "Gemini", "gemini.google.com"),
            site("deepseek", "DeepSeek", "www.deepseek.com"), site("perplexity", "Perplexity", "www.perplexity.ai"),
            site("grok", "Grok", "grok.com"), site("mistral", "Mistral", "mistral.ai"), site("huggingface", "Hugging Face", "huggingface.co"),
            s("claude", "Claude", p("claude-site", "Сайт", "claude.ai"), p("claude-content", "Содержимое · HTTPS узла", "claudeusercontent.com"))),
        category("work", "Работа и заметки", site("notion", "Notion", "www.notion.so"), site("obsidian", "Obsidian", "obsidian.md"),
            site("fandom", "Fandom", "www.fandom.com"), site("figma", "Figma", "www.figma.com"), site("miro", "Miro", "miro.com"),
            site("slack", "Slack", "slack.com"), site("zoom", "Zoom", "zoom.us"), site("atlassian", "Atlassian: Jira, Confluence, Trello", "www.atlassian.com"),
            site("dropbox", "Dropbox", "www.dropbox.com"), site("proton", "Proton: почта и диск", "proton.me")),
        category("development", "Разработка", site("github", "GitHub", "github.com"), site("stackoverflow", "Stack Overflow", "stackoverflow.com"),
            site("npm", "npm", "registry.npmjs.org"), site("pypi", "PyPI и python.org", "pypi.org"), site("docker", "Docker Hub", "hub.docker.com"),
            site("jetbrains", "JetBrains", "www.jetbrains.com"),
            s("virustotal", "VirusTotal", p("virustotal-site", "Сайт", "www.virustotal.com"), p("virustotal-docs", "Документация", "docs.virustotal.com"),
                p("virustotal-login", "Вход · публичный узел", "accounts.google.com"), p("virustotal-api", "Вход — API Google · HTTPS узла", "www.googleapis.com"))),
        category("knowledge", "Знание и архивы", site("wikipedia", "Википедия", "www.wikipedia.org"), site("archive", "Интернет-архив", "archive.org"),
            site("medium", "Medium", "medium.com")),
        category("platforms", "Платформы и CDN", site("google", "Google", "www.google.com"), site("cloudflare", "Cloudflare", "www.cloudflare.com"),
            s("cloudfront", "CloudFront", unsupported("cloudfront-distribution", "Нужен адрес конкретного дистрибутива")), site("amazon", "Amazon", "www.amazon.com"), site("apple", "Apple", "www.apple.com"),
            site("microsoft-store", "Microsoft Store", "apps.microsoft.com"), site("speedtest", "Speedtest", "www.speedtest.net")),
        category("captchas", "Капчи", site("recaptcha", "reCAPTCHA (Google)", "www.recaptcha.net"), site("hcaptcha", "hCaptcha", "www.hcaptcha.com"),
            site("turnstile", "Cloudflare Turnstile", "challenges.cloudflare.com"), site("arkose", "Arkose Labs (FunCaptcha)", "www.arkoselabs.com"),
            site("geetest", "GeeTest", "www.geetest.com")),
        category("other", "Торренты и реестр", site("rutracker", "RuTracker", "rutracker.org"), site("rutor", "Rutor", "rutor.info"),
            s("registry", "Реестр блокировок РФ", unsupported("registry-bulk", "Всё · массовый список не проверяется")))
    )
    val targets: List<ServiceTestTarget> = categories.flatMap { it.services }.flatMap { it.parts }
    private val byId = targets.associateBy { it.id }
    val services:List<ServiceTestService> = categories.flatMap {it.services}
    fun serviceFor(targetId:String):ServiceTestService? = services.firstOrNull {service->service.parts.any {it.id==targetId}}
    init { require(byId.size == targets.size) }
    fun find(id: String) = byId[id]
    fun label(id: String): String? = categories.asSequence().flatMap { it.services.asSequence() }.mapNotNull { service ->
        service.parts.firstOrNull { it.id == id }?.let { "${service.title} · ${it.title}" }
    }.firstOrNull()
    fun matching(query: String): List<ServiceTestCategory> {
        val text = query.trim().take(120)
        if (text.isEmpty()) return categories
        return categories.mapNotNull { category ->
            val services = category.services.mapNotNull { service ->
                val parts = if (category.title.contains(text, true) || service.title.contains(text, true)) service.parts
                    else service.parts.filter { it.title.contains(text, true) || it.host.contains(text, true) }
                if (parts.isEmpty()) null else service.copy(parts = parts)
            }
            if (services.isEmpty()) null else category.copy(services = services)
        }
    }
}
