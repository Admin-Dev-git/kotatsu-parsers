package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.*

@MangaSourceParser("MANHWA18", "Manhwa18.net", "en", type = ContentType.HENTAI)
internal class Manhwa18Parser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANHWA18, pageSize = 18, searchPageSize = 18) {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("manhwa18.net", "www.manhwa18.net")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(
			SortOrder.UPDATED,
			SortOrder.POPULARITY,
			SortOrder.ALPHABETICAL,
			SortOrder.NEWEST,
			SortOrder.RATING,
		)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = tagsMap.get().values.toSet(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
		),
	)

	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon("https://$domain/favicon.ico", 32, null),
			),
			domain,
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/tim-kiem?page=")
			append(page.toString())
			filter.query?.let {
				append("&q=")
				append(it.urlEncoded())
			}
			append("&accept_genres=")
			if (filter.tags.isNotEmpty()) {
				append(filter.tags.joinToString(",") { it.key })
			}
			append("&reject_genres=")
			if (filter.tagsExclude.isNotEmpty()) {
				append(filter.tagsExclude.joinToString(",") { it.key })
			}
			append("&sort=")
			append(
				when (order) {
					SortOrder.ALPHABETICAL -> "az"
					SortOrder.ALPHABETICAL_DESC -> "za"
					SortOrder.POPULARITY -> "top"
					SortOrder.UPDATED -> "update"
					SortOrder.NEWEST -> "new"
					SortOrder.RATING -> "like"
					else -> "update"
				},
			)
			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				append(
					when (it) {
						MangaState.ONGOING -> "1"
						MangaState.FINISHED -> "3"
						MangaState.PAUSED -> "2"
						else -> ""
					},
				)
			}
		}
		val props = webClient.httpGet(url).parseInertiaPage().inertiaProps()
		val mangas = props.getJSONObject("mangas").getJSONArray("data")
		return mangas.mapJSONNotNull { jo -> jo.toManga() }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val props = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseInertiaPage().inertiaProps()
		val details = props.getJSONObject("manga")
		val slug = details.getString("slug")
		val chaptersJson = props.optJSONArray("chapters") ?: JSONArray()
		return manga.copy(
			title = details.getString("name"),
			altTitles = setOfNotNull(details.optString("other_name").nullIfEmpty()),
			authors = emptySet(),
			description = details.optString("pilot").nullIfEmpty(),
			tags = parseMangaTags(details),
			state = details.optInt("status_id", 0).toMangaState(),
			coverUrl = details.optString("thumb_url").nullIfEmpty()
				?: details.optString("cover_url").nullIfEmpty(),
			largeCoverUrl = details.optString("cover_url").nullIfEmpty(),
			chapters = chaptersJson.mapChapters(reversed = true) { index, chapter ->
				val chapterSlug = chapter.getString("slug")
				val chapterUrl = "/manga/$slug/$chapterSlug"
				MangaChapter(
					id = generateUid(chapterUrl),
					title = chapter.optString("name").nullIfEmpty(),
					number = chapter.optDouble("order", (index + 1).toDouble()).toFloat(),
					volume = 0,
					url = chapterUrl,
					scanlator = null,
					uploadDate = parseIsoDate(chapter.optString("updated_at")),
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseInertiaPage().inertiaProps()
		val html = doc.optString("chapterContent")
		if (html.isEmpty()) {
			return emptyList()
		}
		return Jsoup.parse(html, "https://$domain").select("img[src]").mapNotNull { img ->
			val url = img.absUrl("src").nullIfEmpty()
				?: img.attr("src").nullIfEmpty()
				?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun JSONObject.toManga(): Manga {
		val slug = getString("slug")
		val url = "/manga/$slug"
		return Manga(
			id = generateUid(url),
			title = getString("name"),
			altTitles = setOfNotNull(optString("other_name").nullIfEmpty()),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = optDouble("rating_average", 0.0).toFloat().takeIf { it > 0f } ?: RATING_UNKNOWN,
			contentRating = ContentRating.ADULT,
			coverUrl = optString("thumb_url").nullIfEmpty() ?: optString("cover_url").nullIfEmpty(),
			largeCoverUrl = optString("cover_url").nullIfEmpty(),
			tags = emptySet(),
			state = optInt("status_id", 0).toMangaState(),
			authors = emptySet(),
			description = null,
			source = source,
		)
	}

	private fun parseMangaTags(details: JSONObject): Set<MangaTag> {
		val result = LinkedHashSet<MangaTag>()
		details.optJSONArray("genres")?.let { genres ->
			for (i in 0 until genres.length()) {
				val item = genres.optJSONObject(i) ?: continue
				val id = item.opt("id")?.toString()?.nullIfEmpty() ?: continue
				val name = item.optString("name").nullIfEmpty() ?: continue
				result += MangaTag(
					key = id,
					title = name,
					source = source,
				)
			}
		}
		details.optJSONArray("tags")?.let { tags ->
			for (i in 0 until tags.length()) {
				val item = tags.optJSONObject(i) ?: continue
				val id = item.opt("id")?.toString()?.nullIfEmpty() ?: continue
				val name = item.optString("name").nullIfEmpty() ?: continue
				result += MangaTag(
					key = id,
					title = name,
					source = source,
				)
			}
		}
		return result
	}

	private fun Int.toMangaState(): MangaState? = when (this) {
		1 -> MangaState.ONGOING
		2 -> MangaState.PAUSED
		3 -> MangaState.FINISHED
		else -> null
	}

	private fun parseIsoDate(raw: String?): Long {
		raw ?: return 0L
		return runCatching {
			java.time.Instant.parse(raw).toEpochMilli()
		}.getOrDefault(0L)
	}

	private val tagsMap = suspendLazy(initializer = ::parseTags)

	private suspend fun parseTags(): Map<String, MangaTag> {
		val props = webClient.httpGet("https://$domain/tim-kiem").parseInertiaPage().inertiaProps()
		val genres = props.optJSONArray("genres") ?: return emptyMap()
		val result = LinkedHashMap<String, MangaTag>(genres.length())
		for (i in 0 until genres.length()) {
			val item = genres.optJSONObject(i) ?: continue
			val id = item.opt("id")?.toString()?.nullIfEmpty() ?: continue
			val name = item.optString("name").nullIfEmpty() ?: continue
			result[name.lowercase(Locale.ENGLISH)] = MangaTag(
				title = name.toTitleCase(Locale.ENGLISH),
				key = id,
				source = source,
			)
		}
		return result
	}
}
