package com.script.js.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.script.js.core.ocr.OcrApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 图像能力：模板匹配/找色（截屏取帧见 [ScreenCapture]）。
 *
 * 匹配算法：灰度化 + 归一化互相关（NCC），由粗到细两级搜索：
 * 1. 粗搜：屏幕与模板按整数倍降采样（模板缩到约 8px），在区域内滑动打分取候选；
 * 2. 精搜：对候选点在原分辨率邻域内精确定位，再按阈值过滤、相邻命中聚合。
 * 纯 Kotlin 实现，避免引入 OpenCV（AAR 117MB，APK 会涨到 80MB+）；后续需要多尺度/复杂找色时可替换实现。
 * 对应脚本 API 见 docs/API_REFERENCE.md 第 3 节。
 */
object ImageApi {

    data class Point(val x: Int, val y: Int)

    /** 调试日志钩子（引擎运行期注入，便于排查匹配问题） */
    @Volatile
    var debugLogger: ((String) -> Unit)? = null

    // —— JS 侧 Image 句柄：自增 id → Bitmap，避免把 Bitmap 暴露给 JS 引擎 ——
    private val images = ConcurrentHashMap<Int, Bitmap>()
    private val nextId = AtomicInteger(1)

    fun storeImage(bitmap: Bitmap): Int = nextId.getAndIncrement().also { images[it] = bitmap }

    fun image(id: Int?): Bitmap? = id?.let { images[it] }

    fun releaseImage(id: Int) {
        images.remove(id)
    }

    fun clearImages() {
        images.clear()
    }

    // ==================== 文件读写 ====================

    /** 读图（相对路径基于脚本目录解析） */
    fun read(path: String, scriptDir: File): Int? {
        val f = resolve(path, scriptDir) ?: return null
        if (!f.isFile) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        return storeImage(bmp)
    }

    /** 保存图片；format 支持 png/jpg（默认按扩展名，quality 默认 100） */
    fun save(
        imageId: Int,
        path: String,
        scriptDir: File,
        format: String? = null,
        quality: Int = 100
    ): Boolean {
        val bmp = image(imageId) ?: return false
        val f = resolve(path, scriptDir) ?: return false
        f.parentFile?.mkdirs()
        val fmt = format?.lowercase()
            ?: if (f.extension.equals("jpg", true) || f.extension.equals("jpeg", true)) "jpg" else "png"
        return try {
            FileOutputStream(f).use { out ->
                val compress = if (fmt == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                bmp.compress(compress, quality.coerceIn(1, 100), out)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 裁剪，返回新的 Image 句柄 */
    fun clip(imageId: Int, x: Int, y: Int, w: Int, h: Int): Int? {
        val bmp = image(imageId) ?: return null
        val cx = x.coerceIn(0, max(0, bmp.width - 1))
        val cy = y.coerceIn(0, max(0, bmp.height - 1))
        val cw = w.coerceIn(1, bmp.width - cx)
        val ch = h.coerceIn(1, bmp.height - cy)
        return try {
            storeImage(Bitmap.createBitmap(bmp, cx, cy, cw, ch))
        } catch (e: Exception) {
            null
        }
    }

    /** 取像素色值（ARGB int），越界返回 null */
    fun pixel(imageId: Int, x: Int, y: Int): Int? {
        val bmp = image(imageId) ?: return null
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return null
        return bmp.getPixel(x, y)
    }

    fun width(imageId: Int): Int = image(imageId)?.width ?: 0

    fun height(imageId: Int): Int = image(imageId)?.height ?: 0

    // ==================== 找色 ====================

    /**
     * 区域内找色：逐像素比较，各通道色差 ≤ threshold 视为命中，返回首个命中点。
     * region 为 [x, y, w, h]（w/h ≤ 0 表示延伸到边界）。
     */
    fun findColor(
        imageId: Int,
        color: Int,
        threshold: Int = 16,
        region: IntArray? = null
    ): Point? {
        val bmp = image(imageId) ?: return null
        val r = clampRegion(region, bmp.width, bmp.height) ?: return null
        val tr = (color shr 16) and 0xFF
        val tg = (color shr 8) and 0xFF
        val tb = color and 0xFF
        val row = IntArray(bmp.width)
        for (y in r.top until r.bottom) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (x in r.left until r.right) {
                val c = row[x]
                if (abs(((c shr 16) and 0xFF) - tr) <= threshold &&
                    abs(((c shr 8) and 0xFF) - tg) <= threshold &&
                    abs((c and 0xFF) - tb) <= threshold
                ) {
                    return Point(x, y)
                }
            }
        }
        return null
    }

    /** 多点找色：先命中首色，再逐点校验相对偏移颜色（找图的轻量替代） */
    fun findMultiColors(
        imageId: Int,
        firstColor: Int,
        offsets: List<IntArray>,
        threshold: Int = 16,
        region: IntArray? = null
    ): Point? {
        val bmp = image(imageId) ?: return null
        val r = clampRegion(region, bmp.width, bmp.height) ?: return null
        val row = IntArray(bmp.width)
        fun matchAt(x: Int, y: Int, color: Int): Boolean {
            if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return false
            val c = bmp.getPixel(x, y)
            return abs(((c shr 16) and 0xFF) - ((color shr 16) and 0xFF)) <= threshold &&
                    abs(((c shr 8) and 0xFF) - ((color shr 8) and 0xFF)) <= threshold &&
                    abs((c and 0xFF) - (color and 0xFF)) <= threshold
        }
        val fr = (firstColor shr 16) and 0xFF
        val fg = (firstColor shr 8) and 0xFF
        val fb = firstColor and 0xFF
        for (y in r.top until r.bottom) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            for (x in r.left until r.right) {
                val c = row[x]
                if (abs(((c shr 16) and 0xFF) - fr) > threshold ||
                    abs(((c shr 8) and 0xFF) - fg) > threshold ||
                    abs((c and 0xFF) - fb) > threshold
                ) continue
                // 首色命中，校验各偏移点
                if (offsets.all { it.size >= 3 && matchAt(x + it[0], y + it[1], it[2]) }) {
                    return Point(x, y)
                }
            }
        }
        return null
    }

    // ==================== 找图（模板匹配） ====================

    /** 命中结果：x/y 为模板中心坐标，score 为相似度 */
    data class Match(val x: Int, val y: Int, val score: Double)

    /** 找图：返回匹配中心点 */
    fun findImage(
        imageId: Int,
        tplId: Int,
        threshold: Double = 0.9,
        region: IntArray? = null
    ): Point? = findMatches(imageId, tplId, threshold, region, 1).firstOrNull()?.let { Point(it.x, it.y) }

    /** 全部匹配位置（相邻命中已聚合），按从左到右、从上到下排序 */
    fun findAllImages(
        imageId: Int,
        tplId: Int,
        threshold: Double = 0.9,
        region: IntArray? = null,
        max: Int = 5
    ): List<Point> = findMatches(imageId, tplId, threshold, region, max).map { Point(it.x, it.y) }

    /** 模板是否出现在图中 */
    fun existsImage(
        imageId: Int,
        tplId: Int,
        threshold: Double = 0.9,
        region: IntArray? = null
    ): Boolean = findMatches(imageId, tplId, threshold, region, 1).isNotEmpty()

    /** 图文联合匹配结果：匹配中心 + 匹配矩形 + 图内识别文字与逐条文本 */
    data class MatchWithText(
        val x: Int,
        val y: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val text: String,
        val items: List<OcrApi.Item>
    )

    /**
     * 找图并对匹配到的区域做 OCR：适合「卡片样式固定 + 图内文字变化」的场景，
     * 例如匹配商品卡片后读取其中的价格文字。
     * @param padding OCR 区域向四周扩展的像素（默认 0），避免边缘文字被裁
     */
    fun findImageWithText(
        imageId: Int,
        tplId: Int,
        threshold: Double = 0.9,
        region: IntArray? = null,
        padding: Int = 0
    ): MatchWithText? {
        val screen = image(imageId) ?: return null
        val tpl = image(tplId) ?: return null
        val best = findMatches(imageId, tplId, threshold, region, 1).firstOrNull() ?: return null

        // 匹配矩形 = 中心 ± 模板一半，再按 padding 外扩并裁剪到图片范围
        val pad = padding.coerceIn(0, 200)
        val left = (best.x - tpl.width / 2 - pad).coerceAtLeast(0)
        val top = (best.y - tpl.height / 2 - pad).coerceAtLeast(0)
        val right = (best.x + tpl.width / 2 + pad).coerceAtMost(screen.width)
        val bottom = (best.y + tpl.height / 2 + pad).coerceAtMost(screen.height)
        if (right - left <= 0 || bottom - top <= 0) return null

        val items = OcrApi.recognize(screen, intArrayOf(left, top, right - left, bottom - top))
        return MatchWithText(
            x = best.x,
            y = best.y,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            text = items.joinToString("\n") { it.text },
            items = items
        )
    }

    /**
     * 由粗到细模板匹配，返回按位置排序的命中（坐标为模板中心）。
     * region 为 [x, y, w, h]；w/h ≤ 0 表示延伸到图像边界。
     */
    fun findMatches(
        imageId: Int,
        tplId: Int,
        threshold: Double,
        region: IntArray?,
        max: Int
    ): List<Match> {
        val screen = image(imageId) ?: return emptyList()
        val tpl = image(tplId) ?: return emptyList()
        val r = clampRegion(region, screen.width, screen.height) ?: return emptyList()
        val tw = tpl.width
        val th = tpl.height
        if (tw <= 0 || th <= 0 || tw > r.width() || th > r.height()) return emptyList()

        val grayScreen = screen.toGray()
        val grayTpl = tpl.toGray()
        val tplStats = grayTpl.stats()

        // 粗搜倍数：让粗图模板边长约 12px，兼顾速度与特征保留
        val scale = max(1, min(tw, th) / 12)
        val cs = grayScreen.downsample(scale)
        val ct = grayTpl.downsample(scale)
        // 粗图坐标按 scale 换算并裁剪到粗图边界（向上取整可能越界）
        val coarseRegion = Rect(
            (r.left / scale).coerceIn(0, cs.w),
            (r.top / scale).coerceIn(0, cs.h),
            ((r.right + scale - 1) / scale).coerceIn(0, cs.w),
            ((r.bottom + scale - 1) / scale).coerceIn(0, cs.h)
        )
        // 粗筛用平均绝对差而非 NCC：降采样会抹平细笔画特征，NCC 在此阶段会把
        // 大片纯色区域误判为高分（实测真实位置排名可掉到数万名之后），MAD 则稳定居首。
        val coarse = scanCoarseByMad(cs, ct, coarseRegion, limit = max(16, max * 8))
        val candidates = refine(grayScreen, grayTpl, tplStats, coarse, scale, r)
        debugLogger?.invoke(
            "match tpl=${tw}x$th scale=$scale thr=$threshold coarse=${coarse.size} " +
                    "best=" + (candidates.maxByOrNull { it.score }
                ?.let { "(${it.x},${it.y})=${"%.3f".format(it.score)}" } ?: "-")
        )

        return candidates
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .let { aggregate(it, max(4, min(tw, th) / 2)) }
            .let { hits ->
                // max=1 语义为“最佳匹配”，按分数取；多结果按从左到右、从上到下排序
                if (max == 1) hits.sortedByDescending { it.score }.take(1)
                else hits.sortedWith(compareBy({ it.y }, { it.x })).take(max)
            }
    }

    /**
     * 粗筛：降采样图上按平均绝对差相似度取候选，并按邻近去重。
     * 不做阈值过滤——粗图分数区分度有限，最终由全分辨率 NCC 判定。
     */
    private fun scanCoarseByMad(
        scr: Gray,
        tpl: Gray,
        region: Rect,
        limit: Int
    ): List<Match> {
        val maxY = min(region.bottom, scr.h) - tpl.h
        val maxX = min(region.right, scr.w) - tpl.w
        if (maxY < region.top || maxX < region.left) return emptyList()
        val out = ArrayList<Match>()
        for (y in region.top..maxY) {
            for (x in region.left..maxX) {
                out.add(Match(x + tpl.w / 2, y + tpl.h / 2, scr.madSim(x, y, tpl)))
            }
        }
        // 分数降序，邻近候选（同一目标）只保留分数最高者
        val sorted = out.sortedByDescending { it.score }
        val kept = ArrayList<Match>(limit)
        val minDist = max(2, min(tpl.w, tpl.h) / 2)
        for (m in sorted) {
            if (kept.size >= limit) break
            if (kept.none { abs(it.x - m.x) < minDist && abs(it.y - m.y) < minDist }) kept.add(m)
        }
        return kept
    }

    /** 精搜：在粗搜候选附近以原分辨率重新定位 */
    private fun refine(
        scr: Gray,
        tpl: Gray,
        tplStats: Gray.S,
        coarse: List<Match>,
        scale: Int,
        region: Rect
    ): List<Match> {
        val out = ArrayList<Match>(coarse.size)
        val radius = scale * 2
        for (c in coarse) {
            // 粗图坐标 → 原图坐标（粗搜中心乘 scale）
            val baseX = c.x * scale - tpl.w / 2
            val baseY = c.y * scale - tpl.h / 2
            // 有效区间：候选邻域与可放置区间的交集
            val minX = max(region.left, baseX - radius)
            val maxX = min(region.right - tpl.w, baseX + radius)
            val minY = max(region.top, baseY - radius)
            val maxY = min(region.bottom - tpl.h, baseY + radius)
            if (maxX < minX || maxY < minY) continue

            // 第一步：粗步长定位
            var bestX = minX
            var bestY = minY
            var bestScore = -2.0
            var y = minY
            while (y <= maxY) {
                var x = minX
                while (x <= maxX) {
                    val s = scr.ncc(x, y, tpl, tplStats)
                    if (s > bestScore) {
                        bestScore = s
                        bestX = x
                        bestY = y
                    }
                    x += scale
                }
                y += scale
            }
            // 第二步：±scale 邻域逐像素精修
            var fineX = bestX
            var fineY = bestY
            var fineScore = bestScore
            for (fy in max(minY, bestY - scale)..min(maxY, bestY + scale)) {
                for (fx in max(minX, bestX - scale)..min(maxX, bestX + scale)) {
                    val s = scr.ncc(fx, fy, tpl, tplStats)
                    if (s > fineScore) {
                        fineScore = s
                        fineX = fx
                        fineY = fy
                    }
                }
            }
            out.add(Match(fineX + tpl.w / 2, fineY + tpl.h / 2, fineScore))
        }
        return out
    }

    private fun valid(x: Int, y: Int, tpl: Gray, region: Rect): Boolean =
        x >= region.left && y >= region.top &&
                x + tpl.w <= region.right && y + tpl.h <= region.bottom

    /** 相邻命中聚合：距离小于 minDist 的只保留分数最高者 */
    private fun aggregate(sorted: List<Match>, minDist: Int): List<Match> {
        val kept = ArrayList<Match>()
        for (m in sorted) {
            if (kept.none { abs(it.x - m.x) < minDist && abs(it.y - m.y) < minDist }) kept.add(m)
        }
        return kept
    }

    // ==================== 内部工具 ====================

    private fun resolve(path: String, scriptDir: File): File? {
        if (path.isBlank()) return null
        val f = File(path)
        return if (f.isAbsolute) f else File(scriptDir, path)
    }

    /** 灰度方差下限：低于此值视为近似平坦，NCC 改用平均绝对差 */
    private const val MIN_VARIANCE = 1.0

    /** region 为 [x, y, w, h]；非法/越界自动裁剪 */
    private fun clampRegion(region: IntArray?, w: Int, h: Int): Rect? {
        if (region == null || region.size < 2) return Rect(0, 0, w, h)
        val x = region[0].coerceIn(0, w - 1)
        val y = region[1].coerceIn(0, h - 1)
        val rw = if (region.size >= 3 && region[2] > 0) region[2] else w - x
        val rh = if (region.size >= 4 && region[3] > 0) region[3] else h - y
        val right = (x + rw).coerceAtMost(w)
        val bottom = (y + rh).coerceAtMost(h)
        if (right - x <= 0 || bottom - y <= 0) return null
        return Rect(x, y, right, bottom)
    }

    /** 灰度图（亮度 = (77R + 150G + 29B) >> 8） */
    private class Gray(val w: Int, val h: Int, val px: IntArray) {

        /** 模板统计量：均值 + 去均值平方和 */
        data class S(val mean: Double, val varSum: Double)

        private var cached: S? = null

        fun stats(): S = cached ?: run {
            var sum = 0L
            for (v in px) sum += v
            val mean = sum.toDouble() / px.size
            var varSum = 0.0
            for (v in px) {
                val d = v - mean
                varSum += d * d
            }
            S(mean, varSum).also { cached = it }
        }

        /**
         * 归一化互相关，值域 [-1, 1]。
         * 模板或窗口近似平坦（方差过小）时 NCC 数值不稳定、分数会失真，
         * 此时退化为平均绝对差相似度，避免大面积为纯色背景的模板漏检。
         */
        fun ncc(x0: Int, y0: Int, tpl: Gray, ts: S): Double {
            val n = (tpl.w * tpl.h).toDouble()
            var sumI = 0.0
            var sumI2 = 0.0
            var dot = 0.0
            for (ty in 0 until tpl.h) {
                var si = (y0 + ty) * w + x0
                var ti = ty * tpl.w
                for (tx in 0 until tpl.w) {
                    val i = px[si++].toDouble()
                    val t = tpl.px[ti++].toDouble()
                    sumI += i
                    sumI2 += i * i
                    dot += i * t
                }
            }
            val meanI = sumI / n
            val varI = sumI2 - sumI * meanI
            // 模板近似平坦：退化为平均绝对差；窗口平坦但模板有结构：判为不相似
            if (ts.varSum < MIN_VARIANCE) return mad(x0, y0, tpl, n)
            if (varI < MIN_VARIANCE) return 0.0
            val den = sqrt(varI * ts.varSum)
            return ((dot - sumI * ts.mean) / den).coerceIn(-1.0, 1.0)
        }

        /** 平均绝对差相似度：1 - 平均灰度差/255，值域 [0,1]；不依赖方差，适合粗筛 */
        fun madSim(x0: Int, y0: Int, tpl: Gray): Double {
            val n = (tpl.w * tpl.h).toDouble()
            var diff = 0.0
            for (ty in 0 until tpl.h) {
                var si = (y0 + ty) * w + x0
                var ti = ty * tpl.w
                for (tx in 0 until tpl.w) diff += abs(px[si++] - tpl.px[ti++])
            }
            return (1.0 - (diff / n) / 255.0).coerceIn(0.0, 1.0)
        }

        /** 平均绝对差相似度（模板与窗口均近似平坦时的兜底） */
        private fun mad(x0: Int, y0: Int, tpl: Gray, n: Double): Double =
            madSim(x0, y0, tpl)

        /** 整数倍盒式降采样 */
        fun downsample(scale: Int): Gray {
            val nw = max(1, w / scale)
            val nh = max(1, h / scale)
            val out = IntArray(nw * nh)
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    var sum = 0
                    var cnt = 0
                    for (dy in 0 until scale) {
                        val sy = y * scale + dy
                        if (sy >= h) break
                        val rowOff = sy * w
                        for (dx in 0 until scale) {
                            val sx = x * scale + dx
                            if (sx >= w) break
                            sum += px[rowOff + sx]
                            cnt++
                        }
                    }
                    out[y * nw + x] = if (cnt > 0) sum / cnt else 0
                }
            }
            return Gray(nw, nh, out)
        }
    }



    private fun Bitmap.toGray(): Gray {
        val w = width
        val h = height
        val buf = IntArray(w * h)
        getPixels(buf, 0, w, 0, 0, w, h)
        for (i in buf.indices) {
            val c = buf[i]
            buf[i] = (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8
        }
        return Gray(w, h, buf)
    }
}
