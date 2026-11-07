package com.yan.client.vision.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匹配/平滑/播报状态机单测：4 组合矩阵、防错配、一对一、EMA、迟滞、
 * 1.5s 节流、2 条上限、标签等待窗口、台阶优先。
 */
class MatchingSmootherAlertTest {

    // ---------- Associator ----------

    private fun cand(
        left: Float, top: Float, right: Float, bottom: Float,
        dist: Float?, rel: Float = dist ?: 1f,
        label: String? = null, risk: RiskLevel = RiskLevel.OBSTACLE,
    ) = ObstacleCandidate(
        bbox = FBox(left, top, right, bottom), distanceM = dist, distanceRel = rel,
        lateralM = 0f, risk = risk, label = label, direction = "前方")

    private fun box(left: Float, top: Float, right: Float, bottom: Float, label: String) =
        SemanticFrameCache.LabeledBox(FBox(left, top, right, bottom), label, 0.9f)

    @Test
    fun labelAttachesOnOverlap() {
        val cands = listOf(cand(0f, 0f, 10f, 10f, dist = 2f))
        val frame = SemanticFrameCache.SemanticFrame(0, listOf(box(1f, 1f, 9f, 9f, "行人")))
        val out = Associator.attachLabels(cands, frame, depth = null, origWidth = 100, origHeight = 100)
        assertEquals("行人", out[0].label)
    }

    @Test
    fun noOverlapMeansNoLabel() {
        val cands = listOf(cand(0f, 0f, 10f, 10f, dist = 2f))
        val frame = SemanticFrameCache.SemanticFrame(0, listOf(box(50f, 50f, 60f, 60f, "行人")))
        val out = Associator.attachLabels(cands, frame, depth = null, origWidth = 100, origHeight = 100)
        assertNull(out[0].label)
    }

    @Test
    fun oneToOneConstraint() {
        // 两个候选重叠同一个标签框 → 只有一个拿到
        val cands = listOf(
            cand(0f, 0f, 10f, 10f, dist = 2f),
            cand(4f, 4f, 14f, 14f, dist = 2.1f))
        val frame = SemanticFrameCache.SemanticFrame(0, listOf(box(2f, 2f, 12f, 12f, "椅子")))
        val out = Associator.attachLabels(cands, frame, depth = null, origWidth = 100, origHeight = 100)
        assertEquals(1, out.count { it.label == "椅子" })
    }

    @Test
    fun farSameNameDoesNotStealNearCandidate() {
        // 远处同名标签框（深度对不上）不得配到近处候选上
        val near = cand(0f, 0f, 10f, 10f, dist = 1.0f)
        // 深度图：全 5 米（远处），与候选 1.0m 差 > 0.5m → 深度不一致，拒绝匹配
        val depth = DepthMap(10, 10, FloatArray(100) { 5f }, metricOk = true)
        val frame = SemanticFrameCache.SemanticFrame(0, listOf(box(0f, 0f, 10f, 10f, "椅子")))
        val out = Associator.attachLabels(listOf(near), frame, depth, origWidth = 10, origHeight = 10)
        assertNull("深度差超容差不得挂标签", out[0].label)
    }

    @Test
    fun consistentDepthAttachesLabel() {
        val near = cand(0f, 0f, 10f, 10f, dist = 2.0f)
        val depth = DepthMap(10, 10, FloatArray(100) { 2.1f }, metricOk = true)
        val frame = SemanticFrameCache.SemanticFrame(0, listOf(box(0f, 0f, 10f, 10f, "椅子")))
        val out = Associator.attachLabels(listOf(near), frame, depth, origWidth = 10, origHeight = 10)
        assertEquals("椅子", out[0].label)
    }

    @Test
    fun emptyFrameReturnsCandidatesUntouched() {
        val cands = listOf(cand(0f, 0f, 10f, 10f, dist = 2f))
        val out = Associator.attachLabels(cands, null, null, 100, 100)
        assertNull(out[0].label)
    }

    // ---------- TrackSmoother ----------

    @Test
    fun jitterIsSmoothed() {
        val s = TrackSmoother()
        // 三帧抖动：1.8 / 2.6 / 1.9 —— 平滑值不应跟着跳到 2.6
        val seq = listOf(1.8f, 2.6f, 1.9f)
        var last: Float? = null
        for (d in seq) {
            val tracks = s.update(listOf(cand(0f, 0f, 10f, 10f, dist = d)))
            last = tracks[0].distanceM
        }
        assertNotNull(last)
        assertTrue("平滑值应居中而非跳到 2.6：$last", last!! < 2.3f && last > 1.7f)
    }

    @Test
    fun dynamicTargetUsesSlowerEma() {
        val s = TrackSmoother()
        s.update(listOf(cand(0f, 0f, 10f, 10f, dist = 2.0f, label = "行人")))
        val t2 = s.update(listOf(cand(0f, 0f, 10f, 10f, dist = 3.0f, label = "行人")))[0]
        // α=0.3：2.0 + 0.3*1.0 = 2.3
        assertEquals(2.3f, t2.distanceM!!, 0.05f)
    }

    @Test
    fun tracksMergeByIouAcrossFrames() {
        val s = TrackSmoother()
        val t1 = s.update(listOf(cand(0f, 0f, 10f, 10f, dist = 2f)))[0]
        val t2 = s.update(listOf(cand(1f, 1f, 11f, 11f, dist = 2.1f)))[0]
        assertEquals("同目标跨帧应保持同一 trackId", t1.id, t2.id)
    }

    @Test
    fun vanishedTracksAreDropped() {
        val s = TrackSmoother()
        s.update(listOf(cand(0f, 0f, 10f, 10f, dist = 2f)))
        // 连续 3 帧无匹配才清除（防一帧抖动误删）：1、2 帧仍在，第 3 帧移除
        assertEquals(1, s.update(emptyList()).size)
        assertEquals(1, s.update(emptyList()).size)
        assertEquals(0, s.update(emptyList()).size)
    }

    @Test
    fun riskChangeNeedsTwoFrameConfirm() {
        val s = TrackSmoother()
        s.update(listOf(cand(0f, 0f, 10f, 10f, dist = 2f, risk = RiskLevel.OBSTACLE)))
        // 一帧翻转不生效
        val t1 = s.update(listOf(cand(0f, 0f, 10f, 10f, dist = 2f, risk = RiskLevel.UP_STAIR)))[0]
        assertEquals(RiskLevel.OBSTACLE, t1.risk)
        // 第二帧确认生效
        val t2 = s.update(listOf(cand(0f, 0f, 10f, 10f, dist = 2f, risk = RiskLevel.UP_STAIR)))[0]
        assertEquals(RiskLevel.UP_STAIR, t2.risk)
    }

    // ---------- AlertStateMachine ----------

    private fun track(
        id: Int, dist: Float?, label: String? = null,
        risk: RiskLevel = RiskLevel.OBSTACLE, ageFrames: Int = 3,
    ) = TrackSmoother.Track(
        id = id, bbox = FBox(0f, 0f, 1f, 1f), distanceM = dist,
        distanceRel = dist ?: 1f, risk = risk, label = label,
        direction = "前方", ageFrames = ageFrames)

    @Test
    fun newTargetFiresWithMeters() {
        val m = AlertStateMachine()
        val events = m.poll(listOf(track(1, 2.3f, "行人")), nowMs = 1000)
        assertEquals(1, events.size)
        assertTrue(events[0].text.contains("2.3米"))
        assertTrue(events[0].text.contains("行人"))
    }

    @Test
    fun newTargetWithoutLabelSaysObstacle() {
        val m = AlertStateMachine()
        // ageFrames>1 = 已过标签等待窗口
        val events = m.poll(listOf(track(1, 2.3f, label = null, ageFrames = 3)), nowMs = 1000)
        assertEquals(1, events.size)
        assertTrue(events[0].text.contains("障碍物"))
    }

    @Test
    fun labelWaitWindowDefersNewTarget() {
        val m = AlertStateMachine()
        // 新目标（ageFrames=1）且无标签 → 等窗口，不播
        val events = m.poll(listOf(track(1, 2.3f, label = null, ageFrames = 1)), nowMs = 1000)
        assertEquals(0, events.size)
        // 窗口过后（250ms+）仍无标签 → 按「障碍物」播一次
        val later = m.poll(listOf(track(1, 2.3f, label = null, ageFrames = 3)), nowMs = 1400)
        assertEquals(1, later.size)
        // 再来一帧无变化 → 不重复播（不二次升级）
        val again = m.poll(listOf(track(1, 2.3f, label = "行人", ageFrames = 4)), nowMs = 2000)
        assertEquals(0, again.size)
    }

    @Test
    fun minIntervalGatesSpeech() {
        val m = AlertStateMachine()
        m.poll(listOf(track(1, 2f)), nowMs = 1000)
        // 500ms 后新目标 → 1.5s 间隔内被拦
        val events = m.poll(listOf(track(1, 2f), track(2, 3f)), nowMs = 1500)
        assertEquals(0, events.size)
        // 间隔过了 → 可以播
        val later = m.poll(listOf(track(1, 2f), track(2, 3f)), nowMs = 2600)
        assertTrue(later.isNotEmpty())
    }

    @Test
    fun batchLimitedToTwo() {
        val m = AlertStateMachine()
        val events = m.poll(
            listOf(track(1, 3f), track(2, 1f), track(3, 2f), track(4, 4f)), nowMs = 1000)
        assertEquals(2, events.size)
    }

    @Test
    fun distanceChangeOverThresholdRetriggers() {
        val m = AlertStateMachine()
        m.poll(listOf(track(1, 2.0f)), nowMs = 1000)
        // Δ=0.2m < 0.5 → 不触发
        assertEquals(0, m.poll(listOf(track(1, 2.2f)), nowMs = 3000).size)
        // Δ=0.8m > 0.5 → 触发
        assertEquals(1, m.poll(listOf(track(1, 3.0f)), nowMs = 5000).size)
    }

    @Test
    fun stairsSpokenBeforeObstacles() {
        val m = AlertStateMachine()
        val events = m.poll(
            listOf(track(1, 4f, risk = RiskLevel.OBSTACLE), track(2, 5f, risk = RiskLevel.DOWN_STAIR)),
            nowMs = 1000)
        assertEquals(2, events.size)
        assertTrue("台阶应先播报", events[0].text.contains("台阶"))
    }

    @Test
    fun noMetersWhenMetricUnavailable() {
        val m = AlertStateMachine()
        val events = m.poll(listOf(track(1, null, "椅子")), nowMs = 1000)
        assertEquals(1, events.size)
        assertTrue(!events[0].text.contains("米"))
        assertTrue(events[0].text.contains("椅子"))
    }

    @Test
    fun riskUpgradeRetriggers() {
        val m = AlertStateMachine()
        m.poll(listOf(track(1, 2f, risk = RiskLevel.OBSTACLE)), nowMs = 1000)
        val events = m.poll(listOf(track(1, 2f, risk = RiskLevel.UP_STAIR)), nowMs = 3000)
        assertEquals("风险升级应重新播报", 1, events.size)
    }
}
