package org.mcrf.client

import org.junit.Assert.*
import org.junit.Test

class WallpaperCombinationsTest {
    @Test fun exactlyTwentyDistinctCompositions(){assertEquals(20,WallpaperCombinations.all.size);assertEquals(20,WallpaperCombinations.all.map {setOf(it.first,it.second)}.toSet().size);assertEquals(20,WallpaperCombinations.all.map {it.id}.toSet().size)}
    @Test fun oldSelectionsHaveCompatibleAliases(){for(id in listOf("kites","fireflies","ribbons","honeycomb","constellations"))assertTrue(WallpaperCombinations.all.any {it.id==WallpaperCombinations.canonical(id)})}
    @Test fun noneAndUnknownStaySafe(){assertEquals("none",WallpaperCombinations.canonical("none"));assertEquals("none",WallpaperCombinations.canonical("../private"))}
    @Test fun everyCompositionUsesBundledSupportedPrimitives(){
        for(asset in WallpaperCombinations.all.flatMap {listOf(it.first,it.second)}.distinct()){
            val svg=javaClass.getResourceAsStream("/wallpapers/$asset.svg")!!.bufferedReader().use {it.readText()}
            assertTrue(asset,Regex("<(circle|rect|line|polygon|polyline)\\b").containsMatchIn(svg))
        }
    }
}
