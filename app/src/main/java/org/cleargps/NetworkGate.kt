package org.cleargps

import android.content.Context
import android.os.SystemClock
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.IOException
import java.net.URI
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * The only approved Internet egress for Clear GPS.
 *
 * Radio lookup requests are read-only lookups, not contributions:
 * - BeaconDB: its documented Ichnaea-compatible /v1/geolocate endpoint requires POST.
 *   This is the one narrow POST exception and is available only after explicit opt-in.
 * - OpenCellID: GET /cell/get only, with the independent source/network switch enabled.
 * - MapLibre: GET resources from the configured OpenFreeMap host while online maps are selected.
 *
 * No contribution, upload, arbitrary host, redirect target, cleartext URL or other HTTP method
 * is allowlisted. Query strings and request bodies are never written to diagnostics.
 */
internal object NetworkGate {
    internal enum class Operation {
        BEACONDB_LOOKUP,
        OPENCELLID_LOOKUP,
        MAP_RESOURCE,
        AIRPORT_CATALOG_READ
    }

    private const val BEACONDB_HOST = "api.beacondb.net"
    private const val BEACONDB_PATH = "/v1/geolocate"
    private const val OPENCELLID_HOST = "opencellid.org"
    private const val OPENCELLID_PATH = "/cell/get"
    private const val MAP_HOST = "tiles.openfreemap.org"
    private const val AIRPORT_CATALOG_HOST = "davidmegginson.github.io"
    private const val AIRPORT_CATALOG_PATH = "/ourairports-data/airports.csv"
    private const val MAP_LOG_INTERVAL_MS = 60_000L

    private val mapLogLock = Any()
    private var lastMapLogAtElapsedMs = 0L
    @Volatile private var mapResourceClient: OkHttpClient? = null

    /**
     * Pure endpoint validation used by the connection factories and unit tests.
     * BeaconDB's read-only geolocate request is deliberately POST because the upstream
     * Ichnaea-compatible API requires a JSON body; every other POST/PUT/PATCH is rejected.
     */
    internal fun validateRequest(
        url: String,
        method: String,
        operation: Operation,
        consentGranted: Boolean = false,
        mapOnline: Boolean = true
    ): URI {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            reject()
        }
        val normalizedMethod = method.uppercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT) ?: reject()

        if (!uri.scheme.equals("https", ignoreCase = true) ||
            (uri.port != -1 && uri.port != 443) ||
            uri.rawUserInfo != null ||
            uri.rawFragment != null
        ) reject()

        when (operation) {
            Operation.BEACONDB_LOOKUP -> {
                if (!consentGranted || normalizedMethod != "POST" ||
                    host != BEACONDB_HOST || uri.rawPath != BEACONDB_PATH ||
                    !uri.rawQuery.isNullOrEmpty()
                ) reject()
            }

            Operation.OPENCELLID_LOOKUP -> {
                if (!consentGranted || normalizedMethod != "GET" ||
                    host != OPENCELLID_HOST || uri.rawPath != OPENCELLID_PATH
                ) reject()
                validateOpenCellIdQuery(uri.rawQuery)
            }

            Operation.MAP_RESOURCE -> {
                val path = uri.rawPath.orEmpty()
                val approvedPath = path.startsWith("/styles/") ||
                    path.startsWith("/data/") ||
                    path.startsWith("/fonts/") ||
                    path.startsWith("/assets/")
                if (!mapOnline || normalizedMethod != "GET" ||
                    host != MAP_HOST || !approvedPath
                ) reject()
            }

            Operation.AIRPORT_CATALOG_READ -> {
                if (!consentGranted || normalizedMethod != "GET" ||
                    host != AIRPORT_CATALOG_HOST || uri.rawPath != AIRPORT_CATALOG_PATH ||
                    !uri.rawQuery.isNullOrEmpty()
                ) reject()
            }
        }
        return uri
    }

    fun openBeaconDbLookupConnection(context: Context, url: String): HttpsURLConnection {
        val enabled = context.applicationContext
            .getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false)
        val uri = validateRequest(url, "POST", Operation.BEACONDB_LOOKUP, consentGranted = enabled)
        logAllowed(context, "BeaconDB read-only geolocate")
        return openHttpsConnection(uri, "POST")
    }

    fun openAirportCatalogConnection(context: Context, url: String): HttpsURLConnection {
        val enabled = context.applicationContext
            .getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .getBoolean(ClearGpsPreferences.KEY_AIRPORT_CATALOG_ONLINE_ENABLED, false)
        val uri = validateRequest(
            url,
            "GET",
            Operation.AIRPORT_CATALOG_READ,
            consentGranted = enabled
        )
        DiagnosticLogStore.log(context, "NETWORK", "Allowed read-only public airport catalogue download; URL and downloaded rows omitted")
        return openHttpsConnection(uri, "GET")
    }

    fun openOpenCellIdLookupConnection(context: Context, url: String): HttpsURLConnection {
        val appContext = context.applicationContext
        val sourceEnabled = appContext
            .getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_OPENCELLID_ENABLED, true)
        val networkEnabled = OpenCellIdKeyStore.isEnabled(appContext)
        val uri = validateRequest(
            url,
            "GET",
            Operation.OPENCELLID_LOOKUP,
            consentGranted = sourceEnabled && networkEnabled
        )
        logAllowed(context, "OpenCellID read-only cell lookup")
        return openHttpsConnection(uri, "GET")
    }

    /**
     * Installs the MapLibre resource interceptor before the first MapView is created.
     * A network interceptor validates every exchange, including redirects, not just the
     * initial style request. Offline map mode is checked again for every request.
     */
    fun installMapLibre(context: Context) {
        val appContext = context.applicationContext
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                try {
                    val online = isOnlineMapEnabled(appContext)
                    validateRequest(
                        request.url.toString(),
                        request.method,
                        Operation.MAP_RESOURCE,
                        consentGranted = true,
                        mapOnline = online
                    )
                } catch (blocked: SecurityException) {
                    logMapDecision(appContext, allowed = false)
                    throw IOException("Map resource blocked by Clear GPS network policy", blocked)
                }
                logMapDecision(appContext, allowed = true)
                chain.proceed(request)
            }
            .build()

        // Avoid SDK failure logs that could print full URLs (tile paths encode map area).
        HttpRequestUtil.setLogEnabled(false)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        HttpRequestUtil.setOkHttpClient(client)
        mapResourceClient = client
    }

    private fun isOnlineMapEnabled(context: Context): Boolean =
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
            .getString(ClearGpsPreferences.KEY_MAP_SOURCE, "online") == "online"

    private fun validateOpenCellIdQuery(rawQuery: String?) {
        val parts = rawQuery?.split('&') ?: reject()
        val names = parts.map { part ->
            if (!part.contains('=')) reject()
            val name = part.substringBefore('=')
            val value = part.substringAfter('=')
            if (name.isBlank() || value.isBlank()) reject()
            name
        }
        val expected = setOf("key", "mcc", "mnc", "lac", "cellid", "radio", "format")
        if (names.size != expected.size || names.toSet() != expected) reject()
    }

    private fun openHttpsConnection(uri: URI, method: String): HttpsURLConnection {
        val connection = uri.toURL().openConnection() as? HttpsURLConnection ?: reject()
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.doInput = true
        if (method == "POST") connection.doOutput = true
        return connection
    }

    private fun logAllowed(context: Context, description: String) {
        DiagnosticLogStore.log(context, "NETWORK", "Allowed $description; URL query/body omitted")
    }

    private fun logMapDecision(context: Context, allowed: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val shouldLog = synchronized(mapLogLock) {
            if (lastMapLogAtElapsedMs == 0L || now - lastMapLogAtElapsedMs >= MAP_LOG_INTERVAL_MS) {
                lastMapLogAtElapsedMs = now
                true
            } else false
        }
        if (shouldLog) {
            DiagnosticLogStore.log(
                context,
                "NETWORK",
                if (allowed) "MapLibre HTTPS GET allowed; tile URL omitted"
                else "MapLibre request blocked by host/method/online-map policy",
                if (allowed) "INFO" else "WARN"
            )
        }
    }

    private fun reject(): Nothing =
        throw SecurityException("Network request blocked by Clear GPS network policy")
}
