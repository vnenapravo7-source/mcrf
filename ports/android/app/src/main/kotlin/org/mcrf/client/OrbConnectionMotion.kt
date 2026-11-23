package org.mcrf.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.view.View
import org.mcrf.client.ConnectionProgressState.Phase
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Native playback of the MIT Thinking Orbs 20px geometry, pinned by the frame generator. */
internal class OrbConnectionMotion(private val fixedMode: Mode?, host: View) {
    enum class Mode(val asset: String) {
        SOLVING("solving"), CONNECTING("connecting"), BREATHING("breathing")
    }

    private val owner = WeakReference(host)
    private val context = host.context.applicationContext
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pendingMode: Mode? = null
    private var pendingFrames: CompletableFuture<Frames?>? = null

    fun draw(canvas: Canvas, diameter: Float, phase: Phase, animate: Boolean) {
        if (diameter <= 0f) return
        val mode = fixedMode ?: when (phase) {
            Phase.IDLE, Phase.CONNECTED, Phase.PAUSED -> Mode.BREATHING
            Phase.PREPARING, Phase.CONNECTING, Phase.STOPPING -> Mode.CONNECTING
            Phase.VERIFYING, Phase.RECOVERING, Phase.CHALLENGE, Phase.FAILED -> Mode.SOLVING
        }
        if (mode != pendingMode) {
            pendingMode = mode
            pendingFrames = frames(context, mode).also { future ->
                val target = owner
                if (!future.isDone) future.whenComplete { _, _ -> target.get()?.postInvalidate() }
            }
        }
        val frames = pendingFrames?.getNow(null) ?: return
        val restore = canvas.save()
        canvas.translate(-diameter / 2, -diameter / 2)
        canvas.scale(diameter / FRAME_SIZE, diameter / FRAME_SIZE)
        canvas.clipRect(0f, 0f, FRAME_SIZE, FRAME_SIZE)
        val dark = Loft.dark
        if (!animate) {
            frames.draw(canvas, paint, 0, dark)
        } else {
            val cycle = frames.animatedCount - FADE_FRAMES
            val index = (SystemClock.uptimeMillis() / frames.intervalMs % cycle).toInt() + FADE_FRAMES
            val rollover = index - (frames.animatedCount - FADE_FRAMES)
            if (rollover < 0) {
                frames.draw(canvas, paint, index + 1, dark)
            } else {
                val alpha = (rollover + 1) * 255 / FADE_FRAMES
                drawLayer(canvas, frames, index + 1, dark, 255 - alpha)
                drawLayer(canvas, frames, rollover + 1, dark, alpha)
            }
        }
        canvas.restoreToCount(restore)
    }

    private fun drawLayer(canvas: Canvas, frames: Frames, index: Int, dark: Boolean, alpha: Int) {
        if (alpha == 0) return
        val restore = canvas.saveLayerAlpha(0f, 0f, FRAME_SIZE, FRAME_SIZE, alpha)
        frames.draw(canvas, paint, index, dark)
        canvas.restoreToCount(restore)
    }

    private class Frames(private val data: ByteArray) {
        private val offsets: IntArray
        val animatedCount: Int
        val intervalMs: Int

        init {
            require(data.size >= 8 && data[0] == 79.toByte() && data[1] == 82.toByte() &&
                data[2] == 66.toByte() && data[3] == 49.toByte() && unsigned(4) == FRAME_SIZE.toInt())
            val fps = unsigned(5)
            val frameCount = count(6)
            require(fps in 1..60 && frameCount in (FADE_FRAMES + 2)..1000)
            animatedCount = frameCount - 1
            intervalMs = 1000 / fps
            var start = 8
            offsets = IntArray(frameCount) {
                require(start <= data.size - 4)
                val offset = start
                start += 4 + count(start) * 13 + count(start + 2) * 9
                offset
            }
            require(start == data.size)
        }

        private fun unsigned(at: Int) = data[at].toInt() and 255
        private fun count(at: Int) = unsigned(at) or (unsigned(at + 1) shl 8)
        private fun coordinate(at: Int) = count(at).toShort() / 1024f

        private fun ink(at: Int, dark: Boolean): Int {
            val value = unsigned(at + if (dark) 1 else 0)
            val contrast = if (dark) value else 255 - value
            val purple = if (dark) 0xffb49aff.toInt() else 0xff6e47d1.toInt()
            val alpha = unsigned(at + 2) * (96 + contrast * 159 / 255) / 255
            return (purple and 0x00ffffff) or (alpha shl 24)
        }

        fun draw(canvas: Canvas, paint: Paint, frame: Int, dark: Boolean) {
            val start = offsets[frame]
            var offset = start + 4
            paint.style = Paint.Style.STROKE
            repeat(count(start)) {
                paint.color = ink(offset + 10, dark)
                paint.strokeWidth = coordinate(offset + 8)
                canvas.drawLine(coordinate(offset), coordinate(offset + 2),
                    coordinate(offset + 4), coordinate(offset + 6), paint)
                offset += 13
            }
            paint.style = Paint.Style.FILL
            repeat(count(start + 2)) {
                paint.color = ink(offset + 6, dark)
                canvas.drawCircle(coordinate(offset), coordinate(offset + 2), coordinate(offset + 4), paint)
                offset += 9
            }
        }
    }

    private companion object {
        const val FRAME_SIZE = 20f
        const val FADE_FRAMES = 5
        val cache = ConcurrentHashMap<Mode, CompletableFuture<Frames?>>()
        val loader = Executors.newSingleThreadExecutor()

        fun frames(context: Context, mode: Mode) = cache.computeIfAbsent(mode) {
            CompletableFuture.supplyAsync({
                try {
                    // AAPT expands .gz source assets and removes the suffix in the APK.
                    context.assets.open("thinking-orbs/${mode.asset}.orb").use { asset ->
                        Frames(asset.readBytes())
                    }
                } catch (error: IOException) {
                    Log.e("ThinkingOrbs", "Cannot read ${mode.asset} frames", error)
                    null
                } catch (error: IllegalArgumentException) {
                    Log.e("ThinkingOrbs", "Invalid ${mode.asset} frames", error)
                    null
                }
            }, loader)
        }
    }
}
