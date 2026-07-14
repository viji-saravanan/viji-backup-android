package com.aryasubramani.vijibackup.auth.data

fun interface CredentialStateClearer {
    suspend fun clear()
}
