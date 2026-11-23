package com.uacspoofer.mobile.ui.shell

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One country in Web-Mercator units (x = lon in radians, y = mercator y, north up = larger y). */
class MapCountry(
    val iso: String,
    val path: Path,
    val bounds: Rect,
    val home: Rect,
    val label: Offset,
)

@Immutable
class WorldMapData(val countries: Map<String, MapCountry>)

/**
 * Natural Earth 1:50m admin-0 (public domain), simplified and packed by
 * scripts/gen_world_map.py into assets/map/world50.bin. Parsed once on a background
 * thread; each country becomes one cached Path in mercator units and is drawn through a
 * transform, so camera moves never rebuild geometry.
 */
object WorldMapStore {
    private val lock = Mutex()
    @Volatile private var data: WorldMapData? = null

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

@Immutable
data class MapView(
    val focus: String,
    val home: String = "",
    val connected: Boolean = false,
    val animate: Boolean = true,
)

/**
 * The map: focused (fitted) on [MapView.focus], neighbours dark with borders, the focus
 * country filled with the accent. When connected and the user's own country is known,
 * shows a "me" dot and an arc to the server country; the camera then fits both.
 */
@Composable
fun WorldMapCanvas(view: MapView, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sx = LocalSx.current
    val data by produceState<WorldMapData?>(null, context) { value = WorldMapStore.load(context) }
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val map = data ?: return@BoxWithConstraints
        val target = remember(map, view.focus, view.home, view.connected, w, h) { cameraFor(map, view, w, h) }
        val cx = remember { Animatable(target.cx) }
        val cy = remember { Animatable(target.cy) }
        val scale = remember { Animatable(target.scale) }
        LaunchedEffect(target, view.animate) {
            if (!view.animate) {
                cx.snapTo(target.cx); cy.snapTo(target.cy); scale.snapTo(target.scale)
            } else {
                val spec = tween<Float>(720, easing = FastOutSlowInEasing)
                launch { cx.animateTo(target.cx, spec) }
                launch { cy.animateTo(target.cy, spec) }
                launch { scale.animateTo(target.scale, spec) }
            }
        }
        val land = if (sx.light) Color(0xFFD5DEE9) else Color(0xFF233149)
        val border = if (sx.light) Color(0xFFFFFFFF) else Color(0xFF34445F)
        val focusFill = sx.accent.copy(alpha = 0.78f)
        val dash = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 12f)) }
        Canvas(Modifier.fillMaxSize()) {
            val s = scale.value
            val ox = size.width / 2f - cx.value * s
            val oy = size.height / 2f - cy.value * s
            // Visible window in mercator units, for culling.
            val vis = Rect(-ox / s, -oy / s, (size.width - ox) / s, (size.height - oy) / s)
            val focus = map.countries[view.focus]
            withTransform({
                translate(ox, oy)
                scale(s, s, Offset.Zero)
            }) {
                val hair = 1f / s
                for (c in map.countries.values) {
                    if (c === focus || !c.bounds.overlaps(vis)) continue
                    drawPath(c.path, land)
                    drawPath(c.path, border, style = Stroke(width = hair * 1.1f, join = StrokeJoin.Round))
                }
                if (focus != null) {
                    drawPath(focus.path, sx.accent.copy(alpha = 0.22f), style = Stroke(width = hair * 7f, join = StrokeJoin.Round))
                    drawPath(focus.path, focusFill)
                    drawPath(focus.path, sx.accent, style = Stroke(width = hair * 1.8f, join = StrokeJoin.Round))
                }
            }
            fun toScreen(p: Offset) = Offset(p.x * s + ox, p.y * s + oy)
            val server = focus?.label?.let(::toScreen)
            val me = map.countries[view.home]?.takeIf { view.connected && view.home != view.focus }?.label?.let(::toScreen)
            if (server != null && me != null) {
                val mid = Offset((server.x + me.x) / 2f, min(server.y, me.y) - kotlin.math.abs(server.x - me.x) * 0.28f - 30f)
                val arc = Path().apply {
                    moveTo(me.x, me.y)
                    quadraticBezierTo(mid.x, mid.y, server.x, server.y)
                }
                drawPath(arc, sx.accent, style = Stroke(width = 4f, cap = StrokeCap.Round, pathEffect = dash))
                drawCircle(Color.White.copy(alpha = 0.18f), 22f, me)
                drawCircle(Color(0xFF0B1220), 13f, me)
                drawCircle(Color.White, 8f, me)
            }
            if (server != null) {
                drawCircle(Color.White.copy(alpha = 0.22f), 30f, server)
                drawCircle(Color(0xFF0B1220), 17f, server)
                drawCircle(Color.White, 11f, server)
            }
        }
    }
}

private data class Camera(val cx: Float, val cy: Float, val scale: Float)

private fun cameraFor(map: WorldMapData, view: MapView, w: Float, h: Float): Camera {
    val focus = map.countries[view.focus]
    var rect = focus?.home ?: Rect(0.20f, -1.10f, 1.15f, -0.45f) // Europe / Middle East fallback
    val home = map.countries[view.home]
    if (view.connected && home != null && focus != null && view.home != view.focus) {
        rect = Rect(min(rect.left, home.label.x), min(rect.top, home.label.y), max(rect.right, home.label.x), max(rect.bottom, home.label.y))
    }
    // Small countries still get regional context.
    val minSpan = 0.16f
    val spanX = max(rect.width, minSpan)
    val spanY = max(rect.height, minSpan * 0.7f)
    val pad = if (view.connected && home != null) 1.45f else 1.9f
    val scale = min(w / (spanX * pad), h / (spanY * pad))
    // The tooltip sits on the lower-left; nudge the focus up and right a little.
    return Camera(rect.center.x - (w * 0.10f) / scale, rect.center.y + (h * 0.12f) / scale, scale)
}
