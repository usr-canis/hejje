package com.canis.hejje.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.canis.hejje.AppContainer
import com.canis.hejje.domain.Geo
import com.canis.hejje.domain.Route
import com.canis.hejje.domain.Simplifier
import com.canis.hejje.ui.components.LocalContainer
import com.canis.hejje.ui.theme.LocalPalette
import com.canis.hejje.ui.theme.LocalReduceMotion
import com.canis.hejje.ui.theme.Palette
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One unbroken piece of route to draw. [v] is the speed at each point, m/s. */
class MapRoute(val lat: DoubleArray, val lon: DoubleArray, val v: FloatArray) {
    val size: Int get() = lat.size

    /** Position along the line (0..1) of every point, by distance. */
    val fractions: FloatArray by lazy {
        val d = DoubleArray(size)
        for (i in 1 until size) d[i] = d[i - 1] + Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i])
        val total = d.lastOrNull()?.takeIf { it > 0 } ?: 1.0
        FloatArray(size) { (d[it] / total).toFloat() }
    }

    companion object {
        fun of(route: Route) = MapRoute(route.lat, route.lon, route.v)
    }
}

/** kind: 0 = start, 1 = end, 2 = stop along the way, 3 = parked car. */
data class MapMarker(val lat: Double, val lon: Double, val kind: Int)

data class MapCircle(val lat: Double, val lon: Double, val radiusM: Double)

object MapEngine {
    private var ready = false

    /** One-time setup: our HTTP client (with the app's User-Agent) and the tile cache size limit. */
    fun init(context: Context, container: AppContainer) {
        if (ready) return
        MapLibre.getInstance(context.applicationContext)
        // MapLibre keeps its own tile cache on disk, so its HTTP client needs none.
        HttpRequestUtil.setOkHttpClient(container.http.newBuilder().cache(null).build())
        setCacheSize(context, container.settings.current.tileCacheMb)
        ready = true
    }

    fun setCacheSize(context: Context, megabytes: Int) {
        runCatching {
            OfflineManager.getInstance(context.applicationContext).setMaximumAmbientCacheSize(megabytes.coerceAtLeast(10) * 1024L * 1024L, null)
        }
    }

    fun clearCache(context: Context) {
        runCatching { OfflineManager.getInstance(context.applicationContext).clearAmbientCache(null) }
    }
}

private const val MAX_STOPS = 160

/** Space kept above a framed route so a flag on it stays on the map. */
internal val FLAG_ROOM = 34.dp

/**
 * The map. It first shows the route on a plain background from a style stored in the app, so it
 * works offline and appears at once; map tiles then load underneath when there is internet.
 * If the map engine cannot start at all, the route is drawn on a plain canvas instead.
 */
@Composable
fun TripMap(
    routes: List<MapRoute>,
    modifier: Modifier = Modifier,
    bySpeed: Boolean = true,
    markers: List<MapMarker> = emptyList(),
    highlight: Pair<Double, Double>? = null,
    circles: List<MapCircle> = emptyList(),
    animate: Boolean = false,
    interactive: Boolean = true,
    description: String = "",
    center: Pair<Double, Double>? = null,
    onCenterChanged: ((Double, Double) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val container = LocalContainer.current
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    val inspection = LocalInspectionMode.current
    val mapView = remember {
        if (inspection) null else try {
            MapEngine.init(context, container)
            val options = MapLibreMapOptions.createFromAttributes(context)
                .logoEnabled(false).compassEnabled(false).attributionGravity(Gravity.BOTTOM or Gravity.END)
                .attributionTintColor(if (palette.isDark) 0xFFA39E99.toInt() else 0xFF68625D.toInt())
                .foregroundLoadColor(MapStyle.backgroundColor(palette.isDark).toColorInt())
                .textureMode(true)
            MapView(context, options)
        } catch (t: Throwable) {
            null
        }
    }
    if (mapView == null) {
        RouteCanvas(routes, modifier, bySpeed, markers)
        return
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var drawn by rememberSaveable { mutableStateOf(false) }
    val flags = rememberFlags(palette)
    val progress = remember { Animatable(if (animate && !drawn && !reduceMotion) 0f else 1f) }
    val centerCallback by rememberUpdatedState(onCenterChanged)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false
        mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.setAllGesturesEnabled(interactive)
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.setMaxZoomPreference(18.0)
            m.addOnCameraIdleListener { m.cameraPosition.target?.let { centerCallback?.invoke(it.latitude, it.longitude) } }
            map = m
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    // (Re)load the style when the map is ready or the theme flips.
    LaunchedEffect(map, palette.isDark) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromJson(MapStyle.json(palette.isDark))) { style = it }
    }

    // Camera: frame the whole route (or the given centre) without animation, so nothing jumps.
    LaunchedEffect(map, routes, center) {
        val m = map ?: return@LaunchedEffect
        val pad = with(density) { 44.dp.roundToPx() }
        // Room above the route for a flag standing on its first or last point.
        val top = pad + with(density) { FLAG_ROOM.roundToPx() }
        val points = routes.flatMap { r -> (0 until r.size).map { LatLng(r.lat[it], r.lon[it]) } } + markers.map { LatLng(it.lat, it.lon) }
        runCatching {
            when {
                center != null -> m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(center.first, center.second)).zoom(14.5).build()))
                points.size >= 2 && points.any { it.latitude != points[0].latitude || it.longitude != points[0].longitude } ->
                    m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), pad, top, pad, pad))
                points.isNotEmpty() -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(points[0], 15.0))
                else -> Unit
            }
        }
    }

    LaunchedEffect(style, routes, markers, circles) {
        val s = style ?: return@LaunchedEffect
        runCatching { MapLayers.sync(s, routes, markers, circles, palette, bySpeed, progress.value, flags) }
        if (progress.value < 1f && routes.isNotEmpty()) {
            progress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) {
                runCatching { MapLayers.gradients(s, routes, palette, bySpeed, value) }
            }
            drawn = true
        }
    }
    LaunchedEffect(style, bySpeed) {
        val s = style ?: return@LaunchedEffect
        if (progress.value >= 1f) runCatching { MapLayers.gradients(s, routes, palette, bySpeed, 1f) }
    }
    LaunchedEffect(style, highlight) {
        val s = style ?: return@LaunchedEffect
        runCatching { MapLayers.highlight(s, highlight) }
    }

    AndroidView(factory = { mapView }, modifier = modifier.semantics { contentDescription = description })
}

/** Creates and updates the route, marker and zone layers on top of the base style. */
private object MapLayers {
    private fun num(v: Double) = String.format(Locale.US, "%.6f", v)

    private fun lineJson(r: MapRoute): String = buildString {
        append("""{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[""")
        for (i in 0 until r.size) {
            if (i > 0) append(',')
            append('[').append(num(r.lon[i])).append(',').append(num(r.lat[i])).append(']')
        }
        append("]}}")
    }

    private fun pointsJson(points: List<Triple<Double, Double, Int>>): String =
        points.joinToString(",", """{"type":"FeatureCollection","features":[""", "]}") { (lat, lon, kind) ->
            """{"type":"Feature","properties":{"kind":$kind},"geometry":{"type":"Point","coordinates":[${num(lon)},${num(lat)}]}}"""
        }

    private fun circleJson(circles: List<MapCircle>): String =
        circles.joinToString(",", """{"type":"FeatureCollection","features":[""", "]}") { c ->
            val ring = (0..64).joinToString(",") { i ->
                val a = Math.toRadians(i * 360.0 / 64)
                val lat = c.lat + c.radiusM * cos(a) / 110_540.0
                val lon = c.lon + c.radiusM * sin(a) / (111_320.0 * cos(Math.toRadians(c.lat)))
                "[${num(lon)},${num(lat)}]"
            }
            """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[$ring]]}}"""
        }

    private fun source(style: Style, id: String, json: String, lineMetrics: Boolean = false) {
        val existing = style.getSourceAs<GeoJsonSource>(id)
        if (existing != null) existing.setGeoJson(json)
        else style.addSource(GeoJsonSource(id, json, GeoJsonOptions().withLineMetrics(lineMetrics).withTolerance(0.2f)))
    }

    fun sync(
        style: Style, routes: List<MapRoute>, markers: List<MapMarker>, circles: List<MapCircle>, palette: Palette, bySpeed: Boolean, progress: Float,
        flags: Pair<Bitmap, Bitmap>,
    ) {
        // Zones (under the route).
        source(style, "zones", circleJson(circles))
        if (style.getLayer("zones-fill") == null) {
            style.addLayer(FillLayer("zones-fill", "zones").withProperties(PropertyFactory.fillColor(palette.accent.toArgb()), PropertyFactory.fillOpacity(0.16f)))
            style.addLayer(LineLayer("zones-line", "zones").withProperties(PropertyFactory.lineColor(palette.accent.toArgb()), PropertyFactory.lineWidth(1.5f)))
        }
        // Remove route layers left over from a previous route set.
        var i = routes.size
        while (style.getLayer("route-$i") != null) {
            style.removeLayer("route-$i"); style.removeLayer("casing-$i"); style.removeSource("route-$i")
            i++
        }
        routes.forEachIndexed { index, r ->
            source(style, "route-$index", lineJson(r), lineMetrics = true)
            if (style.getLayer("route-$index") == null) {
                val round = arrayOf(PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND))
                style.addLayer(LineLayer("casing-$index", "route-$index").withProperties(PropertyFactory.lineWidth(7.5f), *round))
                style.addLayer(LineLayer("route-$index", "route-$index").withProperties(PropertyFactory.lineWidth(4.5f), *round))
            }
        }
        gradients(style, routes, palette, bySpeed, progress)
        // Markers and the highlighted point sit above the route.
        source(style, "markers", pointsJson(markers.map { Triple(it.lat, it.lon, it.kind) }))
        source(style, "highlight", pointsJson(emptyList()))
        listOf("marker-stop", "marker-start", "marker-end", "marker-parked", "highlight").forEach { style.removeLayer(it) }
        val accent = palette.accent.toArgb()
        val casing = palette.routeCasing.toArgb()
        fun marker(id: String, kind: Int, radius: Float, fill: Int, stroke: Int, strokeWidth: Float) = style.addLayer(
            CircleLayer(id, "markers").withFilter(Expression.eq(Expression.get("kind"), Expression.literal(kind))).withProperties(
                PropertyFactory.circleRadius(radius), PropertyFactory.circleColor(fill),
                PropertyFactory.circleStrokeColor(stroke), PropertyFactory.circleStrokeWidth(strokeWidth),
            ),
        )
        marker("marker-stop", 2, 3.5f, casing, accent, 1.5f)
        addFlags(style, flags)
        flagLayer(style, "marker-start", "markers", 0, Flags.START)
        flagLayer(style, "marker-end", "markers", 1, Flags.END)
        marker("marker-parked", 3, 9f, accent, casing, 3.5f)
        style.addLayer(
            CircleLayer("highlight", "highlight").withProperties(
                PropertyFactory.circleRadius(7f), PropertyFactory.circleColor(accent),
                PropertyFactory.circleStrokeColor(casing), PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }

    /** Colours each route by speed (or plain accent) and reveals it up to [progress] for the draw-in animation. */
    fun gradients(style: Style, routes: List<MapRoute>, palette: Palette, bySpeed: Boolean, progress: Float) {
        routes.forEachIndexed { index, r ->
            if (r.size < 2) return@forEachIndexed
            val keep = Simplifier.thin(r.size, MAX_STOPS)
            val fr = FloatArray(keep.size) { r.fractions[keep[it]] }
            val colors = IntArray(keep.size) { if (bySpeed) palette.speedColor(r.v[keep[it]]).toArgb() else palette.accent.toArgb() }
            style.getLayerAs<LineLayer>("route-$index")?.setProperties(PropertyFactory.lineGradient(expression(fr, colors, progress)))
            val casing = IntArray(keep.size) { palette.routeCasing.toArgb() }
            style.getLayerAs<LineLayer>("casing-$index")?.setProperties(PropertyFactory.lineGradient(expression(fr, casing, progress)))
        }
    }

    private fun expression(fractions: FloatArray, colors: IntArray, progress: Float): Expression {
        val stops = ArrayList<Expression.Stop>()
        var last = -1f
        fun add(f: Float, color: Int) {
            val x = if (f <= last) last + 1e-4f else f
            if (x > 1f) return
            stops += Expression.stop(x, Expression.color(color))
            last = x
        }
        if (progress >= 1f) {
            for (i in fractions.indices) add(fractions[i], colors[i])
        } else {
            var lastColor = colors[0]
            for (i in fractions.indices) {
                if (fractions[i] > progress) break
                add(fractions[i], colors[i])
                lastColor = colors[i]
            }
            add(max(progress, 0f), lastColor)
            add(min(1f, max(progress, 0f) + 0.002f), android.graphics.Color.TRANSPARENT)
            add(1f, android.graphics.Color.TRANSPARENT)
        }
        if (stops.size < 2) add(1f, colors.last())
        return Expression.interpolate(Expression.linear(), Expression.lineProgress(), *stops.toTypedArray())
    }

    fun highlight(style: Style, point: Pair<Double, Double>?) {
        style.getSourceAs<GeoJsonSource>("highlight")?.setGeoJson(pointsJson(listOfNotNull(point?.let { Triple(it.first, it.second, 9) })))
    }
}

/** Plain drawing of the route, used when the map engine is unavailable and in previews. */
@Composable
fun RouteCanvas(routes: List<MapRoute>, modifier: Modifier = Modifier, bySpeed: Boolean = true, markers: List<MapMarker> = emptyList()) {
    val palette = LocalPalette.current
    val flags = rememberFlags(palette)
    val flagImages = remember(flags) { flags.first.asImageBitmap() to flags.second.asImageBitmap() }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(palette.mapBackground)
            val lats = routes.flatMap { it.lat.asList() } + markers.map { it.lat }
            val lons = routes.flatMap { it.lon.asList() } + markers.map { it.lon }
            if (lats.isEmpty()) return@Canvas
            val lat0 = (lats.min() + lats.max()) / 2
            val lon0 = (lons.min() + lons.max()) / 2
            val spanX = max(1.0, Geo.x(lons.max(), lon0, lat0) - Geo.x(lons.min(), lon0, lat0))
            val spanY = max(1.0, Geo.y(lats.max(), lat0) - Geo.y(lats.min(), lat0))
            val pad = 44.dp.toPx()
            val top = pad + FLAG_ROOM.toPx()
            val scale = min((size.width - 2 * pad) / spanX, (size.height - pad - top) / spanY).toFloat()
            val midY = top + (size.height - pad - top) / 2
            fun at(lat: Double, lon: Double) = Offset(
                size.width / 2 + (Geo.x(lon, lon0, lat0) * scale).toFloat(),
                midY - (Geo.y(lat, lat0) * scale).toFloat(),
            )
            for (r in routes) {
                for (i in 1 until r.size) {
                    drawLine(palette.routeCasing, at(r.lat[i - 1], r.lon[i - 1]), at(r.lat[i], r.lon[i]), 7.5.dp.toPx(), StrokeCap.Round)
                }
                for (i in 1 until r.size) {
                    val color: Color = if (bySpeed) palette.speedColor((r.v[i - 1] + r.v[i]) / 2) else palette.accent
                    drawLine(color, at(r.lat[i - 1], r.lon[i - 1]), at(r.lat[i], r.lon[i]), 4.5.dp.toPx(), StrokeCap.Round)
                }
            }
            for (m in markers) {
                val p = at(m.lat, m.lon)
                when (m.kind) {
                    0 -> drawFlag(flagImages.first, p)
                    1 -> drawFlag(flagImages.second, p)
                    2 -> { drawCircle(palette.accent, 4.5.dp.toPx(), p); drawCircle(palette.routeCasing, 3.dp.toPx(), p) }
                    else -> { drawCircle(palette.routeCasing, 8.5.dp.toPx(), p); drawCircle(palette.accent, 6.dp.toPx(), p) }
                }
            }
        }
    }
}

/** The start and finish flag images for the current theme. */
@Composable
internal fun rememberFlags(palette: Palette): Pair<Bitmap, Bitmap> {
    val context = LocalContext.current
    return remember(palette.isDark) {
        val ink = if (palette.isDark) 0xFFECE9E6.toInt() else 0xFF1C1B1A.toInt()
        Flags.bitmap(context, false, palette.accent.toArgb(), palette.routeCasing.toArgb(), ink) to
            Flags.bitmap(context, true, palette.accent.toArgb(), palette.routeCasing.toArgb(), ink)
    }
}

internal fun addFlags(style: Style, flags: Pair<Bitmap, Bitmap>) {
    if (style.getImage(Flags.START) == null) style.addImage(Flags.START, flags.first)
    if (style.getImage(Flags.END) == null) style.addImage(Flags.END, flags.second)
}

/** A flag standing on each point of [source] whose "kind" is [kind]. */
internal fun flagLayer(style: Style, id: String, source: String, kind: Int, image: String) {
    style.removeLayer(id)
    style.addLayer(
        SymbolLayer(id, source).withFilter(Expression.eq(Expression.get("kind"), Expression.literal(kind))).withProperties(
            PropertyFactory.iconImage(image), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM_LEFT),
            PropertyFactory.iconOffset(arrayOf(-Flags.BASE_DP, Flags.BASE_DP)),
        ),
    )
}

/** Draws a flag image so its pole stands on [at]. */
internal fun DrawScope.drawFlag(image: ImageBitmap, at: Offset) {
    val base = Flags.BASE_DP * density
    drawImage(image, topLeft = Offset(at.x - base, at.y - (image.height - base)))
}
