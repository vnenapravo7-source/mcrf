package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class WarpProbeDeadlineTest {
    @Test fun timeoutIsRetryableNotUserCancellation(){assertThrows(java.util.concurrent.TimeoutException::class.java){WarpProbeDeadline.check(false,true)}}
    @Test fun userCancellationWinsOverSimultaneousDeadline(){assertThrows(java.util.concurrent.CancellationException::class.java){WarpProbeDeadline.check(true,true)}}
    @Test fun activeProbeContinues(){WarpProbeDeadline.check(false,false)}
}
