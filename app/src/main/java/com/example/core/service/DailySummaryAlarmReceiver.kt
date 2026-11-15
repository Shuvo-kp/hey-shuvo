package com.example.core.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import java.util.Calendar

class DailySummaryAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("hey_shuvo_allday_diary", Context.MODE_PRIVATE)
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            prefs.all.forEach { (key, value) ->
                if (key.startsWith("enabled_") && value == true) {
                    val restoredOwner = key.removePrefix("enabled_").ifBlank { "local_preview_owner" }
                    val hour = prefs.getInt("hour_$restoredOwner", 21)
                    val minute = prefs.getInt("minute_$restoredOwner", 0)
                    scheduleDailySummaryAlarm(context, hour, minute, restoredOwner)
                }
            }
            return
        }
        val ownerKey = intent?.getStringExtra(EXTRA_OWNER_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: "local_preview_owner"
        val lang = prefs.getString("lang_$ownerKey", null)
            ?: prefs.getString("lang_local_preview_owner", "BN")
            ?: "BN"
        val title = if (lang == "EN") {
            "Hey Shuvo • Daily Voice & Work Summary Ready"
        } else {
            "Hey Shuvo • আজকের ভয়েস ও কাজের সারাংশ প্রস্তুত"
        }
        val body = if (lang == "EN") {
            "Tap to view and email your scheduled end-of-day summary."
        } else {
            "নির্ধারিত সময়ের সারাংশ দেখতে ও ইমেইলে পাঠাতে ট্যাপ করুন।"
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hey Shuvo Daily Summary",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TRIGGER_DAILY_DIARY, true)
        }
        val pi = PendingIntent.getActivity(
            context,
            4041,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heyshuvo)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(4041, notification)
    }

    companion object {
        private const val CHANNEL_ID = "hey_shuvo_daily_summary_channel"
        const val EXTRA_OWNER_KEY = "extra_owner_key"
        const val EXTRA_TRIGGER_DAILY_DIARY = "extra_trigger_daily_diary"

        fun scheduleDailySummaryAlarm(
            context: Context,
            hour: Int,
            minute: Int,
            ownerKey: String = "local_preview_owner"
        ) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, DailySummaryAlarmReceiver::class.java).apply {
                putExtra(EXTRA_OWNER_KEY, ownerKey)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                4041,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                cal.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pendingIntent
            )
        }

        fun cancelDailySummaryAlarm(
            context: Context,
            ownerKey: String = "local_preview_owner"
        ) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, DailySummaryAlarmReceiver::class.java).apply {
                putExtra(EXTRA_OWNER_KEY, ownerKey)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                4041,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(4041)
        }
    }
}
