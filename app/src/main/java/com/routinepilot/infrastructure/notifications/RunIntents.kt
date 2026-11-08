package com.routinepilot.infrastructure.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.routinepilot.MainActivity
import java.util.UUID

/**
 * Intents shared by the run notifications and the components that handle them. Open and resume use different
 * actions so Android does not treat their PendingIntents as equal.
 */
object RunIntents {
    const val ACTION_OPEN_RUN = "com.routinepilot.action.OPEN_RUN"
    const val ACTION_RESUME_RUN = "com.routinepilot.action.RESUME_RUN"
    const val ACTION_START_RUN = "com.routinepilot.action.START_RUN"
    const val ACTION_PAUSE = "com.routinepilot.action.PAUSE_RUN"
    const val ACTION_SKIP = "com.routinepilot.action.SKIP_STEP"
    const val ACTION_FINISH = "com.routinepilot.action.FINISH_RUN"
    const val EXTRA_ROUTINE_ID = "com.routinepilot.extra.ROUTINE_ID"

    private const val REQUEST_OPEN_RUN = 4101
    private const val REQUEST_RESUME_RUN = 4102
    private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun openRun(
        context: Context,
        routineId: UUID,
        resume: Boolean,
    ): PendingIntent {
        val action = if (resume) ACTION_RESUME_RUN else ACTION_OPEN_RUN
        val requestCode = if (resume) REQUEST_RESUME_RUN else REQUEST_OPEN_RUN
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(action)
                .putExtra(EXTRA_ROUTINE_ID, routineId.toString())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PENDING_FLAGS)
    }

    /**
     * An explicit intent to MainActivity for the app's own widgets. The routine id travels as an extra and is parsed
     * back to a UUID, so malformed input is ignored rather than trusted.
     */
    fun activityIntent(
        context: Context,
        routineId: UUID?,
        action: String,
    ): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(action)
            .putExtra(EXTRA_ROUTINE_ID, routineId?.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun command(
        context: Context,
        action: String,
    ): PendingIntent {
        val intent = Intent(action).setClass(context, RunActionReceiver::class.java)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PENDING_FLAGS)
    }
}
