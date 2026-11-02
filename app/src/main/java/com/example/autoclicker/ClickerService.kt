package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.ceil
import kotlin.math.max

class ClickerService : AccessibilityService() {

    companion object {
        const val ADD_SECONDS = 2       // box ke number mein itne sec jodna
        const val GAP_MS = 1000L        // har click ke beech gap (1 sec)
        const val MIN_VALUE = 300       // isse kam number aaye to SKIP
        const val MAX_SKIPS = 100       // lagatar itne skip ke baad ruk jao
        const val LOAD_TIMEOUT_MS = 90000L  // loading itni der se zyada chale to aage badh jao
        const val LOAD_POLL_MS = 1300L      // loading kitni der baad dobara check kare
        const val STABLE_NEEDED = 2         // lagatar itni baar 'koi badlav nahi' = loading khatam
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private lateinit var panel: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var statusView: TextView
    private lateinit var startBtn: Button

    private lateinit var boxFrame: FrameLayout
    private lateinit var boxParams: WindowManager.LayoutParams

    private val markers = mutableListOf<TextView>()
    private val markerParams = mutableListOf<WindowManager.LayoutParams>()

    // box padhne se PEHLE ke click points (hare)
    private val preMarkers = mutableListOf<TextView>()
    private val preParams = mutableListOf<WindowManager.LayoutParams>()

    // SKIP button ka orange gola
    private lateinit var skipMarker: TextView
    private lateinit var skipParams: WindowManager.LayoutParams
    private var skipCount = 0

    // LOADING check wala HARA box
    private lateinit var loadFrame: FrameLayout
    private lateinit var loadParams: WindowManager.LayoutParams
    private var prevLoadCrop: Bitmap? = null
    private var loadStart = 0L

    private var running = false
    private var built = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (built) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildPanel()
        buildBox()
        buildLoadBox()
        addMarker(true)
        addMarker(false)
        addSkipMarker()
        built = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (built) {
            try { wm.removeView(panel) } catch (_: Exception) {}
            try { wm.removeView(boxFrame) } catch (_: Exception) {}
            markers.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
            preMarkers.forEach { try { wm.removeView(it) } catch (_: Exception) {} }
            try { wm.removeView(skipMarker) } catch (_: Exception) {}
            try { wm.removeView(loadFrame) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun baseParams(w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun makeDraggable(handle: View, target: View, p: WindowManager.LayoutParams) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = ox + (e.rawX - sx).toInt()
                    p.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(target, p)
                }
            }
            true
        }
    }

    private fun status(s: String) {
        statusView.text = "≡ $s"
    }

    // ---------- UI ----------

    private fun buildPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC222222"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        statusView = TextView(this).apply {
            text = "≡ Ready"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, 0, dp(4))
        }
        startBtn = smallButton("START") { if (running) stopLoop("Stopped") else startLoop() }
        val addPreBtn = smallButton("+ Pehle") { addMarker(true) }
        val delPreBtn = smallButton("- Pehle") { removeMarker(true) }
        val addBtn = smallButton("+ Baad") { addMarker(false) }
        val delBtn = smallButton("- Baad") { removeMarker(false) }

        panel.addView(statusView)
        panel.addView(startBtn)
        panel.addView(addPreBtn)
        panel.addView(delPreBtn)
        panel.addView(addBtn)
        panel.addView(delBtn)

        panelParams = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(8), dp(120)
        )
        makeDraggable(statusView, panel, panelParams)
        wm.addView(panel, panelParams)
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(40))
            setOnClickListener { onClick() }
        }
    }

    private fun buildBox() {
        boxFrame = FrameLayout(this)
        boxFrame.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), Color.RED)
        }
        val grip = View(this).apply { setBackgroundColor(Color.RED) }
        boxFrame.addView(grip, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END))

        boxParams = baseParams(dp(180), dp(70), dp(60), dp(300))
        makeDraggable(boxFrame, boxFrame, boxParams)

        var sx = 0f
        var sy = 0f
        var sw = 0
        var sh = 0
        grip.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; sw = boxParams.width; sh = boxParams.height
                }
                MotionEvent.ACTION_MOVE -> {
                    boxParams.width = max(dp(60), sw + (e.rawX - sx).toInt())
                    boxParams.height = max(dp(40), sh + (e.rawY - sy).toInt())
                    wm.updateViewLayout(boxFrame, boxParams)
                }
            }
            true
        }
        wm.addView(boxFrame, boxParams)
    }

    private fun buildLoadBox() {
        val green = Color.parseColor("#43A047")
        loadFrame = FrameLayout(this)
        loadFrame.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), green)
        }
        val grip = View(this).apply { setBackgroundColor(green) }
        loadFrame.addView(grip, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END))

        loadParams = baseParams(dp(140), dp(60), dp(60), dp(420))
        makeDraggable(loadFrame, loadFrame, loadParams)

        var sx = 0f
        var sy = 0f
        var sw = 0
        var sh = 0
        grip.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; sw = loadParams.width; sh = loadParams.height
                }
                MotionEvent.ACTION_MOVE -> {
                    loadParams.width = max(dp(50), sw + (e.rawX - sx).toInt())
                    loadParams.height = max(dp(40), sh + (e.rawY - sy).toInt())
                    wm.updateViewLayout(loadFrame, loadParams)
                }
            }
            true
        }
        wm.addView(loadFrame, loadParams)
    }

    private fun addMarker(pre: Boolean) {
        if (running) { status("Pehle STOP karo"); return }
        val list = if (pre) preMarkers else markers
        val plist = if (pre) preParams else markerParams
        val n = list.size + 1
        val tv = TextView(this).apply {
            text = if (pre) "P$n" else n.toString()
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(if (pre) "#AA43A047" else "#AA1E88E5"))
                setStroke(dp(2), Color.WHITE)
            }
        }
        val startX = if (pre) dp(40) else dp(140)
        val p = baseParams(dp(44), dp(44), startX + n * dp(20), dp(200) + n * dp(20))
        makeDraggable(tv, tv, p)
        wm.addView(tv, p)
        list.add(tv)
        plist.add(p)
    }

    private fun addSkipMarker() {
        skipMarker = TextView(this).apply {
            text = "S"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#AAFB8C00"))
                setStroke(dp(2), Color.WHITE)
            }
        }
        skipParams = baseParams(dp(44), dp(44), dp(240), dp(450))
        makeDraggable(skipMarker, skipMarker, skipParams)
        wm.addView(skipMarker, skipParams)
    }

    private fun removeMarker(pre: Boolean) {
        if (running) { status("Pehle STOP karo"); return }
        val list = if (pre) preMarkers else markers
        val plist = if (pre) preParams else markerParams
        if (list.isEmpty()) return
        val tv = list.removeAt(list.size - 1)
        plist.removeAt(plist.size - 1)
        try { wm.removeView(tv) } catch (_: Exception) {}
    }

    private fun setTouchable(t: Boolean) {
        fun upd(v: View, p: WindowManager.LayoutParams) {
            p.flags = if (t) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(v, p)
        }
        upd(boxFrame, boxParams)
        for (i in markers.indices) upd(markers[i], markerParams[i])
        for (i in preMarkers.indices) upd(preMarkers[i], preParams[i])
        upd(skipMarker, skipParams)
        upd(loadFrame, loadParams)
    }

    private fun setOverlaysVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.INVISIBLE
        panel.visibility = vis
        boxFrame.visibility = vis
        markers.forEach { it.visibility = vis }
        preMarkers.forEach { it.visibility = vis }
        skipMarker.visibility = vis
        loadFrame.visibility = vis
    }

    // ---------- loop ----------

    private fun startLoop() {
        if (markers.isEmpty() && preMarkers.isEmpty()) { status("Click point add karo"); return }
        running = true
        skipCount = 0
        startBtn.text = "STOP"
        setTouchable(false)   // taaki tap neeche wali app tak pahunche
        readBox(0)
    }

    private fun stopLoop(msg: String) {
        running = false
        handler.removeCallbacksAndMessages(null)
        startBtn.text = "START"
        setTouchable(true)
        setOverlaysVisible(true)
        status(msg)
    }

    private fun readBox(attempt: Int) {
        if (!running) return
        status("Reading...")
        setOverlaysVisible(false)
        handler.postDelayed({
            if (!running) return@postDelayed
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        setOverlaysVisible(true)
                        if (!running) { result.hardwareBuffer.close(); return }
                        val hw = result.hardwareBuffer
                        val hwBmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        val bmp = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                        hw.close()
                        if (bmp == null) { retry(attempt); return }
                        ocr(bmp, attempt)
                    }

                    override fun onFailure(errorCode: Int) {
                        setOverlaysVisible(true)
                        retry(attempt)
                    }
                }
            )
        }, 350)
    }

    private fun ocr(bmp: Bitmap, attempt: Int) {
        try {
            val loc = IntArray(2)
            boxFrame.getLocationOnScreen(loc)
            val inset = dp(4)
            val x = (loc[0] + inset).coerceIn(0, bmp.width - 1)
            val y = (loc[1] + inset).coerceIn(0, bmp.height - 1)
            val w = (boxFrame.width - 2 * inset).coerceAtMost(bmp.width - x).coerceAtLeast(1)
            val h = (boxFrame.height - 2 * inset).coerceAtMost(bmp.height - y).coerceAtLeast(1)
            val crop = Bitmap.createBitmap(bmp, x, y, w, h)
            val scaled = Bitmap.createScaledBitmap(crop, w * 2, h * 2, true)
            recognizer.process(InputImage.fromBitmap(scaled, 0))
                .addOnSuccessListener { vt -> handleText(vt.text, attempt) }
                .addOnFailureListener { retry(attempt) }
        } catch (e: Exception) {
            retry(attempt)
        }
    }

    private fun handleText(text: String, attempt: Int) {
        if (!running) return
        val m = Regex("\\d+(\\.\\d+)?").find(text)
        val v = m?.value?.toDoubleOrNull()
        if (v == null) {
            retry(attempt)
        } else if (v < MIN_VALUE) {
            doSkip(v)
        } else {
            skipCount = 0
            val total = ceil(v + ADD_SECONDS).toLong()
            runPre(total)
        }
    }

    // number MIN_VALUE se kam hai: SKIP gole par click, phir box dobara padho
    private fun doSkip(v: Double) {
        if (!running) return
        skipCount++
        if (skipCount > MAX_SKIPS) { stopLoop("Bahut skip hue, ruk gaya"); return }
        status("Skip #$skipCount (number ${v.toLong()})")
        val loc = IntArray(2)
        skipMarker.getLocationOnScreen(loc)
        val cx = loc[0] + skipMarker.width / 2f
        val cy = loc[1] + skipMarker.height / 2f
        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, null, null)
        handler.postDelayed({ waitLoading() }, GAP_MS)
    }

    private fun retry(attempt: Int) {
        if (!running) return
        if (attempt >= 2) {
            stopLoop("Number nahi mila")
        } else {
            status("Retry...")
            handler.postDelayed({ readBox(attempt + 1) }, 1500)
        }
    }

    private fun waitSeconds(remaining: Long) {
        if (!running) return
        if (remaining <= 0) { runPost(); return }
        status("Wait ${remaining}s")
        handler.postDelayed({ waitSeconds(remaining - 1) }, 1000)
    }

    // box padhne ke turant baad "Pehle" wale clicks, phir wait
    private fun runPre(total: Long) {
        tapSequence(preMarkers, "Pehle", 0) { waitSeconds(total) }
    }

    // wait ke baad "Baad" wale clicks, phir wapas box padhna (repeat)
    private fun runPost() {
        tapSequence(markers, "Baad", 0) { waitLoading() }
    }

    // ---------- loading check (hara box) ----------

    private fun grabScreen(cb: (Bitmap?) -> Unit) {
        setOverlaysVisible(false)
        handler.postDelayed({
            if (!running) { setOverlaysVisible(true); return@postDelayed }
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        setOverlaysVisible(true)
                        val hw = result.hardwareBuffer
                        val hwBmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        val bmp = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                        hw.close()
                        cb(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        setOverlaysVisible(true)
                        cb(null)
                    }
                }
            )
        }, 350)
    }

    private fun cropBox(bmp: Bitmap, frame: View): Bitmap {
        val loc = IntArray(2)
        frame.getLocationOnScreen(loc)
        val inset = dp(4)
        val x = (loc[0] + inset).coerceIn(0, bmp.width - 1)
        val y = (loc[1] + inset).coerceIn(0, bmp.height - 1)
        val w = (frame.width - 2 * inset).coerceAtMost(bmp.width - x).coerceAtLeast(1)
        val h = (frame.height - 2 * inset).coerceAtMost(bmp.height - y).coerceAtLeast(1)
        return Bitmap.createBitmap(bmp, x, y, w, h)
    }

    private fun pixelsChanged(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return true
        val n = a.width * a.height
        val pa = IntArray(n)
        val pb = IntArray(n)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        var diff = 0
        for (i in 0 until n) {
            val c1 = pa[i]
            val c2 = pb[i]
            val d = Math.abs(Color.red(c1) - Color.red(c2)) +
                Math.abs(Color.green(c1) - Color.green(c2)) +
                Math.abs(Color.blue(c1) - Color.blue(c2))
            if (d > 90) diff++
        }
        return diff >= max(15, n / 500)
    }

    // hare box mein loading khatam hone tak ruko, phir box padho
    private fun waitLoading() {
        if (!running) return
        prevLoadCrop = null
        loadStart = System.currentTimeMillis()
        status("Loading check...")
        pollLoading(0)
    }

    private fun pollLoading(stable: Int) {
        if (!running) return
        if (System.currentTimeMillis() - loadStart > LOAD_TIMEOUT_MS) {
            status("Loading timeout, aage badh raha")
            readBox(0)
            return
        }
        grabScreen { bmp ->
            if (!running) return@grabScreen
            if (bmp == null) {
                handler.postDelayed({ pollLoading(stable) }, LOAD_POLL_MS)
                return@grabScreen
            }
            val crop = try { cropBox(bmp, loadFrame) } catch (e: Exception) { null }
            if (crop == null) {
                readBox(0)
                return@grabScreen
            }
            val prev = prevLoadCrop
            prevLoadCrop = crop
            val changed = prev != null && pixelsChanged(prev, crop)
            val hadPrev = prev != null
            val scaled = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
            recognizer.process(InputImage.fromBitmap(scaled, 0))
                .addOnSuccessListener { vt ->
                    afterLoadPoll(stable, hadPrev, changed || vt.text.contains("load", true))
                }
                .addOnFailureListener { afterLoadPoll(stable, hadPrev, changed) }
        }
    }

    private fun afterLoadPoll(stable: Int, hadPrev: Boolean, loading: Boolean) {
        if (!running) return
        val next = when {
            loading -> 0
            hadPrev -> stable + 1
            else -> stable
        }
        if (next >= STABLE_NEEDED) {
            status("Loading khatam")
            readBox(0)
        } else {
            status(if (loading) "Loading..." else "Loading check...")
            handler.postDelayed({ pollLoading(next) }, LOAD_POLL_MS)
        }
    }

    private fun tapSequence(list: List<TextView>, label: String, i: Int, onDone: () -> Unit) {
        if (!running) return
        if (i >= list.size) { onDone(); return }
        val loc = IntArray(2)
        list[i].getLocationOnScreen(loc)
        val cx = loc[0] + list[i].width / 2f
        val cy = loc[1] + list[i].height / 2f
        status("$label click ${i + 1}/${list.size}")
        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, null, null)
        handler.postDelayed({ tapSequence(list, label, i + 1, onDone) }, GAP_MS)
    }
}
