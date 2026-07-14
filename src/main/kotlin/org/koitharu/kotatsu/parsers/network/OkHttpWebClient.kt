package org.koitharu.kotatsu.parsers.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.GraphQLException
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.parseJson
import java.net.HttpURLConnection

public class OkHttpWebClient(
	private val httpClient: OkHttpClient,
	private val mangaSource: MangaSource,
) : WebClient {

	override suspend fun httpGet(url: HttpUrl, extraHeaders: Headers?): Response {
		val request = Request.Builder()
			.get()
			.url(url)
			.addTags()
			.addExtraHeaders(extraHeaders)
		return httpClient.newCall(request.build()).await().ensureSuccess()
	}

	override suspend fun httpHead(url: HttpUrl): Response {
		val request = Request.Builder()
			.head()
			.url(url)
			.addTags()
		return httpClient.newCall(request.build()).await().ensureSuccess()
	}

	override suspend fun httpPost(url: HttpUrl, form: Map<String, String>, extraHeaders: Headers?): Response {
		val body = FormBody.Builder()
		form.forEach { (k, v) ->
			body.addEncoded(k, v)
		}
		val request = Request.Builder()
			.post(body.build())
			.url(url)
			.addTags()
			.addExtraHeaders(extraHeaders)
		return httpClient.newCall(request.build()).await().ensureSuccess()
	}

	override suspend fun httpPost(url: HttpUrl, payload: String, extraHeaders: Headers?): Response {
		val body = FormBody.Builder()
		payload.split('&').forEach {
			val pos = it.indexOf('=')
			if (pos != -1) {
				val k = it.substring(0, pos)
				val v = it.substring(pos + 1)
				body.addEncoded(k, v)
			}
		}
		val request = Request.Builder()
			.post(body.build())
			.url(url)
			.addTags()
			.addExtraHeaders(extraHeaders)
		return httpClient.newCall(request.build()).await().ensureSuccess()
	}

	override suspend fun httpPost(url: HttpUrl, body: JSONObject, extraHeaders: Headers?): Response {
		val mediaType = "application/json; charset=utf-8".toMediaType()
		val requestBody = body.toString().toRequestBody(mediaType)
		val request = Request.Builder()
			.post(requestBody)
			.url(url)
			.addTags()
			.addExtraHeaders(extraHeaders)
		return httpClient.newCall(request.build()).await().ensureSuccess()
	}

	override suspend fun graphQLQuery(endpoint: String, query: String): JSONObject {
		val body = JSONObject()
		body.put("operationName", null as Any?)
		body.put("variables", JSONObject())
		body.put("query", "{$query}")

		val mediaType = "application/json; charset=utf-8".toMediaType()
		val requestBody = body.toString().toRequestBody(mediaType)
		val request = Request.Builder()
			.post(requestBody)
			.url(endpoint)
			.addTags()
		val json = httpClient.newCall(request.build()).await().parseJson()
		json.optJSONArray("errors")?.let {
			if (it.length() != 0) {
				throw GraphQLException(it)
			}
		}
		return json
	}

	private fun Request.Builder.addTags(): Request.Builder {
		tag(MangaSource::class.java, mangaSource)
		return this
	}

	private fun Request.Builder.addExtraHeaders(headers: Headers?): Request.Builder {
		if (headers != null) {
			headers(headers)
		}
		return this
	}

	private fun Response.ensureSuccess(): Response {
		val requestUrl = request.url.toString()
		val exception: Exception? = when (code) {
			HttpURLConnection.HTTP_UNAUTHORIZED -> request.tag(MangaSource::class.java)?.let {
				AuthRequiredException(it)
			} ?: HttpStatusException(message, code, requestUrl)

			in 400..599 -> {
				if (code == HttpURLConnection.HTTP_FORBIDDEN || code == HttpURLConnection.HTTP_UNAVAILABLE || code == 429) {
					CloudFlareHelper.checkAndClear(httpClient.cookieJar, this)
				}
				HttpStatusException(message, code, requestUrl)
			}

			else -> null
		}
		if (exception != null) {
			runCatching {
				close()
			}.onFailure {
				exception.addSuppressed(it)
			}
			throw exception
		}
		return this
	}
}
