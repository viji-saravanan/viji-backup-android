package com.aryasubramani.vijibackup.auth.data

import com.aryasubramani.vijibackup.auth.domain.GoogleAccount

interface AuthSessionStore {
    suspend fun read(): GoogleAccount?

    suspend fun save(account: GoogleAccount)

    suspend fun clear()

    suspend fun beginProviderCleanup() {
        clear()
    }

    suspend fun isProviderCleanupPending(): Boolean = false

    suspend fun completeProviderCleanup() = Unit
}
