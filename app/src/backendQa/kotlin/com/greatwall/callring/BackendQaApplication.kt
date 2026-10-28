package com.greatwall.callring

import android.app.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints

/** Separate package/process/data directory. Never changes the production application's graph. */
class BackendQaApplication : CallringApplication() {
    override fun backendConfiguration() = BackendConfiguration(
        AppearanceSettings(ThemeSelection.PRESET, "backend_qa", null, ColorMode.FOLLOW_SYSTEM),
        databaseName = "backend_qa.db", ringPreferencesName = "backend_qa_ring",
        widgetPreferencesName = "backend_qa_widgets", appearancePreferencesName = "backend_qa_appearance")
    override fun createGraph(configuration: BackendConfiguration, owner: BackupGraphOwner): BackendGraph {
        return BackendGraph(
            owner, this,
            BackendConfiguration(
                defaultAppearance =
                    AppearanceSettings(
                        ThemeSelection.PRESET,
                        "backend_qa",
                        null,
                        ColorMode.FOLLOW_SYSTEM,
                    ),
                databaseName = "backend_qa.db",
                ringPreferencesName = "backend_qa_ring",
                widgetPreferencesName = "backend_qa_widgets",
                appearancePreferencesName = "backend_qa_appearance",
            ),
            object : UiNotificationEntryPoints {
                override fun home(requestCode: Int): PendingIntent =
                    PendingIntent.getActivity(
                        this@BackendQaApplication,
                        requestCode,
                        packageManager.getLaunchIntentForPackage(packageName)!!,
                        PendingIntent.FLAG_IMMUTABLE,
                    )

                override fun playback(preview: Boolean, stopAction: PendingIntent): Notification {
                    getSystemService(NotificationManager::class.java)
                        .createNotificationChannel(
                            NotificationChannel(
                                    "backend_qa_audio",
                                    "Backend QA",
                                    NotificationManager.IMPORTANCE_LOW,
                                )
                                .apply { setSound(null, null) }
                        )
                    return Notification.Builder(this@BackendQaApplication, "backend_qa_audio")
                        .setSmallIcon(R.drawable.ic_callring)
                        .setContentTitle("backend.qa.playback")
                        .setOngoing(true)
                        .addAction(Notification.Action.Builder(null, "Stop", stopAction).build())
                        .build()
                }
            },
            object : IncomingCallUiSink {
                override fun show(request: IncomingCallUiRequest) = Unit
            },
        )
    }
}
