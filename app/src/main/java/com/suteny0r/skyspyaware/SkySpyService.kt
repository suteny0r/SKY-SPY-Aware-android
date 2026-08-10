package com.suteny0r.skyspyaware

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that keeps MQTT collection running after the UI is
 * closed. START_STICKY means the system restarts it (and [DataRepo] resumes
 * collecting) if the process is killed. It stops entirely when the user
 * disconnects (see [DataRepo.stopCollecting]).
 */
class SkySpyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DataRepo.init(this)
        startForeground(DataRepo.FOREGROUND_NOTIF_ID, DataRepo.buildForegroundNotification())
        DataRepo.ensureCollecting(this)
        return START_STICKY
    }
}
