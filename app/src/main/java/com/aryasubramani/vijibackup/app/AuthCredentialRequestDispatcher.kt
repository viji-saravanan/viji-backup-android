package com.aryasubramani.vijibackup.app

import com.aryasubramani.vijibackup.auth.google.GoogleSignInMode
import com.aryasubramani.vijibackup.auth.google.GoogleSignInResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class AuthCredentialRequestDispatcher(
    private val coroutineScope: CoroutineScope,
    private val signIn: suspend (GoogleSignInMode) -> GoogleSignInResult,
    private val onResult: (requestId: Long, result: GoogleSignInResult) -> Unit,
    private val onInterrupted: (requestId: Long) -> Unit,
) {
    private var activeRequestJob: Job? = null

    fun dispatch(requestId: Long, mode: GoogleSignInMode) {
        if (activeRequestJob?.isActive == true) return

        activeRequestJob = coroutineScope.launch {
            try {
                onResult(requestId, signIn(mode))
            } catch (cancellation: CancellationException) {
                onInterrupted(requestId)
                throw cancellation
            } finally {
                activeRequestJob = null
            }
        }
    }
}
