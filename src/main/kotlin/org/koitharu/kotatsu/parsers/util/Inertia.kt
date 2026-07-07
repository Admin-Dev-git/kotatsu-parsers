@file:JvmName("InertiaUtils")

package org.koitharu.kotatsu.parsers.util

import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * Parse Laravel/Inertia.js bootstrap payload embedded in the app root element.
 */
public fun Response.parseInertiaPage(): JSONObject {
	val doc = parseHtml()
	return doc.parseInertiaPage()
}

public fun Document.parseInertiaPage(): JSONObject {
	val raw = getElementById("app")?.attr("data-page")
		?: throw IllegalStateException("Inertia root element not found")
	if (raw.isEmpty()) {
		throw IllegalStateException("Inertia page payload is empty")
	}
	return JSONObject(Parser.unescapeEntities(raw, true))
}

public fun JSONObject.inertiaComponent(): String = getString("component")

public fun JSONObject.inertiaProps(): JSONObject = getJSONObject("props")
