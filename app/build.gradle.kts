import java.util.Properties

plugins {
  id("com.android.application"); id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.kotlin.plugin.serialization")
  id("com.google.devtools.ksp")
}

// Optional secrets live in local.properties (never commit it):
//   PLACES_API_KEY=...   -> enables Google Places gym search (falls back to OpenStreetMap when blank)
//   API_BASE_URL=...     -> future GymCue sync server (blank = offline/local only)
val localProps = Properties().apply {
  val f = rootProject.file("local.properties"); if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String) = (localProps.getProperty(name) ?: "").replace("\"", "")

android {
  namespace = "com.gymguide.app"; compileSdk = 34
  defaultConfig {
    applicationId = "com.gymguide.app"; minSdk = 26; targetSdk = 34; versionCode = 3; versionName = "3.0"
    buildConfigField("String", "PLACES_API_KEY", "\"${prop("PLACES_API_KEY")}\"")
    buildConfigField("String", "API_BASE_URL", "\"${prop("API_BASE_URL")}\"")
  }
  buildFeatures { compose = true; buildConfig = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  testOptions { unitTests.isIncludeAndroidResources = true }
  sourceSets["debug"].assets.srcDirs(files("$projectDir/schemas"))  // debug-only: lets the migration test read Room schemas
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.09.00"))
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.navigation:navigation-compose:2.8.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")
  implementation("androidx.room:room-runtime:2.6.1"); implementation("androidx.room:room-ktx:2.6.1")
  ksp("androidx.room:room-compiler:2.6.1")
  implementation("io.coil-kt:coil-compose:2.7.0")                       // photos
  implementation("com.journeyapps:zxing-android-embedded:4.3.0")         // QR scanning

  // Tests (run with: gradlew testDebugUnitTest)
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.13")
  testImplementation("androidx.test:core:1.6.1")
  testImplementation("androidx.room:room-testing:2.6.1")
  testImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
  testImplementation("androidx.compose.ui:ui-test-junit4")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
