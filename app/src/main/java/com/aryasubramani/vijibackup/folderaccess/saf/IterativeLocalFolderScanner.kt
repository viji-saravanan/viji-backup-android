package com.aryasubramani.vijibackup.folderaccess.saf

import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanEvent
import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanIssue
import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanProgress
import com.aryasubramani.vijibackup.folderaccess.domain.FolderScanSummary
import com.aryasubramani.vijibackup.folderaccess.domain.LocalFolderScanner
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow

internal class IterativeLocalFolderScanner(
    private val documentSource: LocalFolderDocumentSource,
    private val monotonicClock: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) : LocalFolderScanner {
    override fun scan(treeUri: String): Flow<FolderScanEvent> = channelFlow {
        val startedAt = monotonicClock()
        var progress = FolderScanProgress()
        val issues = linkedSetOf<FolderScanIssue>()
        var hasReturnedCursor = false

        val rootDocumentId = try {
            documentSource.rootDocumentId(treeUri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (rootDocumentId.isNullOrBlank()) {
            send(
                FolderScanEvent.Failed(
                    summary(startedAt, progress, setOf(FolderScanIssue.InvalidTree)),
                ),
            )
            return@channelFlow
        }

        val pendingDirectories = ArrayDeque<String>()
        val seenDocumentIds = mutableSetOf(rootDocumentId)
        pendingDirectories.addLast(rootDocumentId)

        while (pendingDirectories.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val parentDocumentId = pendingDirectories.removeFirst()
            val result = try {
                documentSource.queryChildren(
                    treeUri = treeUri,
                    parentDocumentId = parentDocumentId,
                ) { document ->
                    currentCoroutineContext().ensureActive()
                    if (document.documentId.isBlank()) {
                        issues += FolderScanIssue.MalformedEntry
                        progress = progress.copy(
                            unreadableEntries = progress.unreadableEntries.saturatedIncrement(),
                        )
                        send(FolderScanEvent.Progress(progress))
                    } else if (!seenDocumentIds.add(document.documentId)) {
                        issues += FolderScanIssue.RepeatedDocument
                    } else if (document.isDirectory) {
                        pendingDirectories.addLast(document.documentId)
                    } else {
                        progress = progress.withFile(document.size, issues)
                        send(FolderScanEvent.Progress(progress))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                FolderDocumentQueryResult(
                    cursorReturned = false,
                    issues = setOf(FolderScanIssue.QueryFailure),
                )
            }

            if (result.cursorReturned) {
                hasReturnedCursor = true
                progress = progress.copy(
                    foldersVisited = progress.foldersVisited.saturatedIncrement(),
                )
            } else if (result.issues.isEmpty()) {
                issues += FolderScanIssue.QueryFailure
            }

            val unreadableEntries = result.unreadableEntries.coerceAtLeast(0)
            if (unreadableEntries > 0) {
                issues += FolderScanIssue.MalformedEntry
                progress = progress.copy(
                    unreadableEntries = progress.unreadableEntries.saturatedAdd(unreadableEntries),
                )
            }
            issues += result.issues
            if (result.cursorReturned || unreadableEntries > 0) {
                send(FolderScanEvent.Progress(progress))
            }
        }

        val summary = summary(startedAt, progress, issues)
        val terminal = when {
            !hasReturnedCursor -> FolderScanEvent.Failed(summary)
            issues.isNotEmpty() || progress.unreadableEntries > 0 ->
                FolderScanEvent.Partial(summary)
            else -> FolderScanEvent.Complete(summary)
        }
        send(terminal)
    }.buffer(Channel.RENDEZVOUS)

    private fun summary(
        startedAt: Long,
        progress: FolderScanProgress,
        issues: Set<FolderScanIssue>,
    ) = FolderScanSummary(
        progress = progress,
        elapsedTimeMillis = elapsedSince(startedAt),
        issues = issues.toSet(),
    )

    private fun elapsedSince(startedAt: Long): Long {
        val finishedAt = monotonicClock()
        return (finishedAt - startedAt).coerceAtLeast(0)
    }

    private fun FolderScanProgress.withFile(
        size: Long?,
        issues: MutableSet<FolderScanIssue>,
    ): FolderScanProgress = when {
        size == null -> copy(
            filesDiscovered = filesDiscovered.saturatedIncrement(),
            filesWithUnknownSize = filesWithUnknownSize.saturatedIncrement(),
        )
        size < 0 -> {
            issues += FolderScanIssue.MalformedEntry
            copy(
                filesDiscovered = filesDiscovered.saturatedIncrement(),
                unreadableEntries = unreadableEntries.saturatedIncrement(),
            )
        }
        else -> copy(
            filesDiscovered = filesDiscovered.saturatedIncrement(),
            knownBytes = knownBytes.saturatedAdd(size),
        )
    }

    private fun Long.saturatedIncrement(): Long = saturatedAdd(1)

    private fun Long.saturatedAdd(other: Long): Long = when {
        this < 0 || other < 0 -> Long.MAX_VALUE
        Long.MAX_VALUE - this < other -> Long.MAX_VALUE
        else -> this + other
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
