package com.example.tandemapp.data

import android.util.Log
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class TandemAuthContext(val accessToken: String, val pumperId: String)

sealed interface TandemAuthContextResult {
	data class Success(val context: TandemAuthContext) : TandemAuthContextResult
	data class Failure(val message: String) : TandemAuthContextResult
}

fun interface TandemAuthProvider {
	suspend fun authenticate(username: String, password: String): TandemAuthContextResult
}

class KotlinTandemAuthProvider : TandemAuthProvider {
	private val json = Json { ignoreUnknownKeys = true }
	private val random = SecureRandom()
	private val cache = ConcurrentHashMap<String, TandemAuthContext>()

	override suspend fun authenticate(username: String, password: String): TandemAuthContextResult =
		withContext(Dispatchers.IO) {
			val cacheKey = username.trim().lowercase(Locale.ROOT)
			if (cacheKey.isBlank()) return@withContext TandemAuthContextResult.Failure("Missing username")
			cache[cacheKey]?.takeIf { tokenIsUsable(it.accessToken) }?.let {
				Log.d(TAG, "Reusing Kotlin Tandem authentication context")
				return@withContext TandemAuthContextResult.Success(it)
			}
			cache.remove(cacheKey)

			try {
				val context = authenticateFresh(username, password)
				cache[cacheKey] = context
				Log.i(TAG, "Kotlin OAuth completed and pumperId validated")
				TandemAuthContextResult.Success(context)
			} catch (e: Exception) {
				Log.w(TAG, "Kotlin OAuth attempt failed: ${e.message}")
				TandemAuthContextResult.Failure(e.message ?: "Tandem authentication failed")
			}
		}

	private fun authenticateFresh(username: String, password: String): TandemAuthContext {
		val session = HttpSession()
		val pkce = buildPkce()
		val authorizeUrl = "$ACCOUNTS_BASE_URL/connect/authorize?" + formEncode(
			linkedMapOf(
				"client_id" to CLIENT_ID,
				"redirect_uri" to REDIRECT_URI,
				"response_mode" to "query",
				"response_type" to "code",
				"scope" to "openid email profile tandem.devices.assign",
				"code_challenge" to pkce.challenge,
				"code_challenge_method" to "S256",
				"nonce" to pkce.nonce,
				"state" to pkce.state
			)
		)
		val start = session.request(authorizeUrl, followRedirects = false)
		val authorizeLocation = start.firstHeader("Location")
			?: throw IllegalStateException("Authorize response did not contain a Location header")
		Log.d(TAG, "OAuth authorize start status=${start.status}")

		val loginBody = json.encodeToString(buildJsonObject {
			put("username", username)
			put("password", password)
		})
		val login = session.request(
			url = "$ACCOUNTS_BASE_URL/login",
			method = "POST",
			headers = mapOf(
				"Content-Type" to "application/json",
				"Accept" to "application/json",
				"Origin" to SOURCE_BASE_URL,
				"Referer" to "$SOURCE_BASE_URL/",
				"User-Agent" to USER_AGENT
			),
			body = loginBody.toByteArray(StandardCharsets.UTF_8),
			followRedirects = true
		)
		if (login.status !in 200..299) {
			throw IllegalStateException("Tandem login rejected (HTTP ${login.status})")
		}
		Log.d(TAG, "OAuth login status=${login.status}")

		val returnUrl = queryParameter(authorizeLocation, "ReturnUrl")
			?: throw IllegalStateException("Authorize redirect did not contain ReturnUrl")
		val continuation = session.request(returnUrl, followRedirects = true)
		val callbackUrl = listOf(continuation.url, continuation.firstHeader("Location"))
			.firstOrNull { it?.contains("code=") == true }
			?: throw IllegalStateException("OAuth authorization code was not returned")
		val code = queryParameter(callbackUrl, "code")
			?: throw IllegalStateException("OAuth authorization code was not returned")
		val returnedState = queryParameter(callbackUrl, "state")
		if (returnedState != pkce.state) throw IllegalStateException("OAuth state mismatch")
		Log.d(TAG, "OAuth continuation status=${continuation.status}")

		val token = requestToken(code, pkce.verifier)
		val accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
			?: throw IllegalStateException("Token response did not contain access_token")
		val pumperId = discoverPumperId(session, accessToken, token)
		return TandemAuthContext(accessToken, pumperId)
	}

	private fun requestToken(code: String, verifier: String): JsonObject {
		val body = formEncode(
			linkedMapOf(
				"client_id" to CLIENT_ID,
				"grant_type" to "authorization_code",
				"code" to code,
				"code_verifier" to verifier,
				"redirect_uri" to REDIRECT_URI
			)
		).toByteArray(StandardCharsets.UTF_8)
		val response = HttpSession().request(
			url = "$ACCOUNTS_BASE_URL/connect/token",
			method = "POST",
			headers = mapOf(
				"Content-Type" to "application/x-www-form-urlencoded",
				"Accept" to "application/json",
				"Origin" to SOURCE_BASE_URL,
				"Referer" to "$SOURCE_BASE_URL/",
				"User-Agent" to USER_AGENT
			),
			body = body,
			followRedirects = false
		)
		if (response.status != HttpURLConnection.HTTP_OK) {
			throw IllegalStateException("Token exchange failed (HTTP ${response.status})")
		}
		return json.parseToJsonElement(response.body).jsonObject
	}

	private fun discoverPumperId(session: HttpSession, accessToken: String, token: JsonObject): String {
		val candidates = linkedSetOf<String>()
		listOf("id_token", "access_token").forEach { tokenName ->
			val jwt = token[tokenName]?.jsonPrimitive?.contentOrNull ?: return@forEach
			val payload = decodeJwtPayload(jwt)
			val preferredClaims = if (tokenName == "access_token") {
				listOf("tandem_pumper_id", "pumperId", "pumper_id")
			} else {
				listOf("pumperId", "pumper_id")
			}
			preferredClaims.forEach { claim ->
				payload[claim]?.jsonPrimitive?.contentOrNull?.takeIf(UUID_REGEX::matches)?.let(candidates::add)
			}
			collectUuidStrings(payload, candidates)
		}

		for (candidate in candidates) {
			val response = session.request(
				url = "$SOURCE_BASE_URL/api/pumpers/pumpers/$candidate",
				headers = mapOf(
					"Accept" to "application/json",
					"User-Agent" to USER_AGENT,
					"Authorization" to "Bearer $accessToken"
				),
				followRedirects = true,
				readTimeoutMs = 60_000
			)
			if (response.status != HttpURLConnection.HTTP_OK) continue
			val returnedId = runCatching {
				json.parseToJsonElement(response.body).jsonObject["id"]?.jsonPrimitive?.contentOrNull
			}.getOrNull()
			if (returnedId == candidate) return candidate
		}
		throw IllegalStateException("Unable to discover and validate pumperId")
	}

	private fun collectUuidStrings(element: JsonElement, output: MutableSet<String>) {
		when (element) {
			is JsonObject -> element.values.forEach { collectUuidStrings(it, output) }
			is kotlinx.serialization.json.JsonArray -> element.forEach { collectUuidStrings(it, output) }
			is JsonPrimitive -> element.contentOrNull?.takeIf(UUID_REGEX::matches)?.let(output::add)
		}
	}

	private fun tokenIsUsable(token: String): Boolean {
		val expiresAt = decodeJwtPayload(token)["exp"]?.jsonPrimitive?.longOrNull ?: return false
		return expiresAt > Instant.now().epochSecond + AUTH_EXPIRY_SKEW_SECONDS
	}

	private fun decodeJwtPayload(token: String): JsonObject = runCatching {
		val encoded = token.split('.')[1]
		val decoded = Base64.getUrlDecoder().decode(padBase64(encoded))
		json.parseToJsonElement(String(decoded, StandardCharsets.UTF_8)).jsonObject
	}.getOrElse { JsonObject(emptyMap()) }

	private fun buildPkce(): Pkce {
		val verifier = randomUrlSafe(64)
		val challenge = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
		return Pkce(verifier, challenge, randomUrlSafe(24), randomUrlSafe(24))
	}

	private fun randomUrlSafe(size: Int): String = ByteArray(size).also(random::nextBytes).let {
		Base64.getUrlEncoder().withoutPadding().encodeToString(it)
	}

	private fun queryParameter(url: String, name: String): String? {
		val rawQuery = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
		return rawQuery.split('&').mapNotNull { item ->
			val parts = item.split('=', limit = 2)
			if (decode(parts[0]) == name) decode(parts.getOrElse(1) { "" }) else null
		}.firstOrNull()
	}

	private fun formEncode(values: Map<String, String>): String = values.entries.joinToString("&") {
		"${encode(it.key)}=${encode(it.value)}"
	}

	private fun encode(value: String): String =
		URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

	private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
	private fun padBase64(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)

	private data class Pkce(val verifier: String, val challenge: String, val state: String, val nonce: String)

	private class HttpSession {
		private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

		fun request(
			url: String,
			method: String = "GET",
			headers: Map<String, String> = emptyMap(),
			body: ByteArray? = null,
			followRedirects: Boolean,
			readTimeoutMs: Int = 30_000
		): HttpResponse {
			var currentUrl = url
			var currentMethod = method
			var currentBody = body
			repeat(MAX_REDIRECTS + 1) { redirectIndex ->
				val uri = URI(currentUrl)
				val connection = URL(currentUrl).openConnection() as HttpURLConnection
				try {
					connection.instanceFollowRedirects = false
					connection.requestMethod = currentMethod
					connection.connectTimeout = 30_000
					connection.readTimeout = readTimeoutMs
					headers.forEach(connection::setRequestProperty)
					cookies.get(uri, emptyMap()).forEach { (name, values) ->
						connection.setRequestProperty(name, values.joinToString("; "))
					}
					if (currentBody != null && currentMethod != "GET") {
						connection.doOutput = true
						connection.outputStream.use { it.write(currentBody) }
					}
					val status = connection.responseCode
					val responseHeaders = connection.headerFields
						.filterKeys { it != null }
						.mapKeys { it.key!! }
					cookies.put(uri, responseHeaders)
					val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
						?.bufferedReader()?.use { it.readText() }.orEmpty()
					val location = responseHeaders.entries.firstOrNull { it.key.equals("Location", true) }
						?.value?.firstOrNull()
					if (followRedirects && status in REDIRECT_STATUSES && location != null) {
						if (redirectIndex == MAX_REDIRECTS) throw IllegalStateException("Too many OAuth redirects")
						currentUrl = URL(URL(currentUrl), location).toString()
						if (status != 307 && status != 308) {
							currentMethod = "GET"
							currentBody = null
						}
					} else {
						return HttpResponse(status, responseHeaders, responseBody, currentUrl)
					}
				} finally {
					connection.disconnect()
				}
			}
			throw IllegalStateException("OAuth request did not complete")
		}
	}

	private data class HttpResponse(
		val status: Int,
		val headers: Map<String, List<String>>,
		val body: String,
		val url: String
	) {
		fun firstHeader(name: String): String? =
			headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
	}

	private companion object {
		const val TAG = "TandemAuth"
		const val SOURCE_BASE_URL = "https://source.eu.tandemdiabetes.com"
		const val ACCOUNTS_BASE_URL = "https://tdcservices.eu.tandemdiabetes.com/accounts/api"
		const val CLIENT_ID = "1519e414-eeec-492e-8c5e-97bea4815a10"
		const val REDIRECT_URI = "https://source.eu.tandemdiabetes.com/authorize/callback"
		const val USER_AGENT = "Mozilla/5.0"
		const val AUTH_EXPIRY_SKEW_SECONDS = 90L
		const val MAX_REDIRECTS = 12
		val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
		val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
	}
}
