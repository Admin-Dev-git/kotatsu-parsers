package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("SORA_FANS", "Sora.fans", "en")
internal class SoraFans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.SORA_FANS, pageSize = 24),
	MangaParserAuthProvider {

	override val configKeyDomain = ConfigKey.Domain("sora.fans")
	private val appId = "6a239b5e012a70fb928795c5"
	private val apiBaseUrl = "https://sora.fans/api/apps/$appId/entities"

	override val authUrl: String
		get() = "https://$domain"

	override suspend fun isAuthorized(): Boolean {
		return context.cookieJar.getCookies(domain).isNotEmpty()
	}

	override suspend fun getUsername(): String {
		val url = "$apiBaseUrl/User/me"
		val response = runCatching { webClient.httpGet(url).parseJson() }.getOrNull()
		return response?.optString("email")?.nullIfEmpty()
			?: response?.optString("name")?.nullIfEmpty()
			?: if (isAuthorized()) "Logged In User" else throw AuthRequiredException(source)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "$apiBaseUrl/Manga?limit=1000"
		val response = runCatching { webClient.httpGet(url).parseJsonArray() }.getOrNull() ?: return emptyList()
		val query = filter.query?.trim()?.lowercase()
		val tagKey = filter.tags.firstOrNull()?.key?.lowercase()
		val filtered = response.mapJSONNotNull { item ->
			val manga = parseMangaItem(item)
			val titleMatches = query.isNullOrEmpty() || manga.title.lowercase().contains(query) || manga.authors.any { it.lowercase().contains(query) }
			val tagMatches = tagKey.isNullOrEmpty() || manga.tags.any { it.key.lowercase() == tagKey }
			if (titleMatches && tagMatches) manga else null
		}
		val fromIndex = (page - 1) * pageSize
		if (fromIndex >= filtered.size) return emptyList()
		val toIndex = (fromIndex + pageSize).coerceAtMost(filtered.size)
		return filtered.subList(fromIndex, toIndex)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaId = manga.url.substringAfterLast('/')
		val url = "$apiBaseUrl/Manga/$mangaId"
		val m = webClient.httpGet(url).parseJson()
		val chaptersUrl = "$apiBaseUrl/Chapter?manga_id=$mangaId&limit=1000"
		val chaptersArray = runCatching { webClient.httpGet(chaptersUrl).parseJsonArray() }.getOrDefault(JSONArray())

		val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
		dateFormat.timeZone = TimeZone.getTimeZone("UTC")

		val chapters = chaptersArray.mapJSON { jo ->
			val id = jo.getString("id")
			val chNumber = jo.optDouble("chapter_number", 0.0).toFloat()
			val chTitle = jo.optString("title").ifEmpty { "Chapter $chNumber" }
			MangaChapter(
				id = generateUid(id),
				title = chTitle,
				number = chNumber,
				volume = 0,
				url = "/chapter/$id",
				scanlator = null,
				uploadDate = dateFormat.parseSafe(jo.optString("release_date").nullIfEmpty()),
				branch = null,
				source = source,
			)
		}.reversed()

		val descriptionText = m.optString("description").nullIfEmpty()
		val authorsSet: Set<String> = m.optString("author").nullIfEmpty()?.let { setOf(it) }.orEmpty()
		val statusState: MangaState? = when (m.optString("status").lowercase()) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			else -> null
		}
		val ratingVal: Float = m.optDouble("rating", RATING_UNKNOWN.toDouble()).toFloat()

		return manga.copy(
			title = m.optString("title").ifEmpty { manga.title },
			coverUrl = m.optString("cover_url").nullIfEmpty() ?: manga.coverUrl,
			description = descriptionText,
			tags = parseTags(m.optJSONArray("genres")),
			authors = authorsSet,
			state = statusState,
			rating = ratingVal,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast('/')
		val url = "$apiBaseUrl/Chapter/$chapterId"
		val jo = webClient.httpGet(url).parseJson()
		val images = jo.optJSONArray("pages") ?: return emptyList()
		val len = images.length()
		val pages = ArrayList<MangaPage>(len)
		for (i in 0 until len) {
			val imgUrl = images.getString(i)
			pages.add(
				MangaPage(
					id = generateUid(imgUrl),
					url = imgUrl,
					preview = null,
					source = source,
				),
			)
		}
		return pages
	}

	private fun parseMangaItem(item: JSONObject): Manga {
		val id = item.getString("id")
		val url = "/manga/$id"
		val authorsSet: Set<String> = item.optString("author").nullIfEmpty()?.let { setOf(it) }.orEmpty()
		val statusState: MangaState? = when (item.optString("status").lowercase()) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			else -> null
		}
		val ratingVal: Float = item.optDouble("rating", RATING_UNKNOWN.toDouble()).toFloat()

		return Manga(
			id = generateUid(id),
			title = item.getString("title"),
			altTitles = emptySet(),
			url = url,
			publicUrl = "https://$domain$url",
			rating = ratingVal,
			contentRating = null,
			coverUrl = item.optString("cover_url").nullIfEmpty(),
			tags = parseTags(item.optJSONArray("genres")),
			state = statusState,
			authors = authorsSet,
			source = source,
		)
	}

	private fun parseTags(genresArray: JSONArray?): Set<MangaTag> {
		if (genresArray == null) return emptySet()
		val tags = HashSet<MangaTag>()
		for (i in 0 until genresArray.length()) {
			val genreObj = genresArray.opt(i) ?: continue
			val title = if (genreObj is JSONObject) genreObj.optString("name") else genreObj.toString()
			if (title.isNotEmpty()) {
				tags.add(MangaTag(key = title.lowercase(), title = title, source = source))
			}
		}
		return tags
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val url = "$apiBaseUrl/Manga?limit=1000"
		val list = runCatching { webClient.httpGet(url).parseJsonArray() }.getOrNull() ?: return emptySet()
		val tags = HashSet<MangaTag>()
		for (i in 0 until list.length()) {
			val item = list.optJSONObject(i) ?: continue
			tags.addAll(parseTags(item.optJSONArray("genres")))
		}
		return tags
	}
}
