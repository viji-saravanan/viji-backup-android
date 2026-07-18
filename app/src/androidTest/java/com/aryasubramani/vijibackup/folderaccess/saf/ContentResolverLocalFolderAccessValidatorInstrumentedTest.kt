package com.aryasubramani.vijibackup.folderaccess.saf

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.aryasubramani.vijibackup.folderaccess.domain.FolderAccessHealth
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class ContentResolverLocalFolderAccessValidatorInstrumentedTest {
    private val targetContext = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var provider: ControllableDocumentsProvider
    private lateinit var authority: String
    private lateinit var treeUri: Uri
    private var grantLookupCalls = 0
    private var grantUriMatchedExpected: Boolean? = null
    private var grantLookup: (Uri) -> Boolean? = { true }

    @Before
    fun setUp() {
        authority =
            InstrumentationRegistry.getInstrumentation().context.packageName + ".documents"
        treeUri = Uri.parse("content://$authority/tree/${ControllableDocumentsProvider.ROOT_DOCUMENT_ID}")
        provider = ControllableDocumentsProvider().also { documentsProvider ->
            documentsProvider.attachInfo(
                targetContext,
                ProviderInfo().apply {
                    this.authority = this@ContentResolverLocalFolderAccessValidatorInstrumentedTest.authority
                    exported = true
                    grantUriPermissions = true
                    readPermission = Manifest.permission.MANAGE_DOCUMENTS
                    writePermission = Manifest.permission.MANAGE_DOCUMENTS
                },
            )
        }
        grantLookupCalls = 0
        grantUriMatchedExpected = null
        grantLookup = { true }
    }

    @Test
    fun installedProviderManifestIsDisabledWithVariantSafeProtectedAuthority() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val providerInfo = testContext.packageManager.getProviderInfo(
            ComponentName(testContext.packageName, ControllableDocumentsProvider::class.java.name),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )

        assertEquals(authority, providerInfo.authority)
        assertEquals(false, providerInfo.enabled)
        assertTrue(providerInfo.exported)
        assertTrue(providerInfo.grantUriPermissions)
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, providerInfo.readPermission)
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, providerInfo.writePermission)
    }

    @Test
    fun exactPersistedReadGrantAndSingleDirectoryRowAreReady() = runTest {
        val result = validator().validate(treeUri.toString())

        assertEquals(FolderAccessHealth.Ready, result)
        assertEquals(1, grantLookupCalls)
        assertEquals(true, grantUriMatchedExpected)
        assertEquals(1, provider.queryCount)
        assertEquals(true, provider.lastDocumentIdMatchedRoot)
        assertEquals(
            ControllableDocumentsProvider.DOCUMENT_PROJECTION.toList(),
            provider.lastProjection,
        )
        assertTrue(provider.lastCursor?.isClosed == true)
        assertEquals(0, provider.rootsQueryCount)
        assertEquals(0, provider.childQueryCount)
        assertEquals(0, provider.openDocumentCallCount)
        assertEquals(0, provider.mutationCallCount)
    }

    @Test
    fun absentExactReadGrantDoesNotQueryProvider() = runTest {
        grantLookup = { false }

        assertEquals(FolderAccessHealth.PermissionMissing, validator().validate(treeUri.toString()))
        assertEquals(1, grantLookupCalls)
        assertEquals(0, provider.queryCount)
    }

    @Test
    fun unavailableGrantLookupIsTemporaryAndDoesNotQueryProvider() = runTest {
        grantLookup = { null }

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
        assertEquals(0, provider.queryCount)
    }

    @Test
    fun invalidTreeFailsClosedBeforeGrantOrProviderAccess() = runTest {
        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate("https://provider.test/not-a-tree"),
        )
        assertEquals(0, grantLookupCalls)
        assertEquals(0, provider.queryCount)
    }

    @Test
    fun contentDocumentUriThatIsNotATreeFailsClosed() = runTest {
        val nonTreeUri = "content://$authority/document/root"

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(nonTreeUri),
        )
        assertEquals(0, grantLookupCalls)
        assertEquals(0, provider.queryCount)
    }

    @Test
    fun emptyRootCursorIsTreeMissing() = runTest {
        provider.rows = emptyList()

        assertEquals(FolderAccessHealth.TreeMissing, validator().validate(treeUri.toString()))
        assertTrue(provider.lastCursor?.isClosed == true)
    }

    @Test
    fun documentsProviderFileNotFoundTranslationIsTemporaryNullCursor() = runTest {
        provider.queryFailure = FileNotFoundException("private-root-detail")

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
    }

    @Test
    fun directProviderFileNotFoundIsTreeMissing() = runTest {
        val directProvider = CancellationAwareRootProvider().apply {
            queryFailure = FileNotFoundException("private-root-detail")
        }

        assertEquals(
            FolderAccessHealth.TreeMissing,
            validator(contentResolverFor(directProvider)).validate(treeUri.toString()),
        )
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O)
    fun providerAuthenticationIsClassifiedBeforeSecurityFailure() = runTest {
        val userAction = PendingIntent.getActivity(
            targetContext,
            0,
            Intent(Settings.ACTION_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE,
        )
        provider.queryFailure = Api26TestValues.authenticationRequired(userAction)

        assertEquals(
            FolderAccessHealth.ProviderAuthRequired,
            validator().validate(treeUri.toString()),
        )
        assertEquals(1, grantLookupCalls)
    }

    @Test
    fun securityFailureWithLiveGrantIsTemporary() = runTest {
        provider.queryFailure = SecurityException("private-provider-detail")

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
        assertEquals(2, grantLookupCalls)
    }

    @Test
    fun securityFailureRechecksGrantAndDetectsPermissionLoss() = runTest {
        val lookupResults = ArrayDeque(listOf(true, false))
        grantLookup = { lookupResults.removeFirst() }
        provider.queryFailure = SecurityException("private-provider-detail")

        assertEquals(
            FolderAccessHealth.PermissionMissing,
            validator().validate(treeUri.toString()),
        )
        assertEquals(2, grantLookupCalls)
    }

    @Test
    fun securityFailureWithUnavailableGrantRecheckIsTemporary() = runTest {
        var lookup = 0
        grantLookup = {
            lookup += 1
            if (lookup == 1) true else null
        }
        provider.queryFailure = SecurityException("private-provider-detail")

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
        assertEquals(2, grantLookupCalls)
    }

    @Test
    fun securityFailureWithThrowingGrantRecheckIsTemporary() = runTest {
        var lookup = 0
        grantLookup = {
            lookup += 1
            if (lookup == 1) true else throw IllegalStateException("private-grant-detail")
        }
        provider.queryFailure = SecurityException("private-provider-detail")

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
        assertEquals(2, grantLookupCalls)
    }

    @Test
    fun securityFailureWithCancelledGrantRecheckPropagatesCancellation() = runTest {
        var lookup = 0
        grantLookup = {
            lookup += 1
            if (lookup == 1) true else throw CancellationException("test cancellation")
        }
        provider.queryFailure = SecurityException("private-provider-detail")

        var cancellation: CancellationException? = null
        try {
            validator().validate(treeUri.toString())
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertTrue("Expected grant recheck cancellation", cancellation != null)
        assertEquals(2, grantLookupCalls)
    }

    @Test
    fun nullCursorIsTemporary() = runTest {
        provider.returnNullCursor = true

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
    }

    @Test
    fun multipleRootRowsAreTemporary() = runTest {
        provider.rows = listOf(TestDocumentRow(), TestDocumentRow())

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
        assertTrue(provider.lastCursor?.isClosed == true)
    }

    @Test
    fun wrongRootDocumentIdIsTemporary() = runTest {
        provider.rows = listOf(TestDocumentRow(documentId = "different-root"))

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
    }

    @Test
    fun nonDirectoryRootIsTemporary() = runTest {
        provider.rows = listOf(TestDocumentRow(mimeType = "application/octet-stream"))

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
    }

    @Test
    fun missingRequiredColumnIsTemporary() = runTest {
        provider.returnedColumns =
            ControllableDocumentsProvider.DOCUMENT_PROJECTION.filterNot { column ->
                column == DocumentsContract.Document.COLUMN_FLAGS
            }.toTypedArray()

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
    }

    @Test
    fun nullRequiredValueIsTemporary() = runTest {
        provider.rows = listOf(TestDocumentRow(displayName = null))

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
        assertTrue(provider.lastCursor?.isClosed == true)
    }

    @Test
    fun malformedFlagsAreTemporary() = runTest {
        provider.rows = listOf(TestDocumentRow(flags = "not-an-integer"))

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
        assertTrue(provider.lastCursor?.isClosed == true)
    }

    @Test
    fun loadingCursorIsTemporaryEvenWhenItContainsAReadyRow() = runTest {
        provider.cursorExtras = Bundle().apply {
            putBoolean(DocumentsContract.EXTRA_LOADING, true)
        }

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
    }

    @Test
    fun errorCursorIsTemporaryEvenWhenErrorTextIsBlank() = runTest {
        provider.cursorExtras = Bundle().apply {
            putString(DocumentsContract.EXTRA_ERROR, "")
        }

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
    }

    @Test
    fun unexpectedProviderFailureIsTemporaryWithoutLeakingDetails() = runTest {
        provider.queryFailure = IllegalStateException("private-filename-or-provider-detail")

        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )
    }

    @Test
    fun grantLookupFailureIsTemporaryButCancellationPropagates() = runTest {
        grantLookup = { throw IllegalStateException("private-grant-detail") }
        assertEquals(
            FolderAccessHealth.TemporarilyUnavailable,
            validator().validate(treeUri.toString()),
        )

        grantLookup = { throw CancellationException("test cancellation") }
        var cancellation: CancellationException? = null
        try {
            validator().validate(treeUri.toString())
        } catch (error: CancellationException) {
            cancellation = error
        }
        assertTrue("Expected grant lookup cancellation", cancellation != null)
    }

    @Test
    fun cancellingBlockedQueryCancelsSignalAndPropagatesCancellation() = runBlocking {
        val cancellationProvider = CancellationAwareRootProvider().apply {
            blockQueries = true
        }
        val cancellationValidator = validator(contentResolverFor(cancellationProvider))
        val observedFailure = CompletableDeferred<Throwable>()
        val job = launch(Dispatchers.Default) {
            try {
                cancellationValidator.validate(treeUri.toString())
            } catch (error: Throwable) {
                observedFailure.complete(error)
                throw error
            }
        }

        try {
            assertTrue(cancellationProvider.queryStarted.await(5, TimeUnit.SECONDS))
            job.cancel()
            val failure = withTimeout(5_000) { observedFailure.await() }
            withTimeout(5_000) { job.cancelAndJoin() }

            assertTrue(failure is CancellationException)
            assertTrue(cancellationProvider.cancellationSignals.single()?.isCanceled == true)
        } finally {
            cancellationProvider.blockQueries = false
            withTimeout(5_000) { job.cancelAndJoin() }
        }
    }

    @Test
    fun eachRootQueryReceivesAFreshCancellationSignal() = runTest {
        val cancellationProvider = CancellationAwareRootProvider()
        val validator = validator(contentResolverFor(cancellationProvider))

        assertEquals(FolderAccessHealth.Ready, validator.validate(treeUri.toString()))
        assertEquals(FolderAccessHealth.Ready, validator.validate(treeUri.toString()))

        assertEquals(2, cancellationProvider.cancellationSignals.size)
        assertTrue(cancellationProvider.cancellationSignals.all { it != null })
        assertNotSame(
            cancellationProvider.cancellationSignals[0],
            cancellationProvider.cancellationSignals[1],
        )
    }

    @Test
    fun cancelledQueryCanRetryWithSameValidatorAndProvider() = runBlocking {
        val cancellationProvider = CancellationAwareRootProvider().apply {
            blockQueries = true
        }
        val validator = validator(contentResolverFor(cancellationProvider))
        val first = launch(Dispatchers.Default) {
            validator.validate(treeUri.toString())
        }

        try {
            assertTrue(cancellationProvider.queryStarted.await(5, TimeUnit.SECONDS))
            first.cancel()
            cancellationProvider.blockQueries = false
            withTimeout(5_000) { first.cancelAndJoin() }

            assertEquals(FolderAccessHealth.Ready, validator.validate(treeUri.toString()))
            assertEquals(2, cancellationProvider.cancellationSignals.size)
            assertTrue(cancellationProvider.cancellationSignals.first()?.isCanceled == true)
            assertTrue(cancellationProvider.cancellationSignals.last()?.isCanceled == false)
        } finally {
            cancellationProvider.blockQueries = false
            withTimeout(5_000) { first.cancelAndJoin() }
        }
    }

    private fun validator(
        contentResolver: ContentResolver = contentResolverFor(provider),
    ): ContentResolverLocalFolderAccessValidator =
        ContentResolverLocalFolderAccessValidator(
            contentResolver = contentResolver,
            ioDispatcher = Dispatchers.IO,
            persistedReadGrantLookup = PersistedReadGrantLookup { uri ->
                grantLookupCalls += 1
                grantUriMatchedExpected = uri == treeUri
                grantLookup(uri)
            },
        )

    private fun contentResolverFor(provider: android.content.ContentProvider): ContentResolver =
        ContentResolver.wrap(provider)

    @RequiresApi(Build.VERSION_CODES.O)
    private object Api26TestValues {
        fun authenticationRequired(userAction: PendingIntent): RuntimeException =
            android.app.AuthenticationRequiredException(
                SecurityException("private-auth-detail"),
                userAction,
            )
    }
}
