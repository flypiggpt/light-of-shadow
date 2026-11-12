package com.yan.client.vision.perception

/**
 * 标注触发器 + 语义帧缓存（触发式标注协作机制的服务端）。
 *
 * - [shouldTrigger]：候选缺标签时才触发全帧 YOLO；250ms 防抖合并、上限 4Hz
 *   （防连续发现时逐候选推理压垮帧率——PDF §3 的帧率崩溃坑）
 * - [latest]：TTL 500ms 的标注结果缓存，静止/缓变场景跨帧复用，不重复触发
 */
class SemanticFrameCache {

    /** YOLO 一次全帧推理的结果：框 + 名字（旋转后原图坐标）。 */
    data class LabeledBox(val box: FBox, val label: String, val score: Float)

    data class SemanticFrame(val timestampNs: Long, val boxes: List<LabeledBox>)

    private var frame: SemanticFrame? = null
    private var lastTriggerAtMs: Long = 0L

    @Synchronized
    fun update(frame: SemanticFrame) {
        this.frame = frame
    }

    /** 缓存内最新语义帧；超 TTL 视为过期返回 null（该批候选 label=null）。 */
    @Synchronized
    fun latest(nowNs: Long): SemanticFrame? {
        val f = frame ?: return null
        val ageMs = (nowNs - f.timestampNs) / 1_000_000
        return if (ageMs in 0..PerceptionConfig.LABEL_CACHE_TTL_MS) f else null
    }

    /** 是否该触发一次标注：有候选缺标签 + 防抖窗口已过。 */
    @Synchronized
    fun shouldTrigger(nowMs: Long): Boolean {
        return nowMs - lastTriggerAtMs >= PerceptionConfig.LABEL_MIN_INTERVAL_MS
    }

    @Synchronized
    fun markTriggered(nowMs: Long) {
        lastTriggerAtMs = nowMs
    }
}
