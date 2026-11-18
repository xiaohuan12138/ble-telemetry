package com.pixellab.app.image

/**
 * 滤镜预设核（Filter Presets）
 *
 * 每个预设 = 一个卷积核 + 一句"信号处理视角"的说明。
 * 核权重的设计逻辑都写在注释里，答辩时能逐个讲清楚"为什么这么设"。
 */
enum class FilterPreset(
    val displayName: String,
    val kernel: FloatArray,
    val description: String
) {
    /**
     * 均值模糊：核内 9 个权重相等、总和为 1。
     * 频域视角：相当于低通滤波，衰减高频（细节/噪声），图像变"糊"。
     */
    MEAN_BLUR(
        "均值模糊",
        floatArrayOf(
            1f / 9, 1f / 9, 1f / 9,
            1f / 9, 1f / 9, 1f / 9,
            1f / 9, 1f / 9, 1f / 9
        ),
        "3×3 低通滤波，均匀加权平均"
    ),

    /**
     * 锐化：中心 5、邻居全 -1。恒等核（中心 1）减去均值核（低通）
     * = 高通成分增强，边缘对比被放大，视觉上"更清晰"。
     * 这是典型的"原图 + 高频增强"锐化（unsharp masking 的简化版）。
     */
    SHARPEN(
        "锐化",
        floatArrayOf(
            0f, -1f, 0f,
            -1f, 5f, -1f,
            0f, -1f, 0f
        ),
        "恒等核 - 低通核，高频增强"
    ),

    /**
     * 边缘检测（Laplacian 近似）：中心 -8、邻居全 1，核内总和为 0。
     * 总和为 0 意味着平坦区域输出≈0（黑），只有梯度大的地方（边缘）非零——
     * 这就是"求导"：图像强度函数的二阶差分近似。
     */
    EDGE(
        "边缘检测",
        floatArrayOf(
            -1f, -1f, -1f,
            -1f, 8f, -1f,
            -1f, -1f, -1f
        ),
        "拉普拉斯算子，核总和为 0，只留高频"
    ),

    /**
     * 浮雕：对角方向加权（左上 -2 → 右下 +2），模拟侧光照射的雕刻感。
     * 本质是带方向的一阶差分（梯度算子），加 128 偏移让平坦区呈中性灰。
     */
    EMBOSS(
        "浮雕",
        floatArrayOf(
            -2f, -1f, 0f,
            -1f, 1f, 1f,
            0f, 1f, 2f
        ),
        "方向梯度 + 128 灰度偏移"
    ),

    /**
     * Sobel 垂直边缘：检测竖直方向的强度突变（图像里的竖直轮廓线）。
     * 上行 -1、下行 +1 = 垂直方向的一阶差分，水平方向做了 3 点平滑。
     * "先平滑再差分"正是 Sobel 算子的设计精髓：抗噪 + 求导。
     */
    SOBEL_V(
        "垂直边缘",
        floatArrayOf(
            -1f, 0f, 1f,
            -2f, 0f, 2f,
            -1f, 0f, 1f
        ),
        "Sobel X 算子，抗噪一阶差分"
    );

    companion object {
        /** 按 name 查找，找不到回退到锐化（避免 UI 传进非法值崩溃） */
        fun fromName(name: String?): FilterPreset =
            entries.firstOrNull { it.name == name } ?: SHARPEN
    }
}
