package com.joctaeng.jarvis.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.joctaeng.jarvis.JarvisApp
import com.joctaeng.jarvis.R
import com.joctaeng.jarvis.character.CharacterView
import com.joctaeng.jarvis.character.ComposeCharacterRenderer
import com.joctaeng.jarvis.core.model.AnimState
import com.joctaeng.jarvis.core.model.Emotion
import com.joctaeng.jarvis.device.DeviceState
import com.joctaeng.jarvis.diagnostics.Poc
import com.joctaeng.jarvis.presence.placement.Insets
import com.joctaeng.jarvis.presence.placement.NormalizedPosition
import com.joctaeng.jarvis.presence.placement.Placement
import com.joctaeng.jarvis.presence.placement.Point
import com.joctaeng.jarvis.presence.placement.Size
import com.joctaeng.jarvis.character.CharacterSync
import com.joctaeng.jarvis.chat.ChatActivity
import com.joctaeng.jarvis.settings.PlacementMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * PoC 0.1 — personagem flutuante persistente sobre qualquer app.
 *
 * Foreground service do tipo `specialUse` (obrigatório declarar tipo no Android 14+)
 * que mantém uma janela `TYPE_APPLICATION_OVERLAY` com o personagem. Registra
 * início, batimentos a cada minuto e fim, para medir se o HyperOS mata o serviço.
 */
class OverlayService : LifecycleService(), SavedStateRegistryOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var view: ComposeView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val renderer = ComposeCharacterRenderer()
    private val framesThisSecond = AtomicInteger(0)
    private var reactionStartNanos = 0L
    private val runId = UUID.randomUUID().toString().take(8)
    private val app get() = JarvisApp.from(this)
    private val diagnostics get() = app.diagnostics
    private var dragging = false
    private var lastInteractionTime = SystemClock.elapsedRealtime()
    private var hidden = false
    private var collapseJob: Job? = null
    private var engageJob: Job? = null
    private val prefs by lazy { getSharedPreferences("overlay", Context.MODE_PRIVATE) }

    override fun onCreate() {
        savedStateController.performRestore(null)
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            diagnostics.append(Poc.OVERLAY, "event" to "no_overlay_permission", "run" to runId)
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) {
            // intent nulo = o sistema recriou o serviço sozinho (START_STICKY).
            diagnostics.append(
                Poc.OVERLAY, "event" to "start", "run" to runId,
                "reason" to if (intent == null) "sticky-restart" else "user",
                "battery" to DeviceState.batteryPercent(this),
            )
            // A janela vem antes do startForeground: no Android 15, um app em segundo
            // plano só pode iniciar foreground service se já tiver um overlay visível.
            showOverlay()
            startHeartbeat()
        }
        try {
            startInForeground()
        } catch (e: Exception) {
            // Registrado para a PoC 0.1: sem foreground, o sistema pode matar o serviço.
            diagnostics.append(Poc.OVERLAY, "event" to "foreground_denied", "run" to runId, "error" to e.message)
        }
        OverlayBus.running.value = true
        updateWake()
        return START_STICKY
    }

    /** Liga/desliga a escuta do chamado conforme a opção e o que o sistema permitiu. */
    private fun updateWake() {
        if (app.settings.wakeWord && micAllowed) {
            if (wake == null) wake = WakeWordRunner(this, lifecycleScope, app, ::onWakeWord).also { it.start() }
        } else {
            wake?.stop()
            wake = null
        }
    }

    private fun onWakeWord(rest: String) {
        reactionStartNanos = System.nanoTime()
        renderer.play(AnimState.WAKING)
        renderer.setEmotion(Emotion.HAPPY, 0.8f)
        val intent = Intent(this, ChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ChatActivity.EXTRA_FROM_TAP, true)
            .putExtra(ChatActivity.EXTRA_WAKE_TEXT, rest)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            app.events.error("chamado", "não consegui abrir a conversa pelo chamado", e)
        }
    }

    override fun onDestroy() {
        wake?.stop()
        diagnostics.append(Poc.OVERLAY, "event" to "stop", "run" to runId, "battery" to DeviceState.batteryPercent(this))
        view?.let { windowManager.removeView(it) }
        view = null
        OverlayBus.running.value = false
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = view ?: return
        val pos = if (app.settings.placementMode == PlacementMode.EDGES) {
            Placement.snapToEdge(Point(params.x, params.y), windowSize(), screenSize(), insets())
        } else {
            Placement.clamp(Point(params.x, params.y), windowSize(), screenSize(), insets())
        }
        params.x = pos.x
        params.y = pos.y
        windowManager.updateViewLayout(v, params)
    }

    @Volatile private var micAllowed = false
    private var wake: WakeWordRunner? = null

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.overlay_channel_name), NotificationManager.IMPORTANCE_MIN),
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.overlay_stop), stopIntent)
            .build()
        // O tipo specialUse só existe a partir do Android 14; antes disso, sem tipo.
        val special = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        // "Oi Joca" precisa do tipo microfone (Android 14+); se o sistema negar, segue sem ele e a escuta fica desligada.
        val wantMic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && app.settings.wakeWord &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        micAllowed = false
        if (wantMic) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, special or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                micAllowed = true
                return
            } catch (e: Exception) {
                app.events.warn("chamado", "o sistema negou o microfone em segundo plano: ${e.message}")
            }
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, special)
    }

    private fun showOverlay() {
        val sizePx = dpToPx(app.settings.characterSizeDp)
        params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val saved = NormalizedPosition(prefs.getFloat("x", 1f), prefs.getFloat("y", 0.6f))
        val start = Placement.clamp(Placement.denormalize(saved, windowSize(), screenSize()), windowSize(), screenSize(), insets())
        params.x = start.x
        params.y = start.y

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                CharacterView(
                    renderer = renderer,
                    modifier = Modifier.fillMaxSize(),
                    maxFps = 30,
                    animate = true,
                    onFrame = ::onFrameDrawn,
                )
            }
            setOnTouchListener(DragAndTapListener())
        }
        windowManager.addView(composeView, params)
        view = composeView
        renderer.play(AnimState.IDLE)
        startFpsMeter()
        CharacterSync.bind(lifecycleScope, renderer, app.voice.speaking) { dragging }
        followSettings()
        bindPortalEvents()
        startIdlePortalMonitor()
    }

    /** Reage ao que acontece: emerge quando alguém fala/ouve e recolhe ao pedido ("tchau"). */
    private fun bindPortalEvents() {
        lifecycleScope.launch {
            app.voice.speaking.collect { speaking ->
                if (speaking) {
                    lastInteractionTime = SystemClock.elapsedRealtime()
                    show()
                }
            }
        }
        lifecycleScope.launch {
            OverlayBus.listening.collect { listening ->
                if (listening) {
                    lastInteractionTime = SystemClock.elapsedRealtime()
                    show()
                }
            }
        }
        lifecycleScope.launch { OverlayBus.dismissRequests.collect { hide() } }
        lifecycleScope.launch { OverlayBus.acting.collect { setActing(it) } }
    }

    private var actingSaved: Triple<Int, Int, Int>? = null

    /**
     * "Agindo na tela": vai para o canto superior esquerdo e fica pequeno, para não cobrir o app que está sendo lido;
     * ao terminar, volta exatamente para onde estava.
     */
    private fun setActing(on: Boolean) {
        val v = view ?: return
        if (on) {
            if (actingSaved != null || hidden) return
            actingSaved = Triple(params.x, params.y, params.width)
            val size = dpToPx(ACTING_SIZE_DP)
            params.width = size
            params.height = size
            params.x = dpToPx(6)
            params.y = insets().top + dpToPx(6)
        } else {
            val (x, y, w) = actingSaved ?: return
            actingSaved = null
            params.width = w
            params.height = w
            val p = Placement.clamp(Point(x, y), windowSize(), screenSize(), insets())
            params.x = p.x
            params.y = p.y
        }
        runCatching { windowManager.updateViewLayout(v, params) }
    }

    /** Recolhe depois de um tempo parado — nunca enquanto ouve, pensa, fala ou há conversa aberta. */
    private fun startIdlePortalMonitor() = lifecycleScope.launch {
        while (isActive) {
            delay(3_000)
            if (!app.settings.autoPortalDismiss || hidden || dragging) continue
            val busy = OverlayBus.sessionActive.value || OverlayBus.listening.value || app.voice.speaking.value ||
                OverlayBus.dashboardVisible.value || renderer.state == AnimState.THINKING || renderer.state == AnimState.LISTENING
            if (busy) {
                lastInteractionTime = SystemClock.elapsedRealtime()
                continue
            }
            if (SystemClock.elapsedRealtime() - lastInteractionTime > IDLE_HIDE_MILLIS) hide()
        }
    }

    /** Recolhe: o personagem some e a janela encolhe para um risquinho, que não bloqueia toques ao redor. */
    private fun hide() {
        if (hidden || view == null) return
        hidden = true
        engageJob?.cancel()
        renderer.engage(false)
        renderer.dismissToDimension()
        collapseJob = lifecycleScope.launch {
            delay(480) // deixa a animação de saída terminar
            val v = view ?: return@launch
            val cx = params.x + params.width / 2
            val bottom = params.y + params.height
            params.width = dpToPx(RISK_WIDTH_DP)
            params.height = dpToPx(RISK_HEIGHT_DP)
            val p = Placement.clamp(Point(cx - params.width / 2, bottom - params.height - dpToPx(8)), windowSize(), screenSize(), insets())
            params.x = p.x
            params.y = p.y
            windowManager.updateViewLayout(v, params)
        }
    }

    /** Volta ao tamanho normal, no mesmo lugar (pelos pés), e chega mais perto. */
    private fun show() {
        if (!hidden) return
        val v = view ?: return
        hidden = false
        collapseJob?.cancel()
        val size = dpToPx(app.settings.characterSizeDp)
        val cx = params.x + params.width / 2
        val bottom = params.y + params.height + dpToPx(8)
        params.width = size
        params.height = size
        val p = Placement.clamp(Point(cx - size / 2, bottom - size), windowSize(), screenSize(), insets())
        params.x = p.x
        params.y = p.y
        windowManager.updateViewLayout(v, params)
        lastInteractionTime = SystemClock.elapsedRealtime()
        renderer.emergeFromDimension()
        engage()
    }

    /** Chega mais perto e olha para o usuário por alguns segundos. */
    private fun engage() {
        renderer.engage(true)
        engageJob?.cancel()
        engageJob = lifecycleScope.launch {
            delay(ENGAGE_MILLIS)
            renderer.engage(false)
        }
    }

    /** Tamanho alterado em "Meu Euno" é aplicado na hora. */
    private fun followSettings() = lifecycleScope.launch {
        app.settings.version.collect {
            renderer.applyProfile(app.settings.character)
            if (!hidden && actingSaved == null) resize(dpToPx(app.settings.characterSizeDp))
        }
    }

    private fun resize(sizePx: Int) {
        val v = view ?: return
        if (sizePx == params.width) return
        // Mantém o centro do personagem no mesmo lugar ao crescer ou encolher.
        val cx = params.x + params.width / 2
        val cy = params.y + params.height / 2
        params.width = sizePx
        params.height = sizePx
        val p = Placement.clamp(Point(cx - sizePx / 2, cy - sizePx / 2), windowSize(), screenSize(), insets())
        params.x = p.x
        params.y = p.y
        windowManager.updateViewLayout(v, params)
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).roundToInt()

    private fun onFrameDrawn() {
        framesThisSecond.incrementAndGet()
        if (reactionStartNanos != 0L && renderer.state != AnimState.IDLE && renderer.state != AnimState.SLEEPING) {
            val reaction = (System.nanoTime() - reactionStartNanos) / 1_000_000
            reactionStartNanos = 0L
            OverlayBus.lastReactionMillis.value = reaction
            lifecycleScope.launch(Dispatchers.IO) {
                diagnostics.append(Poc.RENDERER, "event" to "tap_reaction", "reaction_ms" to reaction)
            }
        }
    }

    private fun startFpsMeter() = lifecycleScope.launch {
        var fpsSum = 0L
        var seconds = 0
        while (isActive) {
            delay(1_000)
            val fps = framesThisSecond.getAndSet(0)
            OverlayBus.fps.value = fps
            fpsSum += fps
            seconds++
            if (seconds == 60) {
                diagnostics.append(Poc.RENDERER, "event" to "fps_avg_60s", "fps" to fpsSum / 60f)
                fpsSum = 0
                seconds = 0
            }
        }
    }

    private fun startHeartbeat() = lifecycleScope.launch {
        while (isActive) {
            delay(60_000)
            diagnostics.append(
                Poc.OVERLAY, "event" to "heartbeat", "run" to runId,
                "battery" to DeviceState.batteryPercent(this@OverlayService),
                "uptime_ms" to SystemClock.elapsedRealtime(),
            )
        }
    }

    private fun onTap() {
        wake?.pauseNow() // solta o microfone antes de a conversa abrir e começar a ouvir
        reactionStartNanos = System.nanoTime()
        renderer.play(AnimState.WAKING)
        renderer.setEmotion(Emotion.HAPPY, 0.8f)
        val intent = Intent(this, ChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ChatActivity.EXTRA_FROM_TAP, true)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            renderer.play(AnimState.ERROR)
            diagnostics.append(Poc.TOUCH_SESSION, "event" to "launch_failed", "error" to e.message)
            return
        }
        // O HyperOS pode bloquear a abertura em silêncio (permissão "abrir janelas em
        // segundo plano"). Se a sessão não começar em 3 s, conta como falha.
        lifecycleScope.launch {
            delay(700)
            if (OverlayBus.sessionActive.value) {
                renderer.play(CharacterSync.currentState(app.voice.speaking.value))
                return@launch
            }
            delay(2_300)
            if (!OverlayBus.sessionActive.value && renderer.state == AnimState.WAKING) {
                diagnostics.append(Poc.TOUCH_SESSION, "event" to "launch_timeout")
                renderer.play(AnimState.CONFUSED)
                delay(1_500)
                renderer.play(CharacterSync.currentState(app.voice.speaking.value))
            }
        }
    }

    /** Toque longo: menu com "Abrir Meu Euno" e "Fechar o Euno por completo". */
    private fun onLongPress() {
        try {
            startActivity(com.joctaeng.jarvis.ui.QuickMenuActivity.intent(this))
        } catch (e: Exception) {
            app.events.error("app", "não consegui abrir o menu do toque longo", e)
        }
    }

    private fun onDragEnd() {
        dragging = false
        val dropped = Point(params.x, params.y)
        val snapped = when (app.settings.placementMode) {
            PlacementMode.EDGES -> Placement.snapToEdge(dropped, windowSize(), screenSize(), insets())
            PlacementMode.FREE -> Placement.clamp(dropped, windowSize(), screenSize(), insets())
        }
        params.x = snapped.x
        params.y = snapped.y
        view?.let { windowManager.updateViewLayout(it, params) }
        val normalized = Placement.normalize(snapped, windowSize(), screenSize())
        prefs.edit().putFloat("x", normalized.x).putFloat("y", normalized.y).apply()
        renderer.play(CharacterSync.currentState(app.voice.speaking.value))
    }

    private fun windowSize() = Size(params.width, params.height)

    private fun screenSize(): Size {
        val bounds = windowManager.currentWindowMetrics.bounds
        return Size(bounds.width(), bounds.height())
    }

    private fun insets(): Insets {
        val i = windowManager.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Insets(left = 0, top = i.top, right = 0, bottom = i.bottom)
    }

    /**
     * Arrastar usa coordenadas absolutas da tela (rawX/rawY): como a própria janela
     * se move com o dedo, coordenadas locais oscilariam. Dois dedos = pinça para
     * redimensionar (item 25 da especificação).
     */
    private inner class DragAndTapListener : android.view.View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private val longPressMillis = ViewConfiguration.getLongPressTimeout().toLong()
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downTime = 0L
        private var pinchStartDistance = 0f
        private var pinchStartSize = 0
        private var pinched = false

        override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    downTime = event.eventTime
                    dragging = false
                    pinched = false
                    lastInteractionTime = SystemClock.elapsedRealtime()
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2 && !hidden) {
                    pinched = true
                    dragging = false
                    pinchStartDistance = distance(event)
                    pinchStartSize = params.width
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pinched) {
                        if (event.pointerCount >= 2 && pinchStartDistance > 0f) {
                            val target = (pinchStartSize * distance(event) / pinchStartDistance).roundToInt()
                            resize(target.coerceIn(dpToPx(MIN_SIZE_DP), dpToPx(MAX_SIZE_DP)))
                        }
                        return true
                    }
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        lastInteractionTime = SystemClock.elapsedRealtime()
                        renderer.play(AnimState.DRAGGED)
                    }
                    if (dragging) {
                        val p = Placement.clamp(
                            Point(startX + dx.roundToInt(), startY + dy.roundToInt()), windowSize(), screenSize(), insets(),
                        )
                        params.x = p.x
                        params.y = p.y
                        windowManager.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> when {
                    pinched -> {
                        app.settings.characterSizeDp = (params.width / resources.displayMetrics.density).roundToInt()
                        onDragEnd()
                    }
                    dragging -> onDragEnd()
                    event.eventTime - downTime >= longPressMillis -> {
                        lastInteractionTime = SystemClock.elapsedRealtime()
                        onLongPress()
                    }
                    else -> {
                        v.performClick()
                        lastInteractionTime = SystemClock.elapsedRealtime()
                        if (hidden) {
                            // Estava recolhido: volta e já reage.
                            show()
                            renderer.play(AnimState.WAKING)
                            renderer.setEmotion(Emotion.HAPPY, 0.8f)
                        } else {
                            engage()
                            onTap()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> if (dragging || pinched) onDragEnd()
            }
            return true
        }

        private fun distance(event: MotionEvent): Float =
            hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    companion object {
        const val ACTION_STOP = "com.joctaeng.jarvis.overlay.STOP"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1
        const val MIN_SIZE_DP = 56
        const val MAX_SIZE_DP = 180
        private const val ACTING_SIZE_DP = 56
        private const val RISK_WIDTH_DP = 64
        private const val RISK_HEIGHT_DP = 28
        private const val IDLE_HIDE_MILLIS = 40_000L
        private const val ENGAGE_MILLIS = 6_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
