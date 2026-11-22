package app.homepad

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * The app switcher, drawn the same way the home screen is: one canvas, no widgets.
 *
 * Task data and the screenshots come from tesserad, the root helper: the system only hands other
 * apps' tasks to system callers, and this Android 11 build has no getTaskThumbnail() at all. What it
 * does keep is a jpeg per task under /data/system_ce/0/snapshots, which is what the helper reads.
 */
class RecentsActivity : Activity() {

    private lateinit var view: RecentsView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.setDecorFitsSystemWindows(false)
        view = RecentsView(this)
        setContentView(view)
        view.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars())
            view.insetTop = b.top
            view.insetBottom = b.bottom
            view.invalidate()
            ins
        }
        Recents.load(this) { list -> view.show(list) }
    }

    override fun onResume() {
        super.onResume()
        // A task may have been closed or opened while we were away.
        Recents.load(this) { list -> view.show(list) }
    }

    override fun onBackPressed() = finish()
}

/** One row of the switcher: the app's last frame, its icon and its name. */
class Card(
    val taskId: Int,
    val component: String,
    val label: String,
    val icon: Drawable?,
    val shot: Bitmap?,
) {
    val pkg: String get() = component.substringBefore('/')
}

object Recents {
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val MAX = 6

    /** One command to tesserad; null when the helper is not running. */
    fun root(cmd: String): String? = try {
        LocalSocket().use { s ->
            s.connect(LocalSocketAddress("tesserad", LocalSocketAddress.Namespace.ABSTRACT))
            s.soTimeout = 6000
            s.outputStream.write(cmd.toByteArray())
            s.shutdownOutput()
            s.inputStream.readBytes().toString(Charsets.UTF_8)
        }
    } catch (_: Exception) { null }

    fun load(act: Activity, then: (List<Card>) -> Unit) {
        val app = act.applicationContext
        pool.execute {
            val rows = root("tessera tasks").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
            val out = ArrayList<Card>()
            for (row in rows) {
                val f = row.split('\t')
                if (f.size < 4) continue
                val id = f[0].toIntOrNull() ?: continue
                val type = f[2]
                val comp = f[3]
                if (type == "home") continue
                if (comp.startsWith("app.homepad/.Recents")) continue
                val pkg = comp.substringBefore('/')
                val label = try {
                    val pm = app.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) { pkg }
                val icon = try { app.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
                out.add(Card(id, comp, label, icon, shot(id)))
                if (out.size >= MAX) break
            }
            main.post { then(out) }
        }
    }

    /** Decodes the system's stored screenshot for a task, if it still has one. */
    private fun shot(id: Int): Bitmap? {
        val b64 = root("tessera thumb $id")?.trim().orEmpty()
        if (b64.isEmpty()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
    }

    /** Brings a task forward, then leaves the switcher so it is not left underneath. */
    fun open(act: Activity, c: Card) {
        act.finish()
        act.overridePendingTransition(0, 0)
        pool.execute { root("tessera front ${c.component}") }
    }

    fun close(c: Card) {
        pool.execute { root("tessera kill ${c.pkg}") }
    }
}

class RecentsView(private val act: RecentsActivity) : View(act) {

    var insetTop = 0
    var insetBottom = 0

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    private var cards: List<Card> = emptyList()
    private var loaded = false
    private var scroll = 0f
    private var maxScroll = 0f

    private var downX = 0f
    private var downY = 0f
    private var dragging = -1
    private var dragDy = 0f
    private var moved = false

    private val scrim = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        textSize = 15f * sp
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(6f), 0f, dp(1.5f), 0x66000000)
    }
    private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 17f * sp
        color = 0x99FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val r = RectF()
    private var cardW = 0f
    private var cardH = 0f
    private var gap = 0f
    private var top = 0f
    private val src = Rect()

    fun show(list: List<Card>) {
        cards = list
        loaded = true
        scroll = 0f
        metrics()
        invalidate()
    }

    private fun metrics() {
        val avail = (height - insetTop - insetBottom).toFloat()
        // Two cards should fit side by side without scrolling on a 16:10 tablet.
        cardH = avail * 0.44f
        cardW = cardH * 1.62f
        gap = dp(30f)
        top = insetTop + (avail - cardH) * 0.5f - dp(14f)
        val total = cards.size * cardW + max(0, cards.size - 1) * gap
        maxScroll = max(0f, total - (width - dp(80f)))
        scroll = scroll.coerceIn(0f, maxScroll)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = metrics()

    private fun originX(i: Int): Float {
        val total = cards.size * cardW + max(0, cards.size - 1) * gap
        val lead = if (total <= width - dp(80f)) (width - total) / 2 else dp(40f)
        return lead + i * (cardW + gap) - scroll
    }

    private fun cardAt(x: Float, y: Float): Int {
        if (y < top - dp(10f) || y > top + cardH + dp(60f)) return -1
        for (i in cards.indices) {
            val l = originX(i)
            if (x >= l && x <= l + cardW) return i
        }
        return -1
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0x6E000000)
        if (!loaded) {
            c.drawText("正在读取…", width / 2f, height / 2f, note)
            return
        }
        if (cards.isEmpty()) {
            c.drawText("没有最近使用的应用", width / 2f, height / 2f, note)
            return
        }
        val icon = dp(26f)
        for (i in cards.indices) {
            val card = cards[i]
            val l = originX(i)
            if (l > width || l + cardW < 0) continue
            val lift = if (i == dragging) -dragDy else 0f
            r.set(l, top + lift, l + cardW, top + cardH + lift)
            val alpha = if (i == dragging) (255 * (1f - abs(dragDy) / cardH)).toInt().coerceIn(40, 255) else 255

            // the frame, clipped to a rounded rect
            fill.color = 0x22FFFFFF
            c.drawRoundRect(r, dp(26f), dp(26f), fill)
            val save = c.save()
            c.clipPath(rounded(r, dp(26f)))
            if (card.shot != null) {
                // centre-crop the screenshot so the card is always filled
                val bw = card.shot.width
                val bh = card.shot.height
                val scale = max(r.width() / bw, r.height() / bh)
                val sw = (r.width() / scale).toInt().coerceAtMost(bw)
                val sh = (r.height() / scale).toInt().coerceAtMost(bh)
                src.set((bw - sw) / 2, (bh - sh) / 2, (bw + sw) / 2, (bh + sh) / 2)
                fill.color = 0xFF1C1C20.toInt()
                c.drawRect(r, fill)
                fill.alpha = alpha
                c.drawBitmap(card.shot, src, r, fill)
                fill.alpha = 255
            } else {
                fill.color = (0x33FFFFFF * (alpha / 255f)).toInt()
                c.drawRect(r, fill)
            }
            c.restoreToCount(save)

            // icon and name under the card
            val baseY = top + cardH + lift + dp(30f)
            val label = card.label
            val tw = text.measureText(label)
            val groupW = icon + dp(8f) + tw
            val gx = r.centerX() - groupW / 2
            card.icon?.let {
                it.setBounds(gx.toInt(), (baseY - icon / 2).toInt(), (gx + icon).toInt(), (baseY + icon / 2).toInt())
                it.alpha = alpha
                it.draw(c)
            }
            text.alpha = alpha
            c.drawText(label, gx + icon + dp(8f) + tw / 2, baseY + dp(5f), text)
            text.alpha = 255
        }
    }

    private fun rounded(box: RectF, rad: Float) = android.graphics.Path().apply {
        addRoundRect(box, rad, rad, android.graphics.Path.Direction.CW)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                dragging = cardAt(e.x, e.y)
                dragDy = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dx) > dp(12f) || abs(dy) > dp(12f)) moved = true
                if (dragging >= 0) {
                    // upwards only: that is the gesture that closes a card
                    dragDy = (-dy).coerceIn(0f, cardH)
                    invalidate()
                } else if (abs(dx) > abs(dy) && maxScroll > 0f) {
                    scroll = (scroll - dx * 0.6f).coerceIn(0f, maxScroll)
                    downX = e.x
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val i = dragging
                val lifted = dragDy
                dragging = -1
                dragDy = 0f
                if (i >= 0 && lifted > cardH * 0.28f) {
                    val gone = cards[i]
                    Recents.close(gone)
                    cards = cards.filterIndexed { idx, _ -> idx != i }
                    metrics()
                    invalidate()
                } else if (i >= 0 && !moved) {
                    Recents.open(act, cards[i])
                } else if (i < 0 && !moved) {
                    finish()   // tapped the empty space around the cards
                } else {
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = -1
                dragDy = 0f
                invalidate()
            }
        }
        return true
    }

    private fun finish() {
        act.finish()
        act.overridePendingTransition(0, 0)
    }
}
