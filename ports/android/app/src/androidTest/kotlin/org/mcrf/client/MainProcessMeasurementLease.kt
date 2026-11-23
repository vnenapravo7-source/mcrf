package org.mcrf.client

import android.app.Application
import android.content.Context
import java.lang.reflect.Field

/** Test-only admission in the SAME process as CsqttMigrationJob, before Application.onCreate. */
internal class MainProcessMeasurementLease private constructor(
    private val coordinator: ProbeCoordinator,
    private val measurements: Field,
    private val startLock: Any,
    private val owned: ProbeProgress
) : AutoCloseable {
    private var released = false

    override fun close() {
        synchronized(startLock) {
            if (released) return
            check(measurements.get(coordinator) === owned) { "UI fixture measurement ownership was stolen" }
            measurements.set(coordinator, null)
            released = true
        }
    }

    companion object {
        fun acquire(context: Context, owner: String): MainProcessMeasurementLease {
            check(BuildConfig.DEBUG) { "Debug/common QA only" }
            check(Application.getProcessName() == "org.mcrf.client" && context.packageName == "org.mcrf.client") {
                "UI fixture requires the main target process"
            }
            require(owner.startsWith("fixture-") && owner.length <= 80)
            val coordinator = ProbeCoordinator
            val measurements = ProbeCoordinator::class.java.getDeclaredField("measurements").apply { isAccessible = true }
            val startLock = checkNotNull(ProbeCoordinator::class.java.getDeclaredField("startLock")
                .apply { isAccessible = true }.get(coordinator))
            val owned = ProbeProgress(setOf(owner))
            val lease = MainProcessMeasurementLease(coordinator, measurements, startLock, owned)
            synchronized(startLock) {
                check(measurements.get(coordinator) == null) { "UI fixture requires no active measurement" }
                measurements.set(coordinator, owned)
            }
            return lease
        }
    }
}
