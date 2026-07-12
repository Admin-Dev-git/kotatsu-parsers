package org.koitharu.kotatsu.parsers.site.hotcomics.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.hotcomics.HotComicsParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("TOOMICSEN", "TooMics English", "en")
internal class TooMicsEn(context: MangaLoaderContext) :
	HotComicsParser(context, MangaParserSource.TOOMICSEN, "toomics.com/en") {
	override val isSearchSupported = false
	override val mangasUrl = "/webtoon/ranking/genre"
	override val selectMangas = "li > div.visual"
	override val selectMangaChapters = "li.normal_ep:has(.coin-type1)"
	override val selectTagsList = "div.genre_list li:not(.on) a"
	override val selectPages = "div[id^=load_image_] img"
	override val onePage = true

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(mangaUrl).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)

		val chapters = doc.select(selectMangaChapters).mapChapters { i, li ->
			val a = li.selectFirstOrThrow("a")
			val url = extractChapterUrl(a)
			MangaChapter(
				id = generateUid(url),
				title = null,
				number = li.selectFirst(".num")?.text()?.toFloat() ?: (i + 1f),
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(li.selectFirst("time")?.attr("datetime")),
				branch = null,
				source = source,
			)
		}

		val coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
		val description = doc.selectFirst("meta[property=og:description]")?.attr("content")
		val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
		val title = ogTitle?.removePrefix("Toomics - ")?.nullIfEmpty() ?: manga.title

		return manga.copy(
			title = title,
			description = description ?: manga.description,
			coverUrl = coverUrl ?: manga.coverUrl,
			chapters = chapters,
		)
	}

	private fun extractChapterUrl(a: org.jsoup.nodes.Element): String {
		val href = a.attr("href")
		return if (href.startsWith("/")) {
			"/" + href.removePrefix("/").substringAfter('/')
		} else if (href.startsWith("javascript")) {
			val onclick = a.attr("onclick")
			val h = onclick.substringAfter("location.href='").substringBefore("'")
				.ifEmpty { onclick.substringAfter("popupLogin('").substringBefore("'") }
			"/" + h.removePrefix("/").substringAfter('/')
		} else {
			href
		}
	}
}
