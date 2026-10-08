package com.gymguide.app.ui
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.gymguide.app.R
import com.gymguide.app.data.*
import kotlinx.coroutines.launch

val SPLIT_HELP = mapOf(
  "Full Body" to "Train every major muscle group in one session. Great for beginners.",
  "Push" to "Chest, shoulders, and triceps.", "Pull" to "Back and biceps.", "Legs" to "Quads, hamstrings, glutes, calves.",
  "Upper" to "All upper-body muscles.", "Lower" to "All lower-body muscles and core.",
  "Body-Part Split (Bro Split)" to "One or two muscle groups per day, e.g. Chest Day.")

@Composable fun Home(app: AppContainer, go: (String) -> Unit) {
  val activeGymId by app.gyms.activeGymId.collectAsState()
  val gym by remember(activeGymId) { app.gyms.gymFlow(activeGymId) }.collectAsState(null)
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    NowPlayingBar()
    Image(painterResource(R.drawable.gymcue_logo), contentDescription = "GymCue logo", modifier = Modifier.size(112.dp))
    Text("GYMCUE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Text("Find the machine. Set it up. Go.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth().clickable { go(gym?.let { Routes.gym(it.id) } ?: "gyms") }) {
      Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text(gym?.name ?: "No gym selected", fontWeight = FontWeight.Bold)
          Muted(gym?.address?.ifBlank { null } ?: "Find gyms near you to train at")
        }
        TextButton({ go("gyms") }) { Text(if (gym == null) "Find" else "Change") }
      }
    }
    listOf("Find an exercise by muscle" to "muscles", "Workouts" to "styles", "Find gyms nearby" to "gyms",
      "My gym's equipment" to "equipment", "Workout Tracker" to "tracker").forEach { (l, r) ->
      Button({ go(r) }, Modifier.fillMaxWidth().height(56.dp)) { Text(l) } }
    TextButton({ go("admin") }) { Icon(Icons.Filled.AdminPanelSettings, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Admin") }
  }
}

@Composable fun ListScreen(title: String, items: List<String>, click: (String) -> Unit) = LazyColumn(Modifier.padding(16.dp)) {
  item { Text(title, style = MaterialTheme.typography.titleLarge) }
  if (items.isEmpty()) item { Text("No content yet.") }
  items(items) { ListItem(headlineContent = { Text(it) }, modifier = Modifier.clickable { click(it) }) }
}

@Composable fun ExerciseList(list: List<Exercise>, click: (String) -> Unit) = LazyColumn(Modifier.padding(16.dp)) {
  if (list.isEmpty()) item { Text("No exercises available with your gym's equipment.") }
  items(list) { e -> ListItem(headlineContent = { Text(e.name) },
    supportingContent = { Text("${e.difficulty} · ${e.movement} · ${e.primaryMuscles.joinToString()}") },
    modifier = Modifier.clickable { click(e.id) }) }
}

@Composable fun ExerciseDetail(app: AppContainer, e: Exercise, off: Set<String>, nav: NavHostController) {
  val repo = app.catalog
  var busy by remember { mutableStateOf(false) }
  LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { Text(e.name, style = MaterialTheme.typography.headlineSmall) }
    item { SectionTitle("Equipment"); Muted("Tap for setup and how to use it") }
    item { EquipmentLinks(repo, e) { nav.navigate(Routes.equipment(it, e.id)) } }
    item { e.equipmentIds.mapNotNull(repo::equipment).filter { it.alternateNames.isNotEmpty() }.forEach { q ->
      Muted("${q.name} is also called: ${q.alternateNames.joinToString()}") } }
    item { Text("Works: ${e.primaryMuscles.joinToString()}" + if (e.secondaryMuscles.isNotEmpty()) " (also ${e.secondaryMuscles.joinToString()})" else "") }
    section("Setup", e.setup); section("How to do it", e.steps); section("Common mistakes", e.mistakes)
    if (e.breathing.isNotBlank()) item { Text("Breathing: ${e.breathing}") }
    if (e.safety.isNotBlank()) item { Text("Safety: ${e.safety}", color = MaterialTheme.colorScheme.error) }
    item { OutlinedButton({ busy = !busy }) { Text("Machine busy?") } }
    if (busy) { val alts = repo.alternatives(e, off)
      if (alts.isEmpty()) item { Text("No reviewed alternative yet.") }
      items(alts) { a -> ListItem(headlineContent = { Text(a.name) }, modifier = Modifier.clickable { nav.navigate(Routes.exercise(a.id)) }) } }
  }
}
fun LazyListScope.section(t: String, l: List<String>) { if (l.isEmpty()) return
  item { Text(t, style = MaterialTheme.typography.titleMedium) }
  itemsIndexed(l) { i, s -> Text("${i + 1}. $s") } }

@Composable fun EquipmentScreen(app: AppContainer, off: Set<String>) {
  val scope = rememberCoroutineScope()
  LazyColumn(Modifier.padding(16.dp)) {
    item { Text("Uncheck anything your gym doesn't have", style = MaterialTheme.typography.titleMedium) }
    if (app.catalog.content.equipment.isEmpty()) item { Text("No equipment in the catalog yet.") }
    items(app.catalog.content.equipment) { q -> val on = q.id !in off
      ListItem(headlineContent = { Text(q.name) }, supportingContent = { Text(q.category) },
        trailingContent = { Switch(on, { scope.launch { if (it) app.dao.markAvailable(Unavailable(q.id)) else app.dao.markUnavailable(Unavailable(q.id)) } }) }) }
  }
}

private fun volStr(v: Double) = "%.0f lb".format(v)

@Composable fun StatCard(label: String, value: String, modifier: Modifier = Modifier) =
  Card(modifier) { Column(Modifier.padding(10.dp)) {
    Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable fun ProgressChart(title: String, values: List<Pair<Long, Float>>, valueLabel: (Float) -> String) {
  val lineColor = MaterialTheme.colorScheme.primary
  val gridColor = MaterialTheme.colorScheme.surfaceVariant
  Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (values.size < 2) {
      Text("Log at least two sessions to see this chart.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
      Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val minV = values.minOf { it.second }
        val maxV = values.maxOf { it.second }
        val range = (maxV - minV).coerceAtLeast(1f)
        val stepX = size.width / (values.size - 1)
        val chartH = size.height
        for (i in 0..3) { val yy = chartH * i / 3f; drawLine(gridColor, Offset(0f, yy), Offset(size.width, yy), 1.dp.toPx()) }
        val pts = values.mapIndexed { i, v -> Offset(i * stepX, chartH - 6.dp.toPx() - (v.second - minV) / range * (chartH - 12.dp.toPx())) }
        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        drawPath(path, lineColor, style = Stroke(2.dp.toPx()))
        pts.forEach { p -> drawCircle(lineColor, 3.dp.toPx(), p) }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Start " + valueLabel(values.first().second), style = MaterialTheme.typography.bodySmall)
        Text("Best " + valueLabel(values.maxOf { it.second }), style = MaterialTheme.typography.bodySmall)
        Text("Now " + valueLabel(values.last().second), style = MaterialTheme.typography.bodySmall)
      }
    }
  } }
}

private fun dayKey(t: Long): Long {
  val c = java.util.Calendar.getInstance()
  c.timeInMillis = t
  c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
  c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
  return c.timeInMillis
}

/**
 * CRASH FIX (v2 -> v3): the old version computed exerciseIds from `logs` during composition, but the LazyColumn
 * builder re-read the *live* `logs` state later. When the first set was logged, `logs` was non-empty while the
 * captured `exerciseIds` was still empty, so `exerciseIds.first()` threw NoSuchElementException.
 * Now everything is derived once from a single snapshot, and the selection uses firstOrNull().
 */
@Composable fun Tracker(app: AppContainer) {
  val logsState = app.workouts.history().collectAsState(emptyList())
  val logs = logsState.value                       // one immutable snapshot for this composition
  val stats = remember(logs) { TrackerStats.from(logs) }
  var selected by remember { mutableStateOf("") }
  val sel = stats.exerciseIds.firstOrNull { it == selected } ?: stats.exerciseIds.firstOrNull()
  val dayFmt = remember { java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.US) }

  LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (logs.isEmpty() || sel == null) {
      item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No workouts logged yet.", style = MaterialTheme.typography.titleLarge)
        Text("Start a workout and check off sets — your progress shows up here.") } }
    } else {
      item { Text("Workout Tracker", style = MaterialTheme.typography.headlineSmall) }
      item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Workouts", stats.byDay.size.toString(), Modifier.weight(1f))
        StatCard("Total volume", volStr(stats.totalVolume), Modifier.weight(1f))
        StatCard("Last 7 days", volStr(stats.weekVolume), Modifier.weight(1f))
      } }
      item { ProgressChart("Total volume per workout", stats.volumeSeries) { v -> volStr(v.toDouble()) } }
      item { Text("Exercise progress", style = MaterialTheme.typography.titleMedium) }
      item {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("Exercise:  ")
          Box {
            var menuOpen by remember { mutableStateOf(false) }
            OutlinedButton({ menuOpen = true }) { Text(app.catalog.exercise(sel)?.name ?: sel) }
            DropdownMenu(menuOpen, { menuOpen = false }) {
              stats.exerciseIds.forEach { id ->
                DropdownMenuItem(text = { Text(app.catalog.exercise(id)?.name ?: id) }, onClick = { selected = id; menuOpen = false }) }
            }
          }
        }
      }
      item {
        val series = logs.filter { it.exerciseId == sel }.groupBy { dayKey(it.date) }.toSortedMap()
          .map { (d, sets) -> d to (sets.maxOfOrNull { it.weight } ?: 0.0).toFloat() }
        ProgressChart(app.catalog.exercise(sel)?.name ?: sel, series) { v -> "%.0f lb".format(v) }
      }
      item { Text("Workouts by day", style = MaterialTheme.typography.titleMedium) }
      stats.byDay.forEach { (d, sets) ->
        item { Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(dayFmt.format(java.util.Date(d)), fontWeight = FontWeight.Bold)
          Muted("${sets.size} sets · ${volStr(sets.sumOf { it.weight * it.reps })} total volume")
          sets.groupBy { it.exerciseId }.forEach { (ex, ss) ->
            Text("${app.catalog.exercise(ex)?.name ?: ex}: ${ss.size} sets, best ${"%.0f lb".format(ss.maxOfOrNull { it.weight } ?: 0.0)}",
              style = MaterialTheme.typography.bodySmall)
          }
        } } }
      }
    }
  }
}

private data class TrackerStats(val exerciseIds: List<String>, val byDay: Map<Long, List<SetLog>>,
  val totalVolume: Double, val weekVolume: Double, val volumeSeries: List<Pair<Long, Float>>) {
  companion object { fun from(logs: List<SetLog>): TrackerStats {
    val byDay = logs.groupBy { dayKey(it.date) }.toSortedMap(compareByDescending<Long> { it })
    val dayVolume = byDay.mapValues { it.value.sumOf { s -> s.weight * s.reps } }
    val weekAgo = dayKey(System.currentTimeMillis()) - 6L * 86400000L
    return TrackerStats(logs.map { it.exerciseId }.distinct(), byDay, logs.sumOf { it.weight * it.reps },
      dayVolume.filterKeys { it >= weekAgo }.values.sum(), dayVolume.entries.sortedBy { it.key }.map { it.key to it.value.toFloat() })
  } }
}
