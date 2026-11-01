package com.example.autoclicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqCapture = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val info = TextView(this).apply {
            textSize = 16f
            text = "AutoClicker\n\n" +
                "Android 10 ya neeche ho to PEHLE 'Screen Capture ON' dabao aur Start now karo.\n" +
                "(Android 11+ pe ye button nahi dikhega, zaroorat nahi.)\n\n" +
                "Phir:\n" +
                "1. 'Open Accessibility Settings' dabao.\n" +
                "2. AutoClicker chuno aur ON karo.\n" +
                "   (Agar grey ho: Settings > Apps > AutoClicker > 3 dots > Allow restricted settings)\n" +
                "3. ON hote hi laal NUM box, hara LOAD box, neela gola (1) aur control panel aayega.\n\n" +
                "Kaise use karein:\n" +
                "- Laal NUM box number ke upar rakho. Neeche ke kone se size badlo.\n" +
                "- Hara LOAD box beech wali chhoti loading ke upar rakho (tight).\n" +
                "- Neele gole drag karke click wali jagah rakho. '+ Click' se aur jodo.\n" +
                "- START: number padhega, +2 sec wait, clicks (beech mein 1 sec), loading ka wait, phir repeat.\n" +
                "- STOP dabao to ruk jayega.\n" +
                "- Panel ko upar ke '≡' wale text se drag karo."
        }

        root.addView(info)

        if (Build.VERSION.SDK_INT < 30) {
            val capBtn = Button(this).apply {
                text = "Screen Capture ON (Android 10 ya neeche)"
                setOnClickListener {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
                }
            }
            root.addView(capBtn)
        }

        val btn = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(btn)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture) {
            if (resultCode == RESULT_OK && data != null) {
                CaptureService.resultCode = resultCode
                CaptureService.resultData = data
                val i = Intent(this, CaptureService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                Toast.makeText(this, "Screen Capture ON ho gaya", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Permission nahi mili", Toast.LENGTH_LONG).show()
            }
        }
    }
}
