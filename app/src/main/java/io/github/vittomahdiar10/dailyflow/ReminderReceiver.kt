package io.github.vittomahdiar10.dailyflow

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Calendar

object ReminderScheduler {

    const val CHANNEL_TASKS = "daily_flow_reminders"
    const val CHANNEL_FOCUS = "daily_flow_focus"
    const val CHANNEL_DAILY = "daily_flow_daily_streak"

    const val ACTION_SHOW_REMINDER = "io.github.vittomahdiar10.dailyflow.ACTION_SHOW_REMINDER"
    const val EXTRA_ID_KEY = "extra_id_key"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_BODY = "extra_body"
    const val EXTRA_CHANNEL_ID = "extra_channel_id"
    const val EXTRA_REPEAT_DAILY = "extra_repeat_daily"
    const val EXTRA_TRIGGER_AT = "extra_trigger_at"

    private const val PREFS_NAME = "daily_flow_alarms_v1"
    private const val KEY_ALARMS_JSON = "scheduled_alarms"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val tasksChannel = NotificationChannel(
                CHANNEL_TASKS,
                "یادآور کارها و برنامه روزانه",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "اعلان یادآور ساعت‌دار کارهای ثبت‌شده در Daily Flow"
                enableVibration(true)
            }

            val focusChannel = NotificationChannel(
                CHANNEL_FOCUS,
                "تایمر تمرکز عمیق",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "اعلان پایان جلسه تایمر تمرکز و داینامیک آیلند"
                enableVibration(true)
            }

            val dailyChannel = NotificationChannel(
                CHANNEL_DAILY,
                "یادآور روزانه و حفظ استریک",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "یادآوری هوشمند شبانه برای تکمیل برنامه و حفظ استریک"
                enableVibration(true)
            }

            manager.createNotificationChannel(tasksChannel)
            manager.createNotificationChannel(focusChannel)
            manager.createNotificationChannel(dailyChannel)
        }
    }

    fun normalizeChannelId(raw: String?): String {
        return when (raw) {
            CHANNEL_FOCUS -> CHANNEL_FOCUS
            CHANNEL_DAILY -> CHANNEL_DAILY
            else -> CHANNEL_TASKS
        }
    }

    fun requestCodeForKey(idKey: String): Int {
        return idKey.hashCode() and 0x7fffffff
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun showNotificationNow(
        context: Context,
        idKey: String,
        title: String,
        body: String,
        channelId: String
    ): Boolean {
        createNotificationChannels(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }

        val resolvedChannel = normalizeChannelId(channelId)
        val notificationId = requestCodeForKey(idKey)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ID_KEY, idKey)
            putExtra(EXTRA_CHANNEL_ID, resolvedChannel)
        }

        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, resolvedChannel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Color.parseColor("#4ebec2"))
            .setContentTitle(title.ifBlank { "Daily Flow" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)

        return try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    fun scheduleAlarm(
        context: Context,
        idKey: String,
        triggerAtMillis: Long,
        title: String,
        body: String,
        channelId: String,
        repeatDaily: Boolean
    ): Boolean {
        if (idKey.isBlank()) return false
        val now = System.currentTimeMillis()
        var targetMillis = triggerAtMillis

        if (targetMillis <= now + 1000L) {
            if (repeatDaily) {
                targetMillis = computeNextDailyTrigger(targetMillis, now)
            } else {
                return false
            }
        }

        val resolvedChannel = normalizeChannelId(channelId)
        persistAlarm(context, idKey, targetMillis, title, body, resolvedChannel, repeatDaily)
        return registerAlarmInSystem(
            context = context,
            idKey = idKey,
            triggerAtMillis = targetMillis,
            title = title,
            body = body,
            channelId = resolvedChannel,
            repeatDaily = repeatDaily
        )
    }

    fun cancelAlarm(context: Context, idKey: String) {
        if (idKey.isBlank()) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pendingIntent = buildAlarmPendingIntent(
            context = context,
            idKey = idKey,
            title = "",
            body = "",
            channelId = CHANNEL_TASKS,
            repeatDaily = false,
            triggerAtMillis = 0L
        )
        try {
            alarmManager?.cancel(pendingIntent)
            pendingIntent.cancel()
        } catch (e: Exception) {}

        try {
            NotificationManagerCompat.from(context).cancel(requestCodeForKey(idKey))
        } catch (e: Exception) {}

        removePersistedAlarm(context, idKey)
    }

    fun cancelByPrefix(context: Context, prefix: String) {
        if (prefix.isBlank()) return
        val map = loadPersistedAlarms(context)
        val keysToRemove = mutableListOf<String>()
        val iter = map.keys()
        while (iter.hasNext()) {
            val k = iter.next()
            if (k.startsWith(prefix)) {
                keysToRemove.add(k)
            }
        }
        keysToRemove.forEach { cancelAlarm(context, it) }
    }

    fun rescheduleAllPersisted(context: Context) {
        createNotificationChannels(context)
        val map = loadPersistedAlarms(context)
        val now = System.currentTimeMillis()
        val updated = JSONObject()
        val keys = map.keys()

        while (keys.hasNext()) {
            val idKey = keys.next()
            val item = map.optJSONObject(idKey) ?: continue
            var triggerAt = item.optLong("triggerAt", 0L)
            val title = item.optString("title", "Daily Flow")
            val body = item.optString("body", "")
            val channelId = normalizeChannelId(item.optString("channelId", CHANNEL_TASKS))
            val repeatDaily = item.optBoolean("repeatDaily", false)

            if (triggerAt <= now + 1000L) {
                if (repeatDaily) {
                    triggerAt = computeNextDailyTrigger(triggerAt, now)
                } else {
                    if (now - triggerAt <= 15 * 60 * 1000L) {
                        showNotificationNow(context, idKey, title, body, channelId)
                    }
                    continue
                }
            }

            item.put("triggerAt", triggerAt)
            updated.put(idKey, item)
            registerAlarmInSystem(
                context = context,
                idKey = idKey,
                triggerAtMillis = triggerAt,
                title = title,
                body = body,
                channelId = channelId,
                repeatDaily = repeatDaily
            )
        }

        savePersistedAlarms(context, updated)
    }

    internal fun computeNextDailyTrigger(previousTriggerMillis: Long, nowMillis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = if (previousTriggerMillis > 0L) previousTriggerMillis else nowMillis
        }
        while (cal.timeInMillis <= nowMillis + 5000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    internal fun removePersistedAlarm(context: Context, idKey: String) {
        val map = loadPersistedAlarms(context)
        if (map.has(idKey)) {
            map.remove(idKey)
            savePersistedAlarms(context, map)
        }
    }

    private fun registerAlarmInSystem(
        context: Context,
        idKey: String,
        triggerAtMillis: Long,
        title: String,
        body: String,
        channelId: String,
        repeatDaily: Boolean
    ): Boolean {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val pendingIntent = buildAlarmPendingIntent(
            context = context,
            idKey = idKey,
            title = title,
            body = body,
            channelId = channelId,
            repeatDaily = repeatDaily,
            triggerAtMillis = triggerAtMillis
        )

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            true
        } catch (e: SecurityException) {
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                true
            } catch (ex: Exception) {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun buildAlarmPendingIntent(
        context: Context,
        idKey: String,
        title: String,
        body: String,
        channelId: String,
        repeatDaily: Boolean,
        triggerAtMillis: Long
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_SHOW_REMINDER
            putExtra(EXTRA_ID_KEY, idKey)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_CHANNEL_ID, channelId)
            putExtra(EXTRA_REPEAT_DAILY, repeatDaily)
            putExtra(EXTRA_TRIGGER_AT, triggerAtMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeForKey(idKey),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun persistAlarm(
        context: Context,
        idKey: String,
        triggerAtMillis: Long,
        title: String,
        body: String,
        channelId: String,
        repeatDaily: Boolean
    ) {
        val map = loadPersistedAlarms(context)
        val obj = JSONObject().apply {
            put("triggerAt", triggerAtMillis)
            put("title", title)
            put("body", body)
            put("channelId", channelId)
            put("repeatDaily", repeatDaily)
        }
        map.put(idKey, obj)
        savePersistedAlarms(context, map)
    }

    private fun loadPersistedAlarms(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ALARMS_JSON, "{}") ?: "{}"
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun savePersistedAlarms(context: Context, map: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ALARMS_JSON, map.toString()).apply()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                ReminderScheduler.rescheduleAllPersisted(context)
            }

            ReminderScheduler.ACTION_SHOW_REMINDER -> {
                val idKey = intent.getStringExtra(ReminderScheduler.EXTRA_ID_KEY) ?: return
                val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: "Daily Flow"
                val body = intent.getStringExtra(ReminderScheduler.EXTRA_BODY) ?: ""
                val channelId = intent.getStringExtra(ReminderScheduler.EXTRA_CHANNEL_ID)
                    ?: ReminderScheduler.CHANNEL_TASKS
                val repeatDaily = intent.getBooleanExtra(ReminderScheduler.EXTRA_REPEAT_DAILY, false)
                val prevTrigger = intent.getLongExtra(
                    ReminderScheduler.EXTRA_TRIGGER_AT,
                    System.currentTimeMillis()
                )

                ReminderScheduler.showNotificationNow(context, idKey, title, body, channelId)

                if (repeatDaily) {
                    val nextTrigger = ReminderScheduler.computeNextDailyTrigger(
                        prevTrigger,
                        System.currentTimeMillis()
                    )
                    ReminderScheduler.scheduleAlarm(
                        context = context,
                        idKey = idKey,
                        triggerAtMillis = nextTrigger,
                        title = title,
                        body = body,
                        channelId = channelId,
                        repeatDaily = true
                    )
                } else {
                    ReminderScheduler.removePersistedAlarm(context, idKey)
                }
            }
        }
    }
}
