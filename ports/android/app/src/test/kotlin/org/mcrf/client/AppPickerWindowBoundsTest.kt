package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class AppPickerWindowBoundsTest {
    @Test fun initialWindowKeepsEightyFivePercentCap(){assertEquals(2244,AppPickerWindowBounds.height(2640,2640,72))}
    @Test fun keyboardShrinksWindowIncludingFooterInsideVisibleArea(){assertEquals(1328,AppPickerWindowBounds.height(2640,1400,72))}
    @Test fun keyboardHideRestoresOriginalCap(){assertEquals(2244,AppPickerWindowBounds.height(2640,2544,72))}
    @Test fun shortDisplayNeverForcesWindowBelowKeyboard(){assertEquals(288,AppPickerWindowBounds.height(720,360,72))}
    @Test fun unavailableFirstFrameUsesSafeInitialCap(){assertEquals(2244,AppPickerWindowBounds.height(2640,0,72))}
    @Test fun extremeAvailableHeightNeverCreatesNegativeLayout(){assertEquals(1,AppPickerWindowBounds.height(720,50,72))}
    @Test fun invalidScreenRejected(){assertThrows(IllegalArgumentException::class.java){AppPickerWindowBounds.height(0,0,0)}}
}
