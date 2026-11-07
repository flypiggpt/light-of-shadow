package com.yan.client.vision.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地面校正单测：合成地面深度图拟合、米制门禁、残差符号约定、降级分支。
 */
class GroundCalibratorTest {

    /**
     * 生成理想地面深度图：相机高 h、俯仰角 pitch（向下为正），地面点深度 d = h / sin(θ+pitch)。
     */
    private fun syntheticGroundDepth(h: Float, pitchRad: Float, noise: Float = 0f): DepthMap {
        val w = 64
        val ht = 64
        val values = FloatArray(w * ht)
        val rng = java.util.Random(11)
        val fy = Geometry.focalPx(w)
        for (v in 0 until ht) {
            for (u in 0 until w) {
                val theta = Geometry.elevationRad(v, ht)
                val denom = kotlin.math.sin(theta + pitchRad)
                // 只有像素视线朝向地面（θ+pitch>0）才有地面深度
                // 平面截距公式：d = h·cosθ / sin(θ+pitch)
                val d = if (denom > 0.05f) h * kotlin.math.cos(theta) / denom else 30f
                values[v * w + u] = (d + (rng.nextFloat() - 0.5f) * noise).coerceIn(0.2f, 30f)
            }
        }
        return DepthMap(w, ht, values, metricOk = true)
    }

    @Test
    fun fitRecoversFlatGround() {
        val depth = syntheticGroundDepth(h = 1.5f, pitchRad = 0.35f)
        val fit = GroundCalibrator.fitFromDepth(depth)
        assertNotNull("理想地面应拟合成功", fit)
        fit!!
        assertTrue("内点比例应高：${fit.inlierRatio}", fit.inlierRatio > 0.8f)
        assertEquals("推定相机高度应接近 1.5m", 1.5f, fit.cameraHeightM, 0.2f)
        assertTrue("合理高度应判定米制可信", fit.metricPlausible)
    }

    @Test
    fun fitWithNoiseStillPlausible() {
        val depth = syntheticGroundDepth(h = 1.3f, pitchRad = 0.3f, noise = 0.03f)
        val fit = GroundCalibrator.fitFromDepth(depth)
        assertNotNull(fit)
        fit!!
        assertTrue(fit.metricPlausible)
        assertEquals(1.3f, fit.cameraHeightM, 0.25f)
    }

    @Test
    fun randomDepthFailsToLookLikeGround() {
        // 反光地面/玻璃场景模拟：深度近似随机 → 拟合退化或法向不像地面 → null
        val w = 64
        val ht = 64
        val rng = java.util.Random(5)
        val values = FloatArray(w * ht) { 0.5f + rng.nextFloat() * 8f }
        val fit = GroundCalibrator.fitFromDepth(DepthMap(w, ht, values, metricOk = true))
        if (fit != null) {
            // 极端情况下 RANSAC 可能拟出某个平面，但高度/内点必不达标 → 米制不可信
            assertFalse("随机深度不得判米制可信", fit.metricPlausible)
        }
    }

    @Test
    fun implausibleHeightMarksMetricUntrusted() {
        // 平面拟合本身成功但推定高度离谱（米制尺度偏差）→ 仍可用作台阶基准，但不给米数
        val plane = Plane(0f, -1f, 0f, 4.0f)   // 高度 4.0m，超出 [0.7, 2.3]
        val fit = GroundFit(plane, 1f, plane.estimatedCameraHeight())
        assertEquals(4.0f, fit.cameraHeightM, 1e-3f)
        assertFalse(fit.metricPlausible)
    }

    @Test
    fun wallNormalRejectedAsGround() {
        // 拟到墙（法向指向前方）：ny 不满足地面朝上假设 → null
        val wall = Plane(0f, -0.1f, 0.99f, 2.0f)
        val rng = java.util.Random(3)
        val pts = List(300) {
            floatArrayOf((rng.nextFloat() - 0.5f), (rng.nextFloat() - 0.5f), 2.0f + (rng.nextFloat() - 0.5f) * 0.01f)
        }
        val fit = GroundCalibrator.fit(pts)
        assertNull("垂直墙面不应被当作地面", fit)
    }

    @Test
    fun distanceScaleKnobApplies() {
        val raw = 2.0f
        val corrected = GroundCalibrator.correctDistance(raw)
        assertEquals(raw * PerceptionConfig.GROUND_DISTANCE_SCALE, corrected, 1e-5f)
    }

    @Test
    fun stairResidualSignConventionOnRealPlane() {
        val depth = syntheticGroundDepth(h = 1.5f, pitchRad = 0.35f)
        val fit = GroundCalibrator.fitFromDepth(depth)!!
        // 从平面方程反算 z=2 处的地面 y（平面有俯仰角，不能直接用相机高度当 y）
        val p = fit.plane
        val yOnPlane = -(p.nx * 0f + p.nz * 2f + p.d) / p.ny
        // 地面上的点残差≈0；上方（y 更小）残差为正（上行/障碍）；下方（y 更大）为负（坑/下行）
        val on = GroundCalibrator.residual(fit, floatArrayOf(0f, yOnPlane, 2f))
        val above = GroundCalibrator.residual(fit, floatArrayOf(0f, yOnPlane - 0.3f, 2f))
        val below = GroundCalibrator.residual(fit, floatArrayOf(0f, yOnPlane + 0.3f, 2f))
        assertTrue("平面上点残差应小：$on", kotlin.math.abs(on) < 0.1f)
        assertTrue("凸起点残差为正：$above", above > PerceptionConfig.STAIR_RESIDUAL_M)
        assertTrue("凹陷点残差为负：$below", below < -PerceptionConfig.STAIR_RESIDUAL_M)
    }
}
