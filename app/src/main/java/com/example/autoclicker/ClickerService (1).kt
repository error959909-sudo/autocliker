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
        const val ADD_SECONDS = 10      // box ke number mein itne sec jodna
        const val GAP_MS = 1000L        // har click ke beech gap (1 sec)
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

    private var running = false
    private var built = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (built) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildPanel()
        buildBox()
        addMarker(true)
        addMarker(false)
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
    }

    private fun setOverlaysVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.INVISIBLE
        panel.visibility = vis
        boxFrame.visibility = vis
        markers.forEach { it.visibility = vis }
        preMarkers.forEach { it.visibility = vis }
    }

    // ---------- loop ----------

    private fun startLoop() {
        if (markers.isEmpty() && preMarkers.isEmpty()) { status("Click point add karo"); return }
        running = true
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
        } else {
            val total = ceil(v + ADD_SECONDS).toLong()
            runPre(total)
        }
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
        tapSequence(markers, "Baad", 0) { readBox(0) }
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
