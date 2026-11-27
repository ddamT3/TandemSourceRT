package com.example.tandemapp.data

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChartCacheTransactionTest {
	@get:Rule val temp = TemporaryFolder(File("build").apply { require(isDirectory || mkdirs()) })

	private fun rejected(message: String, block: () -> Unit) {
		try {
			block()
			fail("Expected IOException")
		} catch (failure: IOException) {
			assertEquals(message, failure.message)
		}
	}

	@Test(timeout = 30000L)
	fun concurrentWriterRejectedAndLockReusable() {
		val store = temp.newFolder()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val first = executor.submit<Int> {
				ChartCacheTransaction.withWriter(store) {
					entered.countDown()
					check(release.await(10, TimeUnit.SECONDS))
					42
				}
			}
			assertTrue(entered.await(10, TimeUnit.SECONDS))
			rejected("Generation writer busy") {
				ChartCacheTransaction.withWriter(store) { fail("Concurrent writer entered") }
			}
			release.countDown()
			assertEquals(42, first.get(10, TimeUnit.SECONDS).toInt())
			assertEquals(7, ChartCacheTransaction.withWriter(store) { 7 })
			assertTrue(File(store, ".writer.lock").isFile)
		} finally {
			release.countDown()
			executor.shutdownNow()
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
		}
	}

	@Test(timeout = 30000L)
	fun exceptionReleasesLock() {
		val store = temp.newFolder()
		rejected("Synthetic failure") {
			ChartCacheTransaction.withWriter(store) { throw IOException("Synthetic failure") }
		}
		assertEquals("ok", ChartCacheTransaction.withWriter(store) { "ok" })
	}

	@Test(timeout = 30000L)
	fun nestedWriterRejected() {
		val store = temp.newFolder()
		ChartCacheTransaction.withWriter(store) {
			rejected("Generation writer busy") {
				ChartCacheTransaction.withWriter(store) { fail("Nested writer entered") }
			}
		}
		assertTrue(ChartCacheTransaction.withWriter(store) { true })
	}

	@Test(timeout = 30000L)
	fun existingPointerPreventsMigration() {
		val store = temp.newFolder()
		val generations = File(store, "generations").apply { assertTrue(mkdir()) }
		val pointer = File(store, "current.json").apply { writeText("synthetic existing pointer") }
		val source = temp.newFile().apply { writeText("synthetic legacy") }
		rejected("Current generation already exists") {
			ChartCacheTransaction.migrate(source, store)
		}
		assertEquals("synthetic existing pointer", pointer.readText())
		assertEquals("synthetic legacy", source.readText())
		assertEquals(0, generations.listFiles()!!.size)
		assertTrue(ChartCacheTransaction.withWriter(store) { true })
	}

	@Test(timeout = 30000L)
	fun secondStoreRejectedBeforeLockFileCreation() {
		val first = temp.newFolder()
		val second = temp.newFolder()
		ChartCacheTransaction.withWriter(first) {
			rejected("Generation writer busy") {
				ChartCacheTransaction.withWriter(second) { fail("Second writer entered") }
			}
			assertFalse(File(second, ".writer.lock").exists())
		}
		assertTrue(ChartCacheTransaction.withWriter(second) { true })
	}

	@Test(timeout = 30000L)
	fun invalidStoreDoesNotRetainGate() {
		val invalid = temp.newFile()
		rejected("Generation store unavailable") {
			ChartCacheTransaction.withWriter(invalid) { fail("Invalid store entered") }
		}
		assertTrue(ChartCacheTransaction.withWriter(temp.newFolder()) { true })
	}
}
