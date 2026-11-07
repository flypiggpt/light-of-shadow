package com.yan.client.vision.perception

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 端侧感知管线编排：双模型分频调度 + 触发式标注 + 状态汇合。
 *
 * 线程模型：
 * - 调用线程（cameraExecutor）：[onFrame] 仅解码/旋转/入队/立即 close，不阻塞取帧
 * - depthExecutor：深度推理 → 地面校正 → 候选抽取 → 触发标注（10Hz 门控，队列 1 丢旧）
 * - semExecutor：消费标注触发 → 全帧 YOLO → 更新缓存 → 重挂标签（250ms 防抖）
 * - 主线程：Handler post [PerceptionState]（唯一状态汇合点）
 *
 * 深度不可用（模型资产缺失/推理崩）→ 自动降级纯 YOLO 模式（保底播报，depthMode=false）。
 */
class PerceptionPipeline(
    private val depthSource: DepthSource?,
    private val semanticDetector: SemanticDetector,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : AutoCloseable {

    /** YOLO 全帧标注接口（包装现有 ObstacleDetector）。 */
    fun interface SemanticDetector {
        fun detect(bitmap: Bitmap): List<SemanticFrameCache.LabeledBox>
    }

    /** 汇合状态：候选（已挂标签）+ 深度模式标志。 */
    data class PerceptionState(
        val candidates: List<ObstacleCandidate>,
        val timestampNs: Long,
        val depthMode: Boolean,
    )

    private data class Frame(val bitmap: Bitmap, val timestampNs: Long, val width: Int, val height: Int)

    private val _state = MutableStateFlow(PerceptionState(emptyList(), 0L, depthSource != null))
    val state: StateFlow<PerceptionState> = _state.asStateFlow()

    private val cache = SemanticFrameCache()
    private val depthExecutor = Executors.newSingleThreadExecutor()
    private val semExecutor = Executors.newSingleThreadExecutor()

    private val frameCounter = AtomicLong(0)
    private val depthBusy = AtomicBoolean(false)
    private val semBusy = AtomicBoolean(false)
    private val pendingDepth = AtomicReference<Frame?>(null)
    private val pendingSem = AtomicReference<Frame?>(null)
    private val lastCandidates = AtomicReference<List<ObstacleCandidate>>(emptyList())
    @Volatile private var closed = false

    /** 取帧入口：轻量分发，立即归还 ImageProxy（不再阻塞 cameraExecutor 跑推理）。 */
    fun onFrame(imageProxy: ImageProxy) {
        try {
            if (closed) return
            val bitmap = imageProxy.toBitmap()
            val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
            val frame = Frame(rotated, imageProxy.imageInfo.timestamp, rotated.width, rotated.height)
            val n = frameCounter.incrementAndGet()

            if (depthSource == null) {
                // 纯 YOLO 保底模式：语义分支承担全部
                dispatchSem(frame)
                return
            }
            if (n % PerceptionConfig.DEPTH_FRAME_DIV == 1L) {
                dispatchDepth(frame)
            }
        } catch (t: Throwable) {
            // 感知失败不杀相机预览
        } finally {
            imageProxy.close()
        }
    }

    private fun dispatchDepth(frame: Frame) {
        pendingDepth.set(frame)                      // 丢旧：只留最新
        if (!depthBusy.compareAndSet(false, true)) return
        depthExecutor.execute {
            try {
                while (true) {
                    val f = pendingDepth.getAndSet(null) ?: break
                    runDepthStep(f)
                }
            } finally {
                depthBusy.set(false)
                if (pendingDepth.get() != null) dispatchDepth(pendingDepth.get()!!)
            }
        }
    }

    private fun runDepthStep(frame: Frame) {
        val raw = depthSource?.estimate(frame.bitmap, frame.timestampNs) ?: return
        val ground = GroundCalibrator.fitFromDepth(raw)
        val depth = raw.copy(metricOk = ground?.metricPlausible == true)
        val candidates = CandidateExtractor.extract(depth, ground, frame.width, frame.height)
        lastCandidates.set(candidates)

        // 触发式标注：候选缺标签 + 防抖窗口已过 → 全帧 YOLO（绝不逐候选裁剪）
        if (cache.shouldTrigger(System.currentTimeMillis()) &&
            candidates.any { it.label == null }
        ) {
            cache.markTriggered(System.currentTimeMillis())
            dispatchSem(frame)
        }

        val labeled = Associator.attachLabels(
            candidates, cache.latest(frame.timestampNs), depth, frame.width, frame.height
        )
        publish(labeled, frame.timestampNs, depthMode = true)
    }

    private fun dispatchSem(frame: Frame) {
        pendingSem.set(frame)
        if (!semBusy.compareAndSet(false, true)) return
        semExecutor.execute {
            try {
                while (true) {
                    val f = pendingSem.getAndSet(null) ?: break
                    runSemStep(f)
                }
            } finally {
                semBusy.set(false)
                if (pendingSem.get() != null) dispatchSem(pendingSem.get()!!)
            }
        }
    }

    private fun runSemStep(frame: Frame) {
        val boxes = semanticDetector.detect(frame.bitmap)
        cache.update(SemanticFrameCache.SemanticFrame(frame.timestampNs, boxes))
        val candidates = lastCandidates.get()
        if (candidates.isNotEmpty()) {
            val labeled = Associator.attachLabels(candidates, cache.latest(frame.timestampNs), null, frame.width, frame.height)
            publish(labeled, frame.timestampNs, depthMode = true)
        } else if (depthSource == null) {
            // 纯 YOLO 保底：把标签框当候选（无距离），保证「前方有行人」级别播报不断
            val fallback = boxes.map {
                ObstacleCandidate(
                    bbox = it.box, distanceM = null, distanceRel = 0f,
                    lateralM = Geometry.lateralMeters(
                        (it.box.left + it.box.right) / 2f / frame.width, 1f),
                    risk = RiskLevel.OBSTACLE, label = it.label,
                    direction = Geometry.directionOf(
                        Geometry.lateralMeters((it.box.left + it.box.right) / 2f / frame.width, 1f)),
                )
            }
            publish(fallback, frame.timestampNs, depthMode = false)
        }
    }

    private fun publish(candidates: List<ObstacleCandidate>, ts: Long, depthMode: Boolean) {
        val next = PerceptionState(candidates, ts, depthMode)
        mainHandler.post { if (!closed) _state.value = next }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (out != bitmap) bitmap.recycle()
        return out
    }

    override fun close() {
        closed = true
        depthExecutor.shutdown()
        semExecutor.shutdown()
    }
}
