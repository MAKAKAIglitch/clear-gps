package org.cleargps

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class OpenStreetMapCellSiteLookupStatus {
    FOUND, NOT_FOUND, RATE_LIMITED, INVALID_REQUEST, TEMPORARY_ERROR, INVALID_RESPONSE
}

enum class OpenStreetMapElementType {
    NODE, WAY, RELATION;

    companion object {
        fun parse(value: String): OpenStreetMapElementType? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

data class OpenStreetMapCellSiteObject(
    val elementType: OpenStreetMapElementType,
    val osmId: Long,
    val latitude: Double,
    val longitude: Double,
    val coordinateUncertaintyMeters: Double,
    val manMade: String?,
    val towerType: String?,
    val mobilePhoneCapable: Boolean?,
    val telecom: String?,
    val operator: String?,
    val name: String?,
    val heightMeters: Double?
) {
    val stableKey: String get() = "osm:${elementType.name.lowercase(Locale.ROOT)}:$osmId"
}

data class OpenStreetMapCellSiteResponse(
    val status: OpenStreetMapCellSiteLookupStatus,
    val objects: List<OpenStreetMapCellSiteObject>,
    val message: String
)

/**
 * Read-only Overpass query and parser for communication infrastructure.
 *
 * A bare man_made=mast is not enough: it may be a lighting mast or flagpole. We accept
 * objects with explicit communication/mobile-phone tags, plus the dedicated communications
 * tower tag. Tower geometry and OSM object identity are retained for later evidence matching.
 */
internal object OpenStreetMapCellSiteProtocol {
    const val MINIMUM_RADIUS_METERS = 100
    const val MAXIMUM_RADIUS_METERS = 5_000
    const val DEFAULT_TIMEOUT_SECONDS = 20
    const val NODE_COORDINATE_UNCERTAINTY_METERS = 30.0
    const val AREA_CENTER_UNCERTAINTY_METERS = 100.0
    const val MAX_RESPONSE_ELEMENTS = 2_000

    fun buildQuery(latitude: Double, longitude: Double, radiusMeters: Int): String {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(radiusMeters in MINIMUM_RADIUS_METERS..MAXIMUM_RADIUS_METERS)

        val lat = String.format(Locale.US, "%.6f", latitude)
        val lon = String.format(Locale.US, "%.6f", longitude)
        return """
            [out:json][timeout:$DEFAULT_TIMEOUT_SECONDS];
            (
              nwr(around:$radiusMeters,$lat,$lon)["communication:mobile_phone"="yes"];
              nwr(around:$radiusMeters,$lat,$lon)["tower:type"="communication"];
              nwr(around:$radiusMeters,$lat,$lon)["man_made"="communications_tower"];
              nwr(around:$radiusMeters,$lat,$lon)["telecom"="antenna"];
            );
            out center tags;
        """.trimIndent()
    }

    fun parseResponse(
        httpStatus: Int,
        body: String
    ): OpenStreetMapCellSiteResponse {
        if (httpStatus == 429 || httpStatus == 406) {
            return response(
                OpenStreetMapCellSiteLookupStatus.RATE_LIMITED,
                "Overpass ограничил запрос; повторная попытка должна учитывать паузу сервиса."
            )
        }
        if (httpStatus >= 500) {
            return response(
                OpenStreetMapCellSiteLookupStatus.TEMPORARY_ERROR,
                "Сервис Overpass временно недоступен (HTTP $httpStatus)."
            )
        }
        if (httpStatus !in 200..299) {
            return response(
                OpenStreetMapCellSiteLookupStatus.INVALID_REQUEST,
                "Overpass отклонил запрос (HTTP $httpStatus)."
            )
        }
        if (body.isBlank()) {
            return response(
                OpenStreetMapCellSiteLookupStatus.INVALID_RESPONSE,
                "Overpass вернул пустой ответ."
            )
        }

        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return response(
                OpenStreetMapCellSiteLookupStatus.INVALID_RESPONSE,
                "Overpass вернул ответ не в формате JSON."
            )
        }
        if (root.has("remark") && !root.isNull("remark")) {
            return response(
                OpenStreetMapCellSiteLookupStatus.TEMPORARY_ERROR,
                "Overpass не смог завершить запрос."
            )
        }
        val elements = root.optJSONArray("elements") ?: return response(
            OpenStreetMapCellSiteLookupStatus.INVALID_RESPONSE,
            "В ответе Overpass отсутствует список объектов."
        )
        if (elements.length() > MAX_RESPONSE_ELEMENTS) {
            return response(
                OpenStreetMapCellSiteLookupStatus.INVALID_RESPONSE,
                "Ответ Overpass превышает допустимое число объектов."
            )
        }

        val parsed = LinkedHashMap<String, OpenStreetMapCellSiteObject>()
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val type = OpenStreetMapElementType.parse(element.optString("type")) ?: continue
            val id = element.optLong("id", -1L)
            if (id <= 0L) continue
            val tags = element.optJSONObject("tags") ?: continue

            val manMade = tag(tags, "man_made")
            val towerType = tag(tags, "tower:type")
            val mobilePhone = tag(tags, "communication:mobile_phone")
            val telecom = tag(tags, "telecom")
            if (!isCommunicationStructure(manMade, towerType, mobilePhone, telecom)) continue

            val coordinate = coordinates(element, type) ?: continue
            if (!coordinate.first.isFinite() || coordinate.first !in -90.0..90.0 ||
                !coordinate.second.isFinite() || coordinate.second !in -180.0..180.0
            ) continue

            val objectRecord = OpenStreetMapCellSiteObject(
                elementType = type,
                osmId = id,
                latitude = coordinate.first,
                longitude = coordinate.second,
                coordinateUncertaintyMeters = if (type == OpenStreetMapElementType.NODE) {
                    NODE_COORDINATE_UNCERTAINTY_METERS
                } else AREA_CENTER_UNCERTAINTY_METERS,
                manMade = manMade,
                towerType = towerType,
                mobilePhoneCapable = when (mobilePhone?.lowercase(Locale.ROOT)) {
                    "yes" -> true
                    "no" -> false
                    else -> null
                },
                telecom = telecom,
                operator = tag(tags, "operator"),
                name = tag(tags, "name"),
                heightMeters = parseHeightMeters(tag(tags, "height"))
            )
            parsed[objectRecord.stableKey] = objectRecord
        }

        val result = parsed.values.sortedWith(
            compareBy<OpenStreetMapCellSiteObject> { it.elementType.ordinal }.thenBy { it.osmId }
        )
        return if (result.isEmpty()) {
            response(OpenStreetMapCellSiteLookupStatus.NOT_FOUND, "В радиусе запроса не найдено подходящих объектов связи.")
        } else {
            OpenStreetMapCellSiteResponse(
                OpenStreetMapCellSiteLookupStatus.FOUND,
                result,
                "Получено объектов инфраструктуры связи: ${result.size}."
            )
        }
    }

    private fun isCommunicationStructure(
        manMade: String?,
        towerType: String?,
        mobilePhone: String?,
        telecom: String?
    ): Boolean {
        if (mobilePhone.equals("yes", ignoreCase = true) ||
            towerType.equals("communication", ignoreCase = true) ||
            telecom.equals("antenna", ignoreCase = true)
        ) return true
        // This dedicated tag denotes a communications tower, but not necessarily a mobile mast.
        return manMade.equals("communications_tower", ignoreCase = true)
    }

    private fun coordinates(
        element: JSONObject,
        type: OpenStreetMapElementType
    ): Pair<Double, Double>? {
        if (type == OpenStreetMapElementType.NODE) {
            val latitude = element.optDouble("lat", Double.NaN)
            val longitude = element.optDouble("lon", Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            return latitude to longitude
        }
        val center = element.optJSONObject("center") ?: return null
        val latitude = center.optDouble("lat", Double.NaN)
        val longitude = center.optDouble("lon", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        return latitude to longitude
    }

    private fun tag(tags: JSONObject, key: String): String? {
        if (!tags.has(key) || tags.isNull(key)) return null
        return tags.optString(key).trim().takeIf { it.isNotBlank() }?.take(200)
    }

    private fun parseHeightMeters(value: String?): Double? {
        value ?: return null
        val match = Regex("^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*m?\\s*$", RegexOption.IGNORE_CASE)
            .matchEntire(value) ?: return null
        return match.groupValues[1].toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in 0.1..1_000.0 }
    }

    private fun response(
        status: OpenStreetMapCellSiteLookupStatus,
        message: String
    ) = OpenStreetMapCellSiteResponse(status, emptyList(), message)
}
