package com.yan.client.vision.perception

/**
 * 地面校正：画面下部 [PerceptionConfig.GROUND_FIT_BAND] 做平面拟合（RANSAC），
 * 同时承担两个职责：
 * 1. 给出台阶/坑洞判定的基准平面（残差符号区分上行/下行）；
 * 2. 米制可信度门禁——拟合失败或相机高度异常时 `metricOk=false`，
 *    播报降级为相对深度 + 固定阈值，只说「前方有东西」不给米数（宁可信息少，不可信息错）。
 *
 * 反光地面/纯色墙面/正对大面玻璃会拟合失败——这是刻意的降级分支，不是 bug。
 */
object GroundCalibrator {

    /**
     * 从深度图取下部条带像素构造 3D 样本点。
     * @param depth 深度图（米），row-major
     */
    fun sampleGroundPoints(depth: DepthMap, stride: Int = PerceptionConfig.GROUND_FIT_STRIDE): List<FloatArray> {
        val bandStart = (depth.height * (1f - PerceptionConfig.GROUND_FIT_BAND)).toInt()
        val points = ArrayList<FloatArray>()
        var v = bandStart
        while (v < depth.height) {
            var u = 0
            while (u < depth.width) {
                val d = depth.at(u, v)
                if (d > 0.2f && d < 30f && d.isFinite()) {
                    points.add(Geometry.depthTo3D(u, v, d, depth.width, depth.height))
                }
                u += stride
            }
            v += stride
        }
        return points
    }

    /**
     * 拟合地面平面。样本退化 / 内点不足 / 法向不像地面 → 返回 null（完全降级为相对深度）。
     *
     * 注意：高度异常**不**在此硬拒绝——平面仍可用于台阶残差判定，
     * 只是 [GroundFit.metricPlausible] 为 false，播报不给米数。
     */
    fun fit(samples: List<FloatArray>): GroundFit? {
        val (plane, ratio) = Geometry.planeFitRansac(samples) ?: return null
        if (ratio < PerceptionConfig.PLANE_MIN_INLIER_RATIO) return null
        // 地面法向应大致朝上（planeFitRansac 已归一化为 ny ≤ 0）；否则拟到的是墙而不是地
        if (plane.ny > -0.5f) return null
        return GroundFit(plane = plane, inlierRatio = ratio, cameraHeightM = plane.estimatedCameraHeight())
    }

    /** 一步到位：深度图 → 地面拟合。 */
    fun fitFromDepth(depth: DepthMap): GroundFit? = fit(sampleGroundPoints(depth))

    /**
     * 米制距离全局校正（真机卷尺标定后调 GROUND_DISTANCE_SCALE）。
     */
    fun correctDistance(rawM: Float): Float = rawM * PerceptionConfig.GROUND_DISTANCE_SCALE

    /**
     * 候选点相对地面平面的有符号残差（米）。
     * 正值 = 点在平面上方（凸起障碍 / 上行台阶方向）；负值 = 低于平面（坑洞 / 下行台阶方向）。
     */
    fun residual(ground: GroundFit, point3D: FloatArray): Float =
        ground.plane.signedDistance(point3D[0], point3D[1], point3D[2])
}
