package com.example.tandemapp.data

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateRepositoryTest {
	@get:Rule val temporary = TemporaryFolder()
	private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

	@Test
	fun `published SHA-256 must match actual downloaded bytes`() = runBlocking<Unit> {
		val file = temporary.newFile("checksum.apk").apply { writeText("abc") }
		verifyPublishedChecksum(file, abcSha256)
		file.writeText("abd")
		assertThrows(IllegalArgumentException::class.java) {
			runBlocking { verifyPublishedChecksum(file, abcSha256) }
		}
	}

	@Test
	fun `absent published digest does not claim checksum verification`() = runBlocking<Unit> {
		val file = temporary.newFile("no-checksum.apk").apply { writeText("abc") }
		verifyPublishedChecksum(file, null)
		assertTrue(file.isFile)
	}

	@Test
	fun `successful handoff leaves the verified file owned by the caller`() = runBlocking<Unit> {
		val file = temporary.newFile("success.apk")
		val result = downloadTemporaryApk(createFile = { file }) { it.writeText("abc") }
		assertEquals(file, result)
		assertEquals("abc", result.readText())
	}

	@Test
	fun `failed transfer removes the temporary APK`() {
		val file = temporary.newFile("failure.apk")
		assertThrows(IOException::class.java) {
			runBlocking {
				downloadTemporaryApk(createFile = { file }) {
					it.writeText("partial")
					throw IOException("Transfer failed")
				}
			}
		}
		assertFalse(file.exists())
	}

	@Test
	fun `cancelling an active transfer removes its temporary file`() = runBlocking<Unit> {
		val file = temporary.newFile("cancelled.apk")
		val started = CompletableDeferred<Unit>()
		val task = launch {
			downloadTemporaryApk(createFile = { file }) {
				it.writeText("partial")
				started.complete(Unit)
				awaitCancellation()
			}
		}
		started.await()
		task.cancelAndJoin()
		assertFalse(file.exists())
	}

	@Test
	fun `cancelling after transfer but before dispatcher delivery also removes the file`() {
		val file = temporary.newFile("handoff.apk")
		val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
		try {
			runBlocking {
				val transferred = CountDownLatch(1)
				val result = async(start = CoroutineStart.UNDISPATCHED) {
					downloadTemporaryApk(createFile = { file }, dispatcher = worker) {
						it.writeText("complete")
						transferred.countDown()
					}
				}
				// Block the receiving thread: the IO result cannot be delivered before cancellation.
				assertTrue(transferred.await(5, TimeUnit.SECONDS))
				result.cancel()
				try { result.await(); fail("Cancelled download must not hand off a file") }
				catch (_: CancellationException) { /* expected */ }
			}
		} finally { worker.close() }
		assertFalse(file.exists())
	}
}
