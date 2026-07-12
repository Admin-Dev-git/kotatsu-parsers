package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("TOONDEX", "Toondex", "en")
internal class Toondex(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TOONDEX, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("toondex.io")

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
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/search?q=")
					append(filter.query.urlEncoded())
					if (page > 1) append("&page=").append(page)
				}
				filter.tags.isNotEmpty() -> {
					append("/genres/")
					append(filter.tags.first().key)
					if (page > 1) append("?page=").append(page)
				}
				else -> {
					append("/latest")
					if (page > 1) append("?page=").append(page)
				}
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		val pageProps = extractPageProps(doc)
		val items = pageProps.optJSONArray("items") ?: pageProps.optJSONArray("ssrItems")
			?: return emptyList()
		return items.mapJSON { item -> parseMangaItem(item) }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val pageProps = extractPageProps(doc)
		val m = pageProps.getJSONObject("initialManga")
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
		dateFormat.timeZone = TimeZone.getTimeZone("UTC")
		val chapters = m.optJSONArray("chapters")?.mapJSON { jo ->
			val slug = jo.getString("slug")
			MangaChapter(
				id = generateUid(slug),
				title = jo.optString("name").nullIfEmpty(),
				number = jo.optDouble("number", 0.0).toFloat(),
				volume = 0,
				url = jo.getString("url"),
				scanlator = null,
				uploadDate = dateFormat.parseSafe(jo.optString("updatedAt").nullIfEmpty()),
				branch = null,
				source = source,
			)
		}.orEmpty()
		return manga.copy(
			title = m.optString("name").ifEmpty { manga.title },
			altTitles = m.optJSONArray("altNames")?.mapJSONNotNull { it.optString("name").nullIfEmpty() }
				?.toSet().orEmpty(),
			coverUrl = m.optString("cover").nullIfEmpty() ?: manga.coverUrl,
			description = m.optString("summary").nullIfEmpty(),
			tags = m.optJSONArray("genres")?.mapJSONNotNullToSet { jo ->
				val key = jo.optString("slug").nullIfEmpty() ?: return@mapJSONNotNullToSet null
				MangaTag(
					key = key,
					title = jo.optString("name"),
					source = source,
				)
			}.orEmpty(),
			authors = m.optJSONArray("authors")?.mapJSONNotNullToSet { jo ->
				jo.optString("name").nullIfEmpty()
			}.orEmpty(),
			state = when (m.optString("status").lowercase()) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				else -> null
			},
			rating = m.optDouble("rating", RATING_UNKNOWN.toDouble()).toFloat(),
			contentRating = if (m.optBoolean("isAdult")) ContentRating.ADULT else null,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val pageProps = extractPageProps(doc)
		val images = pageProps.getJSONObject("initialChapter").getJSONArray("images")
		return images.mapJSON { url ->
			MangaPage(
				id = generateUid(url.toString()),
				url = url.toString(),
				preview = null,
				source = source,
			)
		}
	}

	private fun extractPageProps(doc: Document): JSONObject {
		val script = doc.selectFirst("script#__NEXT_DATA__")
			?: throw IllegalStateException("__NEXT_DATA__ not found")
		val json = JSONObject(script.data())
		return json.getJSONObject("props").getJSONObject("pageProps")
	}

	private fun parseMangaItem(item: JSONObject): Manga {
		val url = item.getString("url")
		val slug = item.optString("slug").nullIfEmpty() ?: url.substringAfterLast('/').substringBefore('?')
		return Manga(
			id = generateUid(slug),
			title = item.getString("name"),
			altTitles = emptySet(),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = item.optDouble("rating", RATING_UNKNOWN.toDouble()).toFloat(),
			contentRating = if (item.optBoolean("isAdult")) ContentRating.ADULT else null,
			coverUrl = item.optString("cover").nullIfEmpty(),
			tags = emptySet(),
			state = when (item.optString("status").lowercase()) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				else -> null
			},
			authors = emptySet(),
			source = source,
		)
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/genres").parseHtml()
		val pageProps = extractPageProps(doc)
		val genres = pageProps.getJSONArray("genres")
		return genres.mapJSONNotNullToSet { jo ->
			val slug = jo.optString("slug").nullIfEmpty() ?: return@mapJSONNotNullToSet null
			MangaTag(
				key = slug,
				title = jo.optString("name"),
				source = source,
			)
		}
	}
}
