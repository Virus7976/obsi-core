package app.obsi.core.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.obsi.core.Brand
import app.obsi.core.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the model bridge on while another app on the phone may use it. Android freezes apps in the
 * background, so the bridge needs a small notification to keep answering. It stops by itself after
 * [IDLE_MS] with no request, and at once when no app is linked.
 */
class ModelBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: BridgeServer? = null
    private lateinit var idle: IdleTimer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        idle = IdleTimer(scope, IDLE_MS) { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || ModelLinks.load(this).isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, NOTE_ID, notification(), type) }.onFailure {
            // Android may refuse when the app is not in front. Then the bridge waits for the next open.
            stopSelf()
            return START_NOT_STICKY
        }
        if (server == null) {
            val ctx = applicationContext
            val s = BridgeServer(object : BridgeEnv {
                override val app = Brand.name
                override fun ready() = OnPhone.state.value.ready
                override fun model() = OnPhone.state.value.let { st ->
                    if (st.builtInReady) "Gemini Nano" else st.file?.let { f -> PhoneModel.ofFile(f)?.title ?: f.removeSuffix(".litertlm") } ?: ""
                }
                override fun allowed(token: String) = ModelLinks.allowed(ctx, token).also { if (it) ModelLinks.markUsed(ctx, token) }
                override suspend fun ask(system: String, user: String, maxTokens: Int) = OnPhone.ask(ctx, system, user, maxTokens)
                override fun used() = idle.touch()
            })
            // The port may be taken by another Obsi app that lends the same model: nothing to do then.
            if (!s.start()) {
                _on.value = false
                stopSelf()
                return START_NOT_STICKY
            }
            server = s
            scope.launch { OnPhone.check(ctx) }
        }
        _on.value = true
        idle.touch()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        idle.cancel()
        scope.cancel()
        _on.value = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "AI model for other apps", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while an app you linked, like Obsidian, can use the AI model on this phone."
                setShowBadge(false)
            })
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, ModelBridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE) }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.obsi_ic_model)
            .setContentTitle("Lending the AI model")
            .setContentText("Apps you linked can use it. It stops after 20 minutes unused.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }


    companion object {
        const val IDLE_MS = 20 * 60 * 1000L
        private const val CHANNEL = "obsi_model_bridge"
        private const val NOTE_ID = 47391
        private const val ACTION_STOP = "app.obsi.core.STOP_MODEL_BRIDGE"
        private val _on = MutableStateFlow(false)
        /** The bridge is answering in this app. */
        val on: StateFlow<Boolean> = _on.asStateFlow()

        /** Starts the bridge when an app is linked. Call it while this app is in front. */
        fun start(c: Context) {
            if (ModelLinks.load(c).isEmpty()) return
            runCatching { c.startForegroundService(Intent(c, ModelBridgeService::class.java)) }
        }

        private var watching = false

        /**
         * Starts the bridge each time the app comes to the front, when an app is linked. Android lets
         * a foreground service start only then, and the notification shows the person it is on.
         */
        fun watch(app: android.app.Application) {
            if (watching) return
            watching = true
            app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: android.app.Activity) {
                    if (!_on.value) start(activity)
                }
                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityStarted(activity: android.app.Activity) {}
                override fun onActivityPaused(activity: android.app.Activity) {}
                override fun onActivityStopped(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            })
        }

        fun stop(c: Context) {
            runCatching { c.startService(Intent(c, ModelBridgeService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
