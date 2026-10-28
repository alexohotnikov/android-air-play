package dev.okhotnikov.airplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the receiver with the TV, unless background mode is off. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Prefs.background(context)) AirPlayService.start(context);
    }
}
