package com.suteny0r.skyspyaware

/**
 * Likely operator profile for a drone, derived from the aircraft category.
 * Used to reason about who is probably flying (recreational hobbyist vs
 * commercial / industrial operator vs public-safety agency).
 */
enum class PilotProfile(val label: String, val description: String) {
    RECREATIONAL("Recreational hobbyist", "Inexpensive consumer toy / trainer"),
    ENTHUSIAST("Enthusiast / prosumer", "Capable camera drone, likely skilled amateur or side business"),
    CINEMATOGRAPHY("Professional cinematography", "High-end cinema platform for paid film/video work"),
    COMMERCIAL("Commercial / industrial", "Inspection, mapping, agriculture or logistics operator"),
    PUBLIC_SAFETY("Public safety agency", "Police / fire / emergency services fleet"),
    UNKNOWN("Unknown", "No model attribution yet")
}

/** Aircraft category used for MSRP and pilot-profile estimation. */
enum class DroneCategory {
    TOY, PROSUMER, CINEMATOGRAPHY, INDUSTRIAL, PUBLIC_SAFETY
}

private val CATEGORY_PROFILE = mapOf(
    DroneCategory.TOY to PilotProfile.RECREATIONAL,
    DroneCategory.PROSUMER to PilotProfile.ENTHUSIAST,
    DroneCategory.CINEMATOGRAPHY to PilotProfile.CINEMATOGRAPHY,
    DroneCategory.INDUSTRIAL to PilotProfile.COMMERCIAL,
    DroneCategory.PUBLIC_SAFETY to PilotProfile.PUBLIC_SAFETY
)

/**
 * Reference catalog of known drone models with an estimated US MSRP and the
 * operator profile typically associated with that class of aircraft.
 *
 * Match keys are fuzzy: a detected model string matches a catalog entry if it
 * contains the key, or the key contains the (normalized) detected string.
 */
data class DroneModelSpec(
    val displayName: String,
    val msrpUsd: Int,
    val category: DroneCategory,
    val matchKeys: List<String>,
    val extraCategories: List<DroneCategory> = emptyList()
) {
    /** All categories this aircraft belongs to (primary first). */
    val categories: List<DroneCategory> get() = listOf(category) + extraCategories
    /** Every operator profile implied by its categories; a drone that is both
     *  commercial/industrial and public-safety is badged both ways. */
    val pilotProfiles: Set<PilotProfile>
        get() = categories.mapNotNull { CATEGORY_PROFILE[it] }.toSet()
    val pilotProfile: PilotProfile get() = CATEGORY_PROFILE[category] ?: PilotProfile.UNKNOWN
}

object DroneCatalog {
    val models = listOf(
        // --- DJI consumer / prosumer ---
        DroneModelSpec("DJI Mini 2", 449, DroneCategory.PROSUMER, listOf("mini 2")),
        DroneModelSpec("DJI Mini 3", 559, DroneCategory.PROSUMER, listOf("mini 3")),
        DroneModelSpec("DJI Mini 4 Pro", 999, DroneCategory.PROSUMER, listOf("mini 4 pro", "mini 4k")),
        DroneModelSpec("DJI Mini 5 Pro", 1129, DroneCategory.PROSUMER, listOf("mini 5 pro")),
        DroneModelSpec("DJI Air 2S", 999, DroneCategory.PROSUMER, listOf("air 2s")),
        DroneModelSpec("DJI Air 3", 1099, DroneCategory.PROSUMER, listOf("air 3")),
        DroneModelSpec("DJI Air 3S", 1099, DroneCategory.PROSUMER, listOf("air 3s")),
        DroneModelSpec("DJI Mavic Air 2", 799, DroneCategory.PROSUMER, listOf("mavic air 2")),
        DroneModelSpec("DJI Mavic Air", 799, DroneCategory.PROSUMER, listOf("mavic air")),
        DroneModelSpec("DJI Mavic 2 Pro", 1499, DroneCategory.PROSUMER, listOf("mavic 2 pro")),
        DroneModelSpec("DJI Mavic 2 Zoom", 1249, DroneCategory.PROSUMER, listOf("mavic 2 zoom")),
        DroneModelSpec("DJI Mavic Pro", 999, DroneCategory.PROSUMER, listOf("mavic pro")),
        DroneModelSpec("DJI Phantom 4 Pro", 1499, DroneCategory.PROSUMER, listOf("phantom 4 pro", "phantom 4")),
        DroneModelSpec("DJI Spark", 399, DroneCategory.PROSUMER, listOf("spark")),
        DroneModelSpec("DJI Mavic 3", 2049, DroneCategory.PROSUMER, listOf("mavic 3")),
        DroneModelSpec("DJI Mavic 2 Enterprise", 1999, DroneCategory.INDUSTRIAL, listOf("mavic 2 enterprise"), extraCategories = listOf(DroneCategory.PUBLIC_SAFETY)),
        DroneModelSpec("DJI Mavic 4 Pro", 2199, DroneCategory.PROSUMER, listOf("mavic 4 pro")),
        DroneModelSpec("DJI Avata 2", 999, DroneCategory.PROSUMER, listOf("avata 2", "avata 360", "avata")),
        DroneModelSpec("DJI FPV", 899, DroneCategory.PROSUMER, listOf("dji fpv")),
        DroneModelSpec("DJI Neo", 299, DroneCategory.TOY, listOf("neo")),
        // --- DJI cinema / industrial ---
        DroneModelSpec("DJI Inspire 2", 2999, DroneCategory.CINEMATOGRAPHY, listOf("inspire 2")),
        DroneModelSpec("DJI Inspire 3", 16599, DroneCategory.CINEMATOGRAPHY, listOf("inspire 3", "inspire")),
        DroneModelSpec("DJI Matrice 30", 9499, DroneCategory.INDUSTRIAL, listOf("matrice 30", "m30"), extraCategories = listOf(DroneCategory.PUBLIC_SAFETY)),
        DroneModelSpec("DJI Matrice 300 RTK", 12999, DroneCategory.INDUSTRIAL, listOf("matrice 300", "m300"), extraCategories = listOf(DroneCategory.PUBLIC_SAFETY)),
        DroneModelSpec("DJI Matrice 350 RTK", 14999, DroneCategory.INDUSTRIAL, listOf("matrice 350", "m350"), extraCategories = listOf(DroneCategory.PUBLIC_SAFETY)),
        DroneModelSpec("DJI Matrice 4E", 3599, DroneCategory.INDUSTRIAL, listOf("matrice 4e"), extraCategories = listOf(DroneCategory.PUBLIC_SAFETY)),
        DroneModelSpec("DJI Matrice 4T", 6499, DroneCategory.INDUSTRIAL, listOf("matrice 4t"), extraCategories = listOf(DroneCategory.PUBLIC_SAFETY)),
        DroneModelSpec("DJI Matrice 4P", 5499, DroneCategory.INDUSTRIAL, listOf("matrice 4p"), extraCategories = listOf(DroneCategory.PUBLIC_SAFETY)),
        DroneModelSpec("DJI Mavic 3 Enterprise", 4299, DroneCategory.INDUSTRIAL, listOf("mavic 3 enterprise", "m3e", "m3t", "mavic 3e", "mavic 3t"), extraCategories = listOf(DroneCategory.PUBLIC_SAFETY)),
        DroneModelSpec("DJI Agras T50", 19999, DroneCategory.INDUSTRIAL, listOf("agras", "t50")),
        DroneModelSpec("DJI FlyCart 30", 24999, DroneCategory.INDUSTRIAL, listOf("flycart")),
        // --- Autel ---
        DroneModelSpec("Autel EVO Nano", 649, DroneCategory.PROSUMER, listOf("evo nano")),
        DroneModelSpec("Autel EVO Lite", 999, DroneCategory.PROSUMER, listOf("evo lite")),
        DroneModelSpec("Autel EVO II", 1495, DroneCategory.PROSUMER, listOf("evo ii", "evo 2")),
        // --- Skydio ---
        DroneModelSpec("Skydio 2+", 1099, DroneCategory.PUBLIC_SAFETY, listOf("skydio 2", "skydio 2+"), extraCategories = listOf(DroneCategory.INDUSTRIAL)),
        DroneModelSpec("Skydio X10", 9999, DroneCategory.PUBLIC_SAFETY, listOf("skydio x10", "x10"), extraCategories = listOf(DroneCategory.INDUSTRIAL)),
        // --- Parrot / others ---
        DroneModelSpec("Parrot Anafi", 699, DroneCategory.PROSUMER, listOf("anafi")),
        DroneModelSpec("Parrot Bluegrass", 4999, DroneCategory.INDUSTRIAL, listOf("bluegrass")),
        // --- Public-safety / law-enforcement (non-DJI) ---
        DroneModelSpec("Teal 2", 4500, DroneCategory.PUBLIC_SAFETY, listOf("teal 2", "teal"), extraCategories = listOf(DroneCategory.INDUSTRIAL)),
        DroneModelSpec("Brinc Lemur", 9700, DroneCategory.PUBLIC_SAFETY, listOf("brinc lemur", "lemur")),
        DroneModelSpec("Parrot ANAFI USA", 7000, DroneCategory.PUBLIC_SAFETY, listOf("anafi usa")),
        DroneModelSpec("Draganfly Commander", 18000, DroneCategory.PUBLIC_SAFETY, listOf("draganfly"), extraCategories = listOf(DroneCategory.INDUSTRIAL)),
        DroneModelSpec("WingtraOne", 20000, DroneCategory.INDUSTRIAL, listOf("wingtra"), extraCategories = listOf(DroneCategory.PUBLIC_SAFETY))
    )

    private val index: List<Pair<String, DroneModelSpec>> =
        models.flatMap { m -> m.matchKeys.map { it to m } }

    /**
     * Normalize a make/model token: lowercase, drop parenthetical suffixes like
     * "(MA2UE3W)" that FCC registrations append to model names, and collapse
     * whitespace. This keeps "(serial)" suffixes from defeating a match.
     */
    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("\\s*\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Fuzzy lookup of a detected make/model string to a catalog spec. Matches
     * only when the query actually contains a known model token (e.g. "dji mavic
     * 4 pro" contains "mavic 4 pro"). A bare make with no model ("dji") must
     * NOT match anything: guessing a model would invent an MSRP and skew the
     * fleet-value / pilot-profile statistics. When several keys match, the
     * longest (most specific) wins so a generic key cannot shadow a specific
     * one (e.g. "inspire" must not beat "inspire 2").
     */
    fun match(make: String, model: String): DroneModelSpec? {
        val q = normalize("$make $model")
        if (q.isEmpty()) return null
        var best: DroneModelSpec? = null
        var bestLen = 0
        for ((key, spec) in index) {
            if (key.length >= 3 && key in q && key.length > bestLen) {
                best = spec
                bestLen = key.length
            }
        }
        return best
    }

    /** MSRP for a display label of the form "Make Model" (e.g. from modelCounts). */
    fun msrpForLabel(label: String): Int {
        val n = normalize(label)
        val i = n.indexOf(' ')
        val make = if (i >= 0) n.substring(0, i) else n
        val model = if (i >= 0) n.substring(i + 1) else ""
        return match(make, model)?.msrpUsd ?: 0
    }

    /** Operator profiles for a display label; an intersection drone yields both. */
    fun profilesForLabel(label: String): Set<PilotProfile> {
        val n = normalize(label)
        val i = n.indexOf(' ')
        val make = if (i >= 0) n.substring(0, i) else n
        val model = if (i >= 0) n.substring(i + 1) else ""
        return match(make, model)?.pilotProfiles ?: emptySet()
    }
}
