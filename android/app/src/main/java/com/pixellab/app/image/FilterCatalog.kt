package com.pixellab.app.image

/**
 * 滤镜目录（Filter Catalog）
 *
 * 编辑页芯片条与"算法对比模式"共用的单一数据源：
 *   - chips：完整滤镜清单（卷积预设 → Convolver 专门实现 → A 组高级算法）；
 *   - displayName：key → 中文名（芯片、耗时徽章、历史记录、PDF 报告统一用它）。
 *
 * 新增滤镜只需要在这里加一行 + 在 ProcessEngine.applyFilter 的 when 里接一路分支。
 */
object FilterCatalog {

    /** 芯片条数据项（沿用 UI 层的 FilterChip 结构，这里放 key + 中文名） */
    data class Item(val key: String, val label: String)

    /** 完整滤镜清单（顺序即芯片条顺序） */
    val chips: List<Item> = listOf(
        // —— 经典卷积预设（FilterPreset 枚举）——
        Item("SHARPEN", "锐化"),
        Item("MEAN_BLUR", "均值模糊"),
        Item("GAUSSIAN", "高斯模糊"),
        Item("MEDIAN", "中值滤波"),
        Item("EDGE", "边缘检测"),
        Item("SOBEL_V", "垂直边缘"),
        Item("EMBOSS", "浮雕"),
        // —— 基础点操作 ——
        Item("GRAYSCALE", "灰度"),
        Item("EQUALIZE", "直方图均衡"),
        Item("OTSU", "自动二值化"),
        // —— A 组硬核算法 ——
        Item("CANNY", "Canny 边缘"),
        Item("BILATERAL", "双边滤波"),
        Item("CLAHE", "CLAHE 增强"),
        Item("OTSU_OPEN", "二值开运算"),
        Item("OTSU_CLOSE", "二值闭运算"),
        Item("USM", "USM 锐化"),
        Item("HUE_ROT", "色相旋转"),
        Item("SAT_BOOST", "饱和增强"),
        Item("CHROMA_SHIFT", "色度偏移"),
        Item("DCT", "DCT 压缩"),
        // —— 插值对比（输出尺寸变为 2×）——
        Item("NEAREST2X", "最近邻放大"),
        Item("BILINEAR2X", "双线性放大"),
        Item("BICUBIC2X", "双三次放大"),
        // —— 自定义卷积核（点击弹出 9 宫格输入对话框）——
        Item("CUSTOM", "自定义核")
    )

    /** key → 中文名；卷积预设走枚举 displayName，其余走本表 */
    fun displayName(key: String?): String {
        if (key == null) return "--"
        chips.firstOrNull { it.key == key }?.let { return it.label }
        return FilterPreset.fromName(key).displayName
    }

    /** 算法横向对比模式的参与者（同一张图跑四个滤波，看效果和耗时差异） */
    val compareKeys: List<String> = listOf("MEAN_BLUR", "GAUSSIAN", "MEDIAN", "BILATERAL")
}
