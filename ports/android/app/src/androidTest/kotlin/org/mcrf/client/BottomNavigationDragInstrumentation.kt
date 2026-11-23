package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Injects real window input, holds it before UP and checks pixels as well as state.
 * The debug-only host avoids MainActivity's foreground randomizer and all stores. */
class BottomNavigationDragInstrumentation : Instrumentation() {
    private var options = Bundle()
    override fun onCreate(arguments: Bundle?) {
        options = arguments?.let { Bundle(it) } ?: Bundle()
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        val expectedProcess = "org.mcrf.client:navigationFixture"
        val actualProcess = android.app.Application.getProcessName()
        if (actualProcess != expectedProcess || targetContext.packageName != "org.mcrf.client") {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "FAIL navigation fixture process: actual=$actualProcess expected=$expectedProcess")
            })
            return
        }
        // Test-only, in-memory ownership within the isolated fixture process.
        // This is NOT a barrier for jobs in another process: isolation depends on
        // the exact manifest targetProcesses plus the fail-closed check above.
        // Never launch a measurement or notify normal presentation observers.
        val owned = ProbeProgress(setOf("fixture-navigation"))
        val coordinator = ProbeCoordinator
        // The production property has a private Kotlin setter; keep this access
        // entirely in instrumentation rather than adding a production test hook.
        val measurements = ProbeCoordinator::class.java.getDeclaredField("measurements").apply { isAccessible = true }
        val startLock = ProbeCoordinator::class.java.getDeclaredField("startLock").apply { isAccessible = true }.get(coordinator)
        var acquired = false
        val outcome = try {
            try {
                synchronized(startLock) {
                    check(measurements.get(coordinator) == null) { "Navigation fixture requires no active measurement" }
                    measurements.set(coordinator, owned)
                    acquired = true
                }
                runFixture()
            } finally {
                if (acquired) synchronized(startLock) {
                    check(measurements.get(coordinator) === owned) { "Navigation fixture measurement ownership was stolen" }
                    measurements.set(coordinator, null)
                }
            }
        } catch (error: Throwable) {
            Activity.RESULT_CANCELED to Bundle().apply {
                putString("stream", "FAIL navigation fixture isolation/cleanup: ${error.stackTraceToString()}")
            }
        }
        // Release ownership before finishing instrumentation, including when the
        // protected snapshot, launch, case assertions or teardown throws.
        finish(outcome.first, outcome.second)
    }

    private fun runFixture(): Pair<Int, Bundle> {
        var lastProcessCheck = Long.MIN_VALUE
        fun checkNoMainProcess(force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && lastProcessCheck != Long.MIN_VALUE && now - lastProcessCheck < 1000) return
            val processes = checkNotNull(targetContext.getSystemService(android.app.ActivityManager::class.java).runningAppProcesses) {
                "Navigation fixture cannot verify process isolation"
            }
            val selfPid=android.os.Process.myPid()
            val selfUid=android.os.Process.myUid()
            val mainRecords=processes.filter {it.uid==selfUid&&it.processName==targetContext.packageName}
            check(mainRecords.isEmpty()) {
                // Diagnostic only: metadata cannot turn a matching AM record
                // into a PASS. Never read cmdline, signal or terminate a process.
                fun metadata(pid:Int):String = try {
                    val stat=android.system.Os.stat("/proc/$pid")
                    "proc=present ownerUid=${stat.st_uid} ownerMatches=${stat.st_uid==selfUid}"
                }catch(error:android.system.ErrnoException) {
                    "proc=${if(error.errno==android.system.OsConstants.ENOENT)"absent"else"unknown"} errno=${error.errno}"
                }catch(_:Exception){"proc=unknown"}
                "Navigation fixture isolation lost: main application process is running; " +
                    "selfPid=$selfPid selfUid=$selfUid actualProcess=${android.app.Application.getProcessName()} " +
                    "self[${metadata(selfPid)}] mainCount=${mainRecords.size} mainRecords=" +
                    mainRecords.take(8).joinToString(prefix="[",postfix="]") {
                        "pid=${it.pid} uid=${it.uid} sameAsSelf=${it.pid==selfPid} ${metadata(it.pid)}"
                    }
            }
            check(processes.any { it.pid == android.os.Process.myPid() && it.processName == "org.mcrf.client:navigationFixture" }) {
                "Navigation fixture process is missing from process evidence"
            }
            lastProcessCheck = now
        }
        checkNoMainProcess(force = true)
        val report = StringBuilder()
        val evidence = File(targetContext.getExternalFilesDir(null), "bottom-navigation-drag").apply { mkdirs() }
        var activity: BottomNavigationDragActivity? = null
        var result = Activity.RESULT_OK
        var caseKey = "startup"
        var stage = "launch"
        var captureFailure: (() -> Unit)? = null
        val originalDark = Loft.dark
        var ignoredRuntimeFiles = 0
        fun protectedFiles(): Map<String, String> = listOf(File(targetContext.dataDir, "shared_prefs"), targetContext.filesDir)
            .flatMap { if (it.exists()) it.walkTopDown().filter { entry -> entry.isFile }.toList() else emptyList() }
            .also { files -> ignoredRuntimeFiles = files.count {
                it.relativeTo(targetContext.dataDir).invariantSeparatorsPath == "shared_prefs/android.app.ActivityThread.IDS.xml"
            } }
            .filterNot { it.relativeTo(targetContext.dataDir).invariantSeparatorsPath == "shared_prefs/android.app.ActivityThread.IDS.xml" }
            .associate { it.relativeTo(targetContext.dataDir).path to MessageDigest.getInstance("SHA-256")
                .digest(it.readBytes()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) } }
        val before = protectedFiles()
        fun main(checkIsolation: Boolean = true, block: () -> Unit) {
            // Binder inspection stays on the instrumentation thread, at most once
            // per second. This detects concurrent jobs; it never cancels them.
            if (checkIsolation) checkNoMainProcess()
            val failure = AtomicReference<Throwable?>()
            runOnMainSync { try { block() } catch (error: Throwable) { failure.set(error) } }
            failure.get()?.let { throw it }
        }
        fun save(image: Bitmap, name: String) {
            File(evidence, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        try {
            val styles = options.getString("style")?.let {
                require(it in BottomNavigationStyle.choices) { "Unknown style: $it" }; listOf(it)
            } ?: BottomNavigationStyle.choices.keys.toList()
            fun booleanOption(name: String): List<Boolean> = options.getString(name)?.let {
                listOf(when (it) {
                    "true" -> true
                    "false" -> false
                    else -> error("$name must be true or false")
                })
            } ?: listOf(false, true)
            val directions = booleanOption("rtl")
            val motions = if (options.containsKey("motion")) booleanOption("motion") else listOf(true, false)
            val counts = options.getString("count")?.let {
                val count = it.toIntOrNull()
                require(count != null && count in 3..4) { "count must be 3 or 4" }
                listOf(count)
            } ?: listOf(3, 4)
            report.append("INFO selection: styles=$styles rtl=$directions motion=$motions count=$counts\n")
            val host = startActivitySync(Intent(targetContext, BottomNavigationDragActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BottomNavigationDragActivity
            activity = host
            check(host.javaClass == BottomNavigationDragActivity::class.java) { "Unexpected navigation fixture host" }
            report.append("INFO fixture host=${host.javaClass.name} process=${android.app.Application.getProcessName()} application=${host.application.javaClass.name} ownedSentinel=${ProbeCoordinator.measurements?.ids == setOf("fixture-navigation")}\n")
            val nav = host.navigation
            val centerField = BottomNavigationView::class.java.getDeclaredField("centerX").apply { isAccessible = true }
            val yField = BottomNavigationView::class.java.getDeclaredField("centerY").apply { isAccessible = true }
            val playbackField = BottomNavigationView::class.java.getDeclaredField("playback").apply { isAccessible = true }
            val dragField = BottomNavigationView::class.java.getDeclaredField("drag").apply { isAccessible = true }
            val paintCenterMethod = BottomNavigationView::class.java.getDeclaredMethod("movingPaintCenter").apply { isAccessible = true }
            val halfWidthMethod = BottomNavigationView::class.java.getDeclaredMethod("dragPillHalfWidth").apply { isAccessible = true }
            val regionMethod = BottomNavigationView::class.java.getDeclaredMethod("drawRegion", Canvas::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType).apply { isAccessible = true }
            val labelField = BottomNavigationView::class.java.getDeclaredField("label").apply { isAccessible = true }
            val labelWeightsField = BottomNavigationView::class.java.getDeclaredField("labelWeights").apply { isAccessible = true }
            val stretchField = BottomNavigationView::class.java.getDeclaredField("stretch").apply { isAccessible = true }
            val directionField = BottomNavigationView::class.java.getDeclaredField("direction").apply { isAccessible = true }
            val weightsField = BottomNavigationView::class.java.getDeclaredField("selectionWeights").apply { isAccessible = true }
            val fromXField = BottomNavigationView::class.java.getDeclaredField("fromX").apply { isAccessible = true }
            fun center() = centerField.getFloat(nav) * nav.resources.displayMetrics.density
            fun image(horizontalPadding: Int = 0, inspect: ((Bitmap) -> Unit)? = null): Bitmap {
                lateinit var bitmap: Bitmap
                main {
                    val overhang = (48 * nav.resources.displayMetrics.density).roundToInt()
                    bitmap = Bitmap.createBitmap(nav.width + horizontalPadding * 2, nav.height + overhang, Bitmap.Config.ARGB_8888).also {
                        val canvas = Canvas(it); canvas.translate(horizontalPadding.toFloat(), overhang.toFloat()); nav.draw(canvas)
                    }
                    inspect?.invoke(bitmap)
                }
                return bitmap
            }
            fun frame(name: String) {
                val bitmap = image(); save(bitmap, name); bitmap.recycle()
                val screen = checkNotNull(uiAutomation.takeScreenshot()) { "No device screenshot" }
                save(screen, "$name-screen"); screen.recycle()
            }
            captureFailure = { frame("$caseKey-failure-$stage") }
            // Called on the UI thread. Exercise the actual fill renderer without
            // icons/labels, then verify its unobstructed top corner in the full UI.
            fun roundedRegion(composite: Bitmap, centerPx: Float, halfPx: Float, cornerPx: Float, row: Int, name: String) {
                val density = nav.resources.displayMetrics.density
                val top = BottomNavigationGeometry.top(host.variant) * density
                val overhang = (48 * density).roundToInt()
                val compact = host.variant == BottomNavigationStyle.SLIDING || host.variant == BottomNavigationStyle.LIQUID
                val shape = BottomNavigationGeometry.compactHighlight(host.variant, stretchField.getFloat(nav), directionField.getFloat(nav))
                val centerRow = overhang + (top + 28 * density).roundToInt()
                val lowerRow = if (compact) centerRow + ((shape.height/2-2)*density).roundToInt() else row
                val upperRow = if (compact) centerRow - ((shape.height/2-2)*density).roundToInt() else overhang + (top + 4 * density).roundToInt()
                val x = if (compact) (centerPx+(shape.half-2)*density).roundToInt() else cornerPx.roundToInt()
                val isolated = Bitmap.createBitmap(composite.width, composite.height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(isolated)
                    canvas.drawColor(Loft.SURFACE); canvas.translate(0f, overhang.toFloat())
                    regionMethod.invoke(nav, canvas, centerPx / density, halfPx / density, top, top + 56 * density)
                    save(isolated, "$caseKey-$name-fill-only")
                    fun hex(color: Int) = "%08x".format(color)
                    report.append("INFO $caseKey/$name: compact=$compact geometry=$shape lower=($x,$lowerRow) composite=${hex(composite.getPixel(x,lowerRow))} fillOnly=${hex(isolated.getPixel(x,lowerRow))} surface=${hex(Loft.SURFACE)} upper=($x,$upperRow) composite=${hex(composite.getPixel(x,upperRow))} center=$centerPx availableHalf=$halfPx\n")
                    if (host.variant == BottomNavigationStyle.FLOATING) {
                        val textPaint = labelField.get(nav) as TextPaint
                        val weights = labelWeightsField.get(nav) as List<*>
                        val content = BottomNavigationMotion.singleLabel(weights.map { it as Float })
                        val textHeight = textPaint.descent() - textPaint.ascent()
                        val labelY = overhang + top + minOf(47*density, 56*density-textHeight/2-2*density)
                        for (i in 0 until nav.childCount) {
                            val cell = nav.getChildAt(i)
                            val text = TextUtils.ellipsize(cell.contentDescription, textPaint,
                                (cell.width-12*density).coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
                            val labelHalf = textPaint.measureText(text)/2
                            val labelCenter = (cell.left+cell.right)/2f
                            val overlaps = cornerPx >= labelCenter-labelHalf && cornerPx <= labelCenter+labelHalf &&
                                row >= labelY-textHeight/2 && row <= labelY+textHeight/2
                            report.append("INFO $caseKey/$name: label=$i opacity=${if (i == content.index) content.opacity else 0f} bounds=[${labelCenter-labelHalf},${labelY-textHeight/2},${labelCenter+labelHalf},${labelY+textHeight/2}] lowerSampleInside=$overlaps ink=${hex(Loft.INK)}\n")
                        }
                    }
                    check(isolated.getPixel(centerPx.roundToInt(),centerRow) != Loft.SURFACE) { "$caseKey/$name: isolated fill interior missing" }
                    check(isolated.getPixel(x,lowerRow) == Loft.SURFACE) { "$caseKey/$name: actual fill has square lower corner" }
                    check(composite.getPixel(x,upperRow) == Loft.SURFACE) { "$caseKey/$name: composed fill has square upper corner" }
                    if (compact) {
                        val outside = (centerPx+(shape.half+2)*density).roundToInt()
                        if (outside in 0 until isolated.width) check(isolated.getPixel(outside,centerRow) == Loft.SURFACE) {
                            "$caseKey/$name: broad fill outside compact silhouette"
                        }
                        check(isolated.getPixel(centerPx.roundToInt(),overhang+(52*density).roundToInt()) == Loft.SURFACE) {
                            "$caseKey/$name: old full-height fill remains"
                        }
                    }
                } finally { isolated.recycle() }
            }
            var downTime = 0L
            fun input(action: Int, x: Float, y: Float) {
                if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
                val location = IntArray(2)
                main { nav.getLocationOnScreen(location) }
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, location[0] + x, location[1] + y, 0).let {
                    try { sendPointerSync(it) } finally { it.recycle() }
                }
                Thread.sleep(35)
            }
            fun secondPointer(action: Int, x: Float, y: Float) {
                val location = IntArray(2)
                main { nav.getLocationOnScreen(location) }
                val properties = Array(2) { id -> MotionEvent.PointerProperties().apply {
                    this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val coordinates = Array(2) { id -> MotionEvent.PointerCoords().apply {
                    this.x = location[0] + x + if (id == 0) 0f else -12 * nav.resources.displayMetrics.density
                    this.y = location[1] + y; pressure = 1f; size = 1f
                } }
                MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0).let {
                    try { sendPointerSync(it) } finally { it.recycle() }
                }
                Thread.sleep(35)
            }
            fun settled(index: Int, step: String) {
                stage = step
                val deadline = SystemClock.uptimeMillis() + 2500
                while (SystemClock.uptimeMillis() < deadline) {
                    var done = false
                    main { done = (playbackField.get(nav) as BottomNavigationPlayback).progress >= 1f }
                    if (done) break
                    Thread.sleep(32)
                }
                main {
                    check(host.chosen == index) { "$caseKey/$stage: Selected ${host.chosen}, expected $index; commits=${host.commits}" }
                    check((playbackField.get(nav) as BottomNavigationPlayback).progress == 1f) { "$caseKey/$stage: Did not settle" }
                    val cell = nav.getChildAt(index)
                    check(cell.isSelected) { "$caseKey/$stage: cell not selected" }
                    val actualCenter = center()
                    val expectedCenter = (cell.left + cell.right) / 2f
                    check(abs(actualCenter - expectedCenter) < 2) {
                        "$caseKey/$stage: Final indicator off-center actual=$actualCenter expected=$expectedCenter " +
                            "chosen=${host.chosen} commits=${host.commits} progress=${(playbackField.get(nav) as BottomNavigationPlayback).progress} " +
                            "bounds=${cell.left}..${cell.right} width=${nav.width} focused=${host.hasWindowFocus()} " +
                            "attached=${nav.isAttachedToWindow} layoutRequested=${nav.isLayoutRequested} drag=${(dragField.get(nav) as BottomNavigationDrag).state}"
                    }
                }
            }
            fun tapFrames(index: Int, name: String, stopAt: Float = 1f, injectInterruption: Boolean = false) {
                stage = name
                var previous = 0f
                var interrupting = false
                var clickedProgress = Float.NaN
                var tapX = 0f; var tapY = 0f
                main {
                    host.beforeNavigate = {
                        previous = center()
                        clickedProgress = (playbackField.get(nav) as BottomNavigationPlayback).progress
                    }
                    interrupting = (playbackField.get(nav) as BottomNavigationPlayback).progress < 1f
                    val cell = nav.getChildAt(index)
                    tapX = (cell.left+cell.right)/2f; tapY = nav.height-20*nav.resources.displayMetrics.density
                    if (interrupting && !injectInterruption) check(cell.performClick()) // Atomic retained-frame assertion retained.
                }
                if (!interrupting || injectInterruption) {
                    input(MotionEvent.ACTION_DOWN,tapX,tapY)
                    input(MotionEvent.ACTION_UP,tapX,tapY)
                }
                main {
                    check(clickedProgress.isFinite()) { "$caseKey/$name: injected tap never reached navigation" }
                    if (injectInterruption) check(interrupting && clickedProgress < 1f) {
                        "$caseKey/$name: injected DOWN/UP did not interrupt an active transition"
                    }
                    if (host.motion) check(abs(fromXField.getFloat(nav)*nav.resources.displayMetrics.density-previous) < .1f) {
                        "$caseKey/$name: interrupted tap did not retain current painted position"
                    }
                    report.append("INFO $caseKey/$name: input=${if (interrupting && !injectInterruption) "performClick" else "injected-DOWN-UP"} previousProgress=$clickedProgress callbackCenter=$previous newFromX=${fromXField.getFloat(nav)}\n")
                    host.beforeNavigate = null
                }
                val thresholds = listOf(.08f,.2f,.35f,.5f,.65f,.8f,.95f)
                var next = 0; var progress = 0f
                val positions = mutableSetOf<Int>()
                var settleProbe: List<Pair<Pair<Int,Int>,Int>>? = null
                val deadline = SystemClock.uptimeMillis()+3000
                while (SystemClock.uptimeMillis() < deadline) {
                    val bitmap = image(inspect = { captured ->
                        progress = (playbackField.get(nav) as BottomNavigationPlayback).progress
                        val weights = (weightsField.get(nav) as List<*>).map { it as Float }
                        check(abs(weights.sum()-1f) < .001f) { "$caseKey/$name: zero/unnormalized active weights at $progress: $weights" }
                        check(BottomNavigationMotion.singleLabel(weights).index >= 0) { "$caseKey/$name: no active icon" }
                        if (host.variant == BottomNavigationStyle.EXPANDING)
                            check((halfWidthMethod.invoke(nav) as Float) > 20f) { "$caseKey/$name: tap pill collapsed at $progress" }
                        val position = center()
                        if (progress < 1f) positions.add(position.roundToInt())
                        if (host.variant == BottomNavigationStyle.EXPANDING && progress >= .91f && progress < 1f) {
                            val density = nav.resources.displayMetrics.density
                            val paintCenter = (paintCenterMethod.invoke(nav) as Float)*density
                            val half = (halfWidthMethod.invoke(nav) as Float)*density
                            val row = ((48+10)*density).roundToInt()
                            settleProbe = listOf(-.25f,0f,.25f).map { offset ->
                                val x = (paintCenter+half*offset).roundToInt()
                                (x to row) to captured.getPixel(x,row)
                            }
                            save(captured,"$caseKey-$name-before-settle")
                        }
                        report.append("INFO $caseKey/$name: progress=$progress center=$position weights=$weights\n")
                        if (next < thresholds.size && progress >= thresholds[next]) {
                            save(captured,"$caseKey-$name-${next++}")
                            if (host.variant == BottomNavigationStyle.SLIDING || host.variant == BottomNavigationStyle.LIQUID) {
                                val half = nav.getChildAt(0).width/2f
                                roundedRegion(captured,center(),half,center()+half-2*nav.resources.displayMetrics.density,
                                    (100*nav.resources.displayMetrics.density).roundToInt(),"$name-${next-1}")
                            }
                        }
                    })
                    bitmap.recycle()
                    if (progress >= stopAt) break
                    Thread.sleep(16)
                }
                check(progress >= stopAt) { "$caseKey/$name: transition stalled" }
                if (host.motion && stopAt == 1f) check(positions.size >= 3 && next >= 3) {
                    "$caseKey/$name: fewer than three distinct visible intermediate tap positions"
                }
                if (stopAt == 1f) {
                    settled(index,name)
                    if (host.motion && host.variant == BottomNavigationStyle.EXPANDING) {
                        val before = checkNotNull(settleProbe) { "$caseKey/$name: no pre-settle frame for gradient continuity" }
                        val after = image()
                        save(after,"$caseKey-$name-after-settle")
                        try {
                            for ((point,color) in before) {
                                val actual = after.getPixel(point.first,point.second)
                                report.append("INFO $caseKey/$name: settle pixel=$point before=${"%08x".format(color)} after=${"%08x".format(actual)}\n")
                                check(actual == color) { "$caseKey/$name: fill color jumped at settle pixel=$point" }
                            }
                        } finally { after.recycle() }
                    }
                }
            }
            // Change the retained renderer in place, including a saved removed
            // style. No touch/navigation/layout() call may be needed to paint it.
            stage = "style-refresh-without-touch"
            for (animate in listOf(false, true)) for (next in BottomNavigationStyle.choices.keys + listOf(BottomNavigationStyle.LIQUID) + BottomNavigationStyle.choices.keys) {
                val drawn = java.util.concurrent.CountDownLatch(1)
                val observer = android.view.ViewTreeObserver.OnDrawListener { drawn.countDown() }
                main {
                    nav.viewTreeObserver.addOnDrawListener(observer)
                    host.motion = animate; host.chosen = 0; host.variant = next; host.bind()
                }
                try {
                    // image() calls nav.draw() itself and can conceal a missing
                    // invalidation. Require a window traversal before that probe.
                    check(drawn.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        "Style refresh did not schedule a window draw without input: $next"
                    }
                } finally {
                    main { nav.viewTreeObserver.removeOnDrawListener(observer) }
                }
                waitForIdleSync()
                settled(0, "style-refresh-without-touch-$next-$animate")
                val refreshed = image()
                try {
                    main {
                        check(nav.isShown && nav.width > 0 && nav.height > 0) { "Style refresh hid navigation: $next" }
                        val first = nav.getChildAt(0)
                        check(abs(center() - (first.left + first.right) / 2f) < 2) { "Style refresh needs input to restore selection: $next" }
                        val density = nav.resources.displayMetrics.density
                        val sampleY = if (BottomNavigationStyle.resolve(next) == BottomNavigationStyle.FLOATING)
                            yField.getFloat(nav) else BottomNavigationGeometry.top(BottomNavigationStyle.resolve(next)) + 28f
                        val x = (center() + 13 * density).roundToInt()
                        val row = ((48 + sampleY) * density).roundToInt()
                        check(refreshed.getPixel(x, row) != Loft.SURFACE && (refreshed.getPixel(x, row) ushr 24) != 0) {
                            "Style refresh did not paint selection without input: $next"
                        }
                        check(host.commits == 0) { "Style refresh navigated" }
                    }
                } finally { refreshed.recycle() }
            }
            report.append("PASS retained style changes and saved liquid fallback paint without touch/navigation\n")
            for (style in styles) for (rtl in directions)
                for (motion in motions) for (count in counts) {
                val key = "$style-${if (rtl) "rtl" else "ltr"}-${if (motion) "animated" else "reduced"}-$count-tabs"
                caseKey = key; stage = "configure"
                main {
                    Loft.apply(!motion)
                    host.motion = false; host.chosen = 0; host.commits = 0; host.variant = style
                    host.count = count
                    nav.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    host.bind()
                }
                Thread.sleep(100)
                main { host.motion = motion }
                settled(0, "initial")
                tapFrames(count-1,"tap-end-to-end")
                tapFrames(0,"tap-return")
                if (motion) {
                    tapFrames(count-1,"tap-before-interrupt",.35f)
                    tapFrames(0,"tap-rapid-reverse")
                    tapFrames(count-1,"tap-before-injected-interrupt",.35f)
                    tapFrames(0,"tap-injected-rapid-reverse",injectInterruption = true)
                }
                main { check(host.chosen == 0); host.commits = 0 }
                main {
                    val expectedDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    check(nav.layoutDirection == expectedDirection) {
                        "$key/initial: requested direction=$expectedDirection resolved=${nav.layoutDirection}; RTL fixture support required"
                    }
                }
                // Sweep physical right -> centre -> left while still holding.
                // This checks actual painted interiors, not only a changed ID.
                stage = "held-physical-sweep"
                var leftCenter = 0f; var rightCenter = 0f; var sweepY = 0f
                main {
                    val centers = (0 until nav.childCount).map { i ->
                        val child = nav.getChildAt(i); (child.left + child.right) / 2f
                    }
                    leftCenter = centers.min(); rightCenter = centers.max()
                    sweepY = nav.height - 20 * nav.resources.displayMetrics.density
                }
                input(MotionEvent.ACTION_DOWN, rightCenter, sweepY)
                for ((sample, fraction) in listOf(.8f, .6f, .4f, .2f, 0f, .25f, .5f, .75f, 1f).withIndex()) {
                    val finger = leftCenter + (rightCenter - leftCenter) * fraction
                    input(MotionEvent.ACTION_MOVE, finger, sweepY)
                    var paintedCenter = finger
                    main {
                        check(abs(center() - finger) < 2) { "$key/$stage/$sample: pointer tracking failed" }
                        check(host.commits == 0 && host.chosen == 0) { "$key/$stage/$sample: premature navigation" }
                        if (style == BottomNavigationStyle.EXPANDING) {
                            val density = nav.resources.displayMetrics.density
                            val gesture = dragField.get(nav) as BottomNavigationDrag
                            val destination = gesture.destination()
                            paintedCenter = (paintCenterMethod.invoke(nav) as Float) * density
                            val half = (halfWidthMethod.invoke(nav) as Float) * density
                            check(paintedCenter - half >= -.01f && paintedCenter + half <= nav.width + .01f) {
                                "$key/$stage/$sample: expanding paint outside viewport center=$paintedCenter half=$half width=${nav.width}"
                            }
                            // Use the actual DOWN snapshot's destination; the
                            // paint clamp must never mutate its sampled coordinate.
                            check(abs(gesture.x * density - finger) < 2) { "$key/$stage/$sample: paint clamp altered gesture" }
                            check(gesture.destination() == destination) { "$key/$stage/$sample: paint clamp altered destination" }
                        }
                    }
                    val held = image()
                    val density = nav.resources.displayMetrics.density
                    val fillY = ((48 + 28) * density).roundToInt()
                    if (style != BottomNavigationStyle.FLOATING) {
                        check(held.getPixel(paintedCenter.roundToInt(), fillY) != Loft.SURFACE) {
                            "$key/$stage/$sample: hollow or missing moving interior"
                        }
                        val above = (48 * density).roundToInt() - 1
                        check((0 until held.width).all { x -> held.getPixel(x, above) ushr 24 == 0 }) {
                            "$key/$stage/$sample: local fill/text protruded above rectangular bar"
                        }
                    }
                    save(held, "$key-sweep-$sample"); held.recycle()
                    if (style == BottomNavigationStyle.EXPANDING) {
                        val padding = (96 * density).roundToInt()
                        val padded = image(padding)
                        val firstRow = (48 * density).roundToInt()
                        check((firstRow until padded.height).all { row ->
                            (0 until padding).all { x -> padded.getPixel(x, row) ushr 24 == 0 } &&
                                (padding + nav.width until padded.width).all { x -> padded.getPixel(x, row) ushr 24 == 0 }
                        }) { "$key/$stage/$sample: expanding pill/text painted beyond viewport" }
                        save(padded, "$key-sweep-$sample-viewport"); padded.recycle()
                    }
                }
                input(MotionEvent.ACTION_CANCEL, rightCenter, sweepY)
                settled(0, "held-physical-sweep-cancel")
                var start = 0f; var end = 0f; var y = 0f
                main {
                    start = (nav.getChildAt(0).left + nav.getChildAt(0).right) / 2f
                    val last = nav.getChildAt(host.count - 1)
                    end = (last.left + last.right) / 2f
                    y = nav.height - 20 * nav.resources.displayMetrics.density
                    if (style == BottomNavigationStyle.FLOATING) {
                        check(nav.height == (56 * nav.resources.displayMetrics.density).roundToInt()) { "Floating reserved extra height" }
                        check(!(nav.parent as android.view.ViewGroup).clipChildren)
                        check(host.pageContent.bottom == nav.top) { "Gap reserved between page UI and floating bar" }
                        check(yField.getFloat(nav) - FloatingNotchMotion.CIRCLE / 2f < 0)
                    }
                }
                if (style == BottomNavigationStyle.EXPANDING) {
                    // Release in the wide selected slot just before its frozen edge:
                    // nearest-center hit tests incorrectly choose the neighbour here.
                    var inside = 0f; var outside = 0f
                    fun sharedEdge(cell: View, neighbour: View): Pair<Float, Float> = when {
                        cell.right == neighbour.left -> cell.right.toFloat() to 1f
                        cell.left == neighbour.right -> cell.left.toFloat() to -1f
                        else -> error("$key/frozen-edge: cells are not adjacent: selected=[${cell.left},${cell.right}) neighbour=[${neighbour.left},${neighbour.right})")
                    }
                    main {
                        val cell = nav.getChildAt(0); val gap = 2 * nav.resources.displayMetrics.density
                        val neighbour = nav.getChildAt(1)
                        check(cell.width > neighbour.width)
                        val (edge, towardNeighbour) = sharedEdge(cell, neighbour)
                        inside = edge - towardNeighbour * gap
                        outside = edge + towardNeighbour * gap
                        check(inside >= cell.left && inside < cell.right && outside >= neighbour.left && outside < neighbour.right) {
                            "$key/frozen-edge: invalid shared-edge samples inside=$inside outside=$outside"
                        }
                    }
                    stage = "frozen-edge-inside-input"
                    input(MotionEvent.ACTION_DOWN, start, y)
                    input(MotionEvent.ACTION_MOVE, inside, y)
                    frame("$key-frozen-inside-before-up")
                    input(MotionEvent.ACTION_UP, inside, y)
                    settled(0, "frozen-edge-inside"); main { check(host.commits == 0) }
                    // A same-selection gesture must restore the original edges.
                    // Do not move the release point to accommodate a failed restore.
                    main {
                        val cell = nav.getChildAt(0); val gap = 2 * nav.resources.displayMetrics.density
                        val restoredStart = (cell.left + cell.right) / 2f
                        val neighbour = nav.getChildAt(1)
                        val (edge, towardNeighbour) = sharedEdge(cell, neighbour)
                        val restoredOutside = edge + towardNeighbour * gap
                        check(abs(restoredStart - start) < 1 && abs(restoredOutside - outside) < 1) {
                            "$key/frozen-edge-restore: start=$start restored=$restoredStart outside=$outside restoredOutside=$restoredOutside"
                        }
                        check(outside >= neighbour.left && outside < neighbour.right) {
                            "$key/frozen-edge-outside: x=$outside not in neighbour [${neighbour.left},${neighbour.right})"
                        }
                        report.append("INFO $key/frozen-edge-outside: start=$start release=$outside slots=" +
                            (0 until nav.childCount).joinToString { i ->
                                val child = nav.getChildAt(i); "$i:[${child.left},${child.right})"
                            } + "\n")
                    }
                    stage = "frozen-edge-outside-input"
                    input(MotionEvent.ACTION_DOWN, start, y)
                    input(MotionEvent.ACTION_MOVE, outside, y)
                    frame("$key-frozen-outside-before-up")
                    main {
                        val gesture = dragField.get(nav) as BottomNavigationDrag
                        report.append("INFO $key/$stage: state=${gesture.state} destination=${gesture.destination()} x=${gesture.x} centers=${gesture.centers}\n")
                        check(gesture.state == BottomNavigationDrag.State.DRAGGING && gesture.destination() == 1) {
                            "$key/$stage: frozen neighbour not reached: state=${gesture.state} destination=${gesture.destination()}"
                        }
                        check(host.chosen == 0 && host.commits == 0) { "$key/$stage: committed before UP" }
                    }
                    input(MotionEvent.ACTION_UP, outside, y)
                    settled(1, "frozen-edge-outside"); main { check(host.commits == 1) }
                    frame("$key-frozen-edge-release")
                    main { host.motion = false; host.chosen = 0; host.commits = 0; host.bind() }
                    Thread.sleep(80)
                    main { host.motion = motion }
                    settled(0, "frozen-edge-reset")
                }
                val baseline = image(); save(baseline, "$key-start")
                run {
                    stage = "pressed-fill"
                    // Holding another cell must not create a second selection
                    // or a ripple that survives release beside the moving fill.
                    input(MotionEvent.ACTION_DOWN, end, y)
                    main { check(host.commits == 0 && host.chosen == 0) }
                    val pressed = image()
                    save(pressed, "$key-pressed")
                    val regionY = ((48 + BottomNavigationGeometry.top(style) + 52) * nav.resources.displayMetrics.density).roundToInt()
                    check(pressed.sameAs(baseline)) {
                        "$key DOWN created a second fill/ripple before selection"
                    }
                    check(pressed.getPixel(start.roundToInt(), regionY) == baseline.getPixel(start.roundToInt(), regionY)) {
                        "$key DOWN changed the selected region"
                    }
                    main {
                        val inset = 2 * nav.resources.displayMetrics.density
                        val half = nav.getChildAt(host.count - 1).width / 2f
                        val cornerX = end + (if (rtl) 1 else -1) * (half - inset)
                        if (style == BottomNavigationStyle.SLIDING || style == BottomNavigationStyle.LIQUID) {
                            val selectedHalf = nav.getChildAt(0).width/2f
                            roundedRegion(pressed, start, selectedHalf, start+selectedHalf-inset, regionY, "pressed-corner")
                        }
                    }
                    pressed.recycle()
                    input(MotionEvent.ACTION_CANCEL, end, y)
                    settled(0, "pressed-cancel")
                }
                val intermediate = start + (end - start) * .42f
                stage = "held-preview"
                input(MotionEvent.ACTION_DOWN, start, y)
                input(MotionEvent.ACTION_MOVE, intermediate, y)
                main { check(abs(center() - intermediate) < 2) { "$key first intercepted MOVE did not paint" } }
                Thread.sleep(120) // Still holding: a time-driven click animation cannot satisfy this.
                main {
                    check(host.commits == 0 && host.chosen == 0) { "$key navigated before ACTION_UP" }
                    check(abs(center() - intermediate) < 2) { "$key indicator did not track held pointer" }
                }
                val middle = image()
                save(middle, "$key-before-up")
                var sampleY = 0
                main {
                    val density = nav.resources.displayMetrics.density
                    sampleY = (48 * density).roundToInt() + when (style) {
                        BottomNavigationStyle.FLOATING -> (yField.getFloat(nav) * density).roundToInt()
                        BottomNavigationStyle.LIQUID -> ((BottomNavigationGeometry.top(style) + 28) * density).roundToInt()
                        else -> ((BottomNavigationGeometry.top(style) + 28) * density).roundToInt()
                    }
                }
                var changed = 0
                val cx = intermediate.roundToInt()
                for (py in (sampleY - 3).coerceAtLeast(0)..(sampleY + 3).coerceAtMost(middle.height - 1))
                    for (px in cx - 3..cx + 3)
                        if (middle.getPixel(px, py) != baseline.getPixel(px, py) && (middle.getPixel(px, py) ushr 24) != 0) changed++
                check(changed >= 20) { "$key no painted indicator at intermediate pointer ($changed pixels)" }
                if (style == BottomNavigationStyle.SLIDING || style == BottomNavigationStyle.LIQUID) {
                    val regionY = ((48 + BottomNavigationGeometry.top(style) + if (style == BottomNavigationStyle.FLOATING) 52 else 28) * nav.resources.displayMetrics.density).roundToInt()
                    check(middle.getPixel(cx, regionY) != Loft.SURFACE) { "$key missing compact localized fill" }
                    check(middle.getPixel(end.roundToInt(), regionY) == baseline.getPixel(end.roundToInt(), regionY)) { "$key recolored an inactive region" }
                    main {
                        val cornerX = intermediate + nav.getChildAt(0).width / 2f - 2 * nav.resources.displayMetrics.density
                        roundedRegion(middle, intermediate, nav.getChildAt(0).width / 2f,
                            cornerX, regionY, "moving-corner")
                    }
                }
                if (style == BottomNavigationStyle.FLOATING) main {
                    val density = nav.resources.displayMetrics.density
                    val circleRow = ((48 + yField.getFloat(nav)) * density).roundToInt()
                    val inside = (center() + (FloatingNotchMotion.CIRCLE / 2f - 3) * density).roundToInt()
                    check(middle.getPixel(inside, circleRow) != Loft.SURFACE && (middle.getPixel(inside, circleRow) ushr 24) != 0) {
                        "$key compact floating circle missing"
                    }
                    val plainRow = ((48 + BottomNavigationGeometry.top(style) + 30) * density).roundToInt()
                    check(middle.getPixel(cx, plainRow) == Loft.SURFACE) { "$key floating style kept an oversized tab-wide fill" }
                }
                middle.recycle(); baseline.recycle()
                frame("$key-held")
                if (style == BottomNavigationStyle.FLOATING) {
                    val screen = checkNotNull(uiAutomation.takeScreenshot())
                    val location = IntArray(2); main { nav.getLocationOnScreen(location) }
                    val row = location[1] - (8 * nav.resources.displayMetrics.density).roundToInt()
                    check(screen.getPixel(location[0] + 4, row) == 0xff8bbac3.toInt()) { "Page content does not reach floating bar" }
                    val overlay = image()
                    val expected = overlay.getPixel(cx, (48 * nav.resources.displayMetrics.density).roundToInt() + row - location[1])
                    overlay.recycle()
                    val actual = screen.getPixel(location[0] + cx, row)
                    check((expected ushr 24) == 255 && listOf(0, 8, 16).all { shift ->
                        abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 3
                    }) { "Raised circle clipped above page UI" }
                    save(screen, "$key-overlay-page"); screen.recycle()
                }
                // Rapid reversal while held must track back immediately and keep the original selection.
                input(MotionEvent.ACTION_MOVE, start + (end - start) * .8f, y)
                val reverse = start + (end - start) * .25f
                input(MotionEvent.ACTION_MOVE, reverse, y)
                main { check(host.commits == 0); check(abs(center() - reverse) < 2) { "$key reversal lagged" } }
                frame("$key-reverse")
                input(MotionEvent.ACTION_MOVE, end, y)
                main { check(host.commits == 0) }
                input(MotionEvent.ACTION_UP, end, y)
                settled(host.count - 1, "forward-release")
                main { check(host.commits == 1) }
                frame("$key-released")

                // Reverse to the first tab and CANCEL: restore the last committed tab.
                var origin = 0f
                main {
                    val cell = nav.getChildAt(host.count - 1); origin = (cell.left + cell.right) / 2f
                    // Expanding changed all widths after release. A reverse
                    // gesture freezes this new layout, including its first slot.
                    val first = nav.getChildAt(0); start = (first.left + first.right) / 2f
                }
                input(MotionEvent.ACTION_DOWN, origin, y)
                val cancelX = origin + (start - origin) * .7f
                input(MotionEvent.ACTION_MOVE, cancelX, y)
                main { check(abs(center() - cancelX) < 2); check(host.commits == 1) }
                frame("$key-before-cancel")
                input(MotionEvent.ACTION_CANCEL, cancelX, y)
                settled(host.count - 1, "reverse-cancel")
                main { check(host.commits == 1) }
                frame("$key-cancel-restored")

                // Vertical and outside-and-return must remain rejected until a new DOWN.
                input(MotionEvent.ACTION_DOWN, origin, y)
                input(MotionEvent.ACTION_MOVE, origin, y - nav.resources.displayMetrics.density * 18)
                input(MotionEvent.ACTION_MOVE, start, y)
                input(MotionEvent.ACTION_UP, start, y)
                settled(host.count - 1, "vertical-reject")
                input(MotionEvent.ACTION_DOWN, origin, y)
                input(MotionEvent.ACTION_MOVE, cancelX, y)
                input(MotionEvent.ACTION_MOVE, cancelX, -2f)
                input(MotionEvent.ACTION_MOVE, start, y)
                input(MotionEvent.ACTION_UP, start, y)
                settled(host.count - 1, "outside-return-reject")
                main { check(host.commits == 1) }
                // A second pointer rejects both a pending press and an active
                // drag. Lifting it must not resurrect the original pointer stream.
                for (alreadyDragging in listOf(false, true)) {
                    input(MotionEvent.ACTION_DOWN, origin, y)
                    val pointerX = if (alreadyDragging) cancelX else origin
                    if (alreadyDragging) input(MotionEvent.ACTION_MOVE, pointerX, y)
                    secondPointer(MotionEvent.ACTION_POINTER_DOWN, pointerX, y)
                    secondPointer(MotionEvent.ACTION_POINTER_UP, pointerX, y)
                    input(MotionEvent.ACTION_MOVE, start, y)
                    input(MotionEvent.ACTION_UP, start, y)
                    settled(host.count - 1, "multi-pointer-reject-dragging=$alreadyDragging")
                    main { check(host.commits == 1) { "$key multi-pointer stream committed" } }
                }
                // A complete reverse swipe commits the first tab only on release.
                input(MotionEvent.ACTION_DOWN, origin, y)
                input(MotionEvent.ACTION_MOVE, start, y)
                main { check(host.commits == 1 && host.chosen == host.count - 1) }
                input(MotionEvent.ACTION_UP, start, y)
                settled(0, "reverse-release"); main { check(host.commits == 2) }
                // Duplicate terminal events cannot replay the navigation callback.
                main {
                    MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, start, y, 0).let {
                        try { nav.dispatchTouchEvent(it) } finally { it.recycle() }
                    }
                    check(host.commits == 2) { "$key duplicate UP committed twice" }
                }
                frame("$key-reverse-released")
                // Accessibility action still reaches the ordinary click listener.
                main { check(nav.getChildAt(host.count - 1).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)) }
                settled(host.count - 1, "accessibility-click")
                main { check(host.commits == 3) }
                // A real tap on the last cell (with sub-slop motion) still commits once.
                main { val cell = nav.getChildAt(0); end = (cell.left + cell.right) / 2f }
                input(MotionEvent.ACTION_DOWN, end, y)
                input(MotionEvent.ACTION_MOVE, end + 1, y)
                input(MotionEvent.ACTION_UP, end + 1, y)
                settled(0, "sub-slop-tap")
                main { check(host.commits == 4) }
                // The first beyond-slop sample may be UP itself. Intercepting
                // it cancels the child without calling the parent's onTouchEvent.
                main {
                    val cell = nav.getChildAt(host.count - 1)
                    end = (cell.left + cell.right) / 2f
                }
                input(MotionEvent.ACTION_DOWN, start, y)
                input(MotionEvent.ACTION_UP, end, y)
                settled(host.count - 1, "terminal-only-release")
                main {
                    check(host.commits == 5) { "$key terminal-only displacement did not commit once" }
                    MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, end, y, 0).let {
                        try { nav.dispatchTouchEvent(it) } finally { it.recycle() }
                    }
                    check(host.commits == 5) { "$key terminal-only UP replayed" }
                }
                report.append("PASS $key: multi-frame taps/end-to-end/rapid interruption, first MOVE/held pixels=$changed, single compact selection/no DOWN ghosts, release once, reverse, CANCEL, vertical/outside/multi-pointer rejection, accessibility, tap, terminal-only displacement/replay\n")
            }
        } catch (error: Throwable) {
            result = Activity.RESULT_CANCELED
            report.append("FAIL $caseKey/$stage: ").append(error.stackTraceToString())
            runCatching { captureFailure?.invoke() }.onFailure {
                report.append("\nFAIL evidence capture: ${it.javaClass.simpleName}\n")
            }
        } finally {
            // Compare while the isolated host is foreground. Finishing first can
            // resume MainActivity and trigger unrelated foreground randomization.
            runCatching { checkNoMainProcess(force = true) }.onFailure {
                result = Activity.RESULT_CANCELED
                report.append("FAIL process isolation: ${it.message}\n")
            }
            val after = protectedFiles()
            val added = (after.keys - before.keys).sorted()
            val removed = (before.keys - after.keys).sorted()
            val changed = (before.keys intersect after.keys).filter { before[it] != after[it] }.sorted()
            report.append("INFO protected guard: ignoredRuntimeFiles=$ignoredRuntimeFiles (exact ActivityThread.IDS.xml only)\n")
            if (added.isNotEmpty() || removed.isNotEmpty() || changed.isNotEmpty()) {
                result = Activity.RESULT_CANCELED
                report.append("FAIL protected preferences/files changed: added=${added.size} removed=${removed.size} changed=${changed.size}\n")
                report.append("added=$added\nremoved=$removed\nchanged=$changed\n")
            } else report.append("PASS all shared preferences (including random/style) and internal files unchanged\n")
            main(checkIsolation = false) { Loft.apply(originalDark); activity?.finish() }
            File(evidence, "report.txt").writeText(report.toString())
        }
        return result to Bundle().apply { putString("stream", report.toString()); putString("evidence", evidence.absolutePath) }
    }
}
