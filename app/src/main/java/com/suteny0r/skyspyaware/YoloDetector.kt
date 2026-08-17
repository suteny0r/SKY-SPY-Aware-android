package com.suteny0r.skyspyaware

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device YOLOv8 object detectors run via TensorFlow Lite. Models take a
 * 1024x1024 RGB float image and emit [4 + nc (+1 angle), 21504] anchors
 * (x, y, w, h normalized, then one row of class scores per class). [detect]
 * runs every loaded model and merges the results. Currently one model ships:
 * DOTA (satellite imagery). Additional aerial-trained models (e.g. VisDrone)
 * can be added to [models]; ground-photo detectors do not transfer to
 * top-down satellite tiles.
 *
 * Non-square inputs are letterboxed (aspect-preserving scale + black padding)
 * before inference, and all output coordinates are mapped back to the source
 * bitmap's normalized space, so consumers can multiply by the source
 * dimensions without shearing rotated boxes.
 */
object YoloDetector {

    private const val INPUT_SIZE = 1024
    private const val NUM_ANCHORS = 21504
    private const val NMS_THRESHOLD = 0.45f

    private val DOTA_CLASSES = listOf(
        "plane", "ship", "storage tank", "baseball diamond", "tennis court",
        "basketball court", "ground track field", "harbor", "bridge",
        "large vehicle", "small vehicle", "helicopter", "roundabout",
        "soccer ball field", "swimming pool"
    )

    /** A loaded model: asset name, class names, output layout, box colors. */
    class Model(
        val asset: String,
        val classes: List<String>,
        val hasAngleRow: Boolean,
        val colors: IntArray,
        val confThreshold: Float
    )

    /** Satellite objects (DOTAv1, OBB). */
    val DOTA = Model(
        asset = "satellite_yolo.tflite",
        classes = DOTA_CLASSES,
        hasAngleRow = true,
        colors = intArrayOf(
            0xFFFFD54F.toInt(), 0xFF4FC3F7.toInt(), 0xFF81C784.toInt(), 0xFFF06292.toInt(), 0xFFBA68C8.toInt(),
            0xFFFF8A65.toInt(), 0xFFA1887F.toInt(), 0xFFE57373.toInt(), 0xFF9575CD.toInt(), 0xFF4DB6AC.toInt(),
            0xFFF48FB1.toInt(), 0xFF7986CB.toInt(), 0xFFFFB74D.toInt(), 0xFFAED581.toInt(), 0xFF4DD0E1.toInt()
        ),
        confThreshold = 0.35f
    )

    val models: List<Model> = listOf(DOTA)

    /**
     * A detection in the SOURCE bitmap's normalized coordinate space
     * ([x]/[y]/[w]/[h] and [corners] all in 0..1 of the source width/height).
     * [corners] is the oriented polygon, precomputed in aspect-true input
     * space and unmapped, so it stays a true rectangle even when the source
     * bitmap is not square.
     */
    data class Detection(
        val model: Model,
        val cls: Int,
        val conf: Float,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val angle: Float,
        val corners: List<Pair<Float, Float>>
    ) {
        val className: String get() = model.classes[cls]

        /** Axis-aligned bounds of the oriented polygon: [x1, y1, x2, y2]. */
        val aabb: FloatArray
            get() = floatArrayOf(
                corners.minOf { it.first }, corners.minOf { it.second },
                corners.maxOf { it.first }, corners.maxOf { it.second }
            )
    }

    private val interpreters = HashMap<String, Interpreter>()

    fun init(context: Context) {
        synchronized(interpreters) {
            for (m in models) {
                if (interpreters.containsKey(m.asset)) continue
                try {
                    context.assets.open(m.asset).use { input ->
                        val bytes = input.readBytes()
                        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                        buf.put(bytes)
                        buf.rewind()
                        interpreters[m.asset] = Interpreter(buf)
                    }
                } catch (_: Exception) {
                    // Model missing/corrupt: it simply contributes no detections.
                }
            }
        }
    }

    /** Run every loaded model and merge the detections (per-model NMS). */
    fun detect(bitmap: Bitmap): List<Detection> {
        val out = ArrayList<Detection>()
        for (m in models) {
            val interp = synchronized(interpreters) { interpreters[m.asset] } ?: continue
            out.addAll(runModel(m, interp, bitmap))
        }
        return out
    }

    private fun runModel(model: Model, interp: Interpreter, bitmap: Bitmap): List<Detection> {
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0) return emptyList()

        // Letterbox: aspect-preserving scale onto a black 1024x1024 canvas.
        // A plain stretch would squash non-square inputs (the map-viewport
        // snapshot is ~1:2), breaking detection geometry and shearing every
        // rotated box on the way back.
        val scale = minOf(
            INPUT_SIZE.toFloat() / srcW, INPUT_SIZE.toFloat() / srcH
        )
        val newW = (srcW * scale).toInt().coerceIn(1, INPUT_SIZE)
        val newH = (srcH * scale).toInt().coerceIn(1, INPUT_SIZE)
        val dx = (INPUT_SIZE - newW) / 2f
        val dy = (INPUT_SIZE - newH) / 2f
        val exact = srcW == INPUT_SIZE && srcH == INPUT_SIZE
        val working: Bitmap = if (exact) {
            bitmap
        } else {
            Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888).also { lb ->
                val canvas = Canvas(lb)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(
                    bitmap, null,
                    android.graphics.RectF(dx, dy, dx + newW, dy + newH),
                    paint
                )
            }
        }

        val input = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val px = IntArray(INPUT_SIZE * INPUT_SIZE)
        working.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (v in px) {
            input.putFloat(((v shr 16) and 0xFF) / 255f)
            input.putFloat(((v shr 8) and 0xFF) / 255f)
            input.putFloat((v and 0xFF) / 255f)
        }

        // Output: rows 0-3 = x, y, w, h (normalized), then one row of class
        // scores per class, then (for OBB models) a trailing angle row.
        val nc = model.classes.size
        val rows = 4 + nc + if (model.hasAngleRow) 1 else 0
        val output = Array(1) { Array(rows) { FloatArray(NUM_ANCHORS) } }
        // TFLite Interpreter is not thread-safe, and detect() is reached from
        // several coroutine contexts at once (auto-classify poll, satellite
        // pass, manual scan buttons, map viewport classify). Serialize runs
        // per interpreter or concurrent inference corrupts tensors/crashes.
        synchronized(interp) {
            interp.run(input, output)
        }
        if (working !== bitmap) working.recycle()

        // Unmap from letterboxed input space back to source-normalized space.
        fun unmapX(inputNorm: Float) = (inputNorm * INPUT_SIZE - dx) / (scale * srcW)
        fun unmapY(inputNorm: Float) = (inputNorm * INPUT_SIZE - dy) / (scale * srcH)

        val raw = output[0]
        val dets = ArrayList<Detection>()
        for (a in 0 until NUM_ANCHORS) {
            var best = 0
            var bestScore = raw[4][a]
            for (c in 1 until nc) {
                val s = raw[4 + c][a]
                if (s > bestScore) {
                    bestScore = s
                    best = c
                }
            }
            if (bestScore < model.confThreshold) continue
            // Some anchors can carry NaN/Inf (degenerate angle math inside the
            // exported graph). They must never reach the UI: Compose drawRect
            // throws on non-finite offsets.
            if (!bestScore.isFinite()) continue
            val xi = raw[0][a]
            val yi = raw[1][a]
            val wi = raw[2][a]
            val hi = raw[3][a]
            if (!xi.isFinite() || !yi.isFinite() || !wi.isFinite() || !hi.isFinite()) continue
            // OBB models carry a trailing angle row (radians, decoded at
            // export). Non-OBB models have no angle; use 0 (axis-aligned).
            val angle = if (model.hasAngleRow) raw[4 + nc][a] else 0f
            if (!angle.isFinite()) continue

            // Corners in aspect-true input space (ultralytics xywhr2xyxyxyxy),
            // then unmapped, so rotated boxes stay rectangles on non-square
            // sources.
            val cos = kotlin.math.cos(angle.toDouble())
            val sin = kotlin.math.sin(angle.toDouble())
            val v1x = (wi / 2f * cos).toFloat()
            val v1y = (wi / 2f * sin).toFloat()
            val v2x = (-hi / 2f * sin).toFloat()
            val v2y = (hi / 2f * cos).toFloat()
            val corners = listOf(
                unmapX(xi + v1x + v2x) to unmapY(yi + v1y + v2y),
                unmapX(xi + v1x - v2x) to unmapY(yi + v1y - v2y),
                unmapX(xi - v1x - v2x) to unmapY(yi - v1y - v2y),
                unmapX(xi - v1x + v2x) to unmapY(yi - v1y + v2y)
            )
            // Drop detections fully inside the letterbox padding.
            if (corners.all { it.first < 0f || it.first > 1f } ||
                corners.all { it.second < 0f || it.second > 1f }
            ) continue

            dets.add(
                Detection(
                    model, best, bestScore,
                    x = unmapX(xi),
                    y = unmapY(yi),
                    w = wi * INPUT_SIZE / (scale * srcW),
                    h = hi * INPUT_SIZE / (scale * srcH),
                    angle = angle,
                    corners = corners
                )
            )
        }
        return nms(dets)
    }

    fun boxColor(d: Detection): Int = d.model.colors[d.cls % d.model.colors.size]

    /** Rotated-box NMS. Grouped per model so two models never suppress each
     *  other's boxes even when their class indices collide. Uses the true
     *  oriented-polygon IoU: for long thin diagonal objects (moored ships),
     *  axis-aligned IoU over-covers and suppresses distinct neighbors while
     *  letting duplicates of one object survive. */
    private fun nms(dets: List<Detection>): List<Detection> {
        val sorted = dets.sortedByDescending { it.conf }
        val keep = ArrayList<Detection>()
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val d = sorted[i]
            keep.add(d)
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (sorted[j].model !== d.model || sorted[j].cls != d.cls) continue
                if (rotatedIoU(d, sorted[j]) > NMS_THRESHOLD) suppressed[j] = true
            }
        }
        return keep
    }

    /** IoU of two oriented boxes via convex polygon clipping. */
    private fun rotatedIoU(a: Detection, b: Detection): Float {
        // Cheap reject: disjoint AABBs cannot intersect.
        val ax = a.aabb
        val bx = b.aabb
        if (ax[2] <= bx[0] || bx[2] <= ax[0] || ax[3] <= bx[1] || bx[3] <= ax[1]) return 0f
        val pa = a.corners.map { floatArrayOf(it.first, it.second) }
        val pb = b.corners.map { floatArrayOf(it.first, it.second) }
        val inter = polygonIntersectionArea(pa, pb)
        if (inter <= 0f) return 0f
        val union = polygonArea(pa) + polygonArea(pb) - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Shoelace area of a convex polygon. */
    private fun polygonArea(p: List<FloatArray>): Float {
        var s = 0f
        for (i in p.indices) {
            val j = (i + 1) % p.size
            s += p[i][0] * p[j][1] - p[j][0] * p[i][1]
        }
        return kotlin.math.abs(s) / 2f
    }

    /** Sutherland-Hodgman clip of convex [subject] against convex [clip]. */
    private fun polygonIntersectionArea(
        subject: List<FloatArray>,
        clip: List<FloatArray>
    ): Float {
        // Ensure the clip polygon winds counterclockwise so the inside test
        // is consistent regardless of corner ordering.
        var winding = 0f
        for (i in clip.indices) {
            val j = (i + 1) % clip.size
            winding += (clip[j][0] - clip[i][0]) * (clip[j][1] + clip[i][1])
        }
        val cw = if (winding > 0f) clip.reversed() else clip

        var poly = subject
        for (i in cw.indices) {
            if (poly.isEmpty()) return 0f
            val e1 = cw[i]
            val e2 = cw[(i + 1) % cw.size]
            val next = ArrayList<FloatArray>(poly.size + 4)
            for (k in poly.indices) {
                val cur = poly[k]
                val prev = poly[(k + poly.size - 1) % poly.size]
                val curIn = side(e1, e2, cur) >= 0f
                val prevIn = side(e1, e2, prev) >= 0f
                if (curIn) {
                    if (!prevIn) next.add(lineIntersect(prev, cur, e1, e2))
                    next.add(cur)
                } else if (prevIn) {
                    next.add(lineIntersect(prev, cur, e1, e2))
                }
            }
            poly = next
        }
        return if (poly.size < 3) 0f else polygonArea(poly)
    }

    private fun side(a: FloatArray, b: FloatArray, p: FloatArray): Float =
        (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])

    private fun lineIntersect(
        p1: FloatArray, p2: FloatArray,
        a: FloatArray, b: FloatArray
    ): FloatArray {
        val dx1 = p2[0] - p1[0]
        val dy1 = p2[1] - p1[1]
        val dx2 = b[0] - a[0]
        val dy2 = b[1] - a[1]
        val denom = dx1 * dy2 - dy1 * dx2
        if (denom == 0f) return floatArrayOf(p2[0], p2[1])
        val t = ((a[0] - p1[0]) * dy2 - (a[1] - p1[1]) * dx2) / denom
        return floatArrayOf(p1[0] + t * dx1, p1[1] + t * dy1)
    }
}
