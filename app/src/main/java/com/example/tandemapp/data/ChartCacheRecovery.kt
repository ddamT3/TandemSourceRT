package com.example.tandemapp.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/** Read-only recovery of the explicitly published generation. No orphan promotion. */
internal object ChartCacheRecovery {
	sealed class Result {
		object Missing : Result()
		object Invalid : Result()
		data class Published(val directory: File, val manifest: ChartCacheManifest) : Result()
	}

	/** Caller must preserve immutable generations and prevent concurrent deletion. */
	fun inspect(store: File,
		limits: LegacyCacheExtractor.Limits = LegacyCacheExtractor.Limits()): Result {
		return try {
			val root = store.canonicalFile
			val pointer = File(root, ChartCacheManifest.CURRENT_FILE)
			if (Files.notExists(pointer.toPath(), LinkOption.NOFOLLOW_LINKS))
				return Result.Missing
			val current = Json.decodeFromString<ChartCacheCurrent>(checkedJson(readMetadata(
				pointer, ChartCacheManifestPersistence.MAX_CURRENT_BYTES)))
			val generations = File(root, "generations")
			val directory = File(generations, current.generationId)
			if (generations.canonicalFile != generations ||
				directory.canonicalFile != directory || !directory.isDirectory)
				throw IOException("Invalid generation directory")
			val bytes = readMetadata(File(directory, ChartCacheManifest.MANIFEST_FILE),
				ChartCacheManifestPersistence.MAX_MANIFEST_BYTES)
			val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
				(it.toInt() and 255).toString(16).padStart(2, '0')
			}
			if (hash != current.manifestSha256) throw IOException("Manifest integrity mismatch")
			val manifest = Json.decodeFromString<ChartCacheManifest>(
				checkedJson(bytes))
			ChartCacheManifestFiles.validate(directory, manifest, current.generationId, limits)
			Result.Published(directory, manifest)
		} catch (_: IOException) {
			Result.Invalid
		} catch (_: IllegalArgumentException) {
			Result.Invalid
		}
	}

	/** Preflight without constructing a JSON tree; limits are provisional. */
	private fun checkedJson(bytes: ByteArray): String {
		val text = bytes.decodeToString(throwOnInvalidSequence = true)
		JsonReader(PumpPayloadStreamParser.BoundedJsonReader(StringReader(text), 1024 * 1024)).use { reader ->
			reader.isLenient = false
			var nodes = 0
			fun visit(depth: Int) {
				if (depth > 72 || ++nodes > 100000)
					throw IOException("Metadata structure limit exceeded")
				when (reader.peek()) {
					JsonToken.BEGIN_OBJECT -> {
						val names = HashSet<String>()
						var nameChars = 0
						reader.beginObject()
						while (reader.hasNext()) {
							if (names.size >= 16384) throw IOException("Metadata member limit exceeded")
							val name = reader.nextName()
							if (name.length > 65536 - nameChars || !names.add(name))
								throw IOException("Invalid metadata member")
							nameChars += name.length
							visit(depth + 1)
						}
						reader.endObject()
					}
					JsonToken.BEGIN_ARRAY -> {
						reader.beginArray()
						while (reader.hasNext()) visit(depth + 1)
						reader.endArray()
					}
					JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
					JsonToken.BOOLEAN -> reader.nextBoolean()
					JsonToken.NULL -> reader.nextNull()
					else -> throw IOException("Unexpected metadata token")
				}
			}
			if (reader.peek() != JsonToken.BEGIN_OBJECT) throw IOException("Expected metadata object")
			visit(1)
			if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing metadata")
		}
		return text
	}

	private fun readMetadata(file: File, maximum: Long): ByteArray {
		if (file.canonicalFile != file || !file.isFile)
			throw IOException("Metadata unavailable")
		val size = file.length()
		if (size !in 1..maximum) throw IOException("Metadata size limit exceeded")
		val bytes = ByteArray(size.toInt())
		file.inputStream().use { input ->
			var offset = 0
			while (offset < bytes.size) {
				val count = input.read(bytes, offset, bytes.size - offset)
				if (count <= 0) throw IOException("Incomplete metadata")
				offset += count
			}
			if (input.read() != -1) throw IOException("Metadata size changed")
		}
		return bytes
	}
}
