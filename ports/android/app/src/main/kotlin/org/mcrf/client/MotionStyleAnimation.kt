package org.mcrf.client

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings

internal fun motionStyleAnimationsEnabled(context: Context): Boolean =
    ValueAnimator.areAnimatorsEnabled() &&
        Settings.Global.getInt(context.contentResolver, "remove_animations", 0) == 0
