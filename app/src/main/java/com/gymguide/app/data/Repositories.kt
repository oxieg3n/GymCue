package com.gymguide.app.data
import android.content.Context
import com.gymguide.app.data.remote.EnrichmentService
import com.gymguide.app.data.remote.SyncBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/*
 * Screens talk only to these repositories. Today they read the bundled catalog and the local Room database;
 * when a GymCue server exists, swap in a real SyncBackend / EnrichmentService (see data/remote) without
 * touching the UI.
 */

/** Read-only exercise/equipment/template catalog. Future: refreshed from the server, cached locally. */
class CatalogRepository(ctx: Context) {
  val content: Content = runCatching {
    Json { ignoreUnknownKeys = true }.decodeFromString<Content>(ctx.assets.open("content.json").bufferedReader().readText())
  }.getOrElse { Content(emptyList(), emptyList(), emptyList()) }
  fun exercise(id: String) = content.exercises.firstOrNull { it.id == id }
  fun equipment(id: String) = content.equipment.firstOrNull { it.id == id }
  fun template(id: String) = content.templates.firstOrNull { it.id == id }
  val muscles get() = content.exercises.flatMap { it.primaryMuscles }.distinct().sorted()
  fun usable(e: Exercise, off: Set<String>) = e.equipmentIds.none { it in off }
  fun alternatives(e: Exercise, off: Set<String>) = e.alternativeIds.mapNotNull(::exercise).filter { usable(it, off) }
  fun exercisesUsing(equipmentId: String) = content.exercises.filter { equipmentId in it.equipmentIds }
  fun setupFor(exerciseId: String?, equipmentId: String) =
    exerciseId?.let(::exercise)?.equipmentSetup?.firstOrNull { it.equipmentId == equipmentId }

  /** Exercises that fit a workout: same primary muscles or same movement category as what's already in it. */
  fun suggestionsFor(exerciseIds: List<String>, off: Set<String>): List<Exercise> {
    val inPlan = exerciseIds.mapNotNull(::exercise)
    val muscles = inPlan.flatMap { it.primaryMuscles }.toSet()
    val moves = inPlan.map { it.movement }.toSet()
    return content.exercises.filter { it.id !in exerciseIds && usable(it, off) &&
      (inPlan.isEmpty() || it.primaryMuscles.any(muscles::contains) || it.movement in moves) }
  }

  /** Match free-text equipment names (e.g. a user submission) to a catalog entry. Exact normalized match only. */
  fun matchEquipment(name: String): Equipment? {
    val n = norm(name); if (n.isBlank()) return null
    return content.equipment.firstOrNull { norm(it.name) == n || it.alternateNames.any { a -> norm(a) == n } }
  }
  private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
}

/** Who is using the app. Local device user today; replaced by real sign-in (server account id) later. */
class UserSession(private val ctx: Context, private val dao: GymDao) {
  private val prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
  val userId: String get() = prefs.getString("userId", null) ?: newId().also { prefs.edit().putString("userId", it).apply() }
  suspend fun ensureUser(): UserEntity = dao.user(userId) ?: UserEntity(id = userId, displayName = "This device").also { dao.upsertUser(it) }
  suspend fun current() = ensureUser()
}

class WorkoutRepository(private val dao: GymDao, private val catalog: CatalogRepository, private val session: UserSession) {
  fun customWorkouts(): Flow<List<WorkoutEntity>> = dao.workouts(session.userId)
  /** v1 rows migrated with a placeholder owner are adopted by the current user on first launch. */
  suspend fun adoptMigrated() = dao.adoptOwner(session.userId)
  suspend fun customWorkout(id: String) = dao.workout(id)
  suspend fun customItems(id: String) = dao.workoutItems(id)

  suspend fun plan(kind: String, id: String): WorkoutPlan? = when (kind) {
    "template" -> catalog.template(id)?.let { WorkoutPlan(it.id, it.name, it.items, false) }
    else -> dao.workout(id)?.takeIf { !it.deleted }?.let { w ->
      WorkoutPlan(w.id, w.name, dao.workoutItems(w.id).map { TemplateItem(it.exerciseId, it.sets, it.reps, it.restSec) }, true) }
  }

  suspend fun saveCustom(id: String?, name: String, items: List<TemplateItem>): String {
    val existing = id?.let { dao.workout(it) }
    val w = (existing ?: WorkoutEntity(name = name, ownerId = session.userId)).copy(name = name.trim(), updatedAt = now(),
      syncState = SyncState.PENDING)
    dao.upsertWorkout(w); dao.clearItems(w.id)
    dao.insertItems(items.mapIndexed { i, t -> WorkoutItemEntity(workoutId = w.id, position = i, exerciseId = t.exerciseId,
      sets = t.sets, reps = t.reps, restSec = t.restSec) })
    dao.enqueue(OutboxEntity(entityType = "workout", entityId = w.id, op = if (existing == null) "create" else "update"))
    return w.id
  }
  suspend fun deleteCustom(id: String) { dao.workout(id)?.let {
    dao.upsertWorkout(it.copy(deleted = true, updatedAt = now(), syncState = SyncState.PENDING))
    dao.enqueue(OutboxEntity(entityType = "workout", entityId = id, op = "delete")) } }

  suspend fun logSet(s: SetLog) = dao.log(s)
  fun history() = dao.history()
}

class GymRepository(private val ctx: Context, private val dao: GymDao, private val catalog: CatalogRepository,
                    private val session: UserSession, private val enrichment: EnrichmentService) {
  private val prefs = ctx.getSharedPreferences("gym", Context.MODE_PRIVATE)
  private val _activeGymId = MutableStateFlow(prefs.getString("activeGymId", "") ?: "")
  val activeGymId: StateFlow<String> = _activeGymId
  fun setActiveGym(id: String) { prefs.edit().putString("activeGymId", id).apply(); _activeGymId.value = id }

  fun gyms() = dao.gyms()
  fun gymFlow(id: String) = dao.gymFlow(id)
  suspend fun gym(id: String) = dao.gym(id)
  fun equipmentFor(gymId: String) = dao.gymEquipment(gymId)
  fun allSubmissions() = dao.allGymEquipment()
  suspend fun equipmentItem(id: String) = dao.gymEquipmentItem(id)
  /** gym id -> number of approved machines, for "already in GymCue" badges in search results */
  fun gymSummaries(): Flow<Map<String, Int>> = dao.allGymEquipment().map { l -> l.filter { it.status == ReviewStatus.APPROVED }.groupingBy { it.gymId }.eachCount() }

  suspend fun findExisting(p: com.gymguide.app.data.places.PlaceResult) = dao.gymByPlace(p.provider, p.placeId)

  /** Adds a directory result (GPS search) to GymCue, or returns the existing record. */
  suspend fun addFromPlace(p: com.gymguide.app.data.places.PlaceResult): GymEntity {
    dao.gymByPlace(p.provider, p.placeId)?.let { return it }
    val g = GymEntity(provider = p.provider, providerPlaceId = p.placeId, name = p.name, address = p.address,
      lat = p.lat, lng = p.lng, createdBy = session.userId, syncState = SyncState.PENDING)
    dao.upsertGym(g); dao.enqueue(OutboxEntity(entityType = "gym", entityId = g.id, op = "create"))
    return g
  }
  suspend fun saveGym(g: GymEntity, op: String = "update") { dao.upsertGym(g.copy(updatedAt = now(), syncState = SyncState.PENDING))
    dao.enqueue(OutboxEntity(entityType = "gym", entityId = g.id, op = op)) }

  /** User submission: fill blanks from a matching catalog entry (never guesses), then queue for admin review. */
  suspend fun submitEquipment(draft: GymEquipmentEntity, status: String = ReviewStatus.SUBMITTED): GymEquipmentEntity {
    val enriched = enrichment.enrich(draft).copy(status = status, updatedAt = now(), syncState = SyncState.PENDING)
    dao.upsertGymEquipment(enriched)
    dao.enqueue(OutboxEntity(entityType = "gym_equipment", entityId = enriched.id, op = "upsert"))
    return enriched
  }
  suspend fun saveEquipment(e: GymEquipmentEntity) { dao.upsertGymEquipment(e.copy(updatedAt = now(), syncState = SyncState.PENDING))
    dao.enqueue(OutboxEntity(entityType = "gym_equipment", entityId = e.id, op = "upsert")) }
}

class AdminRepository(private val dao: GymDao, private val session: UserSession, private val catalog: CatalogRepository) {
  fun users() = dao.users()
  fun auditLog() = dao.auditLog()
  fun pendingSync() = dao.pendingSyncCount()
  suspend fun audit(action: String, type: String, id: String, detail: String = "") =
    dao.audit(AuditEntity(actorId = session.userId, action = action, targetType = type, targetId = id, detail = detail))

  suspend fun saveUser(u: UserEntity, action: String) { dao.upsertUser(u.copy(updatedAt = now(), syncState = SyncState.PENDING))
    dao.enqueue(OutboxEntity(entityType = "user", entityId = u.id, op = "upsert")); audit(action, "user", u.id, u.displayName) }
  suspend fun canRemoveAdmin(u: UserEntity) = u.role != Roles.ADMIN || dao.activeAdminCount() > 1
  suspend fun makeCurrentUserAdmin() { val u = session.ensureUser(); if (u.role != Roles.ADMIN) saveUser(u.copy(role = Roles.ADMIN), "grant_admin_first_setup") }

  suspend fun review(e: GymEquipmentEntity, status: String, note: String) {
    dao.upsertGymEquipment(e.copy(status = status, reviewNote = note, updatedAt = now(), syncState = SyncState.PENDING))
    dao.enqueue(OutboxEntity(entityType = "gym_equipment", entityId = e.id, op = "review"))
    audit("review_$status", "gym_equipment", e.id, note)
  }

  /** Missing-information report across catalog content and user submissions. */
  fun missingReport(): Flow<List<MissingItem>> = dao.allGymEquipment().map { subs ->
    val c = catalog.content; val out = mutableListOf<MissingItem>()
    c.equipment.forEach { q -> val m = buildList {
        if (q.photo.isBlank()) add("photo"); if (q.manufacturer.isBlank()) add("manufacturer/model")
        if (q.howToUse.isEmpty()) add("how-to-use steps"); if (q.videoUrl.isBlank() && q.qrCode.isBlank()) add("video or QR code") }
      if (m.isNotEmpty()) out += MissingItem("Equipment", q.id, q.name, m) }
    c.exercises.forEach { e -> val m = buildList {
        if (e.setup.isEmpty()) add("setup steps"); if (e.steps.isEmpty()) add("movement steps")
        if (e.safety.isBlank()) add("safety notes"); if (e.media.isBlank()) add("photo/video")
        if (e.equipmentIds.isEmpty()) add("equipment")
        e.equipmentIds.filter { catalog.equipment(it) == null }.forEach { add("unknown equipment $it") }
        e.equipmentIds.filter { id -> catalog.equipment(id)?.category in setOf("Bench", "Free weight", "Cable") && e.equipmentSetup.none { it.equipmentId == id } }
          .forEach { add("setup/angle for ${catalog.equipment(it)?.name ?: it}") } }
      if (m.isNotEmpty()) out += MissingItem("Exercise", e.id, e.name, m) }
    c.templates.forEach { t -> val m = buildList {
        if (t.items.isEmpty()) add("exercises"); if (t.description.isBlank()) add("description")
        t.items.filter { catalog.exercise(it.exerciseId) == null }.forEach { add("unknown exercise ${it.exerciseId}") } }
      if (m.isNotEmpty()) out += MissingItem("Workout", t.id, t.name, m) }
    subs.forEach { s -> val m = buildList {
        if (s.photoUris.isEmpty()) add("photo"); if (s.manufacturer.isBlank()) add("manufacturer"); if (s.model.isBlank()) add("model")
        if (s.primaryMuscles.isEmpty()) add("muscles worked"); if (s.howToUse.isEmpty()) add("how-to-use steps")
        if (s.qrCode.isBlank() && s.videoUrl.isBlank()) add("video or QR code") }
      if (m.isNotEmpty()) out += MissingItem("Gym equipment", s.id, s.name, m) }
    out
  }
}
data class MissingItem(val type: String, val id: String, val name: String, val missing: List<String>)

/** One object that owns every repository; created once in MainActivity. */
class AppContainer(ctx: Context) {
  val db = GymDb.get(ctx); val dao = db.dao()
  val catalog = CatalogRepository(ctx)
  val session = UserSession(ctx, dao)
  val remote: SyncBackend = SyncBackend.create()
  val enrichment: EnrichmentService = EnrichmentService.create(catalog)
  val workouts = WorkoutRepository(dao, catalog, session)
  val gyms = GymRepository(ctx, dao, catalog, session, enrichment)
  val admin = AdminRepository(dao, session, catalog)
  val adminAuth = com.gymguide.app.auth.AdminAuth(ctx)
  val gymDirectory = com.gymguide.app.data.places.GymDirectory.create()
}
