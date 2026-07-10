package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAGEKO", "MangaGeko", "en")
internal class MangaGeko(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAGEKO, 24) {

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.NEWEST)

	override val configKeyDomain = ConfigKey.Domain("www.mgeko.cc", "mgeko.cc", "www.mgeko.com")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val url = request.url
		// Add Referer for CDN image requests (imgsrv4.com, etc.)
		return if (url.host != domain && url.host.contains("imgsrv")) {
			val newRequest = request.newBuilder()
				.header("Referer", "https://$domain/")
				.build()
			chain.proceed(newRequest)
		} else {
			chain.proceed(request)
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty()) {
			val url = buildBrowseApiUrl(page, order, filter)
			val payload = webClient.httpGet(url).parseJson()
			val html = payload.getString("results_html")
			val results = parseComicCards(Jsoup.parse(html))
			if (results.isNotEmpty() || page > 1) {
				return results
			}
			return parseLegacySearchResults(filter.query)
		}

		val url = buildBrowseApiUrl(page, order, filter)
		val payload = webClient.httpGet(url).parseJson()
		val html = payload.getString("results_html")
		return parseComicCards(Jsoup.parse(html))
	}

	private suspend fun parseLegacySearchResults(query: String): List<Manga> {
		val doc = webClient.httpGet(
			"https://$domain/search/?search=${query.urlEncoded()}",
		).parseHtml()
		return doc.select("li.novel-item").map { div ->
			val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
			val author = div.selectFirst("h6")?.text()?.removePrefix("Author(S): ")?.nullIfEmpty()
			Manga(
				id = generateUid(href),
				title = div.selectFirstOrThrow("h4").text(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = div.selectFirstOrThrow("img").src()?.toDomainUrl(),
				tags = emptySet(),
				state = null,
				authors = setOfNotNull(author),
				source = source,
			)
		}
	}

	private fun buildBrowseApiUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
		return buildString {
			append("https://")
			append(domain)
			append("/browse-comics/data/?page=")
			append(page)
			append("&sort=")
			append(
				when (order) {
					SortOrder.POPULARITY -> "popular_weekly"
					SortOrder.UPDATED -> "latest"
					SortOrder.NEWEST -> "recently_added"
					else -> "latest"
				},
			)
			filter.query?.let { query ->
				append("&q=")
				append(query.urlEncoded())
			}
			if (filter.tags.isNotEmpty()) {
				val genreTags = filter.tags.filter { tag -> tag.key.any { !it.isDigit() } }
				val numericTags = filter.tags.filter { tag -> tag.key.all { it.isDigit() } }
				if (genreTags.isNotEmpty()) {
					append("&include_genres=")
					append(genreTags.joinToString(",") { it.key })
				}
				if (numericTags.isNotEmpty()) {
					append("&tags=")
					append(numericTags.joinToString(",") { it.key })
				}
			}
			if (filter.tagsExclude.isNotEmpty()) {
				append("&exclude_genres=")
				append(filter.tagsExclude.joinToString(",") { it.title })
			}
		}
	}

	private fun parseComicCards(doc: Document): List<Manga> {
		return doc.select("article.comic-card").map { card ->
			val link = card.selectFirst(".comic-card__title a")
				?: card.selectFirst(".comic-card__cover a")
				?: card.selectFirst("a[href]")
				?: throw IllegalStateException("Comic card link not found")
			val href = link.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				title = link.text().ifEmpty { card.selectFirst("img")?.attr("alt").orEmpty() },
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = card.selectFirst(".comic-card__cover img")?.src()?.toDomainUrl(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	/**
	 * Convert CDN URLs (imgsrv4.com) to source domain URLs so the app's
	 * cf_clearance cookie for mgeko.cc covers image requests too.
	 */
	private fun String.toDomainUrl(): String {
		if (contains("imgsrv4.com")) {
			val path = substringAfter("imgsrv4.com")
				.removePrefix("/avatar/288x412") // strip resize prefix if present
			return "https://$domain$path"
		}
		return this
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/browse-comics/").parseHtml()
		val genres = doc.select("button.chip[data-group=include_genres]").mapToSet { chip ->
			MangaTag(
				key = chip.attr("data-value"),
				title = chip.text(),
				source = source,
			)
		}
		val tagSearch = webClient.httpGet("https://$domain/get/tags/?tag=the").parseJsonArray()
		val apiTags = tagSearch.mapJSONNotNullToSet { jo ->
			val id = jo.opt("id")?.toString()?.nullIfEmpty() ?: return@mapJSONNotNullToSet null
			val name = jo.optString("tag_name").nullIfEmpty() ?: return@mapJSONNotNullToSet null
			MangaTag(
				key = id,
				title = name,
				source = source,
			)
		}
		return genres + apiTags
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val chapters = loadChapters(manga.url)
		return manga.copy(
			altTitles = setOfNotNull(doc.selectFirst(".alternative-title")?.textOrNull()),
			state = when (doc.selectFirst(".header-stats span:contains(Status) strong")?.text()?.lowercase()) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				else -> null
			},
			tags = doc.select(".categories ul li a").mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast('='),
					title = a.text(),
					source = source,
				)
			},
			authors = setOfNotNull(doc.selectFirst(".author")?.textOrNull()),
			description = doc.selectFirst(".description")?.html(),
			chapters = chapters,
		)
	}

	private suspend fun loadChapters(mangaUrl: String): List<MangaChapter> {
		val doc = webClient.httpGet("${mangaUrl.toAbsoluteUrl(domain).trimEnd('/')}/all-chapters/").parseHtml()
		val chaptersRoot = doc.getElementById("chapters") ?: doc.selectFirst("ul.chapter-list")?.parent()
		val dateFormat = SimpleDateFormat("MMM dd, yyyy", sourceLocale)
		return chaptersRoot?.select("ul.chapter-list > li")?.mapChapters(reversed = true) { i, li ->
			val a = li.selectFirst("a") ?: return@mapChapters null
			val url = a.attrAsRelativeUrl("href")
			val name = li.selectFirst(".chapter-title")?.text()?.trim()?.nullIfEmpty()
				?: a.attr("title")?.removePrefix("Chapter ")?.nullIfEmpty()
				?: url.substringAfterLast('/').removeSuffix('/')
			val dateText = li.selectFirst(".chapter-update")?.attr("datetime")
				?.substringBeforeLast(',')
				?.replace(".", "")
				?.replace("Sept", "Sep")
			MangaChapter(
				id = generateUid(url),
				title = name,
				number = i + 1f,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(dateText),
				branch = null,
				source = source,
			)
		}.orEmpty()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("img[src]")
			.mapNotNull { it.attr("src").nullIfEmpty() }
			.filterNot { src ->
				src.startsWith("data:image") ||
					src.contains("credits-mgeko.png") ||
					src.contains("loading_api_transparent") ||
					src.contains("logo_200x200")
			}
			.distinct()
			.map { url ->
				val finalUrl = if (url.startsWith("http")) url.toDomainUrl() else url.toAbsoluteUrl(domain)
				MangaPage(
					id = generateUid(finalUrl),
					url = finalUrl,
					preview = null,
					source = source,
				)
			}
	}
}
