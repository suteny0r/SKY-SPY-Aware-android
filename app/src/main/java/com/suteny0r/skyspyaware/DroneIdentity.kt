package com.suteny0r.skyspyaware

/**
 * Best-effort drone make/model attribution.
 *
 * Primary source is the FAA UAS registration lookup text cached per basic_id
 * (lines like "Make: DJI" / "Model: Mini 5 Pro"). When that is unavailable the
 * make falls back to heuristics: the registered OUI of the MAC address, or the
 * well-known manufacturer prefix seen in Remote ID serial numbers. Model is
 * only reported when the FAA registration actually names one.
 */
object DroneIdentity {

    /** MAC OUI (first 3 bytes, lowercase) -> manufacturer, where confident. */
    private val OUI_MAKES = mapOf(
        "60:60:1f" to "DJI",
        // Beijing Unigroup Tsingteng and AMPAK are radio-module vendors that
        // appear in Wi-Fi/Bluetooth MACs; not a confident drone make, so we do
        // not label drones by them.
    )

    /** Serial-number prefix -> manufacturer for Remote ID basic_ids. */
    private val SERIAL_MAKES = mapOf(
        "1581" to "DJI"
    )

    /** Parse "Make:" / "Model:" lines out of cached FAA registration text. */
    fun faaMakeModel(faaText: String?): Pair<String, String> {
        if (faaText.isNullOrBlank()) return "" to ""
        var make = ""
        var model = ""
        for (line in faaText.lineSequence()) {
            val s = line.trim()
            if (make.isEmpty() && s.startsWith("Make:", ignoreCase = true)) {
                make = s.substringAfter(':').trim()
            } else if (model.isEmpty() && s.startsWith("Model:", ignoreCase = true)) {
                model = s.substringAfter(':').trim()
            }
            if (make.isNotEmpty() && model.isNotEmpty()) break
        }
        return make to model
    }

    /** Resolve make/model for a drone keyed by basicId-or-mac. */
    fun resolve(basicId: String, mac: String, faaText: String?): Pair<String, String> {
        val (make, model) = faaMakeModel(faaText)
        if (make.isNotEmpty()) return make to model
        val ouiMake = mac.takeIf { it.length >= 8 }?.substring(0, 8)?.lowercase()
            ?.let { OUI_MAKES[it] }
        if (ouiMake != null) return ouiMake to model
        if (basicId.length >= 4) {
            val serialMake = SERIAL_MAKES[basicId.substring(0, 4)]
            if (serialMake != null) return serialMake to model
        }
        return make to model
    }
}
