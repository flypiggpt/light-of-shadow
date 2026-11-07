package com.yan.client.vision.perception

/**
 * 端侧感知管线参数单点集中（取值来自《端侧障碍感知管线技术设想》，真机调参只改这里）。
 *
 * 每个取舍对应一个具体失败场景，改值前先看注释。
 */
object PerceptionConfig {

    // ---- 频率与调度 ----
    /** 深度帧门控：每 N 帧推理一次（≈10Hz） */
    const val DEPTH_FRAME_DIV = 3
    /** 标注触发防抖：同一波候选只触发一次全帧 YOLO */
    const val LABEL_DEBOUNCE_MS = 250L
    /** 标注触发上限频率（防连续发现时压垮帧率） */
    const val LABEL_MIN_INTERVAL_MS = 250L
    /** 标注结果缓存有效期；候选匹配取缓存内最新帧 */
    const val LABEL_CACHE_TTL_MS = 500L

    // ---- 深度 ----
    const val DEPTH_INPUT_SIZE = 256
    /** 距离取候选区深度的 10 分位：中位数代表物体中心，用户撞上的是最近边缘 */
    const val DISTANCE_PERCENTILE = 0.10

    // ---- 走廊（梯形掩膜，走廊外不播报）----
    /** 近端横向半宽（米） */
    const val CORRIDOR_NEAR_HALF_M = 0.35f
    /** 3 米处横向半宽（米） */
    const val CORRIDOR_FAR_HALF_M = 1.2f
    /** 走廊远端截断（米） */
    const val CORRIDOR_FAR_M = 3.0f
    /** 名义水平视场（度），用于像素↔米制换算 */
    const val HFOV_DEG = 65f

    // ---- 匹配（候选 ↔ YOLO 标签）----
    /** 包围框重叠度下限 */
    const val MATCH_MIN_IOU = 0.3f
    /** 深度一致性上限（米）：只靠重叠度会把远处同名物体错配到近处候选上 */
    const val MATCH_DEPTH_TOL_M = 0.5f

    // ---- 播报 ----
    /** 新目标播报前等标签的窗口；超时按「障碍物」一次性播，不二次升级补播 */
    const val LABEL_WAIT_MS = 250L
    /** 触发播报的最小距离变化（米） */
    const val TRIGGER_DELTA_M = 0.5f
    /** 两次播报最小间隔（毫秒），否则每帧都在读，用户无法分辨 */
    const val ANNOUNCE_MIN_INTERVAL_MS = 1500L
    /** 每轮最多播报条数 */
    const val ANNOUNCE_MAX_BATCH = 2

    // ---- 地面校正与台阶 ----
    /** 平面拟合取画面下部比例 */
    const val GROUND_FIT_BAND = 0.40f
    /** 平面拟合采样步长（像素） */
    const val GROUND_FIT_STRIDE = 4
    /** RANSAC 内点阈值（米，点到平面距离） */
    const val PLANE_INLIER_THRESH_M = 0.06f
    /** RANSAC 迭代次数 */
    const val PLANE_RANSAC_ITERS = 60
    /** 拟合成功要求的最低内点比例 */
    const val PLANE_MIN_INLIER_RATIO = 0.45f
    /** 推定相机高度合理区间（米）：超出说明米制尺度不可信，降级为相对深度 */
    const val CAMERA_HEIGHT_MIN_M = 0.7f
    const val CAMERA_HEIGHT_MAX_M = 2.3f
    /** 台阶/坑洞残差阈值（米）：|残差| 超过它才算显著 */
    const val STAIR_RESIDUAL_M = 0.15f
    /** 判定坑洞（深坑）的残差阈值（米） */
    const val PIT_RESIDUAL_M = 0.30f
    /** 候选连通域最小面积（深度图像素²）：滤除零星噪点 */
    const val CANDIDATE_MIN_AREA_PX = 48
    /** 判定「上行台阶」的最小横向跨度（米）：宽而平的抬升才算台阶，窄的是普通障碍 */
    const val UP_STAIR_MIN_WIDTH_M = 0.4f
    /** 米制距离全局校正系数（真机用卷尺标定后改这里，PR5 调参项） */
    const val GROUND_DISTANCE_SCALE = 1.0f

    // ---- 轨迹平滑 ----
    /** 距离 EMA 系数（一般障碍） */
    const val SMOOTH_ALPHA = 0.4f
    /** 距离 EMA 系数（行人/车辆等动态目标，读数抖动明显） */
    const val SMOOTH_ALPHA_DYNAMIC = 0.3f
    /** 迟滞：风险升级需要的最小距离变化（米） */
    const val HYSTERESIS_UP_M = 0.4f
    /** 迟滞：风险降级需要的最小距离变化（米），防播报在相邻两帧间来回跳变 */
    const val HYSTERESIS_DOWN_M = 0.7f
}
