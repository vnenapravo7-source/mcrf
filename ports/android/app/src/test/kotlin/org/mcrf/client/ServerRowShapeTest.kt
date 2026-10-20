package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class ServerRowShapeTest {
    @Test fun lastRowRoundsOnlyBottomCorners(){assertArrayEquals(floatArrayOf(0f,0f,0f,0f,32f,32f,32f,32f),ServerRowShape.corners(2f,480,160,true),0f)}
    @Test fun interiorRowsRemainFlat(){assertArrayEquals(FloatArray(8),ServerRowShape.corners(3f,720,240,false),0f)}
    @Test fun smallAndInvalidBoundsCannotOverflow(){
        assertEquals(5f,ServerRowShape.corners(3f,10,100,true)[4],0f)
        for(density in listOf(Float.NaN,Float.POSITIVE_INFINITY,-1f,0f))assertArrayEquals(FloatArray(8),ServerRowShape.corners(density,200,100,true),0f)
        assertArrayEquals(FloatArray(8),ServerRowShape.corners(2f,-10,100,true),0f)
    }
}
