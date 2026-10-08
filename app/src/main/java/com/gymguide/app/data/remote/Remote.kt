package com.gymguide.app.data.remote
import com.gymguide.app.BuildConfig
import com.gymguide.app.data.CatalogRepository
import com.gymguide.app.data.GymEquipmentEntity
import com.gymguide.app.data.OutboxEntity

/**
 * Contract for the future GymCue server. The app already records every shareable change in the Outbox table;
 * a sync worker will call push() with those rows and pull() for other users' approved content.
 * Until API_BASE_URL is configured, OfflineBackend keeps everything on the device.
 */
interface SyncBackend {
  val isOnline: Boolean
  suspend fun push(ops: List<OutboxEntity>): List<String>   // returns ids accepted by the server
  suspend fun pull(sinceMillis: Long): Unit
  companion object { fun create(): SyncBackend = OfflineBackend /* TODO: HttpSyncBackend(BuildConfig.API_BASE_URL) */ }
}
object OfflineBackend : SyncBackend {
  override val isOnline = false
  override suspend fun push(ops: List<OutboxEntity>) = emptyList<String>()
  override suspend fun pull(sinceMillis: Long) {}
}

/**
 * Fills in missing equipment details for a user submission. Rule: only copy values from a confidently
 * identified source; anything that can't be found stays BLANK. Each filled field is recorded in fieldSources.
 * Server version (later) can look up manufacturer databases; the local version uses the bundled catalog.
 */
interface EnrichmentService {
  suspend fun enrich(e: GymEquipmentEntity): GymEquipmentEntity
  companion object { fun create(catalog: CatalogRepository): EnrichmentService = CatalogMatchEnrichment(catalog) }
}

class CatalogMatchEnrichment(private val catalog: CatalogRepository) : EnrichmentService {
  override suspend fun enrich(e: GymEquipmentEntity): GymEquipmentEntity {
    val match = (if (e.catalogEquipmentId.isNotBlank()) catalog.equipment(e.catalogEquipmentId) else null)
      ?: catalog.matchEquipment(e.name) ?: e.alternateNames.firstNotNullOfOrNull(catalog::matchEquipment)
    val sources = e.fieldSources.toMutableList()
    fun mark(field: String, from: String) { sources.removeAll { it.startsWith("$field=") }; sources += "$field=$from" }
    // Record which fields the user typed themselves.
    listOf("name" to e.name, "manufacturer" to e.manufacturer, "model" to e.model, "category" to e.category,
      "qrCode" to e.qrCode, "videoUrl" to e.videoUrl, "notes" to e.notes).forEach { (f, v) -> if (v.isNotBlank() && sources.none { it.startsWith("$f=") }) mark(f, "user") }
    if (e.howToUse.isNotEmpty() && sources.none { it.startsWith("howToUse=") }) mark("howToUse", "user")
    if (e.primaryMuscles.isNotEmpty() && sources.none { it.startsWith("primaryMuscles=") }) mark("primaryMuscles", "user")
    if (match == null) return e.copy(fieldSources = sources)   // nothing identifiable -> leave blanks blank

    val src = "catalog:${match.id}"
    val muscles = catalog.exercisesUsing(match.id).flatMap { it.primaryMuscles }.distinct()
    var out = e.copy(catalogEquipmentId = match.id)
    if (out.manufacturer.isBlank() && match.manufacturer.isNotBlank()) { out = out.copy(manufacturer = match.manufacturer); mark("manufacturer", src) }
    if (out.category.isBlank() && match.category.isNotBlank()) { out = out.copy(category = match.category); mark("category", src) }
    if (out.alternateNames.isEmpty() && match.alternateNames.isNotEmpty()) { out = out.copy(alternateNames = match.alternateNames); mark("alternateNames", src) }
    if (out.howToUse.isEmpty() && match.howToUse.isNotEmpty()) { out = out.copy(howToUse = match.howToUse); mark("howToUse", src) }
    if (out.qrCode.isBlank() && match.qrCode.isNotBlank()) { out = out.copy(qrCode = match.qrCode); mark("qrCode", src) }
    if (out.videoUrl.isBlank() && match.videoUrl.isNotBlank()) { out = out.copy(videoUrl = match.videoUrl); mark("videoUrl", src) }
    if (out.primaryMuscles.isEmpty() && muscles.isNotEmpty()) { out = out.copy(primaryMuscles = muscles); mark("primaryMuscles", src) }
    return out.copy(fieldSources = sources)
  }
}
