# GymCue: Find the machine. Set it up. Go.

New to the gym? GymCue shows you which machine to use, how to set it up, and how to use it, all based on the equipment at your gym.

With GymCue you can:
Browse exercises by muscle or workout style
Follow beginner-friendly guided workouts
Build and save your own workouts
Swap exercises when a machine is taken
Track your weight, reps and progress
Find your gym and add its equipment
Control Spotify without leaving the app


Less swapping. Less guessing. More lifting.



Android app (Kotlin + Jetpack Compose + Room) — v3.
Open this folder in Android Studio and run on a device/emulator (Android 8+). Run tests with `gradlew testDebugUnitTest`.

## What's new in v3
- **Crash fix**: Workout Tracker no longer crashes (`NoSuchElementException: List is empty`) when the first set is logged.
- **Workouts**: green square +/- set buttons; add exercises (suggestions that fit the workout, or the full library); red X removes an exercise (with Undo); "Save as custom workout".
- **Custom workouts**: name it, add any exercise, set sets/reps/rest, reorder, save. Listed under *Custom Workouts* on the Workouts page.
- **Equipment pages**: every exercise links to all its equipment (machines, dumbbells, bench). Opened from an exercise, the page shows that exercise's setup (bench angle, seat position, attachment). "How to use this equipment" at the bottom with embedded video (YouTube/.mp4) and the PF QR link.
- **Find gyms nearby** (GPS or name/city search). Gyms already in GymCue are marked. Add a gym, then upload equipment with photos (camera or gallery) and QR scanning. Missing details are filled only from a confident catalog match; everything else stays blank.
- **Admin** (Home > Admin): 6-digit numeric PIN (hashed, lockout after 5 tries, auto-lock after 10 min). Submissions review (approve / needs info / reject), missing-information report, user management, gym management.
- **Music controls** on Home and Workouts: song/artist/podcast, play/pause, previous/next. Tap *Connect* once to grant GymCue "notification access" (used only to see media sessions).

## Configuration (local.properties — never commit)
- `PLACES_API_KEY=...` optional: uses Google Places for gym search. Blank = OpenStreetMap (free, no key; coverage varies).
- `API_BASE_URL=...` reserved for the future GymCue server.

## Shared-data architecture
- `data/Models.kt` catalog content + shared vocabulary (visibility, review status, roles).
- `data/Db.kt` Room v2: every user record has a UUID id, owner, timestamps, syncState, soft-delete; each change is queued in `outbox`.
- `data/Repositories.kt` screens only talk to repositories (`CatalogRepository`, `WorkoutRepository`, `GymRepository`, `AdminRepository`).
- `data/remote/Remote.kt` `SyncBackend` + `EnrichmentService` interfaces. Today: `OfflineBackend` + local catalog matching. Add the server here.
- **Not yet multi-user**: data stays on the device until a server + sign-in exist. Admin PIN protects this device only; the server must enforce admin roles.

## Content
Edit `GymGuide_Content.xlsx`, then `pip install openpyxl && python tools/build_content.py GymGuide_Content.xlsx` to validate and regenerate `app/src/main/assets/content.json`. New in v3: Equipment `how_to_use_steps`, `qr_code`, `video_url`, and the **Exercise Setup** tab (bench angle etc.). Sample rows are placeholders and should be reviewed by a qualified trainer.

Planet Fitness QR codes open tutorials inside the PF App, so GymCue stores the link and opens it; it can't embed those videos. Use `video_url` for videos you have rights to embed.
