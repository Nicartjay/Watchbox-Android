package eu.kanade.tachiyomi.util

import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Jsoup helpers extensions call.
 *
 * Top-level functions here compile to static members of `JsoupExtensionsKt`,
 * which is the name the surveyed extensions reference, so this file must keep
 * its name.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI.
 */

/**
 * Parses the response body as HTML.
 *
 * The final request URL is used as the base URI, not the original one, so
 * relative links resolve correctly after a redirect. The body is consumed here;
 * callers must not read it again.
 */
fun Response.asJsoup(html: String? = null): Document {
    val body = html ?: body?.string().orEmpty()
    return Jsoup.parse(body, request.url.toString())
}
