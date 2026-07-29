package org.koitharu.kotatsu.parsers.site.madara.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MANHUATOP", "ManhuaTop", "en")
internal class ManhuaTop(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANHUATOP, "manhuatop.org") {
	override val listUrl = "manhua/"
	override val withoutAjax = true
}
