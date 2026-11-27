package com.uacspoofer.mobile.ui.shell

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One country in Web-Mercator units (x = lon in radians, y = -mercator y so north is up on screen). */
class MapCountry(
    val iso: String,
    val path: Path,
    val bounds: Rect,
    val home: Rect,
    val label: Offset,
)

@Immutable
class WorldMapData(val countries: Map<String, MapCountry>) {
    /** Draw order without the map's hash order, so bitmaps are deterministic. */
    val list: List<MapCountry> = countries.values.sortedBy { it.iso }
}

/**
 * Natural Earth 1:50m admin-0 (public domain), simplified and packed by
 * scripts/gen_world_map.py into assets/map/world50.bin. Parsed once, off the main thread;
 * each country is one Path in mercator units drawn through a transform, so camera moves
 * never rebuild geometry.
 */
object WorldMapStore {
    private val lock = Mutex()
    @Volatile private var data: WorldMapData? = null

    fun cached(): WorldMapData? = data

    suspend fun load(context: Context): WorldMapData? {
        data?.let { return it }
        return lock.withLock {
            data ?: withContext(Dispatchers.Default) {
                runCatching { parse(context.applicationContext) }.getOrNull()
            }?.also { data = it }
        }
    }

    fun mercX(lon: Double): Float = (lon * PI / 180.0).toFloat()
    fun mercY(lat: Double): Float {
        val clamped = lat.coerceIn(-85.0, 85.0)
        return ln(tan(PI / 4 + clamped * PI / 360.0)).toFloat()
    }

    private fun parse(context: Context): WorldMapData {
        val bytes = context.assets.open("map/world50.bin").use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        require(String(magic, Charsets.US_ASCII) == "SMAP")
        buf.get() // version
        val count = buf.short.toInt() and 0xFFFF
        fun varint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = buf.get().toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        fun unzig(v: Int): Int = (v ushr 1) xor -(v and 1)
        val out = HashMap<String, MapCountry>(count * 2)
        repeat(count) {
            val iso = String(byteArrayOf(buf.get(), buf.get()), Charsets.US_ASCII)
            val lx = buf.int / 100.0
            val ly = buf.int / 100.0
            val b0 = buf.int / 100.0
            val b1 = buf.int / 100.0
            val b2 = buf.int / 100.0
            val b3 = buf.int / 100.0
            val rings = varint()
            val path = out[iso]?.path ?: Path()
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            repeat(rings) {
                val n = varint()
                var x = 0
                var y = 0
                for (i in 0 until n) {
                    x += unzig(varint())
                    y += unzig(varint())
                    val px = mercX(x / 100.0)
                    val py = mercY(y / 100.0)
                    if (px < minX) minX = px; if (px > maxX) maxX = px
                    if (py < minY) minY = py; if (py > maxY) maxY = py
                    if (i == 0) path.moveTo(px, -py) else path.lineTo(px, -py)
                }
                path.close()
            }
            val home = Rect(mercX(b0), -mercY(b3), mercX(b2), -mercY(b1))
            val existing = out[iso]
            val bounds = Rect(minX, -maxY, maxX, -minY).let { r -> existing?.bounds?.let { e -> Rect(min(e.left, r.left), min(e.top, r.top), max(e.right, r.right), max(e.bottom, r.bottom)) } ?: r }
            out[iso] = MapCountry(iso, path, bounds, existing?.home ?: home, existing?.label ?: Offset(mercX(lx), -mercY(ly)))
        }
        return WorldMapData(out)
    }
}

/** How much the map may move. Chosen from the power mode (see [mapMotionFor]). */
enum class MapMotion {
    /** BALANCED: smooth pan to the exit country and a pulsing exit dot. */
    FULL,
    /** SAVER: one-shot pan only, no infinite animation. */
    PAN_ONLY,
    /** ULTRA: static map, no animation at all. */
    STATIC,
}

fun mapMotionFor(anim: AnimLevel, enabled: Boolean = true): MapMotion = if (!enabled) MapMotion.STATIC else when (anim) {
    AnimLevel.FULL -> MapMotion.FULL
    AnimLevel.REDUCED -> MapMotion.PAN_ONLY
    AnimLevel.OFF -> MapMotion.STATIC
}

@Immutable
data class MapView(
    /** Country the camera centres on (exit when connected, else the user's own country). */
    val focus: String,
    /** The user's own country (for the "from" end of the route line). */
    val home: String = "",
    /** Connected: draw the exit dot (and a route line when home differs). */
    val connected: Boolean = false,
)

private data class Camera(val cx: Float, val cy: Float, val scale: Float)

private data class RasterKey(val camera: Camera, val size: IntSize, val sea: Color, val land: Color, val border: Color)

/**
 * The home map card's map. Rendering rules (all draw-phase, no recomposition per frame):
 * - The settled map is rasterised once per camera on a background thread into a bitmap and
 *   drawn with a single drawImage; scrolling or pulsing never re-strokes 240 country paths.
 * - While a pan runs (FULL / PAN_ONLY, one shot, ~700 ms) countries are drawn as cached
 *   vector fills through a transform, culled to the viewport.
 * - The exit dot pulse is an infinite animation only in FULL mode and only while [active]
 *   (home tab shown, screen on/resumed, list not scrolling); it lives in its own layer.
 */
@Composable
fun WorldMapCanvas(
    view: MapView,
    motion: MapMotion,
    active: Boolean,
    modifier: Modifier = Modifier,
    dotColor: Color = Color(0xFFB9D43A),
) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val data by produceState(WorldMapStore.cached(), context) { if (value == null) value = WorldMapStore.load(context) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.onSizeChanged { size = it }) {
        val map = data ?: return@Box
        if (size.width <= 0 || size.height <= 0) return@Box
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val target = remember(map, view.focus, view.home, view.connected, w, h) { cameraFor(map, view, w, h) }
        val cx = remember { Animatable(target.cx) }
        val cy = remember { Animatable(target.cy) }
        val scale = remember { Animatable(target.scale) }
        LaunchedEffect(target, motion) {
            if (motion == MapMotion.STATIC) {
                cx.snapTo(target.cx); cy.snapTo(target.cy); scale.snapTo(target.scale)
            } else {
                val spec = tween<Float>(720, easing = FastOutSlowInEasing)
                launch { cx.animateTo(target.cx, spec) }
                launch { cy.animateTo(target.cy, spec) }
                launch { scale.animateTo(target.scale, spec) }
            }
        }
        val sea = sx.mapSea
        val land = sx.mapLand
        val border = sx.mapBorder
        // Settled frame, rasterised off the main thread.
        // produceState keeps its last value across key changes, so the frame carries its own key:
        // a bitmap from the previous skin or size is never drawn for the new one.
        val frameKey = RasterKey(target, size, sea, land, border)
        val settled by produceState<Pair<RasterKey, ImageBitmap>?>(null, map, frameKey) {
            value = withContext(Dispatchers.Default) {
                runCatching { frameKey to rasterise(map, target, size, sea, land, border) }.getOrNull()
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val s = scale.value
            val x = cx.value
            val y = cy.value
            val frame = settled
            if (frame != null && frame.first == frameKey && x == target.cx && y == target.cy && s == target.scale) {
                drawImage(frame.second)
                return@Canvas
            }
            // Moving (or the bitmap is not ready yet): cheap vector fills.
            drawRect(sea)
            val ox = this.size.width / 2f - x * s
            val oy = this.size.height / 2f - y * s
            val vis = Rect(-ox / s, -oy / s, (this.size.width - ox) / s, (this.size.height - oy) / s)
            withTransform({
                translate(ox, oy)
                scale(s, s, Offset.Zero)
            }) {
                for (c in map.list) {
                    if (c.bounds.overlaps(vis)) drawPath(c.path, land)
                }
            }
        }
        if (view.connected) {
            ExitMarker(map, view, cx, cy, scale, pulse = motion == MapMotion.FULL && active, color = dotColor, line = sx.accent)
        }
    }
}

@Composable
private fun ExitMarker(
    map: WorldMapData,
    view: MapView,
    cx: Animatable<Float, AnimationVector1D>,
    cy: Animatable<Float, AnimationVector1D>,
    scale: Animatable<Float, AnimationVector1D>,
    pulse: Boolean,
    color: Color,
    line: Color,
) {
    val server = map.countries[view.focus]?.label ?: return
    val me = map.countries[view.home]?.label?.takeIf { view.home != view.focus }
    // Pulse value lives in a State read only in the draw lambda below: no recomposition per frame.
    val pulseState: State<Float> = if (pulse) {
        val t = rememberInfiniteTransition(label = "map-pulse")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    } else {
        remember { mutableStateOf(0.35f) }
    }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer() // own layer: pulse frames redraw only this
            .drawWithCache {
                val routePath = Path()
                onDrawBehind {
                    val s = scale.value
                    val ox = size.width / 2f - cx.value * s
                    val oy = size.height / 2f - cy.value * s
                    val sv = Offset(server.x * s + ox, server.y * s + oy)
                    if (me != null) {
                        val mv = Offset(me.x * s + ox, me.y * s + oy)
                        val mid = Offset((sv.x + mv.x) / 2f, min(sv.y, mv.y) - abs(sv.x - mv.x) * 0.25f - 20f)
                        routePath.reset()
                        routePath.moveTo(mv.x, mv.y)
                        routePath.quadraticTo(mid.x, mid.y, sv.x, sv.y)
                        drawPath(routePath, line.copy(alpha = 0.7f), style = Stroke(width = 3f, cap = StrokeCap.Round, pathEffect = dash))
                        drawCircle(line, 6f, mv)
                    }
                    val p = pulseState.value
                    val r = 13f * density
                    drawCircle(color.copy(alpha = 0.22f * (1f - p)), r * (1f + 1.2f * p), sv)
                    drawCircle(color.copy(alpha = 0.45f), r * 0.62f, sv)
                    drawCircle(color, r * 0.36f, sv)
                }
            },
    )
}

/** Renders the settled map once (background thread; android.graphics, no Compose state). */
private fun rasterise(map: WorldMapData, cam: Camera, size: IntSize, sea: Color, land: Color, border: Color): ImageBitmap {
    val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(sea.toArgb())
    val ox = size.width / 2f - cam.cx * cam.scale
    val oy = size.height / 2f - cam.cy * cam.scale
    val vis = Rect(-ox / cam.scale, -oy / cam.scale, (size.width - ox) / cam.scale, (size.height - oy) / cam.scale)
    canvas.translate(ox, oy)
    canvas.scale(cam.scale, cam.scale)
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = land.toArgb()
    }
    val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = border.toArgb()
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeWidth = 1.6f / cam.scale
    }
    for (c in map.list) {
        if (!c.bounds.overlaps(vis)) continue
        val p = c.path.asAndroidPath()
        canvas.drawPath(p, fill)
        canvas.drawPath(p, stroke)
    }
    return bmp.asImageBitmap()
}

/** Fits the focus country with regional context; the focus sits left of centre (tooltip on the right). */
private fun cameraFor(map: WorldMapData, view: MapView, w: Float, h: Float): Camera {
    val focus = map.countries[view.focus]
    val rect = focus?.home ?: Rect(0.20f, -1.10f, 1.15f, -0.45f) // Europe / Middle East fallback
    val minSpan = 0.55f // ~30 degrees of longitude: neighbours stay visible, like ExpressVPN
    val spanX = max(rect.width * 1.6f, minSpan)
    val spanY = max(rect.height * 1.6f, minSpan * 0.5f)
    val scale = min(w / spanX, h / spanY)
    val anchor = focus?.label ?: rect.center
    return Camera(anchor.x + (w * 0.20f) / scale, anchor.y + (h * 0.02f) / scale, scale)
}
