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

    // 输出形状从解释器读取（litert 转换会把 256² 裁到 patch 对齐的 252²，勿硬编码）
    private val outHeight: Int
    private val outWidth: Int
    private val outElements: Int
    @Volatile private var lastDiagMs = 0L

    init {
        val shape = interpreter.getOutputTensor(0).shape()   // [1, H, W] 或 [1, 1, H, W] 等
        outHeight = shape[shape.size - 2]
        outWidth = shape[shape.size - 1]
        outElements = interpreter.getOutputTensor(0).numElements()
    }

    override fun close() = interpreter.close()

    /** 推理一帧：旋转后原图 bitmap → 输出网格深度图（米，形状随模型）。 */
    override fun estimate(bitmap: Bitmap, timestampNs: Long): DepthMap {
        val input = preprocess(bitmap)
        // TFLite 要求输出对象形状逐维匹配（[1,252,252] 不能用扁平数组接收）
        val outObj = java.lang.reflect.Array.newInstance(
            java.lang.Float::class.javaPrimitiveType, *interpreter.getOutputTensor(0).shape())
        interpreter.run(input, outObj)
        val flat = flattenFloats(outObj, FloatArray(outElements))
        // 诊断（~5s 一行）：输出分布/形状/输入抽查——PR5 真机调参观测点
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastDiagMs > 5000L) {
            lastDiagMs = nowMs
            var nanCount = 0; var rawMin = Float.MAX_VALUE; var rawMax = -Float.MAX_VALUE
            for (v in flat) {
                if (!v.isFinite()) nanCount++ else { if (v < rawMin) rawMin = v; if (v > rawMax) rawMax = v }
            }
            android.util.Log.i("DepthEstimator",
                "raw out[min=$rawMin,max=$rawMax,nan=$nanCount] " +
                "inShape=${interpreter.getInputTensor(0).shape().contentToString()} " +
                "outShape=${interpreter.getOutputTensor(0).shape().contentToString()}")
        }
        val clamped = FloatArray(outElements) { i ->
            val v = flat[i]
            if (v.isFinite() && v > 0.05f) v.coerceAtMost(40f) else 0f
        }
        return DepthMap(outWidth, outHeight, clamped, metricOk = true)
    }

    private fun getFloat(buf: java.nio.ByteBuffer, index: Int): Float {
        return try { buf.getFloat(index * 4) } catch (e: Exception) { Float.NaN }
    }

    /** 任意秩嵌套 float 数组 → 扁平（行主序）。 */
    private fun flattenFloats(obj: Any, out: FloatArray, offset: Int = 0): FloatArray {
        if (obj is FloatArray) {
            obj.copyInto(out, offset)
            return out
        }
        val n = java.lang.reflect.Array.getLength(obj)
        var off = offset
        for (i in 0 until n) {
            val child = java.lang.reflect.Array.get(obj, i)
            off = if (child is FloatArray) {
                child.copyInto(out, off); off + child.size
            } else {
                val before = off
                flattenFloats(child, out, off); // 递归写入
                // 计算子树长度
                before + subtreeSize(child)
            }
        }
        return out
    }

    private fun subtreeSize(obj: Any): Int {
        if (obj is FloatArray) return obj.size
        var total = 0
        val n = java.lang.reflect.Array.getLength(obj)
        for (i in 0 until n) total += subtreeSize(java.lang.reflect.Array.get(obj, i))
        return total
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
            // 注意：putFloat 的索引是字节偏移，必须乘 4（曾因漏乘导致输入乱码、输出全 NaN）
            buffer.putFloat(i * 4, (((c shr 16) and 0xFF) / 255f - MEAN_R) / STD_R)
            buffer.putFloat((planeSize + i) * 4, (((c shr 8) and 0xFF) / 255f - MEAN_G) / STD_G)
            buffer.putFloat((2 * planeSize + i) * 4, ((c and 0xFF) / 255f - MEAN_B) / STD_B)
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
