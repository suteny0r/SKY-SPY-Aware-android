package com.suteny0r.skyspyaware

import android.content.Context
import android.graphics.Bitmap
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

    data class Detection(
        val model: Model,
        val cls: Int,
        val conf: Float,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val angle: Float
    ) {
        val className: String get() = model.classes[cls]

        /** Axis-aligned bounding box (normalized): [x1, y1, x2, y2]. */
        val aabb: FloatArray
            get() = floatArrayOf(
                x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f
            )

        /**
         * Four corner points of the oriented box (normalized coords), ordered
         * pt1/pt2/pt3/pt4 around the center. Uses the same rotation math as
         * ultralytics' xywhr2xyxyxyxy so rotated objects (ships, pools,
         * harbors) render aligned with the imagery. For non-OBB models the
         * angle is 0 and this collapses to the axis-aligned rectangle.
         */
        val corners: List<Pair<Float, Float>>
            get() {
                val cos = kotlin.math.cos(angle.toDouble())
                val sin = kotlin.math.sin(angle.toDouble())
                val v1x = (w / 2f * cos).toFloat()
                val v1y = (w / 2f * sin).toFloat()
                val v2x = (-h / 2f * sin).toFloat()
                val v2y = (h / 2f * cos).toFloat()
                return listOf(
                    x + v1x + v2x to y + v1y + v2y,
                    x + v1x - v2x to y + v1y - v2y,
                    x - v1x - v2x to y - v1y - v2y,
                    x - v1x + v2x to y - v1y + v2y
                )
            }
    }

    private val interpreters = HashMap<String, Interpreter>()

    fun init(context: Context) {
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

    /** Run every loaded model and merge the detections (per-model NMS). */
    fun detect(bitmap: Bitmap): List<Detection> {
        val out = ArrayList<Detection>()
        for (m in models) {
            val interp = interpreters[m.asset] ?: continue
            out.addAll(runModel(m, interp, bitmap))
        }
        return out
    }

    private fun runModel(model: Model, interp: Interpreter, bitmap: Bitmap): List<Detection> {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        val input = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val px = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
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
        interp.run(input, output)
        scaled.recycle()

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
            val x = raw[0][a]
            val y = raw[1][a]
            val w = raw[2][a]
            val h = raw[3][a]
            if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite()) continue
            // OBB models carry a trailing angle row (radians, decoded at
            // export). Non-OBB models have no angle; use 0 (axis-aligned).
            val angle = if (model.hasAngleRow) raw[4 + nc][a] else 0f
            if (!angle.isFinite()) continue
            dets.add(Detection(model, best, bestScore, x, y, w, h, angle))
        }
        return nms(dets)
    }

    fun boxColor(d: Detection): Int = d.model.colors[d.cls % d.model.colors.size]

    /** Axis-aligned NMS. Grouped per model so two models never suppress each
     *  other's boxes even when their class indices collide. */
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
                if (iou(d, sorted[j]) > NMS_THRESHOLD) suppressed[j] = true
            }
        }
        return keep
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ax = a.aabb
        val bx = b.aabb
        val ix1 = maxOf(ax[0], bx[0])
        val iy1 = maxOf(ax[1], bx[1])
        val ix2 = minOf(ax[2], bx[2])
        val iy2 = minOf(ax[3], bx[3])
        val iw = maxOf(0f, ix2 - ix1)
        val ih = maxOf(0f, iy2 - iy1)
        val inter = iw * ih
        val union = (ax[2] - ax[0]) * (ax[3] - ax[1]) +
            (bx[2] - bx[0]) * (bx[3] - bx[1]) - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Golden-angle hue sweep so each class gets a visually distinct color. */
    private fun generatePalette(n: Int): IntArray {
        val out = IntArray(n)
        for (i in 0 until n) {
            val hue = (i * 137.508) % 360.0
            out[i] = android.graphics.Color.HSVToColor(
                floatArrayOf(hue.toFloat(), 0.75f, 0.95f)
            )
        }
        return out
    }
}
