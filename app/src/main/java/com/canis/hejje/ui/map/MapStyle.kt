package com.canis.hejje.ui.map

/**
 * The app's own muted map style, generated in code for light and dark mode so the route always
 * stands out. It is stored in the app (nothing to download), which lets the map show the route
 * on a plain background at once; OpenFreeMap tiles load underneath when the phone is online.
 *
 * Tiles: OpenFreeMap (https://openfreemap.org), OpenMapTiles schema, data (c) OpenStreetMap contributors.
 */
object MapStyle {
    private class Colors(
        val background: String, val water: String, val green: String, val building: String,
        val roadMinor: String, val roadMajor: String, val motorway: String, val rail: String,
        val boundary: String, val label: String, val labelMinor: String, val halo: String,
    )

    private val light = Colors(
        background = "#F1EEEA", water = "#D5DDE3", green = "#E3E8DD", building = "#E8E4DF",
        roadMinor = "#FFFFFF", roadMajor = "#FFFFFF", motorway = "#E4DED6", rail = "#D8D3CD",
        boundary = "#C9C3BC", label = "#6F6963", labelMinor = "#9A948E", halo = "#F1EEEA",
    )
    private val dark = Colors(
        background = "#181B1E", water = "#11161B", green = "#1B211E", building = "#1F2326",
        roadMinor = "#262A2E", roadMajor = "#2E3337", motorway = "#383E43", rail = "#2A2E32",
        boundary = "#3A3F44", label = "#8E8984", labelMinor = "#6A6661", halo = "#181B1E",
    )

    const val TILES = "https://tiles.openfreemap.org/planet"
    private const val GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"
    const val ATTRIBUTION = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"

    fun backgroundColor(dark: Boolean): String = (if (dark) this.dark else light).background

    /** @param withTiles false gives just the plain background (used when tiles are not wanted). */
    fun json(dark: Boolean, withTiles: Boolean = true): String {
        val c = if (dark) this.dark else light
        val layers = ArrayList<String>()
        layers += """{"id":"background","type":"background","paint":{"background-color":"${c.background}"}}"""
        if (withTiles) {
            fun fill(id: String, layer: String, color: String, filter: String? = null, minzoom: Int = 0, opacity: Double = 1.0) {
                layers += """{"id":"$id","type":"fill","source":"ofm","source-layer":"$layer","minzoom":$minzoom""" +
                    (filter?.let { ""","filter":$it""" } ?: "") + ""","paint":{"fill-color":"$color","fill-opacity":$opacity}}"""
            }
            fun line(id: String, layer: String, color: String, filter: String, width: String, minzoom: Int = 0, dash: String? = null) {
                layers += """{"id":"$id","type":"line","source":"ofm","source-layer":"$layer","minzoom":$minzoom,"filter":$filter,""" +
                    """"layout":{"line-cap":"round","line-join":"round"},"paint":{"line-color":"$color","line-width":$width""" +
                    (dash?.let { ""","line-dasharray":$it""" } ?: "") + "}}"
            }
            fun label(id: String, layer: String, filter: String, size: String, color: String, minzoom: Int = 0, maxzoom: Int = 24, line: Boolean = false) {
                layers += """{"id":"$id","type":"symbol","source":"ofm","source-layer":"$layer","minzoom":$minzoom,"maxzoom":$maxzoom,"filter":$filter,""" +
                    """"layout":{"text-field":["coalesce",["get","name:latin"],["get","name"]],"text-font":["Noto Sans Regular"],"text-size":$size,""" +
                    (if (line) """"symbol-placement":"line",""" else "") + """"text-max-width":8},""" +
                    """"paint":{"text-color":"$color","text-halo-color":"${c.halo}","text-halo-width":1.2}}"""
            }
            fun w(vararg stops: Pair<Int, Double>) =
                """["interpolate",["exponential",1.5],["zoom"],${stops.joinToString(",") { "${it.first},${it.second}" }}]"""

            fill("landcover", "landcover", c.green, """["in",["get","class"],["literal",["wood","grass","farmland"]]]""", opacity = 0.6)
            fill("park", "park", c.green)
            fill("water", "water", c.water)
            line("waterway", "waterway", c.water, """["==",["geometry-type"],"LineString"]""", w(8 to 0.5, 14 to 2.0), minzoom = 8)
            fill("building", "building", c.building, minzoom = 14)
            line("rail", "transportation", c.rail, """["==",["get","class"],"rail"]""", w(10 to 0.5, 16 to 1.5), minzoom = 10, dash = "[3,3]")
            line(
                "road-minor", "transportation", c.roadMinor,
                """["in",["get","class"],["literal",["minor","service","track","path"]]]""", w(12 to 0.5, 14 to 2.0, 18 to 12.0), minzoom = 12,
            )
            line(
                "road-major", "transportation", c.roadMajor,
                """["in",["get","class"],["literal",["primary","secondary","tertiary","trunk"]]]""", w(7 to 0.5, 12 to 2.0, 14 to 4.0, 18 to 18.0), minzoom = 7,
            )
            line("motorway", "transportation", c.motorway, """["==",["get","class"],"motorway"]""", w(5 to 0.6, 12 to 2.5, 14 to 5.0, 18 to 20.0), minzoom = 5)
            line("boundary", "boundary", c.boundary, """["<=",["get","admin_level"],4]""", w(2 to 0.5, 10 to 1.2), dash = "[4,3]")
            label(
                "road-label", "transportation_name", """["in",["get","class"],["literal",["primary","secondary","tertiary","trunk","motorway","minor"]]]""",
                "11", c.labelMinor, minzoom = 14, line = true,
            )
            label("place-small", "place", """["in",["get","class"],["literal",["suburb","neighbourhood","village","hamlet","quarter"]]]""", "11.5", c.labelMinor, minzoom = 11)
            label("place-town", "place", """["==",["get","class"],"town"]""", "13", c.label, minzoom = 8)
            label("place-city", "place", """["==",["get","class"],"city"]""", "15", c.label, minzoom = 4, maxzoom = 14)
        }
        val sources = if (withTiles) """"ofm":{"type":"vector","url":"$TILES","attribution":"$ATTRIBUTION"}""" else ""
        return """{"version":8,"name":"Hejje ${if (dark) "dark" else "light"}","glyphs":"$GLYPHS","sources":{$sources},"layers":[${layers.joinToString(",")}]}"""
    }
}
