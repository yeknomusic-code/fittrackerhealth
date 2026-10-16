package com.yeknomusic.ftwbridge

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Shown by Health Connect when you tap "privacy policy" on its permission screen. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        setContentView(TextView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0B"))
            setTextColor(Color.parseColor("#CFCFD4"))
            textSize = 16f
            setPadding(pad, pad * 2, pad, pad)
            text = "How FitTracker Health uses your data\n\n" +
                "• Exercise (write): saves the strength workouts you log in FitTracker to Health Connect, so they appear in Samsung Health.\n" +
                "• Weight (read & write): keeps body weight the same in FitTracker and Samsung Health.\n" +
                "• Steps and heart rate (read): shows today's steps and heart rate in your FitTracker phone app.\n" +
                "• Background access (optional): lets the hourly sync read steps and weight while the app is closed.\n\n" +
                "Data goes only to your own FitTracker sync database (the Firebase project you created), under your private pairing code. " +
                "Nothing is shared with anyone else. You can revoke access any time in Health Connect settings."
        })
    }
}
