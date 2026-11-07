package com.yan.client.vision.perception

/**
 * 播报状态机（PDF §3 播报触发）：
 * - 触发：新目标、距离变化 > 0.5m、风险升级
 * - 限流：最小间隔 1.5s、每轮最多 2 条、台阶优先（最易造成摔倒的场景先说）
 * - 标签等待：新目标播报前等标注 ≤250ms；超时按「障碍物」**一次性**播报，不二次升级补播
 *   （宁可信息少，不可信息错 + 不对用户啰嗦）
 */
class AlertStateMachine {

    data class AlertEvent(val key: String, val text: String)

    private data class AnnouncedState(
        var announced: Boolean = false,
        var lastDistanceM: Float? = null,
        var lastRisk: RiskLevel = RiskLevel.OBSTACLE,
        var firstSeenMs: Long = 0L,
    )

    private val announced = HashMap<Int, AnnouncedState>()
    // 初值让首个目标立即可播（否则启动后 1.5s 内全部静默）
    private var lastSpeakAtMs = -PerceptionConfig.ANNOUNCE_MIN_INTERVAL_MS

    /** 每轮状态到达时调用；返回本轮应播报事件（已按优先级排序、已限流）。 */
    @Synchronized
    fun poll(tracks: List<TrackSmoother.Track>, nowMs: Long): List<AlertEvent> {
        val due = ArrayList<Pair<TrackSmoother.Track, Int>>()  // track → 优先级分数（小=先说）

        for (t in tracks) {
            val st = announced.getOrPut(t.id) { AnnouncedState(firstSeenMs = nowMs) }
            val dist = t.distanceM
            val riskUp = riskRank(t.risk) < riskRank(st.lastRisk)   // 枚举序：台阶类优先级更高
            val newTarget = !st.announced
            val bigChange = dist != null && st.lastDistanceM != null &&
                    kotlin.math.abs(dist - st.lastDistanceM!!) > PerceptionConfig.TRIGGER_DELTA_M
            // 标签等待窗口：新目标且还没等到标签，且窗口未超时 → 本轮先不播
            val waitingLabel = newTarget && t.label == null &&
                    nowMs - st.firstSeenMs < PerceptionConfig.LABEL_WAIT_MS &&
                    t.ageFrames <= 1

            if (waitingLabel) continue
            if (newTarget || bigChange || riskUp) {
                val priority = if (riskRank(t.risk) <= riskRank(RiskLevel.UP_STAIR)) 0 else 1
                due.add(t to priority)
            }
        }

        if (due.isEmpty()) return emptyList()
        if (nowMs - lastSpeakAtMs < PerceptionConfig.ANNOUNCE_MIN_INTERVAL_MS) return emptyList()

        // 台阶优先，其次近的先说
        val sorted = due.sortedWith(compareBy({ it.second }, { it.first.distanceM ?: Float.MAX_VALUE }))
        val batch = sorted.take(PerceptionConfig.ANNOUNCE_MAX_BATCH)
        lastSpeakAtMs = nowMs

        return batch.map { (t, _) ->
            val st = announced[t.id]!!
            st.announced = true
            st.lastDistanceM = t.distanceM ?: st.lastDistanceM
            st.lastRisk = t.risk
            AlertEvent(key = "track_${t.id}", text = composeText(t))
        }
    }

    /** 风险优先级序：台阶/坑洞 < 普通障碍（数字小=优先）。 */
    private fun riskRank(risk: RiskLevel): Int = when (risk) {
        RiskLevel.DOWN_STAIR, RiskLevel.PIT -> 0
        RiskLevel.UP_STAIR -> 1
        RiskLevel.OBSTACLE -> 2
    }

    /**
     * 文案组合（失败模式矩阵的出口）：
     * - 米制可用：「左前方 2.3 米 有行人 / 有障碍物」
     * - 米制不可用：「左前方 有行人」（不给米数）
     */
    private fun composeText(t: TrackSmoother.Track): String {
        val name = t.label ?: when (t.risk) {
            RiskLevel.UP_STAIR -> "台阶"
            RiskLevel.DOWN_STAIR -> "下行台阶"
            RiskLevel.PIT -> "坑洞"
            RiskLevel.OBSTACLE -> "障碍物"
        }
        val dist = t.distanceM
        return if (dist != null) {
            val meters = if (dist >= 10f) dist.toInt().toString() else String.format("%.1f", dist)
            "${t.direction} ${meters}米 有${name}"
        } else {
            "${t.direction} 有${name}"
        }
    }
}
