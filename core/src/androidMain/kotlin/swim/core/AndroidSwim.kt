package swim.core

import android.content.Context

private var application: Context? = null

/** Call once from `Application.onCreate`. Swim needs a Context for its files and its keystore. */
fun initializeSwim(context: Context) {
    application = context.applicationContext
}

internal fun androidContext(): Context = application
    ?: error("Call swim.core.initializeSwim(context) from Application.onCreate().")
