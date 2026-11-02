package com.example.focaaa

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Debug builds require a developer-registered Firebase App Check debug token. */
internal object FocaaaAppCheckInitializer {
    fun install(@Suppress("UNUSED_PARAMETER") context: Context) {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
    }
}
