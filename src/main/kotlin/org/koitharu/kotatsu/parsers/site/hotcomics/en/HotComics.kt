package org.koitharu.kotatsu.parsers.site.hotcomics.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.hotcomics.HotComicsParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("HOTCOMICS", "HotComics", "en")
internal class HotComics(context: MangaLoaderContext) :
	HotComicsParser(context, MangaParserSource.HOTCOMICS, "hotcomics.me/en") {

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(mangaUrl).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)

		val chapters = doc.select(selectMangaChapters).mapChapters { i, li ->
			val a = li.selectFirstOrThrow("a")
			val url = extractChapterUrl(a)
			val chapterNum = li.selectFirst(".num")?.text()?.toFloat() ?: (i + 1f)
			MangaChapter(
				id = generateUid(url),
				title = null,
				number = chapterNum,
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
			?: doc.selectFirst("div.title_content_box h2")?.text()

		return manga.copy(
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
