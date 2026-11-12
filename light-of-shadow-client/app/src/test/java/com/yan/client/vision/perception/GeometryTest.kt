package com.yan.client.vision.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

/**
 * 纯几何单测：IoU、走廊掩膜、像素↔米制换算、RANSAC 平面拟合。
 * 合成数据全部可复现（固定 seed），不依赖 Android / 推理。
 */
class GeometryTest {

    // ---- IoU ----

    @Test
    fun iouIdenticalBoxesIsOne() {
        val a = FBox(0f, 0f, 100f, 100f)
        assertEquals(1f, Geometry.iou(a, a), 1e-5f)
    }

    @Test
    fun iouDisjointBoxesIsZero() {
        val a = FBox(0f, 0f, 10f, 10f)
        val b = FBox(20f, 20f, 30f, 30f)
        assertEquals(0f, Geometry.iou(a, b), 1e-5f)
    }

    @Test
    fun iouPartialOverlap() {
        val a = FBox(0f, 0f, 10f, 10f)      // area 100
        val b = FBox(5f, 0f, 15f, 10f)      // area 100，交集 50
        assertEquals(50f / 150f, Geometry.iou(a, b), 1e-5f)
    }

    @Test
    fun iouContainsIsAreaRatio() {
        val big = FBox(0f, 0f, 10f, 10f)    // 100
        val small = FBox(2f, 2f, 4f, 4f)    // 4
        assertEquals(4f / 100f, Geometry.iou(big, small), 1e-5f)
    }

    // ---- 走廊掩膜 ----

    @Test
    fun corridorHalfWidthInterpolates() {
        assertEquals(0.35f, Geometry.corridorHalfWidth(0f), 1e-4f)
        assertEquals(1.2f, Geometry.corridorHalfWidth(3f), 1e-4f)
        assertEquals(0.35f + (1.2f - 0.35f) / 3f, Geometry.corridorHalfWidth(1f), 1e-4f)
    }

    @Test
    fun inCorridorBounds() {
        // 近端（d=0.5）半宽 ≈ 0.49
        assertTrue(Geometry.inCorridor(0.3f, 0.5f))
        assertFalse(Geometry.inCorridor(0.8f, 0.5f))
        // 3m 处半宽 1.2
        assertTrue(Geometry.inCorridor(1.1f, 3f))
        assertFalse(Geometry.inCorridor(1.5f, 3f))
        // 走廊远端截断之外不报
        assertFalse(Geometry.inCorridor(0f, 3.5f))
        assertFalse(Geometry.inCorridor(0f, 0f))
    }

    // ---- 像素 ↔ 米制 ----

    @Test
    fun lateralMetersCentersToZero() {
        assertEquals(0f, Geometry.lateralMeters(0.5f, 2f), 1e-4f)
        assertTrue(Geometry.lateralMeters(0.9f, 2f) > 0f)   // 右侧为正
        assertTrue(Geometry.lateralMeters(0.1f, 2f) < 0f)   // 左侧为负
    }

    @Test
    fun lateralAtImageEdgeMatchesHalfFov() {
        val d = 2f
        val expected = d * tan(Math.toRadians(PerceptionConfig.HFOV_DEG.toDouble()).toFloat() / 2f)
        assertEquals(expected, Geometry.lateralMeters(1f, d), 1e-3f)
    }

    @Test
    fun depthTo3DProjectsCenterOnOpticalAxis() {
        val p = Geometry.depthTo3D(128, 128, 2f, 256, 256)
        assertEquals(0f, p[0], 1e-4f)
        assertEquals(0f, p[1], 1e-4f)
        assertEquals(2f, p[2], 1e-4f)
    }

    @Test
    fun directionOfBands() {
        assertEquals("左前方", Geometry.directionOf(-0.6f))
        assertEquals("前方", Geometry.directionOf(0f))
        assertEquals("右前方", Geometry.directionOf(0.6f))
    }

    // ---- 平面拟合 ----

    private fun groundPlanePoints(height: Float, noise: Float, outlierRatio: Float): List<FloatArray> {
        val rng = java.util.Random(7)
        val pts = ArrayList<FloatArray>()
        // 平面 y = height（相机坐标 Y 向下，地面在相机下方 height 米）
        repeat(400) {
            if (rng.nextDouble() < outlierRatio) {
                pts.add(floatArrayOf(
                    (rng.nextFloat() - 0.5f) * 4f,
                    height + (rng.nextFloat() - 0.5f) * 2f,   // 离群点
                    1f + rng.nextFloat() * 4f))
            } else {
                pts.add(floatArrayOf(
                    (rng.nextFloat() - 0.5f) * 4f,
                    height + (rng.nextFloat() - 0.5f) * noise,
                    1f + rng.nextFloat() * 4f))
            }
        }
        return pts
    }

    @Test
    fun planeFitRecoversGroundPlane() {
        val (plane, ratio) = Geometry.planeFitRansac(groundPlanePoints(1.5f, 0.01f, 0.05f))!!
        assertTrue("内点比例应高，实际 $ratio", ratio > 0.85f)
        assertTrue("地面法向 ny 应为负（Y 向下坐标系）", plane.ny < -0.9f)
        assertEquals(1.5f, plane.estimatedCameraHeight(), 0.1f)
    }

    @Test
    fun planeFitHandlesOutliers() {
        val (plane, ratio) = Geometry.planeFitRansac(groundPlanePoints(1.4f, 0.02f, 0.15f))!!
        assertTrue("含 15% 离群点仍应拟合出地面，内点比 $ratio", ratio > 0.7f)
        assertEquals(1.4f, plane.estimatedCameraHeight(), 0.15f)
    }

    @Test
    fun planeFitDegenerateInputReturnsNull() {
        assertNull(Geometry.planeFitRansac(listOf(floatArrayOf(0f, 0f, 0f))))
        val collinear = listOf(
            floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 1f, 0f), floatArrayOf(2f, 1f, 0f))
        assertNull(Geometry.planeFitRansac(collinear))
    }

    @Test
    fun planeSignedDistanceSignConvention() {
        // 平面 y = 1.5（地面）：法向 (0,-1,0)，d=1.5 → n·p+d = -y+1.5
        val plane = Plane(0f, -1f, 0f, 1.5f)
        // 点在平面上方（y<1.5，凸起障碍）→ 距离为正
        assertTrue(plane.signedDistance(0f, 1.2f, 2f) > 0f)
        // 点低于平面（y>1.5，坑洞）→ 距离为负
        assertTrue(plane.signedDistance(0f, 1.8f, 2f) < 0f)
        assertEquals(1.5f, plane.estimatedCameraHeight(), 1e-4f)
        // 残差符号约定
        val fit = GroundFit(plane, 1f, 1.5f)
        assertTrue(GroundCalibrator.residual(fit, floatArrayOf(0f, 1.2f, 2f)) > 0f)
        assertTrue(GroundCalibrator.residual(fit, floatArrayOf(0f, 1.8f, 2f)) < 0f)
        assertTrue(abs(GroundCalibrator.residual(fit, floatArrayOf(0f, 1.5f, 2f))) < 1e-4f)
    }
}
