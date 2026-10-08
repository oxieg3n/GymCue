package com.gymguide.app.data
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * Every user-created record uses a UUID string id + ownerId + timestamps + syncState so it can be uploaded to,
 * and downloaded from, a shared GymCue server without id collisions. Deletes are soft (deleted = 1) so the
 * deletion itself can be synced. Each local write also adds a row to Outbox for a future sync worker.
 */

class Converters {
  private val json = Json
  @TypeConverter fun fromList(v: List<String>): String = json.encodeToString(v)
  @TypeConverter fun toList(v: String): List<String> = if (v.isBlank()) emptyList() else runCatching { json.decodeFromString<List<String>>(v) }.getOrDefault(emptyList())
}

@Entity data class Unavailable(@PrimaryKey val equipmentId: String)

@Entity data class SetLog(@PrimaryKey(autoGenerate = true) val id: Long = 0, val date: Long,
  val exerciseId: String, val setNo: Int, val reps: Int, val weight: Double, val units: String = "lb", val notes: String = "",
  @ColumnInfo(defaultValue = "") val sessionId: String = "",
  @ColumnInfo(defaultValue = "") val gymId: String = "")

@Entity(tableName = "workouts") data class WorkoutEntity(
  @PrimaryKey val id: String = newId(), val name: String, val description: String = "", val split: String = "Custom",
  val ownerId: String, val visibility: String = Visibility.PRIVATE, val sourceTemplateId: String = "",
  val createdAt: Long = now(), val updatedAt: Long = now(), val syncState: String = SyncState.LOCAL, val deleted: Boolean = false)

@Entity(tableName = "workout_items", indices = [Index("workoutId")]) data class WorkoutItemEntity(
  @PrimaryKey val id: String = newId(), val workoutId: String, val position: Int,
  val exerciseId: String, val sets: Int = 3, val reps: String = "10-12", val restSec: Int = 90)

@Entity(tableName = "gyms", indices = [Index(value = ["provider", "providerPlaceId"], unique = true)]) data class GymEntity(
  @PrimaryKey val id: String = newId(),
  val provider: String,              // "osm", "google", or "manual"
  val providerPlaceId: String,       // stable id from the directory, used to detect "already added"
  val name: String, val address: String = "", val lat: Double = 0.0, val lng: Double = 0.0,
  val status: String = ReviewStatus.APPROVED, val createdBy: String,
  val createdAt: Long = now(), val updatedAt: Long = now(), val syncState: String = SyncState.LOCAL, val deleted: Boolean = false)

@Entity(tableName = "gym_equipment", indices = [Index("gymId")]) data class GymEquipmentEntity(
  @PrimaryKey val id: String = newId(), val gymId: String,
  val catalogEquipmentId: String = "",            // link to catalog Equipment when known
  val name: String, val manufacturer: String = "", val model: String = "", val category: String = "",
  val alternateNames: List<String> = emptyList(), val primaryMuscles: List<String> = emptyList(),
  val howToUse: List<String> = emptyList(), val qrCode: String = "", val videoUrl: String = "", val notes: String = "",
  val photoUris: List<String> = emptyList(),
  /** field name -> where its value came from ("user", "catalog:eq_x", "admin"); blank fields are simply absent */
  val fieldSources: List<String> = emptyList(),
  val status: String = ReviewStatus.SUBMITTED, val reviewNote: String = "", val submittedBy: String,
  val createdAt: Long = now(), val updatedAt: Long = now(), val syncState: String = SyncState.LOCAL, val deleted: Boolean = false)

@Entity(tableName = "users") data class UserEntity(
  @PrimaryKey val id: String = newId(), val displayName: String, val email: String = "",
  val role: String = Roles.USER, val status: String = AccountStatus.ACTIVE,
  val createdAt: Long = now(), val updatedAt: Long = now(), val syncState: String = SyncState.LOCAL)

@Entity(tableName = "audit_log") data class AuditEntity(
  @PrimaryKey val id: String = newId(), val actorId: String, val action: String,
  val targetType: String, val targetId: String, val detail: String = "", val at: Long = now())

@Entity(tableName = "outbox") data class OutboxEntity(
  @PrimaryKey val id: String = newId(), val entityType: String, val entityId: String,
  val op: String, val createdAt: Long = now(), val attempts: Int = 0)

@Dao interface GymDao {
  // equipment toggles + logs (v1)
  @Query("SELECT * FROM Unavailable") fun unavailable(): Flow<List<Unavailable>>
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun markUnavailable(u: Unavailable)
  @Delete suspend fun markAvailable(u: Unavailable)
  @Insert suspend fun log(s: SetLog)
  @Query("SELECT * FROM SetLog ORDER BY date DESC") fun history(): Flow<List<SetLog>>

  // workouts
  @Query("SELECT * FROM workouts WHERE deleted = 0 AND ownerId = :owner ORDER BY updatedAt DESC") fun workouts(owner: String): Flow<List<WorkoutEntity>>
  @Query("SELECT * FROM workouts WHERE id = :id") suspend fun workout(id: String): WorkoutEntity?
  @Query("SELECT * FROM workout_items WHERE workoutId = :id ORDER BY position") suspend fun workoutItems(id: String): List<WorkoutItemEntity>
  @Query("SELECT * FROM workout_items") fun allWorkoutItems(): Flow<List<WorkoutItemEntity>>
  @Upsert suspend fun upsertWorkout(w: WorkoutEntity)
  @Query("DELETE FROM workout_items WHERE workoutId = :id") suspend fun clearItems(id: String)
  @Insert suspend fun insertItems(items: List<WorkoutItemEntity>)
  @Query("UPDATE workouts SET ownerId = :owner WHERE ownerId = '__local__'") suspend fun adoptOwner(owner: String)

  // gyms
  @Query("SELECT * FROM gyms WHERE deleted = 0 ORDER BY name") fun gyms(): Flow<List<GymEntity>>
  @Query("SELECT * FROM gyms WHERE id = :id") suspend fun gym(id: String): GymEntity?
  @Query("SELECT * FROM gyms WHERE id = :id") fun gymFlow(id: String): Flow<GymEntity?>
  @Query("SELECT * FROM gyms WHERE provider = :p AND providerPlaceId = :pid LIMIT 1") suspend fun gymByPlace(p: String, pid: String): GymEntity?
  @Upsert suspend fun upsertGym(g: GymEntity)

  // gym equipment / submissions
  @Query("SELECT * FROM gym_equipment WHERE deleted = 0 AND gymId = :gymId ORDER BY name") fun gymEquipment(gymId: String): Flow<List<GymEquipmentEntity>>
  @Query("SELECT * FROM gym_equipment WHERE deleted = 0 ORDER BY updatedAt DESC") fun allGymEquipment(): Flow<List<GymEquipmentEntity>>
  @Query("SELECT * FROM gym_equipment WHERE id = :id") suspend fun gymEquipmentItem(id: String): GymEquipmentEntity?
  @Upsert suspend fun upsertGymEquipment(e: GymEquipmentEntity)

  // users / admin
  @Query("SELECT * FROM users ORDER BY displayName") fun users(): Flow<List<UserEntity>>
  @Query("SELECT * FROM users WHERE id = :id") suspend fun user(id: String): UserEntity?
  @Query("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE'") suspend fun activeAdminCount(): Int
  @Upsert suspend fun upsertUser(u: UserEntity)
  @Insert suspend fun audit(a: AuditEntity)
  @Query("SELECT * FROM audit_log ORDER BY at DESC LIMIT 200") fun auditLog(): Flow<List<AuditEntity>>

  // sync outbox
  @Insert suspend fun enqueue(o: OutboxEntity)
  @Query("SELECT COUNT(*) FROM outbox") fun pendingSyncCount(): Flow<Int>
  @Query("SELECT * FROM outbox ORDER BY createdAt LIMIT :limit") suspend fun outbox(limit: Int = 100): List<OutboxEntity>
  @Query("DELETE FROM outbox WHERE id IN (:ids)") suspend fun clearOutbox(ids: List<String>)
}

@Database(entities = [Unavailable::class, SetLog::class, WorkoutEntity::class, WorkoutItemEntity::class,
  GymEntity::class, GymEquipmentEntity::class, UserEntity::class, AuditEntity::class, OutboxEntity::class],
  version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class GymDb : RoomDatabase() {
  abstract fun dao(): GymDao
  companion object { @Volatile private var i: GymDb? = null
    fun get(c: Context) = i ?: synchronized(this) {
      i ?: Room.databaseBuilder(c, GymDb::class.java, "gym.db").addMigrations(MIGRATION_1_2).build().also { i = it } } }
}

/** v1 -> v3 app (db 1 -> 2): keeps workout history and any old CustomWorkout rows. */
val MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE `SetLog` ADD COLUMN `sessionId` TEXT NOT NULL DEFAULT ''")
    db.execSQL("ALTER TABLE `SetLog` ADD COLUMN `gymId` TEXT NOT NULL DEFAULT ''")
    SCHEMA_V2_CREATE.forEach { db.execSQL(it) }
    // Move v1 CustomWorkout(id, name, exerciseIdsCsv) rows into workouts/workout_items, owned by "local" until a user exists.
    val c = db.query("SELECT name, exerciseIdsCsv FROM CustomWorkout")
    while (c.moveToNext()) {
      val wid = newId(); val t = now()
      db.insert("workouts", SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
        put("id", wid); put("name", c.getString(0)); put("description", ""); put("split", "Custom")
        put("ownerId", LOCAL_OWNER_PLACEHOLDER); put("visibility", Visibility.PRIVATE); put("sourceTemplateId", "")
        put("createdAt", t); put("updatedAt", t); put("syncState", SyncState.LOCAL); put("deleted", 0) })
      c.getString(1).split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, ex ->
        db.insert("workout_items", SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
          put("id", newId()); put("workoutId", wid); put("position", i); put("exerciseId", ex)
          put("sets", 3); put("reps", "10-12"); put("restSec", 90) })
      }
    }
    c.close()
    db.execSQL("DROP TABLE IF EXISTS `CustomWorkout`")
  }
}
const val LOCAL_OWNER_PLACEHOLDER = "__local__"
