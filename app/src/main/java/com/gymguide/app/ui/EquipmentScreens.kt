package com.gymguide.app.ui
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.gymguide.app.data.AppContainer

/**
 * Equipment page. Opened from an exercise it shows that exercise's specific setup first
 * (bench angle, seat position, attachment), then general info, then "How to use this equipment" at the bottom.
 */
@Composable fun EquipmentDetail(app: AppContainer, equipmentId: String, exerciseId: String?, nav: NavHostController) {
  val q = app.catalog.equipment(equipmentId)
  if (q == null) { NotFound("Equipment not found."); return }
  val ctx = LocalContext.current
  val ex = exerciseId?.let(app.catalog::exercise)
  val setup = app.catalog.setupFor(exerciseId, equipmentId)
  val usedBy = app.catalog.exercisesUsing(equipmentId)

  LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
    item { Text(q.name, style = MaterialTheme.typography.headlineSmall)
      Muted(listOf(q.category, q.manufacturer).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Equipment" }) }
    if (q.photo.isNotBlank()) item {
      AsyncImage(model = "file:///android_asset/media/${q.photo}", contentDescription = q.name,
        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
    if (q.alternateNames.isNotEmpty()) item { Text("Also called: ${q.alternateNames.joinToString()}") }

    if (ex != null) item {
      Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Setup for ${ex.name}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
          if (setup == null) Text("No specific setup recorded yet for this exercise.", color = MaterialTheme.colorScheme.onPrimaryContainer)
          else {
            if (setup.angle.isNotBlank()) SetupLine(Icons.Filled.Straighten, "Angle", setup.angle)
            if (setup.position.isNotBlank()) SetupLine(Icons.Filled.EventSeat, "Position", setup.position)
            if (setup.attachment.isNotBlank()) SetupLine(Icons.Filled.Build, "Attachment", setup.attachment)
            setup.notes.forEachIndexed { i, n -> Text("${i + 1}. $n", color = MaterialTheme.colorScheme.onPrimaryContainer) }
          }
          // Other equipment the same exercise needs (e.g. dumbbells + bench)
          val others = ex.equipmentIds.filter { it != equipmentId }.mapNotNull(app.catalog::equipment)
          if (others.isNotEmpty()) {
            Text("You'll also need:", color = MaterialTheme.colorScheme.onPrimaryContainer)
            others.forEach { o -> TextButton({ nav.navigate(Routes.equipment(o.id, ex.id)) }) { Text(o.name) } }
          }
        }
      }
    }
    if (q.notes.isNotBlank()) item { Muted(q.notes) }

    if (usedBy.isNotEmpty()) {
      item { SectionTitle("Exercises on this equipment") }
      items(usedBy, key = { it.id }) { e -> ListItem(headlineContent = { Text(e.name) }, supportingContent = { Text(e.primaryMuscles.joinToString()) },
        modifier = Modifier.clickable { nav.navigate(Routes.exercise(e.id)) }) }
    }

    // ---- bottom section ----
    item { HorizontalDivider(Modifier.padding(vertical = 6.dp)); SectionTitle("How to use this equipment") }
    if (q.howToUse.isEmpty()) item { Muted("Instructions haven't been added for this equipment yet.") }
    itemsIndexed(q.howToUse) { i, s -> Text("${i + 1}. $s") }
    if (q.videoUrl.isNotBlank()) item { VideoEmbed(q.videoUrl) }
    if (q.qrCode.isNotBlank()) item {
      Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.QrCode2, null); Spacer(Modifier.width(8.dp))
          Text("Planet Fitness tutorial (QR)", fontWeight = FontWeight.Bold) }
        Muted("This tutorial lives in the PF App. GymCue opens it there; it can't be embedded.")
        if (q.qrCode.startsWith("http", true)) TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(q.qrCode))) } }) {
          Text("Open tutorial") } else Muted("Code: ${q.qrCode}")
      } }
    }
    if (q.videoUrl.isBlank() && q.qrCode.isBlank()) item { Muted("No video yet.") }
  }
}

@Composable private fun SetupLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) =
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer); Spacer(Modifier.width(8.dp))
    Text("$label: ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    Text(value, color = MaterialTheme.colorScheme.onPrimaryContainer)
  }
