package com.yan.client.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import androidx.core.graphics.scale
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter
import kotlin.math.abs

private const val INPUT_SIZE = 320
private const val OUTPUT_ROWS = 300
private const val OUTPUT_COLS = 6
private const val SCORE_THRESHOLD = 0.45f

private val OBSTACLE_LABELS = setOf(
    "person",
    "bicycle",
    "car",
    "motorcycle",
    "bus",
    "truck",
    "bench",
    "bird",
    "cat",
    "dog",
    "horse",
    "sheep",
    "cow",
    "backpack",
    "umbrella",
    "handbag",
    "suitcase",
    "chair",
    "couch",
    "potted plant",
    "bed",
    "dining table",
    "tv",
    "laptop",
    "cell phone",
    "microwave",
    "oven",
    "refrigerator",
    "book",
    "vase",
    "teddy bear"
)

private val LABEL_TRANSLATIONS = mapOf(
    "person" to "行人",
    "bicycle" to "自行车",
    "car" to "汽车",
    "motorcycle" to "摩托车",
    "bus" to "公交车",
    "truck" to "卡车",
    "bench" to "长椅",
    "bird" to "鸟",
    "cat" to "猫",
    "dog" to "狗",
    "horse" to "马",
    "sheep" to "羊",
    "cow" to "牛",
    "backpack" to "背包",
    "umbrella" to "雨伞",
    "handbag" to "手提包",
    "suitcase" to "行李箱",
    "chair" to "椅子",
    "couch" to "沙发",
    "potted plant" to "盆栽",
    "bed" to "床",
    "dining table" to "餐桌",
    "tv" to "电视",
    "laptop" to "笔记本电脑",
    "cell phone" to "手机",
    "microwave" to "微波炉",
    "oven" to "烤箱",
    "refrigerator" to "冰箱",
    "book" to "书本",
    "vase" to "花瓶",
    "teddy bear" to "玩具熊"
)

data class ObstacleDetection(
    val label: String,
    val displayLabel: String,
    val score: Float,
    val boundingBox: RectF,
    val direction: String,
    val alertText: String,
    val isObstacle: Boolean
)

class ObstacleDetector(context: Context) : AutoCloseable {
    private val interpreter: Interpreter
    private val labels: List<String>

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        interpreter = Interpreter(loadModelBuffer(context), options)
        labels = loadLabels(context)
    }

    fun detect(imageProxy: ImageProxy): List<ObstacleDetection> {
        val bitmap = imageProxy.toBitmap()
        val rotated = bitmap.rotate(imageProxy.imageInfo.rotationDegrees.toFloat())
        val resized = rotated.scale(INPUT_SIZE, INPUT_SIZE)
        val inputBuffer = bitmapToBuffer(resized)

        val output = Array(1) { Array(OUTPUT_ROWS) { FloatArray(OUTPUT_COLS) } }
        interpreter.run(inputBuffer, output)

        val detections = output[0].mapNotNull { row ->
            val x1 = row[0]
            val y1 = row[1]
            val x2 = row[2]
            val y2 = row[3]
            val score = row[4]
            val classIndex = row[5].toInt()

            if (score < SCORE_THRESHOLD) return@mapNotNull null
            if (x2 <= x1 || y2 <= y1) return@mapNotNull null

            val label = labels.getOrNull(classIndex) ?: "object"
            val translatedLabel = LABEL_TRANSLATIONS[label] ?: label
            val box = RectF(
                x1.coerceIn(0f, INPUT_SIZE.toFloat()),
                y1.coerceIn(0f, INPUT_SIZE.toFloat()),
                x2.coerceIn(0f, INPUT_SIZE.toFloat()),
                y2.coerceIn(0f, INPUT_SIZE.toFloat())
            )
            val direction = resolveDirection(box)
            val isObstacle = label in OBSTACLE_LABELS || score >= 0.7f
            val displayName = when {
                translatedLabel.isBlank() -> "目标"
                translatedLabel.equals("object", ignoreCase = true) -> "目标"
                else -> translatedLabel
            }
            val alertText = "${direction}有${displayName}"

            ObstacleDetection(
                label = label,
                displayLabel = translatedLabel,
                score = score,
                boundingBox = box,
                direction = direction,
                alertText = alertText,
                isObstacle = isObstacle
            )
        }

        return detections
            .sortedWith(
                compareByDescending<ObstacleDetection> { it.isObstacle }
                    .thenByDescending { it.score }
            )
            .take(5)
    }

    override fun close() {
        interpreter.close()
    }

    private fun loadModelBuffer(context: Context): ByteBuffer {
        val bytes = context.assets.open("model.tflite").use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).apply {
            order(ByteOrder.nativeOrder())
            put(bytes)
            rewind()
        }
    }

    private fun loadLabels(context: Context): List<String> {
        return context.assets.open("labels.txt").bufferedReader().useLines { lines ->
            val raw = lines.map { it.trim() }.filter { it.isNotBlank() }.toList()
            if (raw.firstOrNull()?.equals("UNKNOWN", ignoreCase = true) == true) raw.drop(1) else raw
        }
    }

    private fun bitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        val pixelCount = INPUT_SIZE * INPUT_SIZE
        val buffer = ByteBuffer.allocateDirect(pixelCount * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            buffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        buffer.rewind()
        return buffer
    }

    private fun resolveDirection(box: RectF): String {
        val centerX = (box.left + box.right) / 2f
        return when {
            centerX < INPUT_SIZE * 0.33f -> "左侧"
            centerX > INPUT_SIZE * 0.66f -> "右侧"
            else -> "前方"
        }
    }

    // Distance estimation removed: app only needs to know whether an obstacle exists.


    private fun Bitmap.rotate(degrees: Float): Bitmap {
        if (abs(degrees) < 0.5f) return this
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
}


