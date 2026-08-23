package dev.operit.fanqiehook.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Soft UI（新拟物）拨动开关：
 *  - 轨道为凹陷槽（左上暗缘 + 右下亮缘，与凸起方向相反）
 *  - 滑块为浮起圆（左上亮影 + 右下暗影 + 雾面渐变）
 *  - 开启时轨道染上柔和珊瑚色
 *  - 按压时滑块收缩、投影收紧，模拟软性材质
 *  - 180ms 减速曲线，触感柔和
 */
class SoftSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 滑块位置 0=关 1=开，由动画驱动。 */
    private var pos = 0f

    /** 按压缩放系数（1 = 正常）。 */
    private var pressScale = 1f

    private var animator: ValueAnimator? = null

    var checked: Boolean = false
        private set

    var onCheckedChangeListener: ((Boolean) -> Unit)? = null

    private val dark: Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // ── 尺寸（dp，按密度换算） ────────────────────────────────────────────────

    private val dp = resources.displayMetrics.density
    private val trackW = 50f * dp
    private val trackH = 30f * dp
    private val trackRadius = trackH / 2
    private val thumbD = 22f * dp

    // ── 画笔 ──────────────────────────────────────────────────────────────────

    private val shadowLightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val trackRect = RectF()
    private val shadowRect = RectF()

    // ── 颜色（新拟物双阴影 + 柔和主色） ────────────────────────────────────────

    private val surface = if (dark) 0xFF282D36.toInt() else 0xFFE9ECF3.toInt()
    private val surfaceDeep = if (dark) 0xFF22262E.toInt() else 0xFFE0E4ED.toInt()
    private val shadowLight = if (dark) 0xFF343B47.toInt() else 0xFFFDFEFF.toInt()
    private val shadowDark = if (dark) 0xFF161A21.toInt() else 0xFFBEC4D3.toInt()
    private val accentOn = if (dark) 0xFFB06552.toInt() else 0xFFF5A794.toInt()
    private val accentStrong = if (dark) 0xFFE8836B.toInt() else 0xFFF0876F.toInt()
    private val thumbTop = if (dark) 0xFFDDE0E6.toInt() else 0xFFFFFFFF.toInt()
    private val thumbBottom = if (dark) 0xFFB9BEC7.toInt() else 0xFFE6E9F0.toInt()

    // ── 对外接口 ──────────────────────────────────────────────────────────────

    fun setChecked(value: Boolean, animate: Boolean = true) {
        if (checked == value) return
        checked = value
        contentDescription = if (value) "已开启" else "已关闭"
        if (animate && isAttachedToWindow) animateTo(value) else pos = if (value) 1f else 0f
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }

    // ── 测量 ──────────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 双阴影各留 2dp 余量
        setMeasuredDimension(trackW.toInt() + 4, trackH.toInt() + 4)
    }

    // ── 绘制 ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val left = cx - trackW / 2
        val top = cy - trackH / 2
        trackRect.set(left, top, left + trackW, top + trackH)

        drawTrack(canvas)
        drawThumb(canvas)
    }

    /** 轨道：凹陷槽。左上暗缘 + 右下亮缘（阴影方向与凸起相反）。 */
    private fun drawTrack(canvas: Canvas) {
        val o = 1.4f * dp

        // 暗阴影：偏左上
        shadowDarkPaint.color = shadowDark
        shadowRect.set(trackRect.left - o, trackRect.top - o, trackRect.left - o + trackW, trackRect.top - o + trackH)
        canvas.drawRoundRect(shadowRect, trackRadius, trackRadius, shadowDarkPaint)

        // 亮阴影：偏右下
        shadowLightPaint.color = shadowLight
        shadowRect.set(trackRect.left + o, trackRect.top + o, trackRect.left + o + trackW, trackRect.top + o + trackH)
        canvas.drawRoundRect(shadowRect, trackRadius, trackRadius, shadowLightPaint)

        // 槽底：关闭=略深表面，开启=柔和珊瑚（随位置渐变）
        trackPaint.color = mixColor(surfaceDeep, accentOn, pos)
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)

        // 开启时轨道内高光，增强色彩通透感
        if (pos > 0.05f) {
            trackPaint.shader = LinearGradient(
                0f, trackRect.top, 0f, trackRect.bottom,
                mixColor(0x00FFFFFF.toInt(), 0x33FFFFFF.toInt(), pos),
                0x00FFFFFF, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)
            trackPaint.shader = null
        }
    }

    /** 滑块：浮起圆。左上亮影 + 右下暗影 + 雾面渐变主体。 */
    private fun drawThumb(canvas: Canvas) {
        val travel = trackRect.width() - thumbD - 3f * dp
        val thumbCx = trackRect.left + 1.5f * dp + thumbD / 2 + travel * pos
        val thumbCy = trackRect.centerY()
        val r = thumbD / 2 * pressScale
        val o = (1.2f + 0.8f * pressScale) * dp

        // 亮影：偏左上
        shadowLightPaint.color = shadowLight
        canvas.drawCircle(thumbCx - o, thumbCy - o, r, shadowLightPaint)

        // 暗影：偏右下
        shadowDarkPaint.color = shadowDark
        canvas.drawCircle(thumbCx + o, thumbCy + o, r, shadowDarkPaint)

        // 主体：雾面渐变
        thumbPaint.shader = LinearGradient(
            thumbCx, thumbCy - r, thumbCx, thumbCy + r,
            thumbTop, thumbBottom, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(thumbCx, thumbCy, r, thumbPaint)

        // 开启时滑块中心点染主色，状态一目了然
        if (pos > 0.05f) {
            trackPaint.color = mixColor(0x00FFFFFF.toInt(), accentStrong, pos)
            canvas.drawCircle(thumbCx, thumbCy, r * 0.32f, trackPaint)
        }
        thumbPaint.shader = null
    }

    // ── 交互 ──────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressScale = 0.9f
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressScale = 1f
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        setChecked(!checked)
        onCheckedChangeListener?.invoke(checked)
        return true
    }

    // ── 动画与工具 ────────────────────────────────────────────────────────────

    private fun animateTo(target: Boolean) {
        animator?.cancel()
        val to = if (target) 1f else 0f
        animator = ValueAnimator.ofFloat(pos, to).apply {
            duration = 180
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                pos = (it.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
            start()
        }
    }

    private fun mixColor(from: Int, to: Int, t: Float): Int = Color.argb(
        0xFF,
        lerp(Color.red(from), Color.red(to), t),
        lerp(Color.green(from), Color.green(to), t),
        lerp(Color.blue(from), Color.blue(to), t),
    )

    private fun lerp(from: Int, to: Int, t: Float): Int = (from + (to - from) * t).toInt()
}
