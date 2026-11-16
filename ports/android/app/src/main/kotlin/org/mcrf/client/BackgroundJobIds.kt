package org.mcrf.client

/** JobScheduler IDs share an application-wide namespace, not one per service. */
internal object BackgroundJobIds {
    const val SUBSCRIPTIONS=2401
    const val ENGINES=2402
    const val SUBSCRIPTIONS_ON_OPEN=2403
    const val BEHAVIOR_RECOVERY=2404
    const val BEHAVIOR_NEXT=2405
    const val ROUTING=0x4d435244
}
