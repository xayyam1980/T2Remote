package app.t2remote

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // libadb's TLS (Wireless debugging) uses hidden platform Conscrypt APIs.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L") }
        }
        Prefs.init(this)
    }
}
