package com.suteny0r.skyspyaware

import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * FAA UAS Remote ID registration lookup. Mirrors the behavior of the Python
 * server: fetch a session cookie from the docs site, then query the
 * serialNumbers API for a basic_id. Runs off the UI thread.
 */
object FaaClient {

    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:137.0) Gecko/20100101 Firefox/137.0"

    fun lookup(basicId: String): String {
        try {
            CookieHandler.setDefault(CookieManager())
            session()
            val body = query(basicId)
            return summarize(body)
        } catch (e: Exception) {
            return "Lookup failed: ${e.message}"
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

    private fun query(basicId: String): String {
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
                c.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
        } finally {
            c.disconnect()
        }
    }

    private fun summarize(json: String): String {
        if (json.isBlank()) return "No registration data"
        return try {
            val items = JSONObject(json)
                .optJSONObject("data")?.optJSONArray("items")
            val n = items?.length() ?: 0
            if (n == 0) {
                "No FAA registration found"
            } else {
                val first = items!!.getJSONObject(0)
                val sb = StringBuilder("$n record(s) found")
                fun row(label: String, v: String) {
                    if (v.isNotBlank()) sb.append('\n').append(label).append(": ").append(v)
                }
                row("Make", first.optString("makeName"))
                row("Model", first.optString("modelName"))
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
                sb.toString()
            }
        } catch (e: Exception) {
            "Registered (parse error)"
        }
    }
}
