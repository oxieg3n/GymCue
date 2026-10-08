package com.gymguide.app.data
import kotlinx.serialization.Serializable

// ---------- Catalog content (bundled in assets/content.json, generated from GymGuide_Content.xlsx) ----------
// Catalog IDs (eq_*, ex_*, t_*) are stable and shared by every user/server, so local records can reference them safely.

@Serializable data class Equipment(
  val id: String, val name: String, val manufacturer: String = "",
  val alternateNames: List<String> = emptyList(), val category: String = "", val photo: String = "",
  val howToUse: List<String> = emptyList(),   // "How to use this equipment" steps (bottom of the equipment page)
  val qrCode: String = "",                    // raw payload/URL of the Planet Fitness QR sticker, as scanned
  val videoUrl: String = "",                  // embeddable video (YouTube link or direct .mp4) you have rights to show
  val notes: String = ""
)

/** Exercise-specific setup for one piece of equipment (e.g. bench at 30°, cable at shoulder height). */
@Serializable data class EquipmentSetup(
  val equipmentId: String, val position: String = "", val angle: String = "",
  val attachment: String = "", val notes: List<String> = emptyList()
)

@Serializable data class Exercise(val id: String, val name: String, val equipmentIds: List<String>,
  val primaryMuscles: List<String>, val secondaryMuscles: List<String> = emptyList(),
  val movement: String, val difficulty: String = "Beginner",
  val setup: List<String> = emptyList(), val steps: List<String> = emptyList(),
  val breathing: String = "", val mistakes: List<String> = emptyList(), val safety: String = "",
  val alternativeIds: List<String> = emptyList(), val media: String = "",
  val equipmentSetup: List<EquipmentSetup> = emptyList())

@Serializable data class TemplateItem(val exerciseId: String, val sets: Int, val reps: String, val restSec: Int)
@Serializable data class WorkoutTemplate(val id: String, val name: String, val split: String,
  val description: String, val items: List<TemplateItem>)
@Serializable data class Content(val equipment: List<Equipment>, val exercises: List<Exercise>, val templates: List<WorkoutTemplate>)

// ---------- Shared-data vocabulary (same values will be used by the future server API) ----------
object Visibility { const val PRIVATE = "PRIVATE"; const val SHARED = "SHARED"; const val PUBLIC = "PUBLIC" }
object SyncState { const val LOCAL = "LOCAL"; const val PENDING = "PENDING"; const val SYNCED = "SYNCED" }
object ReviewStatus {
  const val DRAFT = "DRAFT"; const val SUBMITTED = "SUBMITTED"; const val NEEDS_INFO = "NEEDS_INFO"
  const val APPROVED = "APPROVED"; const val REJECTED = "REJECTED"
  val all = listOf(DRAFT, SUBMITTED, NEEDS_INFO, APPROVED, REJECTED)
}
object Roles { const val USER = "USER"; const val ADMIN = "ADMIN" }
object AccountStatus { const val ACTIVE = "ACTIVE"; const val SUSPENDED = "SUSPENDED" }

/** A runnable workout regardless of where it came from (bundled template or a user's custom workout). */
data class WorkoutPlan(val id: String, val name: String, val items: List<TemplateItem>, val isCustom: Boolean)

fun newId(): String = java.util.UUID.randomUUID().toString()
fun now(): Long = System.currentTimeMillis()
