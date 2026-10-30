package com.example.autoclicker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
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
                "1. Neeche button dabao, Accessibility settings khulengi.\n" +
                "2. Installed apps / Downloaded apps mein AutoClicker chuno aur ON karo.\n" +
                "   (Agar grey ho: Settings > Apps > AutoClicker > 3 dots > Allow restricted settings)\n" +
                "3. ON hote hi screen par laal box, neela gola (1) aur chhota control panel aayega.\n\n" +
                "Kaise use karein:\n" +
                "- Laal box ko kisi bhi app mein us number ke upar rakho jise padhna hai. Neeche ke kone se size badlo.\n" +
                "- Neele gole ko drag karke jahan click chahiye wahan rakho. '+ Click' se aur gole jodo.\n" +
                "- START dabao: box ka number padhega, +10 sec wait, phir clicks (har click ke beech 1 sec), phir repeat.\n" +
                "- STOP dabao to ruk jayega.\n" +
                "- Control panel ko upar ke '≡' wale text se drag karo."
        }

        val btn = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        root.addView(info)
        root.addView(btn)
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
