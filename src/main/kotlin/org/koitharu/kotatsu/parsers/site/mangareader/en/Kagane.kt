package org.koitharu.kotatsu.parsers.site.mangareader.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("KAGANE", "Kagane", "en")
internal class Kagane(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KAGANE, "kagane.to", pageSize = 10, searchPageSize = 10)
