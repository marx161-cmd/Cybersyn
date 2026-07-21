package com.termux.cybersyn.app

import android.app.Application
import android.os.StrictMode
import androidx.room.Room
import com.termux.cybersyn.core.registerCoreRuntime
import com.termux.cybersyn.core.actions.registerActionMetadata
import com.termux.cybersyn.core.logging.AppLogger
import com.termux.cybersyn.core.storage.AppDatabase
import com.termux.cybersyn.core.storage.DatabaseBackupManager
import com.termux.cybersyn.core.storage.DatabaseMigrations
import com.termux.cybersyn.core.storage.PendingRestoreApplyResult
import com.termux.cybersyn.core.storage.VariableRepository
import com.termux.cybersyn.core.diagnostics.CrashLogHandler
import com.termux.cybersyn.core.engine.RunLogPruneWorker
import com.termux.cybersyn.core.engine.EngineWatchdogWorker
import com.termux.cybersyn.core.platform.AppVisibilityTracker
import com.termux.cybersyn.core.power.ShizukuPowerBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Application singleton keeps startup deterministic while Hilt is not active.
class OpenTaskerApp_NoHilt : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private var _db: AppDatabase? = null
        
        val db: AppDatabase
            get() {
                if (_db == null) {
                    throw IllegalStateException("Database not initialized.")
                }
                return requireNotNull(_db)
            }
    }

    override fun onCreate() {
        super.onCreate()
        installStrictModeInDebug()
        CrashLogHandler.install(this)
        AppVisibilityTracker.register(this)
        ShizukuPowerBackend.initialize(this)
        registerActionMetadata()
        registerCoreRuntime()
         
        if (_db == null) {
            when (val restoreResult = DatabaseBackupManager.applyPendingRestoreIfPresent(this)) {
                is PendingRestoreApplyResult.Applied -> {
                    AppLogger.info("OpenTasker", "Applied pending database restore from ${restoreResult.databaseFile.name}")
                }
                is PendingRestoreApplyResult.Failed -> {
                    AppLogger.error("OpenTasker", "Pending database restore failed", restoreResult.exception)
                }
                PendingRestoreApplyResult.NoPending -> Unit
            }

            _db = Room.databaseBuilder(
                this,
                AppDatabase::class.java,
                "opentasker.db"
            )
                .addMigrations(*DatabaseMigrations.getAllMigrations())
                .build()
        }

        applicationScope.launch {
            runCatching {
                VariableRepository(db.variableDao()).migrateLegacySensitiveVariables()
            }.onFailure { error ->
                AppLogger.error("OpenTasker", "Legacy secret migration failed", error)
            }
        }

        RunLogPruneWorker.enqueue(this)
        EngineWatchdogWorker.enqueue(this)
    }

    /**
     * In debug builds, surface accidental main-thread disk/network I/O and leaked closeables or
     * receivers/services to logcat (never crashes the app). Helps keep the automation engine's
     * work off the UI thread as the codebase evolves.
     */
    private fun installStrictModeInDebug() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
    }
}
