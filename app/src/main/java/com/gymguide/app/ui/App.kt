package com.gymguide.app.ui
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.gymguide.app.data.AppContainer

/** Route helpers so every screen builds URLs the same way (and encodes user text safely). */
object Routes {
  fun list(m: String) = "list/${Uri.encode(m)}"
  fun exercise(id: String) = "ex/${Uri.encode(id)}"
  fun equipment(id: String, exerciseId: String? = null) = "equip/${Uri.encode(id)}" + (exerciseId?.let { "?ex=${Uri.encode(it)}" } ?: "")
  fun runTemplate(id: String) = "run/template/${Uri.encode(id)}"
  fun runCustom(id: String) = "run/custom/${Uri.encode(id)}"
  fun builder(id: String? = null) = "builder" + (id?.let { "?id=${Uri.encode(it)}" } ?: "")
  fun gym(id: String) = "gym/${Uri.encode(id)}"
  fun addEquipment(gymId: String, editId: String? = null) = "gym/${Uri.encode(gymId)}/add" + (editId?.let { "?edit=${Uri.encode(it)}" } ?: "")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App(app: AppContainer) {
  val nav = rememberNavController()
  val backStackEntry by nav.currentBackStackEntryAsState()
  val route = backStackEntry?.destination?.route
  val off by app.dao.unavailable().collectAsState(emptyList())
  val offIds = off.map { it.equipmentId }.toSet()
  LaunchedEffect(Unit) { app.session.ensureUser(); app.workouts.adoptMigrated() }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("GymCue") },
        navigationIcon = {
          if (route != null && route != "home") {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
          }
        }
      )
    }
  ) { pad ->
    NavHost(nav, "home", Modifier.padding(pad)) {
      composable("home") { Home(app, nav::navigate) }
      composable("muscles") { ListScreen("Choose a muscle group", app.catalog.muscles) { nav.navigate(Routes.list(it)) } }
      composable("list/{m}") { val m = it.arguments?.getString("m").orEmpty()
        ExerciseList(app.catalog.content.exercises.filter { e -> m in e.primaryMuscles && app.catalog.usable(e, offIds) }) { id -> nav.navigate(Routes.exercise(id)) } }
      composable("ex/{id}") { val e = app.catalog.exercise(it.arguments?.getString("id").orEmpty())
        if (e == null) NotFound("Exercise not found.") else ExerciseDetail(app, e, offIds, nav) }
      composable("equip/{id}?ex={ex}", listOf(navArgument("ex") { type = NavType.StringType; nullable = true; defaultValue = null })) {
        EquipmentDetail(app, it.arguments?.getString("id").orEmpty(), it.arguments?.getString("ex"), nav) }
      composable("styles") { Workouts(app, nav) }
      composable("run/{kind}/{id}") { ActiveWorkout(app, it.arguments?.getString("kind").orEmpty(), it.arguments?.getString("id").orEmpty(), offIds, nav) {
        nav.popBackStack("home", false) } }
      composable("builder?id={id}", listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) {
        WorkoutBuilder(app, it.arguments?.getString("id"), offIds) { nav.popBackStack() } }
      composable("equipment") { EquipmentScreen(app, offIds) }
      composable("tracker") { Tracker(app) }
      composable("gyms") { GymSearch(app, nav) }
      composable("gym/{id}") { GymDetail(app, it.arguments?.getString("id").orEmpty(), nav) }
      composable("gym/{id}/add?edit={edit}", listOf(navArgument("edit") { type = NavType.StringType; nullable = true; defaultValue = null })) {
        AddGymEquipment(app, it.arguments?.getString("id").orEmpty(), it.arguments?.getString("edit")) { nav.popBackStack() } }
      composable("admin") { AdminHome(app, nav) }
    }
  }
}

@Composable fun NotFound(msg: String) = Text(msg, Modifier.padding(androidx.compose.ui.unit.Dp(16f)), style = MaterialTheme.typography.titleMedium)
