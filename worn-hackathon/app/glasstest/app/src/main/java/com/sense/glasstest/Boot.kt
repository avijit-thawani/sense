package com.sense.glasstest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts Sense when the glasses power on, so no laptop (and no visual menu) is needed to open it. */
class Boot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("Sense", "boot: starting Sense (${intent.action})")
        context.startActivity(Intent(context, Sense::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
