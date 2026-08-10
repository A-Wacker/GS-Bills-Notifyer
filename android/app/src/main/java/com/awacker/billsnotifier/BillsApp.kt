package com.awacker.billsnotifier

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.awacker.billsnotifier.data.BillsRepository
import com.awacker.billsnotifier.data.local.BillsDatabase
import com.awacker.billsnotifier.data.prefs.AppSettings
import com.awacker.billsnotifier.data.prefs.SettingsStore
import com.awacker.billsnotifier.data.remote.SheetSyncClient
import com.awacker.billsnotifier.work.DailyDigestWorker
import com.awacker.billsnotifier.work.DigestScheduler
import com.awacker.billsnotifier.work.SyncWorker
import kotlinx.coroutines.runBlocking

/**
 * Wires the app together.
 *
 * Hand-rolled rather than using a DI framework. At this size the container below is the
 * whole of it, and skipping the framework avoids an annotation processor, its Gradle
 * plugin, and the version-alignment problems that come with them. The only thing Hilt would
 * meaningfully simplify is worker injection, which [AppWorkerFactory] handles in a dozen
 * lines.
 */
class AppContainer(private val context: Context) {

    val database: BillsDatabase by lazy {
        Room.databaseBuilder(context, BillsDatabase::class.java, "bills.db")
            // No migrations to preserve yet. Once the app holds real data, replace this
            // with proper Migration objects — the schema is exported to app/schemas.
            .fallbackToDestructiveMigration()
            .build()
    }

    val settingsStore: SettingsStore by lazy { SettingsStore(context) }

    val syncClient: SheetSyncClient by lazy { SheetSyncClient() }

    val repository: BillsRepository by lazy {
        BillsRepository(dao = database.billsDao(), settings = settingsStore)
    }

    /**
     * Reads settings synchronously.
     *
     * Only for the scheduler, which runs from callbacks with no coroutine scope of their
     * own. DataStore reads from local disk, so the block is short — but do not reach for
     * this from the UI, which observes [SettingsStore.settings] instead.
     */
    fun settingsBlocking(): AppSettings = runBlocking { settingsStore.current() }
}

class BillsApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        DailyDigestWorker.ensureChannel(this)
        // Idempotent: KEEP leaves an already-pending digest alone rather than pushing it out.
        DigestScheduler.ensureScheduled(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(AppWorkerFactory(container))
            .build()

    companion object {
        fun container(context: Context): AppContainer =
            (context.applicationContext as BillsApp).container
    }
}

/** Supplies workers their dependencies, since they are constructed by WorkManager. */
class AppWorkerFactory(private val container: AppContainer) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        DailyDigestWorker::class.java.name ->
            DailyDigestWorker(appContext, workerParameters, container.repository)

        SyncWorker::class.java.name -> SyncWorker(
            appContext,
            workerParameters,
            container.repository,
            container.settingsStore,
            container.syncClient,
        )

        // Null hands the class back to WorkManager's default factory.
        else -> null
    }
}
