package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class QrPreviewTransformTest {
    @Test fun portraitDoesNotRotateSensorCorrectedTextureAgain() {
        for(sensor in listOf(90,270)) {
            val result=QrPreviewTransform.calculate(1280,720,720,1280,sensor,0)
            assertEquals(0f,result.rotation,0f)
            assertEquals(1f,result.scaleX,0.0001f)
            assertEquals(1f,result.scaleY,0.0001f)
        }
    }
    @Test fun landscapeAndReversePortraitCompensateDisplayOnly() {
        for(sensor in listOf(0,90,180,270))for(display in listOf(0,90,180,270)) {
            val result=QrPreviewTransform.calculate(1280,720,900,600,sensor,display)
            assertEquals(-display.toFloat(),result.rotation,0f)
        }
    }
    @Test fun allOrientationsFillWindowWithoutStretchingOrMirroring() {
        for(sensor in listOf(0,90,180,270))for(display in listOf(0,90,180,270)) {
            val result=QrPreviewTransform.calculate(1280,720,530,910,sensor,display)
            val naturalWidth=if(sensor%180==0)1280f else 720f
            val naturalHeight=if(sensor%180==0)720f else 1280f
            val scaledWidth=530*result.scaleX
            val scaledHeight=910*result.scaleY
            assertTrue(result.scaleX>0&&result.scaleY>0)
            assertEquals(scaledWidth/naturalWidth,scaledHeight/naturalHeight,0.0001f)
            val width=if(display%180==0)scaledWidth else scaledHeight
            val height=if(display%180==0)scaledHeight else scaledWidth
            assertTrue(width>=530-0.001f&&height>=910-0.001f)
            assertTrue(abs(width-530)<0.001f||abs(height-910)<0.001f)
        }
    }
    @Test fun emptySurfaceIsRejectedBeforeComputingScale() {
        assertThrows(IllegalArgumentException::class.java){QrPreviewTransform.calculate(1280,720,0,910,90,0)}
    }
}
