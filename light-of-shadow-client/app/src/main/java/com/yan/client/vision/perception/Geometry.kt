package com.yan.client.vision.perception

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 感知几何：IoU、走廊掩膜、像素↔米制换算、地面平面拟合（RANSAC）。
 * 全部纯函数，JVM 可单测。
 */
object Geometry {

    /** 包围框交并比。 */
    fun iou(a: FBox, b: FBox): Float {
        val inter = a.intersection(b)
        if (inter.area <= 0f) return 0f
        return inter.area / (a.area + b.area - inter.area)
    }

    /** 名义水平视场换算焦距（像素）：fx = (W/2) / tan(HFOV/2)，方像素 fy=fx。 */
    fun focalPx(imageWidth: Int): Float =
        (imageWidth / 2f) / tan(Math.toRadians(PerceptionConfig.HFOV_DEG.toDouble()).toFloat() / 2f)

    /** 候选横向位置（米，负左正右）：xNorm ∈ [0,1]，距离 d 处的成像宽度 2·d·tan(HFOV/2)。 */
    fun lateralMeters(xNorm: Float, distanceM: Float): Float =
        (xNorm - 0.5f) * 2f * distanceM * tan(Math.toRadians(PerceptionConfig.HFOV_DEG.toDouble()).toFloat() / 2f)

    /** 走廊在距离 d 处的横向半宽（米）：近端 ±0.35 → 3m 处 ±1.2 线性外推。 */
    fun corridorHalfWidth(distanceM: Float): Float {
        val t = (distanceM / PerceptionConfig.CORRIDOR_FAR_M).coerceIn(0f, 1f)
        return PerceptionConfig.CORRIDOR_NEAR_HALF_M +
                (PerceptionConfig.CORRIDOR_FAR_HALF_M - PerceptionConfig.CORRIDOR_NEAR_HALF_M) * t
    }

    /** 是否在走廊内（走廊外不播报，避免被路侧物体持续打扰）。 */
    fun inCorridor(lateralM: Float, distanceM: Float): Boolean {
        if (distanceM <= 0f || distanceM > PerceptionConfig.CORRIDOR_FAR_M) return false
        return abs(lateralM) <= corridorHalfWidth(distanceM)
    }

    /**
     * 深度图像素 → 相机坐标 3D 点（X右 Y下 Z前，米）。
     * 内参用名义 HFOV 推定（佩戴俯仰角/个体差异留真机标定，见 PerceptionConfig.GROUND_DISTANCE_SCALE）。
     */
    fun depthTo3D(u: Int, v: Int, depthM: Float, imageWidth: Int, imageHeight: Int): FloatArray {
        val fx = focalPx(imageWidth)
        val cx = imageWidth / 2f
        val cy = imageHeight / 2f
        return floatArrayOf((u - cx) * depthM / fx, (v - cy) * depthM / fx, depthM)
    }

    /** 竖直方向角（弧度，向下为正）：像素行 v 相对光心。 */
    fun elevationRad(v: Int, imageHeight: Int): Float {
        val fy = focalPx(imageHeight)
        return atan((v - imageHeight / 2f) / fy)
    }

    /**
     * RANSAC 平面拟合。返回平面 + 内点比例；样本 <3 或退化返回 null。
     * threshold：点到平面的内点距离阈值（米）。
     */
    fun planeFitRansac(
        points: List<FloatArray>,
        iterations: Int = PerceptionConfig.PLANE_RANSAC_ITERS,
        threshold: Float = PerceptionConfig.PLANE_INLIER_THRESH_M,
        seed: Int = 42,
    ): Pair<Plane, Float>? {
        if (points.size < 3) return null
        val rng = java.util.Random(seed.toLong())
        var best: Plane? = null
        var bestInliers = 0

        repeat(iterations) {
            val a = points[rng.nextInt(points.size)]
            val b = points[rng.nextInt(points.size)]
            val c = points[rng.nextInt(points.size)]
            var plane = planeFrom3(a, b, c) ?: return@repeat
            // 法向符号随机：统一 ny ≤ 0（相机 Y 向下，地面法向朝上），保证残差符号约定稳定
            if (plane.ny > 0f) plane = Plane(-plane.nx, -plane.ny, -plane.nz, -plane.d)
            var inliers = 0
            for (p in points) {
                if (abs(plane.signedDistance(p[0], p[1], p[2])) <= threshold) inliers++
            }
            if (inliers > bestInliers) {
                bestInliers = inliers
                best = plane
            }
        }
        val plane = best ?: return null
        return plane to (bestInliers.toFloat() / points.size)
    }

    /** 三点定平面（退化返回 null）。 */
    fun planeFrom3(a: FloatArray, b: FloatArray, c: FloatArray): Plane? {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val norm = sqrt(nx * nx + ny * ny + nz * nz)
        if (norm < 1e-6f) return null
        nx /= norm; ny /= norm; nz /= norm
        return Plane(nx, ny, nz, -(nx * a[0] + ny * a[1] + nz * a[2]))
    }

    /** 方向文案：走廊横向位置 → 左/中/右（与现有播报口径一致）。 */
    fun directionOf(lateralM: Float): String = when {
        lateralM < -0.25f -> "左前方"
        lateralM > 0.25f -> "右前方"
        else -> "前方"
    }
}
