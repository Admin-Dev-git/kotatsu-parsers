package org.koitharu.kotatsu.parsers.site.en

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MGREAD", "MgRead", "en")
internal class MgRead(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MGREAD, pageSize = 24, searchPageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("mgread.io")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	private fun decodeHtml(raw: String): String = Jsoup.parse(raw).text()

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val json = webClient.httpGet("https://$domain/wp-json/wp/v2/genre?per_page=100").parseJsonArray()
		return json.mapJSONToSet { jo ->
			MangaTag(
				key = jo.getString("slug"),
				title = jo.getString("name").toTitleCase(),
				source = source,
			)
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty()) {
			if (page > 1) return emptyList()
			val url = "https://$domain/wp-json/wp/v2/manga?search=${filter.query.urlEncoded()}&per_page=24&_embed=wp:featuredmedia"
			return parseMangaList(webClient.httpGet(url).parseJsonArray())
		}
		val orderParam = when (order) {
			SortOrder.NEWEST -> "date"
			SortOrder.ALPHABETICAL -> "title"
			else -> "modified"
		}
		val url = buildString {
			append("https://")
			append(domain)
			append("/wp-json/wp/v2/manga?per_page=$pageSize&page=$page&orderby=")
			append(orderParam)
			append("&order=desc&_embed=wp:featuredmedia")
			filter.tags.firstOrNull()?.let { tag ->
				append("&genre=")
				append(tag.key)
			}
		}
		return parseMangaList(webClient.httpGet(url).parseJsonArray())
	}

	private fun parseMangaList(jsonArray: JSONArray): List<Manga> = jsonArray.mapJSON { jo ->
		val link = jo.getString("link")
		val slug = jo.getString("slug")
		val cover = jo.optJSONObject("_embedded")
			?.optJSONArray("wp:featuredmedia")
			?.optJSONObject(0)
			?.optString("source_url")
		Manga(
			id = generateUid(slug),
			url = link.toRelativeUrl(domain),
			publicUrl = link,
			title = decodeHtml(jo.getJSONObject("title").getString("rendered")),
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
			contentRating = null,
			largeCoverUrl = null,
			coverUrl = cover,
			description = jo.optJSONObject("content")?.optString("rendered")?.let(::decodeHtml),
			chapters = null,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfter("/manga/").trim('/')
		val apiUrl = "https://$domain/wp-json/wp/v2/manga?slug=$slug&_embed=wp:featuredmedia,wp:term"
		val jo = webClient.httpGet(apiUrl).parseJsonArray().optJSONObject(0)
			?: return parseDetailsHtml(manga)
		val embedded = jo.optJSONObject("_embedded")
		val cover = embedded?.optJSONArray("wp:featuredmedia")?.optJSONObject(0)?.optString("source_url")
		val tags = embedded?.optJSONArray("wp:term")?.optJSONArray(0)?.mapJSONToSet { term ->
			MangaTag(
				key = term.getString("slug"),
				title = term.getString("name").toTitleCase(),
				source = source,
			)
		} ?: emptySet()
		val doc = webClient.httpGet(manga.publicUrl).parseHtml()
		val state = doc.selectFirst("#manga-status")?.text()?.let {
			when {
				it.contains("ongoing", true) -> MangaState.ONGOING
				it.contains("completed", true) || it.contains("finished", true) -> MangaState.FINISHED
				else -> null
			}
		}
		return manga.copy(
			title = decodeHtml(jo.getJSONObject("title").getString("rendered")),
			coverUrl = cover ?: manga.coverUrl,
			description = doc.selectFirst("#manga-description")?.text()
				?: jo.optJSONObject("content")?.optString("rendered")?.let(::decodeHtml),
			tags = tags,
			state = state,
			chapters = getChapters(doc, manga.url),
		)
	}

	private suspend fun parseDetailsHtml(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.publicUrl).parseHtml()
		return manga.copy(
			description = doc.selectFirst("#manga-description")?.text(),
			chapters = getChapters(doc, manga.url),
		)
	}

	private fun getChapters(doc: Document, @Suppress("UNUSED_PARAMETER") mangaUrl: String): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
		return doc.select("div.chapter-item").mapChapters(reversed = true) { i, element ->
			val a = element.selectFirst("a[href*=chapter-]") ?: return@mapChapters null
			val href = a.attr("href").ifEmpty { a.attr("abs:href") }
			val url = href.toRelativeUrl(domain)
			val name = element.selectFirst("h3")?.text()?.substringAfter("–")?.trim()
				?: href.substringAfter("chapter-").trim('/').let { "Chapter $it" }
			val dateStr = element.selectFirst("time[datetime]")?.attr("datetime")?.substringBefore('+')
			MangaChapter(
				id = generateUid(url),
				title = name,
				number = i + 1f,
				volume = 0,
				url = url,
				uploadDate = dateStr?.let { runCatching { dateFormat.parse(it)?.time ?: 0L }.getOrDefault(0L) } ?: 0L,
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("div.chapter-content img[src]").mapIndexed { _, img ->
			val url = img.attr("src").ifEmpty { img.attr("abs:src") }
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
