package com.gymguide.app.ui
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import com.gymguide.app.data.*
import com.gymguide.app.data.places.DeviceLocation
import com.gymguide.app.data.places.PlaceResult
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import java.io.File

private fun hasLocation(ctx: Context) = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
  .any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
private fun miles(m: Float?) = m?.let { "%.1f mi".format(it / 1609.34f) } ?: ""

// ---------------- Find gyms (GPS + text search) ----------------
@Composable fun GymSearch(app: AppContainer, nav: NavHostController) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var results by remember { mutableStateOf<List<PlaceResult>?>(null) }
  var loading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var query by rememberSaveable { mutableStateOf("") }
  var here by remember { mutableStateOf<Pair<Double, Double>?>(null) }
  val gyms by app.gyms.gyms().collectAsState(emptyList())
  val counts by app.gyms.gymSummaries().collectAsState(emptyMap())
  val byPlace = gyms.associateBy { it.provider to it.providerPlaceId }
  val activeId by app.gyms.activeGymId.collectAsState()

  fun runNearby() = scope.launch {
    loading = true; error = null
    runCatching {
      val loc = DeviceLocation.current(ctx) ?: error("Couldn't get your location. Turn on Location or search by name/city below.")
      here = loc.latitude to loc.longitude
      app.gymDirectory.nearby(loc.latitude, loc.longitude)
    }.onSuccess { results = it }.onFailure { error = it.message ?: "Search failed" }
    loading = false
  }
  fun runText() = scope.launch {
    if (query.isBlank()) return@launch
    loading = true; error = null
    runCatching { app.gymDirectory.search(query, here?.first, here?.second) }.onSuccess { results = it }.onFailure { error = it.message ?: "Search failed" }
    loading = false
  }
  val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
    if (r.values.any { it }) runNearby() else error = "Location permission denied. You can still search by gym name or city." }

  LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
    item { Text("Find gyms", style = MaterialTheme.typography.headlineSmall)
      Muted("Pick the gym you train at, or add one so you can upload its equipment.") }
    item { Button({ if (hasLocation(ctx)) runNearby() else perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
      Modifier.fillMaxWidth().height(52.dp), enabled = !loading) { Icon(Icons.Filled.MyLocation, null); Spacer(Modifier.width(8.dp)); Text("Search near me") } }
    item { Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text("Gym name or city") })
      IconButton({ runText() }, enabled = !loading && query.isNotBlank()) { Icon(Icons.Filled.Search, "Search") } } }
    if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
    results?.let { list ->
      item { Muted("${list.size} results from ${app.gymDirectory.providerName}. Listings may not include every gym.") }
      items(list, key = { it.provider + it.placeId }) { r ->
        val existing = byPlace[r.provider to r.placeId]
        ListItem(headlineContent = { Text(r.name) },
          supportingContent = { Column {
            Text(listOf(r.address, miles(r.distanceM)).filter { it.isNotBlank() }.joinToString(" · "))
            if (existing != null) Text("✓ In GymCue · ${counts[existing.id] ?: 0} machines" + if (existing.id == activeId) " · My gym" else "",
              color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
          } },
          trailingContent = {
            if (existing != null) TextButton({ nav.navigate(Routes.gym(existing.id)) }) { Text("Open") }
            else FilledTonalButton({ scope.launch { val g = app.gyms.addFromPlace(r); nav.navigate(Routes.gym(g.id)) } }) { Text("Add") }
          })
      }
    }
    if (gyms.isNotEmpty()) {
      item { HorizontalDivider(); SectionTitle("Gyms in GymCue") }
      items(gyms, key = { it.id }) { g -> ListItem(headlineContent = { Text(g.name) },
        supportingContent = { Text("${counts[g.id] ?: 0} machines" + if (g.id == activeId) " · My gym" else "") },
        modifier = Modifier.clickable { nav.navigate(Routes.gym(g.id)) }) }
    }
  }
}

// ---------------- Gym page ----------------
@Composable fun GymDetail(app: AppContainer, gymId: String, nav: NavHostController) {
  val gym by remember(gymId) { app.gyms.gymFlow(gymId) }.collectAsState(null)
  val equipment by remember(gymId) { app.gyms.equipmentFor(gymId) }.collectAsState(emptyList())
  val activeId by app.gyms.activeGymId.collectAsState()
  val me = app.session.userId
  val g = gym ?: run { NotFound("Loading gym…"); return }
  val visible = equipment.filter { it.status == ReviewStatus.APPROVED || it.submittedBy == me }
  LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
    item { Text(g.name, style = MaterialTheme.typography.headlineSmall); if (g.address.isNotBlank()) Muted(g.address) }
    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (g.id == activeId) AssistChip({}, { Text("My gym") }, leadingIcon = { Icon(Icons.Filled.Check, null) })
      else Button({ app.gyms.setActiveGym(g.id) }) { Text("Train here") }
      OutlinedButton({ nav.navigate(Routes.addEquipment(g.id)) }) { Icon(Icons.Filled.AddAPhoto, null); Spacer(Modifier.width(6.dp)); Text("Add equipment") }
    } }
    item { SectionTitle("Equipment (${visible.count { it.status == ReviewStatus.APPROVED }} approved)") }
    if (visible.isEmpty()) item { Muted("No equipment uploaded for this gym yet. Be the first — snap a photo of each machine and its label.") }
    items(visible, key = { it.id }) { e ->
      val mine = e.submittedBy == me && e.status != ReviewStatus.APPROVED
      ListItem(headlineContent = { Text(e.name) },
        supportingContent = { Column {
          Text(listOf(e.category, e.primaryMuscles.joinToString()).filter { it.isNotBlank() }.joinToString(" · "))
          if (e.status != ReviewStatus.APPROVED) Text(statusLabel(e.status) + if (e.reviewNote.isNotBlank()) " — ${e.reviewNote}" else "",
            style = MaterialTheme.typography.bodySmall, color = if (e.status == ReviewStatus.REJECTED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        } },
        leadingContent = { if (e.photoUris.isNotEmpty()) PhotoRow(e.photoUris.take(1)) else Icon(Icons.Filled.FitnessCenter, null) },
        modifier = Modifier.clickable {
          when { mine -> nav.navigate(Routes.addEquipment(g.id, e.id)); e.catalogEquipmentId.isNotBlank() -> nav.navigate(Routes.equipment(e.catalogEquipmentId)) } })
    }
  }
}
fun statusLabel(s: String) = when (s) { ReviewStatus.DRAFT -> "Draft"; ReviewStatus.SUBMITTED -> "Waiting for review"
  ReviewStatus.NEEDS_INFO -> "Needs more info"; ReviewStatus.APPROVED -> "Approved"; ReviewStatus.REJECTED -> "Rejected"; else -> s }

// ---------------- Add / edit equipment (user submission) ----------------
private fun newPhotoFile(ctx: Context): File = File(ctx.filesDir, "photos").apply { mkdirs() }.let { File(it, "${newId()}.jpg") }
private fun copyToPhotos(ctx: Context, uri: Uri): String? = runCatching {
  val f = newPhotoFile(ctx); ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }; f.absolutePath }.getOrNull()
private fun lines(s: String) = s.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AddGymEquipment(app: AppContainer, gymId: String, editId: String?, done: () -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var existing by remember { mutableStateOf<GymEquipmentEntity?>(null) }
  var name by rememberSaveable { mutableStateOf("") }
  var catalogId by rememberSaveable { mutableStateOf("") }
  var manufacturer by rememberSaveable { mutableStateOf("") }
  var model by rememberSaveable { mutableStateOf("") }
  var category by rememberSaveable { mutableStateOf("") }
  var altNames by rememberSaveable { mutableStateOf("") }
  var muscles by rememberSaveable { mutableStateOf("") }
  var howTo by rememberSaveable { mutableStateOf("") }
  var qr by rememberSaveable { mutableStateOf("") }
  var video by rememberSaveable { mutableStateOf("") }
  var notes by rememberSaveable { mutableStateOf("") }
  val photos = remember { mutableStateListOf<String>() }
  var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
  var result by remember { mutableStateOf<GymEquipmentEntity?>(null) }
  var catalogMenu by remember { mutableStateOf(false) }

  LaunchedEffect(editId) { if (editId != null) app.gyms.equipmentItem(editId)?.let { e -> existing = e
    name = e.name; catalogId = e.catalogEquipmentId; manufacturer = e.manufacturer; model = e.model; category = e.category
    altNames = e.alternateNames.joinToString("; "); muscles = e.primaryMuscles.joinToString("; "); howTo = e.howToUse.joinToString("\n")
    qr = e.qrCode; video = e.videoUrl; notes = e.notes; photos.clear(); photos += e.photoUris } }

  val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
    val p = pendingPhoto; if (ok && p != null) photos += p else p?.let { File(it).delete() }; pendingPhoto = null }
  fun launchCamera() { val f = newPhotoFile(ctx); pendingPhoto = f.absolutePath
    takePicture.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)) }
  val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) launchCamera() }
  val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
    uris.forEach { u -> copyToPhotos(ctx, u)?.let { photos += it } } }
  val scanQr = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { qr = it } }

  fun build() = (existing ?: GymEquipmentEntity(gymId = gymId, name = "", submittedBy = app.session.userId)).copy(
    name = name.trim(), catalogEquipmentId = catalogId, manufacturer = manufacturer.trim(), model = model.trim(), category = category.trim(),
    alternateNames = lines(altNames), primaryMuscles = lines(muscles), howToUse = lines(howTo), qrCode = qr.trim(), videoUrl = video.trim(),
    notes = notes.trim(), photoUris = photos.toList())

  result?.let { r ->
    val filled = r.fieldSources.filter { it.contains("=catalog:") }.map { it.substringBefore("=") }
    val blank = buildList { if (r.manufacturer.isBlank()) add("manufacturer"); if (r.model.isBlank()) add("model"); if (r.category.isBlank()) add("category")
      if (r.primaryMuscles.isEmpty()) add("muscles"); if (r.howToUse.isEmpty()) add("how-to-use steps"); if (r.photoUris.isEmpty()) add("photo")
      if (r.qrCode.isBlank() && r.videoUrl.isBlank()) add("video/QR") }
    AlertDialog(onDismissRequest = done, confirmButton = { TextButton(done) { Text("OK") } },
      title = { Text(if (r.status == ReviewStatus.DRAFT) "Draft saved" else "Submitted for review") },
      text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (filled.isNotEmpty()) Text("Filled in automatically: ${filled.joinToString()}")
        if (blank.isNotEmpty()) Text("Left blank (add later if you can): ${blank.joinToString()}")
        if (r.status != ReviewStatus.DRAFT) Text("An admin will review it before it shows for everyone.")
      } })
  }

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(if (editId == null) "Add equipment" else "Edit submission", style = MaterialTheme.typography.headlineSmall)
    Muted("Only the name is required. Leave anything you don't know blank — GymCue fills what it can identify and leaves the rest empty.")
    existing?.takeIf { it.reviewNote.isNotBlank() }?.let { Text("Reviewer note: ${it.reviewNote}", color = MaterialTheme.colorScheme.primary) }

    SectionTitle("Photos")
    PhotoRow(photos) { photos.remove(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button({ if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else camPerm.launch(Manifest.permission.CAMERA) }) { Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Take photo") }
      OutlinedButton({ pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
        Icon(Icons.Filled.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("Gallery") }
    }
    Muted("Tip: photograph the whole machine and the label/instruction placard.")

    SectionTitle("Details")
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Machine name (as on the label) *") })
    ExposedDropdownMenuBox(catalogMenu, { catalogMenu = it }) {
      OutlinedTextField(app.catalog.equipment(catalogId)?.name ?: "Not sure / new machine", {}, readOnly = true,
        label = { Text("Same as a GymCue machine?") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catalogMenu) },
        modifier = Modifier.menuAnchor().fillMaxWidth())
      ExposedDropdownMenu(catalogMenu, { catalogMenu = false }) {
        DropdownMenuItem(text = { Text("Not sure / new machine") }, onClick = { catalogId = ""; catalogMenu = false })
        app.catalog.content.equipment.forEach { q -> DropdownMenuItem(text = { Text(q.name) }, onClick = { catalogId = q.id; if (name.isBlank()) name = q.name; catalogMenu = false }) }
      }
    }
    OutlinedTextField(manufacturer, { manufacturer = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Manufacturer") })
    OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Model") })
    OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Category (Selectorized, Plate-loaded, Cable, Bench…)") })
    OutlinedTextField(altNames, { altNames = it }, Modifier.fillMaxWidth(), label = { Text("Other names (separate with ;)") })
    OutlinedTextField(muscles, { muscles = it }, Modifier.fillMaxWidth(), label = { Text("Muscles worked (separate with ;)") })
    OutlinedTextField(howTo, { howTo = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp), label = { Text("How to use it (one step per line)") })

    SectionTitle("Video / QR code")
    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(qr, { qr = it }, Modifier.weight(1f), singleLine = true, label = { Text("QR code on the machine") })
      IconButton({ scanQr.launch(ScanOptions().setPrompt("Scan the QR sticker on the machine").setBeepEnabled(false).setOrientationLocked(false)) }) {
        Icon(Icons.Filled.QrCodeScanner, "Scan QR") }
    }
    OutlinedTextField(video, { video = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Video link (YouTube or .mp4)") })
    OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes (where it is, quirks)") })

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton({ scope.launch { result = app.gyms.submitEquipment(build(), ReviewStatus.DRAFT) } }, Modifier.weight(1f), enabled = name.isNotBlank()) { Text("Save draft") }
      Button({ scope.launch { result = app.gyms.submitEquipment(build(), ReviewStatus.SUBMITTED) } }, Modifier.weight(1f), enabled = name.isNotBlank()) { Text("Submit") }
    }
    Spacer(Modifier.height(24.dp))
  }
}
