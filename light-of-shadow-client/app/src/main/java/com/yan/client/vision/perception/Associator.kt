package com.yan.client.vision.perception

import kotlin.math.abs

/**
 * 候选 ↔ YOLO 标签匹配（深度×YOLO 协作的连接件）。
 *
 * 双约束防错配（PDF §3）：
 * - 包围框重叠度 ≥ 0.3；只靠重叠度会把远处同名物体错配到近处候选上
 * - 深度一致性 ≤ 0.5m：候选 p10 与标签框∩候选区的深度中位数之差
 *
 * 贪心按 IoU 降序一对一：一个标签只贴一个候选；未匹配候选照报距离（label=null），
 * 未匹配标签直接丢弃（距离优先，无候选不报）。
 *
 * 坐标约定：候选与标签框均为**旋转后原图坐标**；深度图为该帧 256² 拉伸副本，
 * 采样时按 origWidth/origHeight 换算。
 */
object Associator {

    /** 把标签挂到候选上，返回带 label 的候选副本。 */
    fun attachLabels(
        candidates: List<ObstacleCandidate>,
        frame: SemanticFrameCache.SemanticFrame?,
        depth: DepthMap?,
        origWidth: Int,
        origHeight: Int,
    ): List<ObstacleCandidate> {
        if (frame == null || candidates.isEmpty()) return candidates

        data class Pairing(val ci: Int, val bi: Int, val iou: Float)

        val pairs = ArrayList<Pairing>()
        for ((ci, c) in candidates.withIndex()) {
            for ((bi, b) in frame.boxes.withIndex()) {
                val overlap = Geometry.iou(c.bbox, b.box)
                if (overlap >= PerceptionConfig.MATCH_MIN_IOU &&
                    depthConsistent(c, b, depth, origWidth, origHeight)
                ) {
                    pairs.add(Pairing(ci, bi, overlap))
                }
            }
        }
        pairs.sortByDescending { it.iou }

        val labels = arrayOfNulls<String>(candidates.size)
        val usedBoxes = BooleanArray(frame.boxes.size)
        val usedCands = BooleanArray(candidates.size)
        for (p in pairs) {
            if (usedCands[p.ci] || usedBoxes[p.bi]) continue
            usedCands[p.ci] = true
            usedBoxes[p.bi] = true
            labels[p.ci] = frame.boxes[p.bi].label
        }
        return candidates.mapIndexed { i, c -> if (labels[i] == null) c else c.copy(label = labels[i]) }
    }

    /** 深度一致性：候选距离与标签框∩候选区深度中位数差 ≤ 容差；无深度图退化为仅 IoU。 */
    private fun depthConsistent(
        candidate: ObstacleCandidate,
        labeled: SemanticFrameCache.LabeledBox,
        depth: DepthMap?,
        origWidth: Int,
        origHeight: Int,
    ): Boolean {
        if (depth == null || origWidth <= 0 || origHeight <= 0) return true
        val med = overlapDepthMedian(candidate.bbox, labeled.box, depth, origWidth, origHeight) ?: return true
        val ref = candidate.distanceM ?: candidate.distanceRel
        return abs(ref - med) <= PerceptionConfig.MATCH_DEPTH_TOL_M
    }

    /** 两框交集区的深度中位数（原图坐标 → 256² 深度图采样）。 */
    private fun overlapDepthMedian(
        a: FBox,
        b: FBox,
        depth: DepthMap,
        origWidth: Int,
        origHeight: Int,
    ): Float? {
        val inter = a.intersection(b)
        if (inter.area <= 0f) return null
        val u0 = (inter.left / origWidth * depth.width).toInt().coerceIn(0, depth.width - 1)
        val u1 = (inter.right / origWidth * depth.width).toInt().coerceIn(0, depth.width - 1)
        val v0 = (inter.top / origHeight * depth.height).toInt().coerceIn(0, depth.height - 1)
        val v1 = (inter.bottom / origHeight * depth.height).toInt().coerceIn(0, depth.height - 1)
        val samples = ArrayList<Float>()
        var v = v0
        while (v <= v1) {
            var u = u0
            while (u <= u1) {
                val d = depth.at(u, v)
                if (d > 0.2f && d < 30f && d.isFinite()) samples.add(d)
                u += 2
            }
            v += 2
        }
        if (samples.isEmpty()) return null
        samples.sort()
        return samples[samples.size / 2]
    }
}
