package com.openalc.inspnative

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

object TodoReminder {
    private fun intent(context: Context, id: String) = PendingIntent.getBroadcast(context, id.hashCode(),
        Intent(context, TodoReminderReceiver::class.java).apply { data = Uri.parse("inspnative://todo/$id"); putExtra("id", id) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(intent(context, id))
    }

    fun schedule(context: Context, todo: NativeTodo) {
        cancel(context, todo.id)
        if (todo.completed || todo.remindAt == null) return
        val alarm = context.getSystemService(AlarmManager::class.java)
        val due = nextTime(todo.remindAt!!, todo.repeat)
        if (due <= System.currentTimeMillis()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms())
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, intent(context, todo.id))
        else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, intent(context, todo.id))
    }

    private fun nextTime(first: Long, repeat: String): Long {
        if (repeat == "none") return first
        val date = Calendar.getInstance().apply { timeInMillis = first }
        val field = when (repeat) { "daily" -> Calendar.DATE; "weekly" -> Calendar.WEEK_OF_YEAR; else -> Calendar.MONTH }
        while (date.timeInMillis <= System.currentTimeMillis()) date.add(field, 1)
        return date.timeInMillis
    }
}

class TodoReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val todo = NativeStore(context).todos().firstOrNull { it.id == id } ?: return
        if (todo.completed) return
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("todo", "待办提醒", NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, id.hashCode(), Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_todos", true)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(id.hashCode(), NotificationCompat.Builder(context, "todo")
            .setSmallIcon(R.drawable.ic_note).setContentTitle("拾页 · 待办").setContentText(todo.text)
            .setAutoCancel(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_HIGH).build())
        if (todo.repeat != "none") TodoReminder.schedule(context, todo)
    }
}

class TodoBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        NativeStore(context).todos().forEach { TodoReminder.schedule(context, it) }
    }
}
