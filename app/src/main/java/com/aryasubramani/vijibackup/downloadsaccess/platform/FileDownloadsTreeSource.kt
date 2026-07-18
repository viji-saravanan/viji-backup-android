package com.aryasubramani.vijibackup.downloadsaccess.platform

import com.aryasubramani.vijibackup.downloadsaccess.domain.DownloadsScanIssue
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class FileDownloadsTreeSource(
    private val rootDirectory: () -> File,
    private val hasAccess: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fileSystem: DownloadsReadOnlyFileSystem = JavaIoDownloadsReadOnlyFileSystem,
) : DownloadsTreeSource {
    override fun root(): DownloadsRootResult {
        if (!accessIsGranted()) return DownloadsRootResult.PermissionMissing
        val root = canonicalRoot() ?: return DownloadsRootResult.Unavailable
        return DownloadsRootResult.Found(root.path)
    }

    override suspend fun readChildren(
        parentId: String,
        onEntry: suspend (DownloadsTreeEntry) -> Unit,
    ): DownloadsDirectoryReadResult = withContext(ioDispatcher) {
        if (!accessIsGranted()) {
            return@withContext failed(DownloadsScanIssue.PermissionLost)
        }
        val root = canonicalRoot()
            ?: return@withContext failed(DownloadsScanIssue.RootUnavailable)
        val parent = canonicalFile(File(parentId))
            ?: return@withContext failed(
                DownloadsScanIssue.DirectoryUnreadable,
                unreadableEntries = 1,
            )
        if (!parent.isWithin(root)) {
            return@withContext failed(
                DownloadsScanIssue.RootEscapeRejected,
                unreadableEntries = 1,
            )
        }
        if (
            !fileSystem.exists(parent) ||
            !fileSystem.isDirectory(parent) ||
            !fileSystem.canRead(parent)
        ) {
            return@withContext failed(
                DownloadsScanIssue.DirectoryUnreadable,
                unreadableEntries = 1,
            )
        }

        val children = try {
            fileSystem.listChildren(parent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@withContext failed(
            DownloadsScanIssue.DirectoryUnreadable,
            unreadableEntries = 1,
        )

        var unreadableEntries = 0L
        val issues = linkedSetOf<DownloadsScanIssue>()
        children.forEach { child ->
            currentCoroutineContext().ensureActive()
            val absoluteChild = File(parent, child.name).absoluteFile
            val canonicalChild = canonicalFile(child)
            if (canonicalChild == null) {
                unreadableEntries = unreadableEntries.saturatedIncrement()
                issues += DownloadsScanIssue.EntryUnreadable
                return@forEach
            }
            if (!canonicalChild.isWithin(root)) {
                unreadableEntries = unreadableEntries.saturatedIncrement()
                issues += DownloadsScanIssue.RootEscapeRejected
                return@forEach
            }
            if (absoluteChild.path != canonicalChild.path) {
                unreadableEntries = unreadableEntries.saturatedIncrement()
                issues += DownloadsScanIssue.SymbolicLinkSkipped
                return@forEach
            }
            if (!fileSystem.exists(canonicalChild)) {
                unreadableEntries = unreadableEntries.saturatedIncrement()
                issues += DownloadsScanIssue.EntryUnreadable
                return@forEach
            }

            when {
                fileSystem.isDirectory(canonicalChild) -> onEntry(
                    DownloadsTreeEntry(
                        id = canonicalChild.path,
                        isDirectory = true,
                        size = null,
                    ),
                )
                fileSystem.isFile(canonicalChild) -> onEntry(
                    DownloadsTreeEntry(
                        id = canonicalChild.path,
                        isDirectory = false,
                        size = fileSystem.length(canonicalChild),
                    ),
                )
                else -> {
                    unreadableEntries = unreadableEntries.saturatedIncrement()
                    issues += DownloadsScanIssue.EntryUnreadable
                }
            }
        }

        DownloadsDirectoryReadResult(
            directoryRead = true,
            unreadableEntries = unreadableEntries,
            issues = issues,
        )
    }

    private fun canonicalRoot(): File? {
        val absoluteRoot = try {
            rootDirectory().absoluteFile
        } catch (_: Exception) {
            return null
        }
        val canonicalRoot = canonicalFile(absoluteRoot) ?: return null
        if (
            !fileSystem.exists(canonicalRoot) ||
            !fileSystem.isDirectory(canonicalRoot) ||
            !fileSystem.canRead(canonicalRoot)
        ) {
            return null
        }
        return canonicalRoot
    }

    private fun canonicalFile(file: File): File? = try {
        fileSystem.canonicalFile(file)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun accessIsGranted(): Boolean = try {
        hasAccess()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun File.isWithin(root: File): Boolean =
        path == root.path || path.startsWith(root.path + File.separator)

    private fun failed(
        issue: DownloadsScanIssue,
        unreadableEntries: Long = 0,
    ) = DownloadsDirectoryReadResult(
        directoryRead = false,
        unreadableEntries = unreadableEntries,
        issues = setOf(issue),
    )

    private fun Long.saturatedIncrement(): Long =
        if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1
}

internal interface DownloadsReadOnlyFileSystem {
    fun canonicalFile(file: File): File

    fun exists(file: File): Boolean

    fun isDirectory(file: File): Boolean

    fun isFile(file: File): Boolean

    fun canRead(file: File): Boolean

    fun length(file: File): Long

    fun listChildren(directory: File): Array<File>?
}

internal object JavaIoDownloadsReadOnlyFileSystem : DownloadsReadOnlyFileSystem {
    override fun canonicalFile(file: File): File = file.canonicalFile

    override fun exists(file: File): Boolean = file.exists()

    override fun isDirectory(file: File): Boolean = file.isDirectory

    override fun isFile(file: File): Boolean = file.isFile

    override fun canRead(file: File): Boolean = file.canRead()

    override fun length(file: File): Long = file.length()

    override fun listChildren(directory: File): Array<File>? = directory.listFiles()
}
