package org.mcrf.client

import android.view.View
import android.widget.LinearLayout

/** Entry to each actual transport's encrypted options, not another copy of
 * per-server preferences that could diverge from its runtime profile. */
internal object VkTurnUi {
    fun serverSettings(activity:MainActivity):View=with(activity) {
        CarrierStreamDefaultsUi.build(activity).apply {tag="vk-turn-transport-settings"}
    }
}
