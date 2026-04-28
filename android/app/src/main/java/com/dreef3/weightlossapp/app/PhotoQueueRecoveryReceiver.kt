package com.dreef3.weightlossapp.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dreef3.weightlossapp.work.PendingPhotoRecoveryScheduler

class PhotoQueueRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> PendingPhotoRecoveryScheduler.schedule(context)
        }
    }
}
