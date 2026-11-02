package com.example.focaaa

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

/** Direct-download releases use the package-restricted reCAPTCHA Enterprise App Check key. */
internal object FocaaaAppCheckInitializer {
    fun install(context: Context) {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            RecaptchaAppCheckProviderFactory.getInstance(context.getString(R.string.app_check_site_key))
        )
    }
}
