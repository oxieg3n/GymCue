package com.gymguide.app.data.places
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import com.gymguide.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

data class PlaceResult(val provider: String, val placeId: String, val name: String, val address: String,
                       val lat: Double, val lng: Double, val distanceM: Float? = null)

/** Finds gyms. Google Places when PLACES_API_KEY is set in local.properties, otherwise OpenStreetMap (free, no key). */
interface GymDirectory {
  val providerName: String
  suspend fun nearby(lat: Double, lng: Double, radiusM: Int = 8000): List<PlaceResult>
  suspend fun search(query: String, lat: Double?, lng: Double?): List<PlaceResult>
  companion object { fun create(): GymDirectory =
    if (BuildConfig.PLACES_API_KEY.isNotBlank()) GooglePlacesDirectory(BuildConfig.PLACES_API_KEY) else OsmDirectory() }
}

private val json = Json { ignoreUnknownKeys = true }
private const val UA = "GymCue/3.0 (Android; gym finder)"

private suspend fun http(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
  val c = URL(url).openConnection() as HttpURLConnection
  c.connectTimeout = 15000; c.readTimeout = 30000; c.setRequestProperty("User-Agent", UA)
  headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
  if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
  val code = c.responseCode
  val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
  if (code !in 200..299) error("Search failed ($code)")
  text
}
private fun dist(lat: Double?, lng: Double?, p: PlaceResult): PlaceResult {
  if (lat == null || lng == null) return p
  val r = FloatArray(1); Location.distanceBetween(lat, lng, p.lat, p.lng, r); return p.copy(distanceM = r[0])
}
private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull ?: ""
private fun JsonElement?.dbl() = (this as? JsonPrimitive)?.doubleOrNull ?: 0.0

class OsmDirectory : GymDirectory {
  override val providerName = "OpenStreetMap"
  override suspend fun nearby(lat: Double, lng: Double, radiusM: Int): List<PlaceResult> {
    val q = "[out:json][timeout:25];(nwr[\"leisure\"=\"fitness_centre\"](around:$radiusM,$lat,$lng);" +
      "nwr[\"amenity\"=\"gym\"](around:$radiusM,$lat,$lng););out center tags 60;"
    val res = http("https://overpass-api.de/api/interpreter", "data=" + URLEncoder.encode(q, "UTF-8"),
      mapOf("Content-Type" to "application/x-www-form-urlencoded"))
    return json.parseToJsonElement(res).jsonObject["elements"]?.jsonArray.orEmpty().mapNotNull { el ->
      val o = el.jsonObject; val tags = o["tags"]?.jsonObject ?: return@mapNotNull null
      val name = tags["name"].str().ifBlank { tags["brand"].str() }.ifBlank { return@mapNotNull null }
      val center = o["center"]?.jsonObject
      val la = o["lat"]?.dbl() ?: center?.get("lat").dbl(); val lo = o["lon"]?.dbl() ?: center?.get("lon").dbl()
      val addr = listOf(listOf(tags["addr:housenumber"].str(), tags["addr:street"].str()).filter { it.isNotBlank() }.joinToString(" "),
        tags["addr:city"].str(), tags["addr:state"].str()).filter { it.isNotBlank() }.joinToString(", ")
      dist(lat, lng, PlaceResult("osm", "${o["type"].str()}/${o["id"].str()}", name, addr, la, lo))
    }.sortedBy { it.distanceM ?: 0f }
  }
  override suspend fun search(query: String, lat: Double?, lng: Double?): List<PlaceResult> {
    val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=0&limit=25&q=" + URLEncoder.encode(query, "UTF-8")
    return json.parseToJsonElement(http(url)).jsonArray.mapNotNull { el ->
      val o = el.jsonObject
      val name = o["name"].str().ifBlank { o["display_name"].str().substringBefore(",") }
      dist(lat, lng, PlaceResult("osm", "${o["osm_type"].str()}/${o["osm_id"].str()}", name,
        o["display_name"].str().substringAfter(", ", ""), o["lat"].str().toDoubleOrNull() ?: 0.0, o["lon"].str().toDoubleOrNull() ?: 0.0))
    }
  }
}

class GooglePlacesDirectory(private val key: String) : GymDirectory {
  override val providerName = "Google Places"
  private val headers get() = mapOf("Content-Type" to "application/json", "X-Goog-Api-Key" to key,
    "X-Goog-FieldMask" to "places.id,places.displayName,places.formattedAddress,places.location")
  private fun parse(res: String, lat: Double?, lng: Double?) =
    json.parseToJsonElement(res).jsonObject["places"]?.jsonArray.orEmpty().map { el -> val o = el.jsonObject
      dist(lat, lng, PlaceResult("google", o["id"].str(), o["displayName"]?.jsonObject?.get("text").str(), o["formattedAddress"].str(),
        o["location"]?.jsonObject?.get("latitude").dbl(), o["location"]?.jsonObject?.get("longitude").dbl())) }
  override suspend fun nearby(lat: Double, lng: Double, radiusM: Int) = parse(http("https://places.googleapis.com/v1/places:searchNearby",
    """{"includedTypes":["gym"],"maxResultCount":20,"rankPreference":"DISTANCE","locationRestriction":{"circle":{"center":{"latitude":$lat,"longitude":$lng},"radius":${radiusM.coerceAtMost(50000)}.0}}}""",
    headers), lat, lng).sortedBy { it.distanceM ?: 0f }
  override suspend fun search(query: String, lat: Double?, lng: Double?): List<PlaceResult> {
    val bias = if (lat != null && lng != null) ""","locationBias":{"circle":{"center":{"latitude":$lat,"longitude":$lng},"radius":30000.0}}""" else ""
    val q = query.replace("\\", "").replace("\"", "")
    return parse(http("https://places.googleapis.com/v1/places:searchText", """{"textQuery":"$q","includedType":"gym"$bias}""", headers), lat, lng)
  }
}

/** One-shot location using the platform LocationManager (no Google Play Services dependency). */
object DeviceLocation {
  @SuppressLint("MissingPermission")
  suspend fun current(ctx: Context): Location? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000) return last
    val provider = providers.firstOrNull { it == LocationManager.NETWORK_PROVIDER } ?: providers.firstOrNull() ?: return last
    val fresh = withTimeoutOrNull(15_000) { suspendCancellableCoroutine<Location?> { cont ->
      if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, ctx.mainExecutor) { cont.resume(it) }
      else @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, { cont.resume(it) }, android.os.Looper.getMainLooper())
    } }
    return fresh ?: last
  }
}
