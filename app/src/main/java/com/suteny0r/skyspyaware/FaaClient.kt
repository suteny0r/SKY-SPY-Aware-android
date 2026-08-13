package com.suteny0r.skyspyaware

import org.json.JSONObject
import java.net.ConnectException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/** Result of a registration lookup; [retriable] marks transient failures. */
data class FaaLookup(
    val text: String,
    val retriable: Boolean,
    val make: String = "",
    val model: String = ""
)

/** Definitive result when a serial number has no FAA registration. */
const val FAA_NOT_FOUND = "No FAA registration found"

/**
 * FAA UAS Remote ID registration lookup. Mirrors the behavior of the Python
 * server: fetch a session cookie from the docs site, then query the
 * serialNumbers API for a basic_id. Runs off the UI thread.
 *
 * The serialNumbers endpoint is an undocumented internal API of the public
 * uasdoc.faa.gov web app with no published quotas. It is frequently slow, so
 * network failures are reported as retriable instead of being cached forever.
 */
object FaaClient {

    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:137.0) Gecko/20100101 Firefox/137.0"

    fun lookup(basicId: String): FaaLookup {
        return try {
            CookieHandler.setDefault(CookieManager())
            session()
            val (code, body) = query(basicId)
            when {
                code in 200..299 && body.isNotBlank() -> {
                    val s = summarize(body)
                    FaaLookup(s.text, false, s.make, s.model)
                }
                code == 429 ->
                    FaaLookup("Lookup failed: rate limited (429)", true)
                code in 500..599 ->
                    FaaLookup("Lookup failed: server error ($code)", true)
                code == 0 ->
                    FaaLookup("Lookup failed: no response", true)
                else ->
                    FaaLookup("No registration data", false)
            }
        } catch (e: SocketTimeoutException) {
            FaaLookup("Lookup failed: timeout", true)
        } catch (e: ConnectException) {
            FaaLookup("Lookup failed: ${e.message}", true)
        } catch (e: UnknownHostException) {
            FaaLookup("Lookup failed: no network", true)
        } catch (e: Exception) {
            FaaLookup("Lookup failed: ${e.message}", true)
        }
    }

    private fun session() {
        val c = URL("https://uasdoc.faa.gov/listdocs")
            .openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = 20_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", UA)
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun query(basicId: String): Pair<Int, String> {
        val params =
            "itemsPerPage=8&pageIndex=0" +
                "&orderBy%5B0%5D=updatedAt&orderBy%5B1%5D=DESC" +
                "&findBy=serialNumber&serialNumber=" +
                URLEncoder.encode(basicId, "UTF-8")
        val c = URL("https://uasdoc.faa.gov/api/v1/serialNumbers?$params")
            .openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "GET"
            c.connectTimeout = 20_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            c.setRequestProperty("Referer", "https://uasdoc.faa.gov/listdocs")
            c.setRequestProperty("client", "external")
            if (c.responseCode in 200..299) {
                c.responseCode to c.inputStream.bufferedReader().use { it.readText() }
            } else {
                c.responseCode to ""
            }
        } finally {
            c.disconnect()
        }
    }

    private fun summarize(json: String): FaaSummary {
        if (json.isBlank()) return FaaSummary("No registration data", "", "")
        return try {
            val items = JSONObject(json)
                .optJSONObject("data")?.optJSONArray("items")
            val n = items?.length() ?: 0
            if (n == 0) {
                FaaSummary(FAA_NOT_FOUND, "", "")
            } else {
                val first = items!!.getJSONObject(0)
                val make = first.optString("makeName")
                val model = first.optString("modelName")
                val sb = StringBuilder("$n record(s) found")
                fun row(label: String, v: String) {
                    if (v.isNotBlank()) sb.append('\n').append(label).append(": ").append(v)
                }
                row("Make", make)
                row("Model", model)
                row("Series", first.optString("series"))
                row("Tracking #", first.optString("trackingNumber"))
                row("Status", first.optString("status"))
                row("Category", first.optString("categoryDeclarationFor"))
                val compliance = first.optJSONArray("complianceCategories")
                if (compliance != null && compliance.length() > 0) {
                    val list = (0 until compliance.length()).joinToString(", ") {
                        val el = compliance.get(it)
                        if (el is String) el else el.toString()
                    }
                    row("Compliance", list)
                }
                row("Updated", first.optString("updatedAt"))
                FaaSummary(sb.toString(), make, model)
            }
        } catch (e: Exception) {
            FaaSummary("Registered (parse error)", "", "")
        }
    }

    private data class FaaSummary(val text: String, val make: String, val model: String)
}
