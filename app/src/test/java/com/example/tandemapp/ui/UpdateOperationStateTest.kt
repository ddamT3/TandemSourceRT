package com.example.tandemapp.ui

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class UpdateOperationStateTest {
	@Test
	fun `cancelled job callbacks and late finally cannot overwrite a new download`() = runBlocking {
		val operations = UpdateOperationState()
		val old = operations.begin(UpdateUiState(busy = true, progress = 5, message = "old download"))
		val releaseCleanup = CompletableDeferred<Unit>()
		val oldTask = launch(start = CoroutineStart.UNDISPATCHED) {
			try { awaitCancellation() }
			finally {
				withContext(NonCancellable) {
					releaseCleanup.await()
					assertFalse(operations.update(old) { it.copy(progress = 99) })
					assertFalse(operations.update(old) { it.copy(ready = File("old.apk"), message = "old result") })
					assertFalse(operations.update(old) { it.copy(ready = null, message = "old error") })
					assertFalse(operations.finish(old))
				}
			}
		}
		operations.cancelDownload()
		oldTask.cancel()
		val current = operations.begin(UpdateUiState(busy = true, progress = 0, message = "new download"))
		operations.update(current) { it.copy(progress = 25) }
		releaseCleanup.complete(Unit)
		oldTask.join()
		assertEquals(UpdateUiState(busy = true, progress = 25, message = "new download"), operations.value)
		val ready = File("new.apk")
		assertTrue(operations.update(current) { it.copy(ready = ready, message = "verified") })
		assertTrue(operations.finish(current))
		assertEquals(UpdateUiState(ready = ready, message = "verified"), operations.value)
	}

	@Test
	fun `cancel resets buttons progress and ready state and invalidates prior completion`() {
		val operations = UpdateOperationState()
		val old = operations.begin(UpdateUiState(busy = true, progress = 80, showRelease = true))
		operations.cancelDownload()
		assertFalse(operations.value.busy)
		assertNull(operations.value.progress)
		assertNull(operations.value.ready)
		assertTrue(operations.value.showRelease)
		assertEquals("Download cancelled.", operations.value.message)
		assertFalse(operations.finish(old))
		assertEquals("Download cancelled.", operations.value.message)
	}

	@Test
	fun `idle callbacks cannot overwrite the status of an active operation`() {
		val operations = UpdateOperationState()
		val current = operations.begin(UpdateUiState(busy = true, progress = 40, message = "current"))
		operations.editIdle { it.copy(message = "late activity result") }
		assertEquals("current", operations.value.message)
		operations.finish(current)
		operations.editIdle { it.copy(message = "idle") }
		assertEquals("idle", operations.value.message)
	}
}
