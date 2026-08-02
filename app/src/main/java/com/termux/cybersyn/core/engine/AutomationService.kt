package com.termux.cybersyn.core.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.termux.cybersyn.app.MainActivity
import com.termux.cybersyn.app.CybersynApp_NoHilt
import com.termux.cybersyn.automation.app.AppUsageMonitor
import com.termux.cybersyn.automation.network.ConnectivityMonitor
import com.termux.cybersyn.automation.network.WiFiNetworkMonitor
import com.termux.cybersyn.automation.sensor.ShakeDetector
import com.termux.cybersyn.automation.scheduler.TimeEventScheduler
import com.termux.cybersyn.core.contexts.ExternalTriggerContextEvents
import com.termux.cybersyn.core.evdev.KeyHijackController
import com.termux.cybersyn.core.evdev.KeyTriggerConfig
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.contexts.BluetoothContextEvents
import com.termux.cybersyn.core.contexts.ContextSourceRegistry
import com.termux.cybersyn.core.contexts.LogcatContextSource
import com.termux.cybersyn.core.contexts.BootContextEvents
import com.termux.cybersyn.core.contexts.CameraMicContextEvents
import com.termux.cybersyn.core.contexts.PackageContextEvents
import com.termux.cybersyn.core.contexts.PluginConditionSubscription
import com.termux.cybersyn.core.contexts.PluginConditionSubscriptions
import com.termux.cybersyn.core.contexts.TimeContextEvents
import com.termux.cybersyn.core.contexts.TimeTickContextEvents
import com.termux.cybersyn.core.model.AutomationMode
import com.termux.cybersyn.core.mqtt.MqttBridge
import com.termux.cybersyn.core.model.Profile
import com.termux.cybersyn.core.model.Task
import com.termux.cybersyn.core.platform.AudioForegroundServiceEligibility
import com.termux.cybersyn.core.storage.RunLogRetentionSettings
import com.termux.cybersyn.core.storage.minimumTimestamp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.termux.cybersyn.core.storage.ProfileEntity
import java.util.ArrayDeque
import java.util.Collections

/**
 * Foreground service that hosts the trigger engine.
 *
 * Subscribes to context sources, evaluates active profiles, and dispatches tasks.
 * A foreground service is required on modern Android (Doze/App Standby) for the
 * automation engine to evaluate triggers reliably.
 */
class AutomationService : Service() {
    private val job = Job()
    // Engine orchestration (context matching, dispatch) runs off the main thread. Room suspend DAOs
    // dispatch to Room's own executor, and task execution hops to Dispatchers.IO inside
    // executeAndLogTask, so no automation work blocks the UI thread.
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val db by lazy { CybersynApp_NoHilt.db }
    private val timeEventScheduler by lazy { TimeEventScheduler(this) }
    private val wifiNetworkMonitor by lazy { WiFiNetworkMonitor(this) }
    private val connectivityMonitor by lazy { ConnectivityMonitor(this) }
    private val appUsageMonitor by lazy { AppUsageMonitor(this) }
    private val shakeDetector by lazy { ShakeDetector(this) }
    private val runLogRetentionSettings by lazy { RunLogRetentionSettings(this) }
    private val engineHeartbeatStore by lazy { EngineHeartbeatStore(this) }
    private val logcatContextSource by lazy { LogcatContextSource() }
    private val contextMonitorLifecycle by lazy {
        ContextMonitorLifecycle(
            mapOf(
                ContextMonitor.WIFI to ContextMonitorHandle(
                    start = { wifiNetworkMonitor.start() },
                    stop = { wifiNetworkMonitor.stop() },
                ),
                ContextMonitor.CONNECTIVITY to ContextMonitorHandle(
                    start = { connectivityMonitor.start() },
                    stop = { connectivityMonitor.stop() },
                ),
                ContextMonitor.APP_USAGE to ContextMonitorHandle(
                    start = { appUsageMonitor.start(scope) },
                    stop = { appUsageMonitor.stop() },
                ),
                ContextMonitor.SHAKE to ContextMonitorHandle(
                    start = { shakeDetector.start() },
                    stop = { shakeDetector.stop() },
                ),
                ContextMonitor.CAMERA_MIC to ContextMonitorHandle(
                    start = { CameraMicContextEvents.start(this) },
                    stop = { CameraMicContextEvents.stop(this) },
                ),
                ContextMonitor.PACKAGE_EVENTS to ContextMonitorHandle(
                    start = {
                        ContextCompat.registerReceiver(
                            this,
                            PackageContextEvents.receiver,
                            PackageContextEvents.intentFilter(),
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        true
                    },
                    stop = { unregisterReceiver(PackageContextEvents.receiver) },
                ),
                ContextMonitor.BLUETOOTH_EVENTS to ContextMonitorHandle(
                    start = {
                        ContextCompat.registerReceiver(
                            this,
                            BluetoothContextEvents.receiver,
                            BluetoothContextEvents.intentFilter(),
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        true
                    },
                    stop = { unregisterReceiver(BluetoothContextEvents.receiver) },
                ),
                ContextMonitor.LOGCAT to ContextMonitorHandle(
                    start = {
                        ContextSourceRegistry.register(logcatContextSource)
                        true
                    },
                    stop = {
                        // Reference-counted shutdown normally handles this, but reconcile() can
                        // toggle this monitor off/on across a flaky profile reload; forceStop()
                        // guarantees the reader process actually dies instead of leaking a
                        // `su -c logcat` child every time that happens.
                        logcatContextSource.forceStop()
                    },
                ),
            ),
        )
    }
    
    private val cooldownStore by lazy { CooldownStore(this) }
    private val matchers = Collections.synchronizedMap(mutableMapOf<Long, ProfileMatcher>())
    private val profileCooldowns = Collections.synchronizedMap(mutableMapOf<Long, Long>()) // profileId -> cooldownUntilMs
    private val matcherJobs = Collections.synchronizedMap(mutableMapOf<Long, Job>()) // Track jobs for cleanup
    private val profileTaskJobs = Collections.synchronizedMap(mutableMapOf<Long, Job>())
    private val queuedProfileTasks = Collections.synchronizedMap(mutableMapOf<Long, ArrayDeque<Task>>())
    private val profileReloadMutex = Mutex()
    @Volatile private var lastRunLogPruneAt = 0L
    @Volatile private var audioForegroundServiceEligibility = AudioForegroundServiceEligibility.BACKGROUND_STARTED
    @Volatile private var engineLoaded = false

    /** Completes when the stray-logcat-reader sweep has finished. See onCreate. */
    private val strayLogcatSweep = CompletableDeferred<Unit>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Register the logcat source before anything can reload profiles.
        //
        // ContextMonitor.LOGCAT's start() *is* this registration, but reconcile() runs at
        // the END of reloadProfiles(), after the matchers are built -- so on a cold start
        // the source is not in the registry yet, no LOGCAT matcher can subscribe, and the
        // reader never spawns. It only appeared to work because reloadProfiles() used to
        // run every minute: the second pass found the source registered by the first.
        // With the per-minute rebuild gone, LOGCAT profiles stayed dead until something
        // else forced a reload (observed live 2026-08-02: no reader process at all).
        //
        // Registering is not starting: the reader is reference-counted on subscribers, so
        // with no LOGCAT profile this costs nothing, and the monitor's start() stays
        // correct as an idempotent re-registration.
        ContextSourceRegistry.register(logcatContextSource)
        // A prior instance that crashed/was killed (not onDestroy()'d) leaves its su-spawned
        // `logcat` reader orphaned under init, since that child process's lifetime was never
        // tied to this service's. Sweep those before this instance registers its own reader.
        //
        // reloadProfiles() waits on strayLogcatSweep before starting any matcher. The sweep
        // matches by cmdline, so it cannot tell this generation's reader from a previous
        // one: left unsynchronised it races the reader that reloadProfiles() has just
        // started and kills it, leaving every LOGCAT profile silently dead with no reader
        // process at all. Observed live 2026-08-02 -- the su spawn here takes long enough
        // to routinely lose that race.
        scope.launch(Dispatchers.IO) {
            try {
                killStrayLogcatReaders()
            } finally {
                strayLogcatSweep.complete(Unit)
            }
        }
        startForegroundCompat()
        startSystemContextSources()
        startKeyHijack()
        timeEventScheduler.scheduleNextMinute()
        engineHeartbeatStore.recordAlive()
        scope.launch {
            while (isActive) {
                engineHeartbeatStore.recordAlive()
                delay(ENGINE_HEARTBEAT_INTERVAL_MS)
            }
        }
        profileCooldowns.putAll(cooldownStore.loadAll())
        scope.launch { pruneRunLogs(force = true) }
        observeProfileRegistry()
    }

    /**
     * Eagerly start context sources that provide always-on MQTT-based services —
     * media session bridge, clipboard sync, and incoming file offers. These run
     * regardless of whether any profile references their type, because their side
     * effects (MediaSession, clipboard sync, saved files/album art) are the whole point.
     */
    private fun startSystemContextSources() {
        // Collect from the flow to keep the source alive in this scope.
        // channelFlow blocks in awaitClose{} until the scope is cancelled.
        ContextSourceRegistry.get("media")?.let { source ->
            scope.launch { source.events(this@AutomationService).collect {} }
        }
        ContextSourceRegistry.get("clipboard")?.let { source ->
            scope.launch { source.events(this@AutomationService).collect {} }
        }
        ContextSourceRegistry.get("file_offer")?.let { source ->
            scope.launch { source.events(this@AutomationService).collect {} }
        }
    }

    /**
     * Keeps the running engine in sync with the profiles table. Creating, editing, enabling,
     * disabling, or deleting a profile changes the reconcile signature and triggers a matcher and
     * plugin-subscription rebuild without a service restart. The initial snapshot is dropped because
     * [onStartCommand] performs the first load, so the initial snapshot is dropped and only
     * subsequent engine-relevant changes reconcile. Identical signatures are coalesced by
     * [distinctUntilChanged], and [reloadProfiles] is idempotent, so rapid edits are safe.
     *
     * In-flight task runs are intentionally left running — reconciling re-subscribes matchers but
     * does not cancel a task that is already executing.
     */
    private fun observeProfileRegistry() {
        scope.launch {
            db.profileDao().getAllAsFlow()
                .map { profileRegistrySignature(it) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    AppLogger.info(TAG, "Profile registry changed; reconciling matchers")
                    reloadProfiles()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_STARTED_FROM_VISIBLE_UI, false) == true) {
            audioForegroundServiceEligibility = AudioForegroundServiceEligibility.WHILE_IN_USE
        }
        val bootCompletedTrigger = intent?.action == ACTION_BOOT_COMPLETED_TRIGGER
        val timeTickTrigger = intent?.action == ACTION_TIME_TICK_TRIGGER
        val reloadRequest = intent?.action == ACTION_RELOAD_PROFILES
        timeEventScheduler.scheduleNextMinute()
        engineHeartbeatStore.recordAlive()
        scope.launch {
            // A time tick must NOT rebuild the engine. Reloading tears down every matcher
            // and re-collects every context flow, which respawns the root logcat reader
            // and the MQTT subscriber -- 1440x/day, each one a spawn/kill race that leaks
            // root children (a leaked reader was found live on 2026-08-02), plus a 1-2s
            // window where LOGCAT and EVENT profiles observe nothing.
            //
            // 6a32638 introduced the unconditional reload "for import sync", inverting
            // the doze fix from c263f0f (`!timeTickTrigger || !engineLoaded`). Import
            // sync now has its own action, and observeProfileRegistry() reconciles on any
            // DB change anyway, so the tick has no reason to reload.
            if (reloadRequest || !engineLoaded) {
                reloadProfiles()
                // Key triggers are config, same as profiles — pick up edits to
                // key_triggers.json on the same explicit reload.
                if (reloadRequest) registerKeyTriggers()
            }
            if (bootCompletedTrigger) {
                BootContextEvents.publishBootCompleted()
            }
            if (timeTickTrigger) {
                TimeContextEvents.publish()
                // Periodic profiles (watchdogs, log shipping) run off this pulse. TIME is
                // a level context and activates only on its false->true edge, so before
                // this existed they only appeared periodic because reloadProfiles() ran
                // every tick and rebuilt their matchers.
                TimeTickContextEvents.publish()
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLogger.warn(TAG, "Foreground service timeout (startId=$startId, fgsType=$fgsType); stopping cleanly")
        engineHeartbeatStore.recordStopped()
        timeEventScheduler.scheduleRecovery()
        scope.launch {
            val entry = com.termux.cybersyn.core.storage.RunLogEntity(
                taskId = 0,
                taskName = "AutomationService",
                timestamp = System.currentTimeMillis(),
                durationMs = 0,
                success = false,
                message = "Service stopped by system foreground-service timeout",
                source = "system",
                sourceLabel = "FGS timeout",
            )
            runCatching { db.runLogDao().insert(entry) }
        }
        stopSelf(startId)
    }

    /**
     * Start the evdev key-hijack helper and feed it IME visibility.
     *
     * SpectreBoard publishes spectreboard/ime_shown on transitions only and not retained,
     * so the current state is seeded from the system first -- otherwise a service start
     * while the keyboard is already up leaves the helper in the wrong mode until the next
     * show/hide, which silently routes the gyro keys to the volume slider.
     */
    private fun startKeyHijack() {
        KeyHijackController.appContext = applicationContext
        registerKeyTriggers()
        KeyHijackController.start(applicationContext)
        scope.launch(Dispatchers.IO) {
            // Seed before the subscription so a start with the keyboard already up isn't
            // stuck in the wrong mode. SpectreBoard publishes ime_shown retained, so the
            // subscribe below normally delivers the true state within milliseconds; the
            // dumpsys query is the belt-and-braces path for a broker that is unreachable
            // at start (which is exactly when the retained message can't arrive).
            KeyHijackController.imeShown = queryImeShown()
            MqttBridge.subscribe(applicationContext, "spectreboard/ime_shown").collect { msg ->
                KeyHijackController.imeShown = msg.payload.trim().equals("ON", ignoreCase = true)
            }
        }
    }

    /**
     * Publish classified hardware-key triggers as `external_trigger` events.
     *
     * This is the whole seam between the vendored Key Mapper input layer and Cybersyn's
     * engine: the evdev side classifies presses (short/long/double, chords) and names a
     * trigger id; everything downstream is an ordinary EVENT profile, matched on
     * `event=external_trigger` + `trigger=<id>`, exactly like the Quick Tap stub. No
     * task dispatch logic lives in the input layer.
     */
    private fun registerKeyTriggers() {
        val triggers = KeyTriggerConfig.load()
        KeyHijackController.setTriggers(triggers) { trigger ->
            AppLogger.info(TAG, "Key trigger '${trigger.id}' matched")
            ExternalTriggerContextEvents.publishTrigger(
                triggerName = trigger.id,
                sourcePackage = packageName,
                source = SOURCE_HARDWARE_KEY,
            )
        }
        if (triggers.isNotEmpty()) {
            AppLogger.info(TAG, "Registered ${triggers.size} key trigger(s): ${triggers.joinToString { it.id }}")
        }
    }

    /**
     * Current IME visibility, from the system's own input-method state.
     *
     * NOT `InputMethodManager.isAcceptingText`: that reflects *this process's* input
     * connection, and a background Service never has a served view, so it returns false
     * unconditionally — a seed that always reads "keyboard hidden" is worse than none,
     * because it looks like it works. `dumpsys input_method` is the system-wide truth.
     * Blocking (runs on Dispatchers.IO from the caller).
     */
    private fun queryImeShown(): Boolean = runCatching {
        val proc = ProcessBuilder("su", "-c", "dumpsys input_method")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        // mInputShown is the field PhoneWindowManager/IMMS keep for "IME window visible".
        Regex("""mInputShown=(true|false)""").find(out)?.groupValues?.get(1) == "true"
    }.onFailure { AppLogger.warn(TAG, "IME state query failed; assuming hidden", it) }
        .getOrDefault(false)

    override fun onDestroy() {
        KeyHijackController.stop()
        val matcherJobSnapshot = matcherJobs.values.toList()
        val taskJobSnapshot = profileTaskJobs.values.toList()
        matcherJobSnapshot.forEach { it.cancel() }
        taskJobSnapshot.forEach { it.cancel() }
        matcherJobs.clear()
        matchers.clear()
        profileCooldowns.clear()
        profileTaskJobs.clear()
        queuedProfileTasks.clear()
        engineHeartbeatStore.recordStopped()
        PluginConditionSubscriptions.clear()
        job.cancel()
        logContextMonitorTransition(contextMonitorLifecycle.stopAll())
        super.onDestroy()
    }

    private fun killStrayLogcatReaders() {
        runCatching {
            // Bracket trick: the pattern text appears in this sweep's own su/sh wrapper
            // cmdline, and pkill spares only itself, not its shell. 'threadtim[e]' matches
            // the readers but not the wrapper carrying the literal pattern.
            ProcessBuilder("su", "-c", "pkill -f 'logcat -v threadtim[e]'").start().waitFor()
        }.onFailure { AppLogger.warn(TAG, "Failed to sweep stray logcat readers", it) }
    }

    private suspend fun reloadProfiles() = profileReloadMutex.withLock {
        // Never register matchers while the stray-reader sweep is still in flight: it
        // matches readers by cmdline and would kill the one a LOGCAT matcher is about to
        // start. See onCreate.
        strayLogcatSweep.await()
        val oldJobs = matcherJobs.values.toList()
        matcherJobs.clear()
        matchers.clear()
        oldJobs.forEach { it.cancel() }
        oldJobs.joinAll()

        val profileEntities = db.profileDao().getAllEnabled()
        val profiles = profileEntities.mapNotNull { entity ->
            val decoded = entity.toDomainDecodeResult()
            decoded.issue?.let { issue ->
                AppLogger.error(
                    TAG,
                    "Profile ${entity.id} is corrupt and was not registered: ${issue.message}",
                )
                return@mapNotNull null
            }
            decoded.value
        }
        val activeIds = profiles.map { it.id }.toSet()
        cooldownStore.pruneDeleted(activeIds)
        synchronized(queuedProfileTasks) {
            // Slots are keyed +id for enter tasks and -id for exit tasks; prune both.
            queuedProfileTasks.keys.removeAll { kotlin.math.abs(it) !in activeIds }
        }
        registerPluginSubscriptions(profiles)
        for (domain in profiles) {
            val matcher = ProfileMatcher(this, domain)

            val matcherJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    matcher.stateChanges().collect { change ->
                        try {
                            when (change) {
                                is ProfileStateChange.Activated -> onProfileActivated(domain)
                                is ProfileStateChange.Deactivated -> onProfileDeactivated(domain)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLogger.error(TAG, "Failed handling state change for ${domain.name}", e)
                            recordMatcherError("Failed handling state change for ${domain.name}", e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.error(TAG, "Profile matcher stopped for ${domain.name}", e)
                    recordMatcherError("Profile matcher stopped for ${domain.name}", e)
                }
            }
            
            matcherJobs[domain.id] = matcherJob
            matchers[domain.id] = matcher
        }
        val subscriptionsReady = withTimeoutOrNull(MONITOR_SUBSCRIPTION_TIMEOUT_MS) {
            matchers.values.toList().forEach { it.awaitMonitorSubscriptions() }
            true
        } == true
        if (!subscriptionsReady) {
            AppLogger.warn(TAG, "Context monitor subscription barrier timed out; starting required sources in degraded mode")
        }
        logContextMonitorTransition(contextMonitorLifecycle.reconcile(profiles))
        engineLoaded = true
    }

    private fun logContextMonitorTransition(transition: ContextMonitorTransition) {
        val counts = contextMonitorLifecycle.currentReferenceCounts()
        transition.started.forEach { monitor ->
            AppLogger.info(TAG, "Context monitor $monitor started for ${counts.getOrDefault(monitor, 0)} enabled profile(s)")
        }
        transition.stopped.forEach { monitor ->
            AppLogger.info(TAG, "Context monitor $monitor stopped after its final enabled profile was removed")
        }
        transition.failedToStart.forEach { monitor ->
            AppLogger.warn(TAG, "Context monitor $monitor could not start; it will retry on the next profile reconcile")
        }
        transition.failedToStop.forEach { monitor ->
            AppLogger.warn(TAG, "Context monitor $monitor could not stop cleanly")
        }
    }

    private fun registerPluginSubscriptions(profiles: List<Profile>) {
        val subs = profiles.flatMap { profile ->
            profile.contexts
                .filter { it.type == com.termux.cybersyn.core.model.ContextType.PLUGIN }
                .mapNotNull { spec ->
                    val pkg = spec.config["package"]?.trim().orEmpty()
                    if (pkg.isBlank()) return@mapNotNull null
                    PluginConditionSubscription(
                        packageName = pkg,
                        bundleJson = spec.config["bundleJson"]?.trim().orEmpty().ifBlank { "{}" },
                        timeoutMs = spec.config["timeoutMs"]?.toLongOrNull()?.coerceIn(1_000, 30_000) ?: 5_000,
                    )
                }
        }.toSet()
        PluginConditionSubscriptions.replaceAll(subs)
    }

    private suspend fun onProfileActivated(profile: com.termux.cybersyn.core.model.Profile) {
        if (profile.enterTaskId <= 0) return

        val task = db.taskDao().getById(profile.enterTaskId)
        if (task == null) {
            AppLogger.warn(TAG, "Enter task ${profile.enterTaskId} not found for profile ${profile.name}")
            return
        }
        val decoded = task.toDomainDecodeResult()
        if (decoded.issue != null) {
            AppLogger.error(TAG, "Enter task ${profile.enterTaskId} is corrupt for profile ${profile.name}: ${decoded.issue.message}")
            logProfileSkippedRun(profile, decoded.value, "Enter task data is corrupt (${decoded.issue.fieldName}); recover it before it can run.")
            return
        }
        dispatchTask(profile, decoded.value, isExit = false)
    }

    private suspend fun onProfileDeactivated(profile: com.termux.cybersyn.core.model.Profile) {
        if (profile.exitTaskId == null || profile.exitTaskId <= 0) return
        val task = db.taskDao().getById(profile.exitTaskId)
        if (task == null) {
            AppLogger.warn(TAG, "Exit task ${profile.exitTaskId} not found for profile ${profile.name}")
            return
        }
        val decoded = task.toDomainDecodeResult()
        if (decoded.issue != null) {
            AppLogger.error(TAG, "Exit task ${profile.exitTaskId} is corrupt for profile ${profile.name}: ${decoded.issue.message}")
            logProfileSkippedRun(profile, decoded.value, "Exit task data is corrupt (${decoded.issue.fieldName}); recover it before it can run.")
            return
        }
        dispatchTask(profile, decoded.value, isExit = true)
    }

    /**
     * Enter and exit tasks are distinct execution units: they use separate job slots (so a
     * still-running enter task never suppresses or gets cancelled by the exit task) and only
     * enter dispatch consumes the profile cooldown — cooldown gates re-triggering, not the
     * cleanup work users rely on when a profile deactivates.
     */
    private fun dispatchTask(
        profile: Profile,
        task: Task,
        isExit: Boolean,
    ) {
        val slot = taskSlotKey(profile.id, isExit)
        when (profile.automationMode) {
            AutomationMode.SINGLE -> {
                if (profileTaskJobs[slot]?.isActive == true) {
                    AppLogger.info(TAG, "Profile ${profile.id} already running; SINGLE mode skipped retrigger")
                    logProfileSkippedRun(profile, task, "Profile is already running in SINGLE mode.")
                    return
                }
                if (!isExit) {
                    val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                    if (!reservation.accepted) {
                        logCooldownSkip(profile, task, reservation.remainingMs)
                        return
                    }
                }
                profileTaskJobs[slot] = launchTrackedTask(profile, slot, task)
            }

            AutomationMode.RESTART -> {
                if (!isExit) {
                    val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                    if (!reservation.accepted) {
                        logCooldownSkip(profile, task, reservation.remainingMs)
                        return
                    }
                }
                profileTaskJobs[slot]?.cancel()
                profileTaskJobs[slot] = launchTrackedTask(profile, slot, task)
            }

            AutomationMode.QUEUED -> {
                if (!isExit) {
                    val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                    if (!reservation.accepted) {
                        logCooldownSkip(profile, task, reservation.remainingMs)
                        return
                    }
                }
                // The queue check, enqueue, and the consumer's drain-or-deregister decision all
                // synchronize on queuedProfileTasks, so a task can never be enqueued into a queue
                // whose consumer has already decided to exit (which would strand it unrun).
                val outcome = synchronized(queuedProfileTasks) {
                    if (profileTaskJobs[slot]?.isActive == true) {
                        val queue = queuedProfileTasks.getOrPut(slot) { ArrayDeque() }
                        if (queue.size >= MAX_QUEUED_TASKS) {
                            QueueOutcome.FULL
                        } else {
                            queue.add(task)
                            QueueOutcome.QUEUED
                        }
                    } else {
                        QueueOutcome.START
                    }
                }
                when (outcome) {
                    QueueOutcome.FULL -> {
                        AppLogger.warn(TAG, "Profile ${profile.id} queue full ($MAX_QUEUED_TASKS), dropping retrigger")
                        logProfileSkippedRun(profile, task, "Task queue is full ($MAX_QUEUED_TASKS pending).")
                    }
                    QueueOutcome.QUEUED -> AppLogger.info(TAG, "Profile ${profile.id} queued retrigger")
                    QueueOutcome.START -> profileTaskJobs[slot] = launchQueuedTasks(profile, slot, task)
                }
            }

            AutomationMode.PARALLEL -> {
                if (!isExit) {
                    val reservation = reserveCooldown(profile.id, profile.cooldownSec)
                    if (!reservation.accepted) {
                        logCooldownSkip(profile, task, reservation.remainingMs)
                        return
                    }
                }
                scope.launch { runTask(task, profile) }
            }
        }
    }

    /** Profile ids are positive Room autogenerated keys, so -id is a collision-free exit slot. */
    private fun taskSlotKey(profileId: Long, isExit: Boolean): Long = if (isExit) -profileId else profileId

    private enum class QueueOutcome { START, QUEUED, FULL }

    private fun launchTrackedTask(profile: Profile, slot: Long, task: Task): Job =
        scope.launch(start = CoroutineStart.DEFAULT) {
            val thisJob = currentCoroutineContext()[Job]
            try {
                runTask(task, profile)
            } finally {
                synchronized(profileTaskJobs) {
                    if (profileTaskJobs[slot] == thisJob) {
                        profileTaskJobs.remove(slot)
                    }
                }
            }
        }

    private fun launchQueuedTasks(profile: Profile, slot: Long, firstTask: Task): Job =
        scope.launch(start = CoroutineStart.DEFAULT) {
            val thisJob = currentCoroutineContext()[Job]
            var nextTask: Task? = firstTask
            try {
                while (isActive && nextTask != null) {
                    runTask(requireNotNull(nextTask), profile)
                    nextTask = synchronized(queuedProfileTasks) {
                        val polled = queuedProfileTasks[slot]?.poll()
                        if (polled == null) {
                            // Deregister inside the queue lock so the producer either sees an
                            // active consumer (and enqueues into a queue that will be drained)
                            // or no consumer at all (and starts a fresh one) — never a consumer
                            // that has already decided to exit.
                            queuedProfileTasks.remove(slot)
                            synchronized(profileTaskJobs) {
                                if (profileTaskJobs[slot] == thisJob) {
                                    profileTaskJobs.remove(slot)
                                }
                            }
                        } else if (queuedProfileTasks[slot]?.isEmpty() == true) {
                            queuedProfileTasks.remove(slot)
                        }
                        polled
                    }
                }
            } finally {
                synchronized(queuedProfileTasks) {
                    synchronized(profileTaskJobs) {
                        if (profileTaskJobs[slot] == thisJob) {
                            profileTaskJobs.remove(slot)
                        }
                    }
                }
            }
        }

    private suspend fun runTask(
        task: Task,
        profile: Profile,
    ) {
        val result = executeAndLogTask(
            appContext = this,
            db = db,
            task = task,
            source = "Profile: ${profile.name}",
            metadata = profileRunMetadata(profile),
            audioForegroundService = audioForegroundServiceEligibility,
        )
        if (result.logInserted) {
            pruneRunLogs(force = false)
        }
    }

    /**
     * A cooldown skip is the cooldown doing its job, so it is logged but NOT written to
     * run_logs. Writing it produced a failure row on every suppressed pulse: LogShipTimer
     * (1-minute context, 300s cooldown) buried the table under 4 rows per real run --
     * 265 skips against 50 runs in a single day -- which is what made genuine failures
     * unfindable. Skips that ARE anomalies (corrupt task data, queue full, SINGLE-mode
     * collision) still get their row.
     */
    private fun logCooldownSkip(profile: Profile, task: Task, remainingMs: Long) {
        AppLogger.debug(
            TAG,
            "Profile ${profile.name} suppressed by cooldown (${formatRemainingCooldown(remainingMs)} left)",
        )
    }

    private fun logProfileSkippedRun(profile: Profile, task: Task, reason: String) {
        scope.launch {
            val inserted = logSkippedRun(
                db = db,
                task = task,
                source = "Profile: ${profile.name}",
                reason = reason,
                metadata = profileRunMetadata(profile),
            )
            if (inserted) pruneRunLogs(force = false)
        }
    }

    private suspend fun pruneRunLogs(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRunLogPruneAt < RUN_LOG_PRUNE_INTERVAL_MS) return

        val policy = runLogRetentionSettings.load()
        runCatching {
            db.runLogDao().pruneRetention(
                maxEntries = policy.maxEntries,
                minimumTimestamp = policy.minimumTimestamp(now),
            )
        }
            .onSuccess { deleted ->
                lastRunLogPruneAt = now
                if (deleted > 0) {
                    AppLogger.info(TAG, "Pruned $deleted old run log entries")
                }
            }
            .onFailure { error ->
                AppLogger.error(TAG, "Failed to prune run logs", error)
            }
    }

    private fun reserveCooldown(profileId: Long, cooldownSec: Int): CooldownReservation {
        val now = System.currentTimeMillis()
        synchronized(profileCooldowns) {
            val cooldownUntil = profileCooldowns[profileId] ?: 0
            if (now < cooldownUntil) {
                AppLogger.info(TAG, "Profile $profileId on cooldown, skipping")
                return CooldownReservation(accepted = false, remainingMs = cooldownUntil - now)
            }
            if (cooldownSec > 0) {
                val deadline = now + (cooldownSec * 1000L)
                profileCooldowns[profileId] = deadline
                cooldownStore.set(profileId, deadline)
            }
            return CooldownReservation(accepted = true)
        }
    }

    private fun profileRunMetadata(profile: Profile): List<String> = buildList {
        add("Profile ID: ${profile.id}")
        add("Mode: ${profile.automationMode.name.lowercase()}")
        if (profile.cooldownSec > 0) add("Cooldown: ${profile.cooldownSec}s")
    }

    private fun formatRemainingCooldown(remainingMs: Long): String {
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs).coerceAtLeast(1)
        return if (seconds == 1L) "1 second" else "$seconds seconds"
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "Cybersyn engine", NotificationManager.IMPORTANCE_MIN)
        nm.createNotificationChannel(channel)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Cybersyn is running")
            .setContentText("Tap to open automation status")
            .setSmallIcon(com.termux.cybersyn.app.R.drawable.ic_notification)
            .setColor(0xFF00A478.toInt())
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        val activeTypes = foregroundServiceTypes()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, activeTypes)
        } else {
            startForeground(NOTIF_ID, n)
        }
        engineHeartbeatStore.recordAlive(foregroundServiceTypes = activeTypes)
    }

    private fun foregroundServiceTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (hasBackgroundLocationForegroundServicePrerequisites()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun hasBackgroundLocationForegroundServicePrerequisites(): Boolean {
        if (!hasAnyLocationPermission()) return false
        if (Build.VERSION.SDK_INT >= 29 && !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return false
        val locationManager = getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) {
            locationManager.isLocationEnabled
        } else {
            runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
                runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        }
    }

    private fun hasAnyLocationPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun recordMatcherError(message: String, error: Throwable) {
        val detail = error.message?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
        engineHeartbeatStore.recordMatcherError(message + detail)
    }

    companion object {
        private const val TAG = "AutomationService"
        const val ACTION_BOOT_COMPLETED_TRIGGER = "com.termux.cybersyn.action.BOOT_COMPLETED_TRIGGER"
        const val ACTION_TIME_TICK_TRIGGER = "com.termux.cybersyn.action.TIME_TICK_TRIGGER"

        /**
         * Force a full matcher rebuild. Used by CLI bundle import, which writes profiles
         * and needs them live immediately. Deliberately separate from the per-minute time
         * tick: a rebuild is expensive and racy (see onStartCommand), so it happens on a
         * real config change, not on a clock edge.
         */
        const val ACTION_RELOAD_PROFILES = "com.termux.cybersyn.action.RELOAD_PROFILES"
        const val EXTRA_STARTED_FROM_VISIBLE_UI = "com.termux.cybersyn.extra.STARTED_FROM_VISIBLE_UI"

        /** `source` reported on external_trigger events raised by a grabbed hardware key. */
        const val SOURCE_HARDWARE_KEY = "hardware_key"
        private const val CHANNEL = "opentasker.engine"
        private const val NOTIF_ID = 1001
        private const val MAX_QUEUED_TASKS = 50
        private const val MONITOR_SUBSCRIPTION_TIMEOUT_MS = 2_000L
        private const val ENGINE_HEARTBEAT_INTERVAL_MS = 60_000L
        private val RUN_LOG_PRUNE_INTERVAL_MS = TimeUnit.HOURS.toMillis(1)
    }
}

/**
 * Reconcile signature for the running profile registry. Two profile-table snapshots produce the
 * same signature when nothing the engine depends on has changed, so purely cosmetic edits (name,
 * group) do not thrash matcher rebuilds while enable/disable, context, task-wiring, mode, and
 * cooldown changes do. Disabled profiles are excluded because the engine only runs enabled ones.
 */
internal fun profileRegistrySignature(profiles: List<ProfileEntity>): List<String> =
    profiles.asSequence()
        .filter { it.enabled && !it.requiresRiskAcknowledgement }
        .sortedBy { it.id }
        .map { p ->
            listOf(
                p.id,
                p.enterTaskId,
                p.exitTaskId,
                p.cooldownSec,
                p.automationMode,
                p.contextsJson,
            ).joinToString("|")
        }
        .toList()

private data class CooldownReservation(
    val accepted: Boolean,
    val remainingMs: Long = 0,
)
