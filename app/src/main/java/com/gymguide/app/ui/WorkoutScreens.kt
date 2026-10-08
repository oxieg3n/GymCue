package com.gymguide.app.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.gymguide.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val MAX_SETS = 10
const val MIN_SETS = 1

// ---------------- Workouts list: custom workouts + bundled templates ----------------
@Composable fun Workouts(app: AppContainer, nav: NavHostController) {
  val customs by app.workouts.customWorkouts().collectAsState(emptyList())
  val allItems by app.dao.allWorkoutItems().collectAsState(emptyList())
  val scope = rememberCoroutineScope()
  var confirmDelete by remember { mutableStateOf<WorkoutEntity?>(null) }
  confirmDelete?.let { w -> AlertDialog(onDismissRequest = { confirmDelete = null },
    title = { Text("Delete \"${w.name}\"?") }, text = { Text("Your logged sets stay in the Workout Tracker.") },
    confirmButton = { TextButton({ scope.launch { app.workouts.deleteCustom(w.id) }; confirmDelete = null }) { Text("Delete") } },
    dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } }) }

  LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    item { NowPlayingBar(Modifier.padding(bottom = 8.dp)) }
    item { Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Custom Workouts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
      FilledTonalButton({ nav.navigate(Routes.builder()) }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Create") } } }
    if (customs.isEmpty()) item { Muted("Build your own workout from any exercise in the library.") }
    items(customs, key = { it.id }) { w -> val n = allItems.count { it.workoutId == w.id }
      ListItem(headlineContent = { Text(w.name) }, supportingContent = { Text("$n exercise${if (n == 1) "" else "s"}") },
        trailingContent = { Row {
          IconButton({ nav.navigate(Routes.builder(w.id)) }) { Icon(Icons.Filled.Edit, "Edit") }
          IconButton({ confirmDelete = w }) { Icon(Icons.Filled.Delete, "Delete") } } },
        modifier = Modifier.clickable { nav.navigate(Routes.runCustom(w.id)) })
    }
    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
    app.catalog.content.templates.groupBy { it.split }.forEach { (split, ts) ->
      item { Text(split, style = MaterialTheme.typography.titleLarge); SPLIT_HELP[split]?.let { Text(it) } }
      items(ts) { t -> ListItem(headlineContent = { Text(t.name) }, supportingContent = { Text(t.description) },
        modifier = Modifier.clickable { nav.navigate(Routes.runTemplate(t.id)) }) }
    }
  }
}

// ---------------- Active workout session ----------------
class SetRow { var weight by mutableStateOf(""); var reps by mutableStateOf(""); var done by mutableStateOf(false); var logged = false }

/** One exercise inside today's session. Editing the session never changes the saved workout/template. */
class SessionExercise(val exerciseId: String, val swappedFrom: String?, val reps: String, val restSec: Int, sets: Int) {
  val uid = newId()
  val sets = mutableStateListOf<SetRow>().apply { repeat(sets.coerceIn(MIN_SETS, MAX_SETS)) { add(SetRow()) } }
}

@Composable fun ActiveWorkout(app: AppContainer, kind: String, planId: String, off: Set<String>, nav: NavHostController, onDone: () -> Unit) {
  val repo = app.catalog
  var plan by remember { mutableStateOf<WorkoutPlan?>(null) }
  var loaded by remember { mutableStateOf(false) }
  val session = remember { mutableStateListOf<SessionExercise>() }
  val sessionId = remember { newId() }
  val gymId by app.gyms.activeGymId.collectAsState()
  LaunchedEffect(kind, planId) {
    if (!loaded) {
      plan = app.workouts.plan(kind, planId)
      plan?.items?.forEach { item ->
        val base = repo.exercise(item.exerciseId) ?: return@forEach      // skip ids that no longer exist
        val ex = if (repo.usable(base, off)) base else repo.alternatives(base, off).firstOrNull() ?: base
        session += SessionExercise(ex.id, if (ex.id != base.id) base.id else null, item.reps, item.restSec, item.sets)
      }
      loaded = true
    }
  }
  val p = plan
  if (!loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
  if (p == null) { NotFound("This workout no longer exists."); return }

  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  var rest by remember { mutableIntStateOf(0) }
  LaunchedEffect(rest) { if (rest > 0) { delay(1000); rest-- } }
  var showDone by remember { mutableStateOf(false) }
  var picking by remember { mutableStateOf(false) }
  var saveAs by remember { mutableStateOf(false) }

  fun log(se: SessionExercise, idx: Int, row: SetRow) { if (row.logged) return; row.logged = true
    scope.launch { app.workouts.logSet(SetLog(date = now(), exerciseId = se.exerciseId, setNo = idx + 1,
      reps = row.reps.toIntOrNull() ?: 0, weight = row.weight.toDoubleOrNull() ?: 0.0, sessionId = sessionId, gymId = gymId)) } }

  if (showDone) AlertDialog(onDismissRequest = {}, confirmButton = { TextButton({ onDone() }) { Text("Done") } },
    title = { Text("Workout saved") }, text = { Text("Nice work — your sets are in the Workout Tracker.") })
  if (picking) ExercisePickerSheet(repo, repo.suggestionsFor(session.map { it.exerciseId }, off), session.map { it.exerciseId }.toSet(),
    onDismiss = { picking = false }) { picked ->
    picked.forEach { session += SessionExercise(it.id, null, "10-12", 90, 3) }; picking = false }
  if (saveAs) NameDialog("Save as custom workout", p.name + " (my version)", { saveAs = false }) { name ->
    scope.launch { app.workouts.saveCustom(null, name, session.map { TemplateItem(it.exerciseId, it.sets.size, it.reps, it.restSec) })
      saveAs = false; snackbar.showSnackbar("Saved to Custom Workouts") } }

  Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
      contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
      item { NowPlayingBar() }
      item { Text(p.name, style = MaterialTheme.typography.headlineSmall)
        if (rest > 0) Text("Rest: ${rest}s", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
      if (session.isEmpty()) item { Card { Column(Modifier.padding(16.dp)) {
        Text("No exercises in this workout.", fontWeight = FontWeight.Bold); Muted("Tap \"Add exercise\" below to build today's session.") } } }
      itemsIndexed(session, key = { _, se -> se.uid }) { index, se ->
        val ex = repo.exercise(se.exerciseId)
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { nav.navigate(Routes.exercise(se.exerciseId)) }) {
              Text((ex?.name ?: se.exerciseId) + (se.swappedFrom?.let { " (swap for ${repo.exercise(it)?.name ?: it})" } ?: ""), fontWeight = FontWeight.Bold)
              Muted("Target: ${se.sets.size} sets × ${se.reps} reps · rest ${se.restSec}s")
            }
            RemoveCircleButton("Remove ${ex?.name ?: "exercise"}") {
              val removed = session.removeAt(index)
              scope.launch {
                val r = snackbar.showSnackbar("Removed ${ex?.name ?: "exercise"}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) session.add(index.coerceAtMost(session.size), removed)
              }
            }
          }
          if (ex != null) EquipmentLinks(repo, ex) { nav.navigate(Routes.equipment(it, ex.id)) }
          Row(verticalAlignment = Alignment.CenterVertically) {
            SetStepButton(add = false, enabled = se.sets.size > MIN_SETS) { if (se.sets.size > MIN_SETS) se.sets.removeAt(se.sets.lastIndex) }
            Text("  ${se.sets.size} sets  ", style = MaterialTheme.typography.titleMedium)
            SetStepButton(add = true, enabled = se.sets.size < MAX_SETS) { if (se.sets.size < MAX_SETS) se.sets.add(SetRow()) }
          }
          se.sets.forEachIndexed { n, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text("Set ${n + 1}  ")
              OutlinedTextField(row.weight, { row.weight = it }, Modifier.width(90.dp), label = { Text("lb") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
              OutlinedTextField(row.reps, { row.reps = it }, Modifier.width(90.dp), label = { Text("reps") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
              Checkbox(row.done, { c -> row.done = c; if (c) { log(se, n, row); rest = se.restSec } })
            }
          }
        } }
      }
      item { OutlinedButton({ picking = true }, Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add exercise") } }
      item { TextButton({ saveAs = true }, Modifier.fillMaxWidth(), enabled = session.isNotEmpty()) { Text("Save as custom workout") } }
      item { Button({
        session.forEach { se -> se.sets.forEachIndexed { i, row -> if (!row.done && (row.weight.isNotBlank() || row.reps.isNotBlank())) log(se, i, row) } }
        showDone = true
      }, Modifier.fillMaxWidth().height(56.dp)) { Text("Finish Workout") } }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
  }
}

@Composable fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
  var name by remember { mutableStateOf(initial) }
  AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
    text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Workout name") }) },
    confirmButton = { TextButton({ save(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
    dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

// ---------------- Custom workout builder ----------------
class BuilderItem(val exerciseId: String, sets: Int, reps: String, rest: Int) {
  val key = newId(); var sets by mutableIntStateOf(sets); var reps by mutableStateOf(reps); var rest by mutableStateOf(rest.toString())
}

@Composable fun WorkoutBuilder(app: AppContainer, editId: String?, off: Set<String>, onSaved: () -> Unit) {
  val repo = app.catalog
  var name by rememberSaveable { mutableStateOf("") }
  val items = remember { mutableStateListOf<BuilderItem>() }
  var loaded by remember { mutableStateOf(editId == null) }
  var picking by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(editId) { if (editId != null && !loaded) {
    app.workouts.customWorkout(editId)?.let { name = it.name }
    app.workouts.customItems(editId).forEach { items += BuilderItem(it.exerciseId, it.sets, it.reps, it.restSec) }
    loaded = true } }

  if (picking) ExercisePickerSheet(repo, null, items.map { it.exerciseId }.toSet(), { picking = false }) { picked ->
    picked.forEach { items += BuilderItem(it.id, 3, "10-12", 90) }; picking = false }

  LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
    item { Text(if (editId == null) "Create custom workout" else "Edit custom workout", style = MaterialTheme.typography.headlineSmall) }
    item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Workout name") }) }
    if (items.isEmpty()) item { Muted("No exercises yet. Add any exercise from the full library.") }
    itemsIndexed(items, key = { _, it -> it.key }) { i, it ->
      val ex = repo.exercise(it.exerciseId)
      Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) { Text("${i + 1}. ${ex?.name ?: it.exerciseId}", fontWeight = FontWeight.Bold)
            Muted(ex?.primaryMuscles?.joinToString() ?: "Unknown exercise") }
          IconButton({ if (i > 0) items.add(i - 1, items.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
          IconButton({ if (i < items.lastIndex) items.add(i + 1, items.removeAt(i)) }, enabled = i < items.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
          RemoveCircleButton("Remove ${ex?.name ?: "exercise"}") { items.removeAt(i) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SetStepButton(false, it.sets > MIN_SETS) { if (it.sets > MIN_SETS) it.sets-- }
          Text("${it.sets} sets", style = MaterialTheme.typography.titleMedium)
          SetStepButton(true, it.sets < MAX_SETS) { if (it.sets < MAX_SETS) it.sets++ }
          OutlinedTextField(it.reps, { v -> it.reps = v }, Modifier.width(88.dp), singleLine = true, label = { Text("reps") })
          OutlinedTextField(it.rest, { v -> it.rest = v.filter(Char::isDigit) }, Modifier.width(80.dp), singleLine = true, label = { Text("rest s") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
      } }
    }
    item { OutlinedButton({ picking = true }, Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add exercises") } }
    item { Button({ scope.launch {
        app.workouts.saveCustom(editId, name, items.map { TemplateItem(it.exerciseId, it.sets, it.reps.ifBlank { "10" }, it.rest.toIntOrNull() ?: 90) })
        onSaved() } },
      Modifier.fillMaxWidth().height(56.dp), enabled = loaded && name.isNotBlank() && items.isNotEmpty()) { Text("Save workout") } }
  }
}
