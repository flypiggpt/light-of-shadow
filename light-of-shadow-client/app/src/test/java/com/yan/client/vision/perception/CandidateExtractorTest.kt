package com.yan.client.vision.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * 候选抽取单测：合成深度图（地面 + 凸起块 / 凹陷块）验证
 * 残差分割、走廊过滤、p10 距离、风险分级、米制门禁、相对深度降级。
 */
class CandidateExtractorTest {

    private val W = 64
    private val H = 64

    /** 生成地面深度图（相机高 1.5m、俯仰 0.35rad），可叠加矩形异常区。 */
    private fun groundDepth(
        h: Float = 1.5f,
        pitch: Float = 0.35f,
        block: ((u: Int, v: Int, groundD: Float) -> Float)? = null,
    ): DepthMap {
        val values = FloatArray(W * H)
        for (v in 0 until H) {
            for (u in 0 until W) {
                val theta = Geometry.elevationRad(v, H)
                val denom = sin(theta + pitch)
                val d = if (denom > 0.05f) h * kotlin.math.cos(theta) / denom else 30f
                values[v * W + u] = block?.invoke(u, v, d) ?: d
            }
        }
        return DepthMap(W, H, values, metricOk = true)
    }

    private fun fitOf(depth: DepthMap): GroundFit? = GroundCalibrator.fitFromDepth(depth)

    // ---- 基础：纯地面不产生候选 ----

    @Test
    fun flatGroundYieldsNoCandidates() {
        val depth = groundDepth()
        val cands = CandidateExtractor.extract(depth, fitOf(depth), 64, 64)
        assertTrue("纯地面不应有候选，实际 $cands", cands.isEmpty())
    }

    // ---- 凸起障碍 ----

    @Test
    fun raisedBlockProducesObstacleCandidate() {
        // 中央 8×8 像素块深度打 6 折（明显近于地面 → 正残差）
        val depth = groundDepth(block = { u, v, g ->
            if (u in 28..35 && v in 30..37) g * 0.6f else g
        })
        val cands = CandidateExtractor.extract(depth, fitOf(depth), 64, 64)
        assertTrue("凸起块应产生候选", cands.isNotEmpty())
        val c = cands.maxByOrNull { it.bbox.area }!!
        // 中心附近、距离小于地面距离
        assertTrue("距离应为候选区 p10：${c.distanceM}", c.distanceM != null && c.distanceM < 3f)
        assertEquals("前方", c.direction)
        assertTrue("米制可用时距离非空", c.distanceM != null)
    }

    @Test
    fun candidatePicksNearestEdge() {
        // 同一候选内一半近一半远（连通）→ p10 应偏向近侧边缘
        val depth = groundDepth(block = { u, v, g ->
            if (u in 28..39 && v in 30..39) {
                if (u <= 33) g * 0.5f else g * 0.8f
            } else g
        })
        val cands = CandidateExtractor.extract(depth, fitOf(depth), 64, 64)
        assertEquals("两半连通应合成一个候选", 1, cands.size)
        assertTrue("p10 应接近最近边缘而非中位：${cands[0].distanceRel}", cands[0].distanceRel < 2.0f)
    }

    // ---- 凹陷：下行台阶 / 坑洞 ----

    @Test
    fun belowPlaneRegionIsDownwardRisk() {
        // 块深于地面 0.5m → 负残差 → 下行台阶/坑洞
        val depth = groundDepth(block = { u, v, g ->
            if (u in 28..35 && v in 48..57) g + 0.25f else g
        })
        val cands = CandidateExtractor.extract(depth, fitOf(depth), 64, 64)
        assertTrue("凹陷块应产生候选", cands.isNotEmpty())
        val risk = cands.first().risk
        assertTrue("应判下行/坑洞，实际 $risk", risk == RiskLevel.DOWN_STAIR || risk == RiskLevel.PIT)
    }

    @Test
    fun deepPitIsPitNotJustDownStair() {
        val depth = groundDepth(block = { u, v, g ->
            if (u in 28..35 && v in 48..57) g + 0.8f else g
        })
        val cands = CandidateExtractor.extract(depth, fitOf(depth), 64, 64)
        assertTrue("深坑块应产生候选", cands.isNotEmpty())
        assertEquals(RiskLevel.PIT, cands.first().risk)
    }

    // ---- 走廊过滤 ----

    @Test
    fun outOfCorridorBlockIgnored() {
        // 最左列边缘块（走廊外）
        val depth = groundDepth(block = { u, v, g ->
            if (u in 0..3 && v in 30..37) g * 0.6f else g
        })
        val cands = CandidateExtractor.extract(depth, fitOf(depth), 64, 64)
        assertTrue("走廊外不应产生候选，实际 $cands", cands.isEmpty())
    }

    // ---- 米制门禁与降级 ----

    @Test
    fun metricUntrustedHidesMeters() {
        val depth = groundDepth(block = { u, v, g ->
            if (u in 28..35 && v in 30..37) g * 0.6f else g
        }).copy(metricOk = false)
        val cands = CandidateExtractor.extract(depth, fitOf(depth), 64, 64)
        assertTrue(cands.isNotEmpty())
        assertNull("米制不可信不得给米数", cands.first().distanceM)
        assertTrue("相对深度信号仍在", cands.first().distanceRel > 0f)
    }

    @Test
    fun noGroundFallsBackToRelativeMode() {
        // 无地面平面：近距块仍被发现，但不给米数（「前方有东西」路径）
        val values = FloatArray(W * H) { 5f }
        for (v in 30..37) for (u in 28..35) values[v * W + u] = 0.8f
        val depth = DepthMap(W, H, values, metricOk = true)
        val cands = CandidateExtractor.extract(depth, ground = null, origWidth = 64, origHeight = 64)
        assertTrue("相对深度模式应发现近距块", cands.isNotEmpty())
        assertNull("校正失败不得给米数", cands.first().distanceM)
    }

    @Test
    fun tinyNoiseFilteredOut() {
        // 单像素噪点低于最小面积阈值
        val depth = groundDepth(block = { u, v, g -> if (u == 32 && v == 32) g * 0.6f else g })
        val cands = CandidateExtractor.extract(depth, fitOf(depth), 64, 64)
        assertTrue("零星噪点应被过滤", cands.isEmpty())
    }

    // ---- 方向 ----

    @Test
    fun lateralBlockGetsLeftOrRightDirection() {
        val depth = groundDepth(block = { u, v, g ->
            if (u in 12..22 && v in 30..37) g * 0.6f else g
        })
        val cands = CandidateExtractor.extract(depth, fitOf(depth), 64, 64)
        assertTrue(cands.isNotEmpty())
        assertEquals("左前方", cands.first().direction)
    }
}
