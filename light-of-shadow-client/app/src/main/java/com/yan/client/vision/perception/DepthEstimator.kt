package com.yan.client.vision.perception

import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * 深度推理封装：Depth Anything V2 Metric Small（TFLite，256×256 输入）。
 *
 * 模型产出米制稠密深度图；输出形状兼容 [1,1,H,W] / [1,H,W] / [H,W]。
 * 资产缺失/加载失败时 [create] 返回 null——调用方降级为纯 YOLO 模式（保底播报）。
 */
interface DepthSource {
    /** 返回米制深度图（metricOk 由调用方在地面校正后决定/覆盖）。 */
    fun estimate(bitmap: Bitmap, timestampNs: Long): DepthMap
}

class DepthEstimator private constructor(private val interpreter: Interpreter) : AutoCloseable, DepthSource {

    override fun close() = interpreter.close()

    /** 推理一帧：旋转后原图 bitmap → 256² 深度图（米）。 */
    override fun estimate(bitmap: Bitmap, timestampNs: Long): DepthMap {
        val input = preprocess(bitmap)
        val out = Array(1) { Array(1) { FloatArray(DEPTH_OUT * DEPTH_OUT) } }
        interpreter.run(input, out)
        val flat = out[0][0]
        // 兜底另一种常见输出布局 [1,H,W]
        val values = if (flat.size == DEPTH_OUT * DEPTH_OUT) flat
        else FloatArray(DEPTH_OUT * DEPTH_OUT) { i -> flat.getOrElse(i) { 0f } }
        val clamped = FloatArray(values.size) { i ->
            val v = values[i]
            if (v.isFinite() && v > 0.05f) v.coerceAtMost(40f) else 0f
        }
        return DepthMap(DEPTH_OUT, DEPTH_OUT, clamped, metricOk = true)
    }

    private fun preprocess(bitmap: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, DEPTH_OUT, DEPTH_OUT, true)
        val pixels = IntArray(DEPTH_OUT * DEPTH_OUT)
        scaled.getPixels(pixels, 0, DEPTH_OUT, 0, 0, DEPTH_OUT, DEPTH_OUT)
        // ImageNet 归一化（Depth Anything 预处理口径）
        val buffer = ByteBuffer.allocateDirect(4 * 3 * DEPTH_OUT * DEPTH_OUT)
        buffer.order(ByteOrder.nativeOrder())
        val planeSize = DEPTH_OUT * DEPTH_OUT
        for (i in pixels.indices) {
            val c = pixels[i]
            buffer.putFloat(i, (((c shr 16) and 0xFF) / 255f - MEAN_R) / STD_R)
            buffer.putFloat(planeSize + i, (((c shr 8) and 0xFF) / 255f - MEAN_G) / STD_G)
            buffer.putFloat(2 * planeSize + i, ((c and 0xFF) / 255f - MEAN_B) / STD_B)
        }
        if (scaled != bitmap) scaled.recycle()
        return buffer
    }

    companion object {
        private const val DEPTH_OUT = PerceptionConfig.DEPTH_INPUT_SIZE
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f

        const val ASSET_NAME = "depth_metric.tflite"

        /** 资产缺失或解释器初始化失败 → null（调用方走纯 YOLO 保底）。 */
        fun create(context: Context): DepthEstimator? = try {
            val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(bytes)
                rewind()
            }
            val options = Interpreter.Options().apply { setNumThreads(4) }
            DepthEstimator(Interpreter(buffer, options))
        } catch (t: Throwable) {
            null
        }
    }
}
