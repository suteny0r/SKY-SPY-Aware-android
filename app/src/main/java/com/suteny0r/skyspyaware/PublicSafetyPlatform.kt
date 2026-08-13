package com.suteny0r.skyspyaware

import java.util.Locale

/**
 * Best-effort heuristic that flags drone makes/models commonly operated by
 * public-safety agencies (police, fire, government). This is a guess based on
 * the platform, not confirmation of who operates a given drone, since those
 * models are also used commercially.
 */
object PublicSafetyPlatform {

    fun label(make: String, model: String): String? {
        val m = (make + " " + model).uppercase(Locale.US)
        return when {
            "MATRICE" in m -> "Possible public-safety platform (DJI Matrice)"
            "SKYDIO" in m -> "Possible public-safety platform (Skydio)"
            "SIRAS" in m -> "Possible public-safety platform (Teledyne SIRAS)"
            "LEMUR" in m -> "Possible public-safety platform (BRINC Lemur)"
            "ANAFI USA" in m -> "Possible public-safety platform (Parrot Anafi USA)"
            "EVO MAX" in m -> "Possible public-safety platform (Autel EVO Max)"
            "HOVER UAV" in m -> "Possible public-safety platform (Hover UAV)"
            "ELISTAIR" in m -> "Possible public-safety platform (Elistair)"
            else -> null
        }
    }
}
