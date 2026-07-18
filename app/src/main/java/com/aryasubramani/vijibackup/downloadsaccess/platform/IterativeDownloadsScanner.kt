package com.aryasubramani.vijibackup.downloadsaccess.platform

import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanEvent
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanIssue
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanProgress
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanSummary
import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanner
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow

internal class IterativeDownloadsScanner(
    private val source: DownloadsTreeSource,
    private val monotonicClock: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) : DownloadsScanner {
    override fun scan(): Flow<DownloadsScanEvent> = channelFlow {
        val startedAt = monotonicClock()
        var progress = DownloadsScanProgress()
        val issues = linkedSetOf<DownloadsScanIssue>()
        var hasReadDirectory = false

        val rootResult = try {
            source.root()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DownloadsRootResult.Unavailable
        }
        val rootId = when (rootResult) {
            is DownloadsRootResult.Found -> rootResult.rootId.takeIf(String::isNotBlank)
            DownloadsRootResult.PermissionMissing -> {
                send(failure(startedAt, progress, DownloadsScanIssue.PermissionLost))
                return@channelFlow
            }
            DownloadsRootResult.Unavailable -> {
                send(failure(startedAt, progress, DownloadsScanIssue.RootUnavailable))
                return@channelFlow
            }
        }
        if (rootId == null) {
            send(failure(startedAt, progress, DownloadsScanIssue.RootUnavailable))
            return@channelFlow
        }

        val pendingDirectories = ArrayDeque<String>()
        val seenEntries = mutableSetOf(rootId)
        pendingDirectories.addLast(rootId)

        while (pendingDirectories.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val parentId = pendingDirectories.removeFirst()
            val result = try {
                source.readChildren(parentId) { entry ->
                    currentCoroutineContext().ensureActive()
                    when {
                        entry.id.isBlank() -> {
                            issues += DownloadsScanIssue.EntryUnreadable
                            progress = progress.copy(
                                unreadableEntries = progress.unreadableEntries.saturatedIncrement(),
                            )
                            send(DownloadsScanEvent.Progress(progress))
                        }
                        !seenEntries.add(entry.id) -> {
                            issues += DownloadsScanIssue.RepeatedEntry
                        }
                        entry.isDirectory -> pendingDirectories.addLast(entry.id)
                        else -> {
                            progress = progress.withFile(entry.size, issues)
                            send(DownloadsScanEvent.Progress(progress))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DownloadsDirectoryReadResult(
                    directoryRead = false,
                    issues = setOf(DownloadsScanIssue.DirectoryUnreadable),
                )
            }

            if (result.directoryRead) {
                hasReadDirectory = true
                progress = progress.copy(
                    foldersVisited = progress.foldersVisited.saturatedIncrement(),
                )
            } else if (result.issues.isEmpty()) {
                issues += DownloadsScanIssue.DirectoryUnreadable
            }
            val unreadableEntries = result.unreadableEntries.coerceAtLeast(0)
            progress = progress.copy(
                unreadableEntries = progress.unreadableEntries.saturatedAdd(unreadableEntries),
            )
            issues += result.issues
            if (result.directoryRead || unreadableEntries > 0) {
                send(DownloadsScanEvent.Progress(progress))
            }
        }

        val summary = summary(startedAt, progress, issues)
        send(
            when {
                !hasReadDirectory -> DownloadsScanEvent.Failed(summary)
                issues.isNotEmpty() || progress.unreadableEntries > 0 ->
                    DownloadsScanEvent.Partial(summary)
                else -> DownloadsScanEvent.Complete(summary)
            },
        )
    }.buffer(Channel.RENDEZVOUS)

    private fun failure(
        startedAt: Long,
        progress: DownloadsScanProgress,
        issue: DownloadsScanIssue,
    ) = DownloadsScanEvent.Failed(summary(startedAt, progress, setOf(issue)))

    private fun summary(
        startedAt: Long,
        progress: DownloadsScanProgress,
        issues: Set<DownloadsScanIssue>,
    ) = DownloadsScanSummary(
        progress = progress,
        elapsedTimeMillis = (monotonicClock() - startedAt).coerceAtLeast(0),
        issues = issues.toSet(),
    )

    private fun DownloadsScanProgress.withFile(
        size: Long?,
        issues: MutableSet<DownloadsScanIssue>,
    ): DownloadsScanProgress = when {
        size == null -> copy(
            filesDiscovered = filesDiscovered.saturatedIncrement(),
            filesWithUnknownSize = filesWithUnknownSize.saturatedIncrement(),
        )
        size < 0 -> {
            issues += DownloadsScanIssue.EntryUnreadable
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
