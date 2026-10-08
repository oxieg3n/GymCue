package com.gymguide.app
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.gymguide.app.data.AppContainer
import com.gymguide.app.ui.App

private val DarkScheme = darkColorScheme(
  primary = Color(0xFF00FF41),
  onPrimary = Color(0xFF062B0F),
  primaryContainer = Color(0xFF004D1F),
  onPrimaryContainer = Color(0xFF7BFFA8),
  secondary = Color(0xFF66FF99),
  background = Color(0xFF16181A),
  onBackground = Color(0xFFE6EAE4),
  surface = Color(0xFF1B1E20),
  onSurface = Color(0xFFE6EAE4),
  surfaceVariant = Color(0xFF24282A),
  onSurfaceVariant = Color(0xFFAEB5AD),
  error = Color(0xFFFF6E6E)
)

class MainActivity : ComponentActivity() {
  override fun onCreate(b: Bundle?) { super.onCreate(b)
    val app = AppContainer(applicationContext)
    setContent { MaterialTheme(colorScheme = DarkScheme) { App(app) } } }
}
