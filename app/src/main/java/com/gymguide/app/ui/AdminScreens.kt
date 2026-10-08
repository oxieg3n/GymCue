package com.gymguide.app.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.gymguide.app.auth.AdminAuth
import com.gymguide.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable fun AdminHome(app: AppContainer, nav: NavHostController) {
  var unlocked by remember { mutableStateOf(app.adminAuth.isUnlocked) }
  // Auto-lock when the admin session expires.
  LaunchedEffect(unlocked) { while (unlocked) { delay(15_000); if (!app.adminAuth.isUnlocked) unlocked = false } }
  if (!unlocked) PinGate(app) { unlocked = true } else AdminTabs(app, nav) { app.adminAuth.lock(); unlocked = false }
}

@Composable private fun PinGate(app: AppContainer, onUnlocked: () -> Unit) {
  val auth = app.adminAuth
  val scope = rememberCoroutineScope()
  val setup = !auth.hasPin
  var pin by remember { mutableStateOf("") }
  var confirm by remember { mutableStateOf("") }
  var msg by remember { mutableStateOf<String?>(null) }
  fun digits(s: String) = s.filter { it in '0'..'9' }.take(6)
  Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Icon(Icons.Filled.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
    Text(if (setup) "Create admin PIN" else "Enter admin PIN", style = MaterialTheme.typography.headlineSmall)
    Muted(if (setup) "Choose a 6-digit, numbers-only PIN. This device's account becomes an admin." else "6 digits")
    OutlinedTextField(pin, { pin = digits(it) }, singleLine = true, label = { Text("PIN") },
      visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
    if (setup) OutlinedTextField(confirm, { confirm = digits(it) }, singleLine = true, label = { Text("Confirm PIN") },
      visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
    msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button({
      if (setup) {
        when { !auth.isValidFormat(pin) -> msg = "PIN must be exactly 6 digits."; pin != confirm -> msg = "PINs don't match."
          else -> { auth.setPin(pin); scope.launch { app.admin.makeCurrentUserAdmin(); app.admin.audit("set_admin_pin", "device", app.session.userId) }; onUnlocked() } }
      } else when (val r = auth.verify(pin)) {
        AdminAuth.Result.Ok -> { scope.launch { app.admin.audit("admin_unlock", "device", app.session.userId) }; onUnlocked() }
        AdminAuth.Result.BadFormat -> msg = "PIN must be exactly 6 digits."
        is AdminAuth.Result.Wrong -> { msg = "Wrong PIN. ${r.left} attempt(s) left."; pin = "" }
        is AdminAuth.Result.Locked -> { msg = "Too many attempts. Try again in ${(r.ms / 60000) + 1} min."; pin = "" }
      }
    }, Modifier.fillMaxWidth().height(52.dp), enabled = pin.length == 6) { Text(if (setup) "Create PIN" else "Unlock") }
  }
}

@Composable private fun AdminTabs(app: AppContainer, nav: NavHostController, lock: () -> Unit) {
  var tab by rememberSaveableInt(0)
  val tabs = listOf("Submissions", "Missing info", "Users", "Gyms")
  val pending by app.admin.pendingSync().collectAsState(0)
  Column {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) { Text("Admin", style = MaterialTheme.typography.headlineSmall)
        Muted("Local admin mode · $pending change(s) waiting to sync") }
      TextButton(lock) { Icon(Icons.Filled.Lock, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Lock") }
    }
    ScrollableTabRow(tab, edgePadding = 8.dp) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i; app.adminAuth.touch() }, text = { Text(t) }) } }
    when (tab) { 0 -> SubmissionsTab(app); 1 -> MissingTab(app); 2 -> UsersTab(app); else -> GymsTab(app, nav) }
  }
}
@Composable private fun rememberSaveableInt(v: Int) = androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(v) }

// ---- Reporting: user uploads for gyms ----
@Composable private fun SubmissionsTab(app: AppContainer) {
  val subs by app.gyms.allSubmissions().collectAsState(emptyList())
  val gyms by app.gyms.gyms().collectAsState(emptyList())
  val gymNames = gyms.associate { it.id to it.name }
  var filter by remember { mutableStateOf(ReviewStatus.SUBMITTED) }
  var open by remember { mutableStateOf<GymEquipmentEntity?>(null) }
  open?.let { ReviewDialog(app, it, gymNames[it.gymId] ?: "Unknown gym") { open = null } }
  val list = subs.filter { it.status == filter }
  LazyColumn(Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
    item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
      ReviewStatus.all.forEach { s -> FilterChip(filter == s, { filter = s }, { Text("${statusLabel(s)} (${subs.count { it.status == s }})") }) } } }
    item { Muted("New gyms added: ${gyms.size} · uploads total: ${subs.size}") }
    if (list.isEmpty()) item { Text("Nothing here.", Modifier.padding(vertical = 12.dp)) }
    items(list, key = { it.id }) { e -> ListItem(headlineContent = { Text(e.name) },
      supportingContent = { Text("${gymNames[e.gymId] ?: "Unknown gym"} · ${e.photoUris.size} photo(s) · ${java.text.DateFormat.getDateInstance().format(java.util.Date(e.updatedAt))}") },
      leadingContent = { if (e.photoUris.isNotEmpty()) PhotoRow(e.photoUris.take(1)) else Icon(Icons.Filled.FitnessCenter, null) },
      modifier = Modifier.clickable { open = e }) }
  }
}

@Composable private fun ReviewDialog(app: AppContainer, e: GymEquipmentEntity, gymName: String, close: () -> Unit) {
  val scope = rememberCoroutineScope()
  var note by remember { mutableStateOf(e.reviewNote) }
  fun act(status: String) = scope.launch { app.admin.review(e, status, note.trim()); close() }
  AlertDialog(onDismissRequest = close, title = { Text(e.name) },
    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Muted("$gymName · ${statusLabel(e.status)}")
      PhotoRow(e.photoUris)
      val src = e.fieldSources.associate { it.substringBefore("=") to it.substringAfter("=") }
      @Composable fun f(label: String, key: String, v: String) = Text("$label: ${v.ifBlank { "—" }}" + (src[key]?.let { "  [$it]" } ?: ""),
        color = if (v.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
      f("Catalog link", "catalogEquipmentId", app.catalog.equipment(e.catalogEquipmentId)?.name ?: "")
      f("Manufacturer", "manufacturer", e.manufacturer); f("Model", "model", e.model); f("Category", "category", e.category)
      f("Other names", "alternateNames", e.alternateNames.joinToString()); f("Muscles", "primaryMuscles", e.primaryMuscles.joinToString())
      f("How to use", "howToUse", e.howToUse.joinToString(" / ")); f("QR", "qrCode", e.qrCode); f("Video", "videoUrl", e.videoUrl); f("Notes", "notes", e.notes)
      OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note to submitter") })
    } },
    confirmButton = { Row { TextButton({ act(ReviewStatus.REJECTED) }) { Text("Reject", color = MaterialTheme.colorScheme.error) }
      TextButton({ act(ReviewStatus.NEEDS_INFO) }) { Text("Needs info") }; TextButton({ act(ReviewStatus.APPROVED) }) { Text("Approve") } } },
    dismissButton = { TextButton(close) { Text("Close") } })
}

// ---- Reporting: missing information ----
@Composable private fun MissingTab(app: AppContainer) {
  val report by app.admin.missingReport().collectAsState(emptyList())
  var type by remember { mutableStateOf<String?>(null) }
  val types = report.map { it.type }.distinct()
  LazyColumn(Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
    item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
      FilterChip(type == null, { type = null }, { Text("All (${report.size})") })
      types.forEach { t -> FilterChip(type == t, { type = t }, { Text("$t (${report.count { it.type == t }})") }) } } }
    val list = report.filter { type == null || it.type == type }
    if (list.isEmpty()) item { Text("Everything is complete.", Modifier.padding(vertical = 12.dp)) }
    items(list, key = { it.type + it.id }) { m -> ListItem(headlineContent = { Text(m.name) }, overlineContent = { Text("${m.type} · ${m.id}") },
      supportingContent = { Text("Missing: " + m.missing.joinToString(), color = MaterialTheme.colorScheme.error) }) }
  }
}

// ---- User management ----
@Composable private fun UsersTab(app: AppContainer) {
  val users by app.admin.users().collectAsState(emptyList())
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  var adding by remember { mutableStateOf(false) }
  if (adding) { var n by remember { mutableStateOf("") }; var em by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add user") },
      text = { Column { OutlinedTextField(n, { n = it }, label = { Text("Display name") }, singleLine = true)
        OutlinedTextField(em, { em = it }, label = { Text("Email") }, singleLine = true) } },
      confirmButton = { TextButton({ scope.launch { app.admin.saveUser(UserEntity(displayName = n.trim(), email = em.trim()), "create_user") }; adding = false },
        enabled = n.isNotBlank()) { Text("Add") } }, dismissButton = { TextButton({ adding = false }) { Text("Cancel") } }) }
  Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
      item { Row(verticalAlignment = Alignment.CenterVertically) { Muted("${users.size} user(s)"); Spacer(Modifier.weight(1f))
        FilledTonalButton({ adding = true }) { Icon(Icons.Filled.PersonAdd, null); Spacer(Modifier.width(4.dp)); Text("Add user") } } }
      items(users, key = { it.id }) { u -> var menu by remember { mutableStateOf(false) }
        ListItem(headlineContent = { Text(u.displayName + if (u.id == app.session.userId) " (you)" else "") },
          supportingContent = { Text(listOf(u.email, u.role, u.status).filter { it.isNotBlank() }.joinToString(" · "),
            color = if (u.status == AccountStatus.SUSPENDED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
          trailingContent = { Box { IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Manage") }
            DropdownMenu(menu, { menu = false }) {
              DropdownMenuItem(text = { Text(if (u.role == Roles.ADMIN) "Remove admin" else "Make admin") }, onClick = { menu = false; scope.launch {
                if (u.role == Roles.ADMIN && !app.admin.canRemoveAdmin(u)) snackbar.showSnackbar("Can't remove the last admin.")
                else app.admin.saveUser(u.copy(role = if (u.role == Roles.ADMIN) Roles.USER else Roles.ADMIN), "change_role") } })
              DropdownMenuItem(text = { Text(if (u.status == AccountStatus.ACTIVE) "Suspend" else "Reactivate") }, onClick = { menu = false; scope.launch {
                if (u.id == app.session.userId) snackbar.showSnackbar("You can't suspend yourself.")
                else app.admin.saveUser(u.copy(status = if (u.status == AccountStatus.ACTIVE) AccountStatus.SUSPENDED else AccountStatus.ACTIVE), "change_status") } })
            } } })
      }
      item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); ChangePin(app) }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
  }
}
@Composable private fun ChangePin(app: AppContainer) {
  var open by remember { mutableStateOf(false) }; var p by remember { mutableStateOf("") }; var msg by remember { mutableStateOf("") }
  TextButton({ open = !open }) { Text("Change admin PIN") }
  if (open) Row(verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(p, { p = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), singleLine = true, label = { Text("New 6-digit PIN") },
      visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
    TextButton({ msg = if (app.adminAuth.setPin(p)) "PIN changed" else "Must be 6 digits"; p = "" }, enabled = p.length == 6) { Text("Save") }
  }
  if (msg.isNotBlank()) Muted(msg)
}

// ---- Gym management (replaces manual spreadsheet edits for gyms) ----
@Composable private fun GymsTab(app: AppContainer, nav: NavHostController) {
  val gyms by app.gyms.gyms().collectAsState(emptyList())
  val all by app.gyms.allSubmissions().collectAsState(emptyList())
  val scope = rememberCoroutineScope()
  var editing by remember { mutableStateOf<GymEntity?>(null) }
  var creating by remember { mutableStateOf(false) }
  if (creating || editing != null) { val g = editing
    var n by remember(g) { mutableStateOf(g?.name ?: "") }; var a by remember(g) { mutableStateOf(g?.address ?: "") }
    AlertDialog(onDismissRequest = { creating = false; editing = null }, title = { Text(if (g == null) "Add gym" else "Edit gym") },
      text = { Column { OutlinedTextField(n, { n = it }, label = { Text("Name") }, singleLine = true)
        OutlinedTextField(a, { a = it }, label = { Text("Address") }) } },
      confirmButton = { TextButton({ scope.launch {
          if (g == null) { val ng = GymEntity(provider = "manual", providerPlaceId = newId(), name = n.trim(), address = a.trim(), createdBy = app.session.userId)
            app.gyms.saveGym(ng, "create"); app.admin.audit("create_gym", "gym", ng.id, ng.name) }
          else { app.gyms.saveGym(g.copy(name = n.trim(), address = a.trim())); app.admin.audit("edit_gym", "gym", g.id, n) } }
        creating = false; editing = null }, enabled = n.isNotBlank()) { Text("Save") } },
      dismissButton = { if (g != null) TextButton({ scope.launch { app.gyms.saveGym(g.copy(deleted = true), "delete"); app.admin.audit("delete_gym", "gym", g.id, g.name) }
        editing = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } else TextButton({ creating = false }) { Text("Cancel") } })
  }
  LazyColumn(Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
    item { Row(verticalAlignment = Alignment.CenterVertically) { Muted("${gyms.size} gym(s)"); Spacer(Modifier.weight(1f))
      FilledTonalButton({ creating = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Add gym") } } }
    items(gyms, key = { it.id }) { g -> val eq = all.filter { it.gymId == g.id }
      ListItem(headlineContent = { Text(g.name) },
        supportingContent = { Text("${eq.count { it.status == ReviewStatus.APPROVED }} approved · ${eq.count { it.status == ReviewStatus.SUBMITTED }} waiting · ${g.provider}") },
        trailingContent = { Row { IconButton({ editing = g }) { Icon(Icons.Filled.Edit, "Edit gym") }
          IconButton({ nav.navigate(Routes.addEquipment(g.id)) }) { Icon(Icons.Filled.Add, "Add equipment") } } },
        modifier = Modifier.clickable { nav.navigate(Routes.gym(g.id)) })
    }
  }
}
