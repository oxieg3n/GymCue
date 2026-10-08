package com.gymguide.app
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gymguide.app.data.*
import com.gymguide.app.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class RegressionTests {
  @get:Rule val compose = createComposeRule()
  private val ctx: Context get() = ApplicationProvider.getApplicationContext()

  /** Reproduces the v2 crash: Tracker open with no data, then the first set is logged. */
  @Test fun trackerSurvivesFirstLoggedSet() {
    val app = AppContainer(ctx)
    compose.setContent { Tracker(app) }
    compose.onNodeWithText("No workouts logged yet.").assertExists()
    runBlocking { app.workouts.logSet(SetLog(date = now(), exerciseId = "ex_leg_press", setNo = 1, reps = 10, weight = 100.0)) }
    compose.waitUntil(10_000) { compose.onAllNodesWithText("Workout Tracker").fetchSemanticsNodes().isNotEmpty() }
    runBlocking { app.workouts.logSet(SetLog(date = now(), exerciseId = "ex_deleted_from_catalog", setNo = 1, reps = 5, weight = 50.0)) }
    compose.waitUntil(10_000) { compose.onAllNodesWithText("Ex_deleted_from_catalog", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
    compose.onNodeWithText("Workout Tracker").assertExists()
  }

  /** v1 database (with history + an old custom workout) upgrades to v2 without losing data. */
  @Test fun migrationKeepsHistory() {
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDb::class.java)
    helper.createDatabase("mig.db", 1).apply {
      execSQL("INSERT INTO SetLog(date,exerciseId,setNo,reps,weight,units,notes) VALUES(1,'ex_leg_press',1,10,100,'lb','')")
      execSQL("INSERT INTO CustomWorkout(name,exerciseIdsCsv) VALUES('Old one','ex_leg_press,ex_chest_press')")
      close() }
    val db = helper.runMigrationsAndValidate("mig.db", 2, true, MIGRATION_1_2)
    db.query("SELECT COUNT(*) FROM SetLog").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    db.query("SELECT COUNT(*) FROM workout_items").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
    db.close()
  }

  /** Removing every exercise and the minimum-set rule never crash the active workout. */
  @Test fun activeWorkoutRemoveAllAndMinimumSets() {
    val app = AppContainer(ctx)
    compose.setContent { val nav = androidx.navigation.compose.rememberNavController()
      NavHost(nav, "w") { composable("w") {
        ActiveWorkout(app, "template", "t_fullbody_1", emptySet(), nav) {} } } }
    compose.waitForIdle()
    // Set minus is disabled once only one set remains (template has 2)
    compose.onAllNodesWithContentDescription("Remove set")[0].performClick()
    compose.onAllNodesWithContentDescription("Remove set")[0].assertIsNotEnabled()
    compose.onAllNodesWithContentDescription("Add set")[0].performClick()
    repeat(3) { compose.onAllNodes(hasContentDescription("Remove ", substring = true) and hasClickAction() and !hasContentDescription("Remove set"))[0].performClick(); compose.waitForIdle() }
    compose.onNodeWithText("No exercises in this workout.").assertExists()
  }

  @Test fun customWorkoutSavesAndLoads() = runBlocking {
    val app = AppContainer(ctx); app.session.ensureUser()
    val id = app.workouts.saveCustom(null, "My Push", listOf(TemplateItem("ex_chest_press", 3, "10", 60), TemplateItem("ex_db_incline_press", 2, "8-10", 90)))
    val plan = app.workouts.plan("custom", id)!!
    assertEquals("My Push", plan.name); assertEquals(2, plan.items.size); assertEquals("ex_db_incline_press", plan.items[1].exerciseId)
    assertNull(app.workouts.plan("template", "does_not_exist"))
  }

  @Test fun enrichmentFillsOnlyIdentifiedFieldsAndLeavesRestBlank() = runBlocking {
    val app = AppContainer(ctx)
    val known = app.gyms.submitEquipment(GymEquipmentEntity(gymId = "g", name = "Seated chest press", submittedBy = "u"))
    assertEquals("eq_chest_press", known.catalogEquipmentId); assertEquals("Selectorized", known.category)
    assertTrue(known.primaryMuscles.contains("Chest")); assertEquals("", known.model)
    val unknown = app.gyms.submitEquipment(GymEquipmentEntity(gymId = "g", name = "Mystery Machine 3000", submittedBy = "u"))
    assertEquals("", unknown.catalogEquipmentId); assertEquals("", unknown.category); assertTrue(unknown.primaryMuscles.isEmpty())
  }

  @Test fun adminPinRulesAndLockout() {
    val auth = com.gymguide.app.auth.AdminAuth(ctx)
    assertFalse(auth.setPin("12345")); assertFalse(auth.setPin("12a456")); assertTrue(auth.setPin("482916"))
    auth.lock(); assertTrue(auth.verify("482916") is com.gymguide.app.auth.AdminAuth.Result.Ok)
    auth.lock(); repeat(4) { assertTrue(auth.verify("000000") is com.gymguide.app.auth.AdminAuth.Result.Wrong) }
    assertTrue(auth.verify("000000") is com.gymguide.app.auth.AdminAuth.Result.Locked)
    assertTrue(auth.verify("482916") is com.gymguide.app.auth.AdminAuth.Result.Locked)  // correct PIN still blocked during lockout
  }

  @Test fun youtubeLinksAreRecognised() {
    assertEquals("dQw4w9WgXcQ", youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    assertEquals("dQw4w9WgXcQ", youtubeId("https://youtu.be/dQw4w9WgXcQ"))
    assertNull(youtubeId("https://planetfitness.com/some-page"))
  }
}
