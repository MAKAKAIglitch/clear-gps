package org.cleargps

import org.json.JSONObject
import java.util.Locale

data class OpenCellIdCellKey(
    val radio: String,
    val mcc: Int,
    val mnc: Int,
    val area: Int,
    val cellId: Long
) {
    init {
        require(radio in SUPPORTED_RADIOS)
        require(mcc in 1..999 && mnc in 0..999 && area >= 0 && cellId > 0L)
    }

    companion object {
        private val SUPPORTED_RADIOS = setOf("GSM", "UMTS", "LTE", "NR")

        fun fromRadioSignal(signal: RadioSignal): OpenCellIdCellKey? {
            if (signal.type != "cell") return null
            val parts = signal.identifier.split(':')
            if (parts.size != 5) return null
            val radio = when (parts[0].lowercase(Locale.ROOT)) {
                "gsm" -> "GSM"
                "wcdma" -> "UMTS"
                "lte" -> "LTE"
                "nr" -> "NR"
                else -> return null
            }
            val mcc = parts[1].toIntOrNull() ?: return null
            val mnc = parts[2].toIntOrNull() ?: return null
            val area = parts[3].toIntOrNull() ?: return null
            val cellId = parts[4].toLongOrNull() ?: return null
            return runCatching { OpenCellIdCellKey(radio, mcc, mnc, area, cellId) }.getOrNull()
        }
    }
}

data class OpenCellIdCellRecord(
    val key: OpenCellIdCellKey,
    val latitude: Double?,
    val longitude: Double?,
    val rangeMeters: Int?,
    val samples: Int,
    val changeable: Boolean,
    val checkedAtMillis: Long,
    val status: String
)

enum class OpenCellIdLookupStatus {
    FOUND, NOT_FOUND, UNAUTHORIZED, RATE_LIMITED, INVALID_REQUEST, TEMPORARY_ERROR, INVALID_RESPONSE
}

data class OpenCellIdParsedResponse(
    val status: OpenCellIdLookupStatus,
    val record: OpenCellIdCellRecord?,
    val message: String
)

internal object OpenCellIdProtocol {
    fun parseResponse(
        httpStatus: Int,
        body: String,
        requested: OpenCellIdCellKey,
        checkedAtMillis: Long
    ): OpenCellIdParsedResponse {
        if (httpStatus == 401) return failure(OpenCellIdLookupStatus.UNAUTHORIZED, "OpenCellID отклонил API-ключ (HTTP 401).")
        if (httpStatus == 429) return failure(OpenCellIdLookupStatus.RATE_LIMITED, "OpenCellID ограничил частоту или дневную квоту (HTTP 429).")
        if (httpStatus >= 500) return failure(OpenCellIdLookupStatus.TEMPORARY_ERROR, "OpenCellID временно недоступен (HTTP $httpStatus).")
        if (body.isBlank()) return failure(OpenCellIdLookupStatus.INVALID_RESPONSE, "OpenCellID вернул пустой ответ.")
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            return failure(OpenCellIdLookupStatus.INVALID_RESPONSE, "OpenCellID вернул ответ не в формате JSON.")
        }

        val code = json.optInt("code", 0)
        if (code == 1) {
            return OpenCellIdParsedResponse(
                OpenCellIdLookupStatus.NOT_FOUND,
                OpenCellIdCellRecord(requested, null, null, null, 0, false, checkedAtMillis, "not_found"),
                "Эта сотовая ячейка пока не найдена в OpenCellID."
            )
        }
        if (code == 2) return failure(OpenCellIdLookupStatus.UNAUTHORIZED, "OpenCellID отклонил API-ключ.")
        if (code == 3 || httpStatus == 400) {
            return failure(OpenCellIdLookupStatus.INVALID_REQUEST, "OpenCellID отклонил параметры ячейки.")
        }
        if (code == 7) return failure(OpenCellIdLookupStatus.RATE_LIMITED, "Исчерпана или ограничена квота OpenCellID.")
        if (httpStatus !in 200..299) {
            return failure(OpenCellIdLookupStatus.TEMPORARY_ERROR, "OpenCellID вернул HTTP $httpStatus.")
        }
        if (json.has("error") && !json.isNull("error")) {
            return failure(OpenCellIdLookupStatus.INVALID_RESPONSE, "OpenCellID вернул ошибку в теле ответа.")
        }

        fun optionalLong(name: String): Long? {
            if (!json.has(name) || json.isNull(name)) return null
            return when (val value = json.opt(name)) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
        }
        val latitude = json.optDouble("lat", Double.NaN)
        val longitude = json.optDouble("lon", Double.NaN)
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0
        ) {
            return failure(OpenCellIdLookupStatus.INVALID_RESPONSE, "OpenCellID не вернул корректные координаты вышки.")
        }

        val responseMcc = optionalLong("mcc")
        val responseMnc = optionalLong("mnc")
        val responseArea = optionalLong("lac") ?: optionalLong("tac")
        val responseCellId = optionalLong("cellid") ?: optionalLong("cid")
        if ((responseMcc != null && responseMcc != requested.mcc.toLong()) ||
            (responseMnc != null && responseMnc != requested.mnc.toLong()) ||
            (responseArea != null && responseArea != requested.area.toLong()) ||
            (responseCellId != null && responseCellId != requested.cellId)
        ) {
            return failure(OpenCellIdLookupStatus.INVALID_RESPONSE, "OpenCellID вернул данные другой сотовой ячейки; запись отклонена.")
        }
        val responseRadio = json.optString("radio", requested.radio).uppercase(Locale.ROOT)
        val normalizedRadio = if (responseRadio == "WCDMA") "UMTS" else responseRadio
        if (json.has("radio") && normalizedRadio != requested.radio) {
            return failure(OpenCellIdLookupStatus.INVALID_RESPONSE, "Тип радио в ответе OpenCellID не совпадает с запросом.")
        }

        val range = optionalLong("range")?.takeIf { it in 1L..10_000_000L }?.toInt()
        val samples = optionalLong("samples")?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 1
        val changeable = optionalLong("changeable")?.let { it != 0L } ?: false
        val record = OpenCellIdCellRecord(
            requested, latitude, longitude, range, samples, changeable, checkedAtMillis, "found"
        )
        return OpenCellIdParsedResponse(OpenCellIdLookupStatus.FOUND, record, "Координаты базовой станции получены из OpenCellID.")
    }

    private fun failure(status: OpenCellIdLookupStatus, message: String) =
        OpenCellIdParsedResponse(status, null, message)
}

internal object OpenCellIdLookupPolicy {
    const val SUCCESS_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    const val NOT_FOUND_TTL_MILLIS = 24L * 60L * 60L * 1_000L
    const val MAX_LOOKUPS_PER_ACTION = 20

    fun shouldLookup(cached: OpenCellIdCellRecord?, nowMillis: Long): Boolean {
        if (cached == null || cached.checkedAtMillis <= 0L || nowMillis < cached.checkedAtMillis) return true
        val age = nowMillis - cached.checkedAtMillis
        val ttl = if (cached.status == "not_found") NOT_FOUND_TTL_MILLIS else SUCCESS_TTL_MILLIS
        return age >= ttl
    }
}
