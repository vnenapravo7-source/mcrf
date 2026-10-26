package org.mcrf.client

import java.util.concurrent.atomic.AtomicBoolean

/** A helper cannot capture the service's network before its owning platform
 * monitor exists. Each prepared start materializes one fresh private ingress. */
internal class SteerHelperPreparation<T>(private val create:(Long)->T) {
    private val consumed=AtomicBoolean()
    fun materialize(physicalHandle:Long?):T {
        require(physicalHandle!=null&&physicalHandle>0){"Нет физической сети для протокольного модуля."}
        check(consumed.compareAndSet(false,true)){"STEER_HELPER_PREPARATION_ALREADY_USED"}
        return create(physicalHandle)
    }
}
