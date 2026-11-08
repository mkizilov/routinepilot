package com.routinepilot.infrastructure.di

import com.routinepilot.data.sync.AccountSessionManager
import com.routinepilot.data.sync.BackendSyncRemote
import com.routinepilot.data.sync.RoomLocalRecordWriter
import com.routinepilot.data.sync.RoomOutboxStore
import com.routinepilot.data.sync.SyncAccountStore
import com.routinepilot.domain.sync.AccountSessions
import com.routinepilot.domain.sync.LocalRecordWriter
import com.routinepilot.domain.sync.OutboxStore
import com.routinepilot.domain.sync.SyncCredentials
import com.routinepilot.domain.sync.SyncEngine
import com.routinepilot.domain.sync.SyncRemote
import com.routinepilot.domain.sync.SyncSessionStore
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBindingsModule {
    @Binds
    abstract fun bindCredentials(impl: SyncAccountStore): SyncCredentials

    @Binds
    abstract fun bindSessionStore(impl: SyncAccountStore): SyncSessionStore

    @Binds
    abstract fun bindRemote(impl: BackendSyncRemote): SyncRemote

    @Binds
    abstract fun bindLocalWriter(impl: RoomLocalRecordWriter): LocalRecordWriter

    @Binds
    abstract fun bindAccountSessions(impl: AccountSessionManager): AccountSessions
}

@Module
@InstallIn(SingletonComponent::class)
object SyncProvidesModule {
    @Provides
    @Singleton
    fun provideOutboxStore(database: RoutinePilotDatabase): OutboxStore = RoomOutboxStore(database.syncOutboxDao())

    /** A single engine, so every screen reads the same sync state. */
    @Provides
    @Singleton
    fun provideSyncEngine(
        outbox: OutboxStore,
        remote: SyncRemote,
        session: SyncSessionStore,
        credentials: SyncCredentials,
        writer: LocalRecordWriter,
        clock: Clock,
    ): SyncEngine = SyncEngine(outbox, remote, session, credentials, writer) { clock.millis() }
}
