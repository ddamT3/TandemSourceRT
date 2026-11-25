package com.example.tandemapp.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Isolated prototype for bounded-memory archival of original payload bytes.
 *
 * The caller owns the input stream and the destination directory.
 * A successful archive is not yet a validated Tandem generation.
 */
internal object PumpPayloadArchive {
	const val BUFFER_BYTES = 64 * 1024

	data class Result(
		val bytes: Long,
		val sha256: String
	)

	fun archive(
		input: InputStream,
		destination: File,
		maxBytes: Long,
		replace: (File, File) -> Unit = { temporary, target ->
			Files.move(
				temporary.toPath(),
				target.toPath(),
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING
			)
		}
	): Result {
		require(maxBytes > 0)

		val target = destination.absoluteFile
		val parent = target.parentFile
			?: throw IOException("Missing archive directory")

		if (!parent.isDirectory) {
			throw IOException("Archive directory unavailable")
		}

		val temporary = File.createTempFile(
			".payload-",
			".tmp",
			parent
		)

		try {
			val digest = MessageDigest.getInstance("SHA-256")
			val buffer = ByteArray(BUFFER_BYTES)
			var total = 0L

			FileOutputStream(temporary).use { output ->
				while (true) {
					val count = input.read(buffer)

					if (count < 0) break

					if (count == 0) {
						throw IOException("Input made no progress")
					}

					if (count.toLong() > maxBytes - total) {
						throw IOException("Archive size limit exceeded")
					}

					output.write(buffer, 0, count)
					digest.update(buffer, 0, count)
					total += count.toLong()
				}

				if (total == 0L) {
					throw IOException("Empty archive")
				}

				output.flush()
				output.fd.sync()
			}

			val hash = digest.digest().joinToString("") {
				(it.toInt() and 0xff).toString(16).padStart(2, '0')
			}

			val result = Result(total, hash)

			replace(temporary, target)

			return result
		} finally {
			temporary.delete()
		}
	}
}
