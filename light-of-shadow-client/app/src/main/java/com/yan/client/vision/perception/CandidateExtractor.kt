package com.yan.client.vision.perception

/**
 * 候选结构化：深度图 → 障碍候选列表（报障的唯一来源）。
 *
 * 有地面平面时走**残差分割**（PDF 语义：台阶是相对地面的残差，不是低深度块）：
 * - 高于平面（正残差）→ 普通障碍 / 上行台阶（宽而平的抬升）
 * - 显著低于平面（负残差）→ 下行台阶 / 坑洞
 * 无地面平面（校正失败）时降级为**相对深度 + 固定阈值**：只报「有东西」，不给米数。
 */
object CandidateExtractor {

    /** 深度图像素 → 原图矩形的缩放映射由调用方给定（深度输入是 256² 拉伸过的）。 */
    fun extract(
        depth: DepthMap,
        ground: GroundFit?,
        origWidth: Int,
        origHeight: Int,
    ): List<ObstacleCandidate> {
        val w = depth.width
        val h = depth.height
        val metricUsable = ground != null && ground.metricPlausible && depth.metricOk

        // 1) 逐像素掩膜：+1=凸起障碍，-1=凹陷（坑/下行），0=地面/无关
        val mask = ByteArray(w * h)
        val residuals = FloatArray(w * h)
        val relThreshold = relativeThreshold(depth)
        for (v in 0 until h) {
            for (u in 0 until w) {
                val d = depth.at(u, v)
                if (d <= 0.2f || d >= 30f || !d.isFinite()) continue
                val xNorm = u / (w - 1f)
                val lateral = Geometry.lateralMeters(xNorm, if (metricUsable) GroundCalibrator.correctDistance(d) else d)
                if (!Geometry.inCorridor(lateral, d)) continue
                val idx = v * w + u
                if (ground != null) {
                    val p = Geometry.depthTo3D(u, v, d, w, h)
                    val r = ground.plane.signedDistance(p[0], p[1], p[2])
                    residuals[idx] = r
                    when {
                        r >= PerceptionConfig.STAIR_RESIDUAL_M -> mask[idx] = 1
                        r <= -PerceptionConfig.STAIR_RESIDUAL_M -> mask[idx] = -1
                    }
                } else {
                    // 校正失败降级：相对深度近距块视为障碍（不给米数）
                    if (d <= relThreshold) mask[idx] = 1
                }
            }
        }

        // 2) 连通域（4 邻域 BFS，正负掩膜分开聚类）
        val components = connectedComponents(mask, w, h)

        // 3) 逐组件出候选
        val scaleX = origWidth / w.toFloat()
        val scaleY = origHeight / h.toFloat()
        return components.mapNotNull { comp ->
            if (comp.pixels.size < PerceptionConfig.CANDIDATE_MIN_AREA_PX) return@mapNotNull null
            buildCandidate(comp, depth, ground, residuals, mask, metricUsable, scaleX, scaleY, w)
        }
    }

    // ------------------------------------------------------------------

    private class Component(var minX: Int, var maxX: Int, var minY: Int, var maxY: Int) {
        val pixels = ArrayList<Int>()
    }

    private fun connectedComponents(mask: ByteArray, w: Int, h: Int): List<Component> {
        val visited = BooleanArray(w * h)
        val out = ArrayList<Component>()
        val queue = ArrayDeque<Int>()
        for (start in mask.indices) {
            if (mask[start] == 0.toByte() || visited[start]) continue
            val sign = mask[start]
            queue.add(start)
            visited[start] = true
            val sx = start % w
            val sy = start / w
            val comp = Component(sx, sx, sy, sy)
            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                comp.pixels.add(idx)
                val x = idx % w
                val y = idx / w
                if (x < comp.minX) comp.minX = x
                if (x > comp.maxX) comp.maxX = x
                if (y < comp.minY) comp.minY = y
                if (y > comp.maxY) comp.maxY = y
                // 4 邻域，同号才合并
                if (x > 0 && !visited[idx - 1] && mask[idx - 1] == sign) { visited[idx - 1] = true; queue.add(idx - 1) }
                if (x < w - 1 && !visited[idx + 1] && mask[idx + 1] == sign) { visited[idx + 1] = true; queue.add(idx + 1) }
                if (y > 0 && !visited[idx - w] && mask[idx - w] == sign) { visited[idx - w] = true; queue.add(idx - w) }
                if (y < h - 1 && !visited[idx + w] && mask[idx + w] == sign) { visited[idx + w] = true; queue.add(idx + w) }
            }
            out.add(comp)
        }
        return out
    }

    private fun buildCandidate(
        comp: Component,
        depth: DepthMap,
        ground: GroundFit?,
        residuals: FloatArray,
        mask: ByteArray,
        metricUsable: Boolean,
        scaleX: Float,
        scaleY: Float,
        w: Int,
    ): ObstacleCandidate {
        // 距离 = 组件内深度 10 分位（用户撞的是最近边缘）
        val depths = comp.pixels.map { depth.values[it] }.sorted()
        val p10 = depths[((depths.size - 1) * PerceptionConfig.DISTANCE_PERCENTILE).toInt()]
        val distanceRel = p10
        val distanceM = if (metricUsable) GroundCalibrator.correctDistance(p10) else null

        val centerXNorm = (comp.minX + comp.maxX) / 2f / (w - 1f)
        val lateral = Geometry.lateralMeters(centerXNorm, if (metricUsable) GroundCalibrator.correctDistance(p10) else p10)

        // 风险：凹陷组件按残差深度分坑洞/下行台阶；凸起组件按横向跨度分上行台阶/普通障碍（v1 启发式，PR5 真机调参）
        val sign = mask[comp.pixels[0]]
        val widthM = (comp.maxX - comp.minX + 1) * p10 / Geometry.focalPx(w)
        val risk = when {
            ground != null && sign < 0 -> {
                val minResidual = comp.pixels.minOf { residuals[it] }
                if (minResidual <= -PerceptionConfig.PIT_RESIDUAL_M) RiskLevel.PIT else RiskLevel.DOWN_STAIR
            }
            ground != null && widthM >= PerceptionConfig.UP_STAIR_MIN_WIDTH_M -> RiskLevel.UP_STAIR
            else -> RiskLevel.OBSTACLE
        }

        val direction = Geometry.directionOf(lateral)
        return ObstacleCandidate(
            bbox = FBox(comp.minX * scaleX, comp.minY * scaleY, (comp.maxX + 1) * scaleX, (comp.maxY + 1) * scaleY),
            distanceM = distanceM,
            distanceRel = distanceRel,
            lateralM = lateral,
            risk = risk,
            label = null,
            direction = direction,
        )
    }

    /** 相对深度模式的近距阈值：帧内深度 30 分位的 1.2 倍（无地面时的降级分割）。 */
    private fun relativeThreshold(depth: DepthMap): Float {
        val valid = depth.values.filter { it > 0.2f && it < 30f && it.isFinite() }.sorted()
        if (valid.isEmpty()) return 0f
        val p30 = valid[((valid.size - 1) * 0.30f).toInt()]
        return p30 * 1.2f
    }
}
