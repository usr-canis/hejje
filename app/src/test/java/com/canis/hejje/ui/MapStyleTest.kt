package com.canis.hejje.ui

import com.canis.hejje.ui.map.MapStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapStyleTest {
    @Test fun styles_areValidJson_forBothThemes() {
        for (dark in listOf(false, true)) {
            val text = MapStyle.json(dark)
            val root = Json.parseToJsonElement(text).jsonObject
            assertEquals(8, root["version"]!!.jsonPrimitive.content.toInt())
            val layers = root["layers"]!!.jsonArray
            assertEquals("background", layers.first().jsonObject["id"]!!.jsonPrimitive.content)
            assertTrue(layers.size > 10)
            // Every layer id is unique and every data layer names the tile source.
            assertEquals(layers.size, layers.map { it.jsonObject["id"] }.toSet().size)
            assertTrue(layers.drop(1).all { it.jsonObject["source"]!!.jsonPrimitive.content == "ofm" })
            File("build/screens").mkdirs()
            File("build/screens/style-${if (dark) "dark" else "light"}.json").writeText(text)
        }
        // Offline variant: just the plain background, no network sources at all.
        val plain = Json.parseToJsonElement(MapStyle.json(dark = false, withTiles = false)).jsonObject
        assertEquals(1, plain["layers"]!!.jsonArray.size)
        assertTrue(plain["sources"]!!.jsonObject.isEmpty())
    }
}
