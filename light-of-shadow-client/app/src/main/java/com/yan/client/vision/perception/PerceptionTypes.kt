package com.yan.client.vision.perception

/**
 * 感知管线共享数据类型（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 * 图像坐标一律用「旋转后原图坐标」；FBox 与 android.graphics.RectF 在边界处互转。
 */

/** 像素矩形（浮点，原图坐标）。 */
data class FBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = (width).coerceAtLeast(0f) * (height).coerceAtLeast(0f)

    fun intersection(other: FBox): FBox {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return FBox(l, t, r, b)
    }
}

/** 单位法向平面：n·p + d = 0（相机坐标 X右 Y下 Z前）。 */
data class Plane(val nx: Float, val ny: Float, val nz: Float, val d: Float) {

    /** 带符号点到平面距离（米）：正值=点在平面上方（障碍凸起/上行台阶方向）。 */
    fun signedDistance(x: Float, y: Float, z: Float): Float = nx * x + ny * y + nz * z + d

    /** 由平面几何推定的相机离地高度（米）：|d| / |ny|（平面法向 Y 分量≈重力对齐程度）。 */
    fun estimatedCameraHeight(): Float {
        val nyAbs = kotlin.math.abs(ny)
        return if (nyAbs < 1e-3f) Float.MAX_VALUE else kotlin.math.abs(d) / nyAbs
    }
}

/** 平面拟合结果。 */
data class GroundFit(
    val plane: Plane,
    val inlierRatio: Float,
    /** 推定相机离地高度（米），用于米制可信度门禁 */
    val cameraHeightM: Float,
) {
    /** 米制是否可信：内点足够 + 相机高度在合理区间，否则播报不给米数 */
    val metricPlausible: Boolean
        get() = inlierRatio >= PerceptionConfig.PLANE_MIN_INLIER_RATIO &&
                cameraHeightM in PerceptionConfig.CAMERA_HEIGHT_MIN_M..PerceptionConfig.CAMERA_HEIGHT_MAX_M
}

/** 深度图（row-major，单位米；rel 为相对深度兜底）。 */
data class DepthMap(
    val width: Int,
    val height: Int,
    val values: FloatArray,
    /** 本次地面校正/米制门禁是否通过；false 时只许报相对距离，不给米数 */
    val metricOk: Boolean,
) {
    fun at(x: Int, y: Int): Float = values[y * width + x]
}

/** 风险等级（台阶优先级高于普通障碍，最易造成摔倒）。 */
enum class RiskLevel { OBSTACLE, UP_STAIR, DOWN_STAIR, PIT }

/** 深度侧发现的障碍候选（报障唯一来源）。 */
data class ObstacleCandidate(
    val bbox: FBox,
    /** 候选区深度 10 分位（米）；metricOk=false 时为 null */
    val distanceM: Float?,
    /** 相对深度信号（米制失败时的兜底播报依据） */
    val distanceRel: Float,
    /** 走廊坐标系横向位置（米，负左正右） */
    val lateralM: Float,
    val risk: RiskLevel,
    /** YOLO 触发式标注挂上的名字；null=待标注或标注失败 → 播报「障碍物」 */
    val label: String?,
    val direction: String,
)
