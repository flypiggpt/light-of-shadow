package com.yan.client.vision.perception

import kotlin.math.abs

/**
 * 轨迹平滑：跨帧 IoU 关联 trackId + 距离 EMA + 迟滞（PDF §4 动态目标）。
 *
 * 行人/车辆深度读数抖动明显，不平滑会让播报距离在相邻两帧间来回跳变。
 * 动态目标用更保守的 EMA 系数；风险等级变化需连续两帧确认（迟滞）。
 */
class TrackSmoother {

    data class Track(
        val id: Int,
        val bbox: FBox,
        /** 平滑后播报距离（米）；metric 不可信为 null */
        val distanceM: Float?,
        val distanceRel: Float,
        val risk: RiskLevel,
        val label: String?,
        val direction: String,
        /** 出现帧数（用于标注等待窗口） */
        val ageFrames: Int,
    )

    private data class MutableTrack(
        var id: Int,
        var bbox: FBox,
        var smoothM: Float?,
        var smoothRel: Float,
        var risk: RiskLevel,
        var pendingRisk: RiskLevel,
        var riskConfirm: Int,
        var label: String?,
        var direction: String,
        var ageFrames: Int,
        var missedFrames: Int,
    )

    private val tracks = ArrayList<MutableTrack>()
    private var nextId = 1

    /** 一批候选 → 平滑后的轨迹（距离已 EMA，风险已迟滞，失踪轨迹已清）。 */
    @Synchronized
    fun update(candidates: List<ObstacleCandidate>, nowMs: Long = 0L): List<Track> {
        // 贪心 IoU 关联
        data class Pairing(val ti: Int, val ci: Int, val iou: Float)
        val pairs = ArrayList<Pairing>()
        for ((ti, t) in tracks.withIndex()) {
            for ((ci, c) in candidates.withIndex()) {
                val iou = Geometry.iou(t.bbox, c.bbox)
                if (iou >= 0.3f) pairs.add(Pairing(ti, ci, iou))
            }
        }
        pairs.sortByDescending { it.iou }
        val matchedTrack = BooleanArray(tracks.size)
        val matchedCand = BooleanArray(candidates.size)

        for (p in pairs) {
            if (matchedTrack[p.ti] || matchedCand[p.ci]) continue
            matchedTrack[p.ti] = true
            matchedCand[p.ci] = true
            val t = tracks[p.ti]
            val c = candidates[p.ci]
            t.bbox = c.bbox
            t.direction = c.direction
            t.label = c.label ?: t.label
            t.ageFrames += 1
            t.missedFrames = 0
            // 距离 EMA：动态目标更保守
            val alpha = if (isDynamic(t.label)) PerceptionConfig.SMOOTH_ALPHA_DYNAMIC else PerceptionConfig.SMOOTH_ALPHA
            t.smoothM = if (c.distanceM != null) {
                val prev = t.smoothM
                if (prev == null) c.distanceM else prev + alpha * (c.distanceM - prev)
            } else t.smoothM
            t.smoothRel = if (t.smoothRel <= 0f) c.distanceRel else t.smoothRel + alpha * (c.distanceRel - t.smoothRel)
            // 风险迟滞：变化需连续两帧确认
            if (c.risk == t.risk) {
                t.pendingRisk = c.risk
                t.riskConfirm = 0
            } else if (c.risk == t.pendingRisk) {
                t.riskConfirm += 1
                if (t.riskConfirm >= 2) {
                    t.risk = c.risk
                    t.riskConfirm = 0
                }
            } else {
                t.pendingRisk = c.risk
                t.riskConfirm = 1
            }
        }

        // 未出现轨迹计失踪（只扫本轮之前已存在的轨迹，避免与 matchedTrack 长度错位）
        for (ti in tracks.indices) {
            if (!matchedTrack[ti]) {
                tracks[ti].missedFrames += 1
            }
        }
        tracks.removeAll { it.missedFrames >= 3 }

        // 新轨迹
        for ((ci, c) in candidates.withIndex()) {
            if (matchedCand[ci]) continue
            tracks.add(MutableTrack(
                id = nextId++, bbox = c.bbox,
                smoothM = c.distanceM, smoothRel = c.distanceRel,
                risk = c.risk, pendingRisk = c.risk, riskConfirm = 0,
                label = c.label, direction = c.direction,
                ageFrames = 1, missedFrames = 0))
        }

        return tracks.map {
            Track(it.id, it.bbox, it.smoothM, it.smoothRel, it.risk, it.label, it.direction, it.ageFrames)
        }
    }

    private fun isDynamic(label: String?): Boolean = label in setOf(
        "行人", "自行车", "汽车", "摩托车", "公交车", "卡车", "鸟", "猫", "狗", "马", "羊", "牛"
    )
}
