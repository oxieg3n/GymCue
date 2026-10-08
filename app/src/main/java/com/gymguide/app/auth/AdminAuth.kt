package com.gymguide.app.auth
import android.content.Context
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Six-digit numeric admin PIN, stored only as a salted PBKDF2 hash (never in the APK or in plain text).
 * Lockout after repeated failures; admin session expires after inactivity.
 *
 * IMPORTANT: this protects the admin screens on THIS device. When the shared server exists, the PIN must be
 * verified server-side for an account that already has the ADMIN role, and every admin API call must be
 * authorized by the server — never trust this local check for shared data.
 */
class AdminAuth(ctx: Context) {
  private val p = ctx.getSharedPreferences("admin_auth", Context.MODE_PRIVATE)
  private var unlockedUntil = 0L
  companion object { const val MAX_ATTEMPTS = 5; const val SESSION_MS = 10 * 60_000L; private const val ITER = 120_000 }

  val hasPin get() = p.contains("hash")
  val isUnlocked get() = System.currentTimeMillis() < unlockedUntil
  fun touch() { if (isUnlocked) unlockedUntil = System.currentTimeMillis() + SESSION_MS }
  fun lock() { unlockedUntil = 0 }
  fun lockedForMs(): Long = (p.getLong("lockedUntil", 0) - System.currentTimeMillis()).coerceAtLeast(0)
  fun attemptsLeft() = MAX_ATTEMPTS - p.getInt("fails", 0)

  fun isValidFormat(pin: String) = pin.length == 6 && pin.all { it in '0'..'9' }

  fun setPin(pin: String): Boolean {
    if (!isValidFormat(pin)) return false
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    p.edit().putString("salt", salt.hex()).putString("hash", hash(pin, salt).hex()).putInt("fails", 0).putLong("lockedUntil", 0).apply()
    unlockedUntil = System.currentTimeMillis() + SESSION_MS
    return true
  }

  sealed interface Result { data object Ok : Result; data class Wrong(val left: Int) : Result; data class Locked(val ms: Long) : Result; data object BadFormat : Result }

  fun verify(pin: String): Result {
    if (lockedForMs() > 0) return Result.Locked(lockedForMs())
    if (!isValidFormat(pin)) return Result.BadFormat
    val salt = p.getString("salt", null)?.unhex() ?: return Result.Wrong(attemptsLeft())
    val ok = java.security.MessageDigest.isEqual(hash(pin, salt), p.getString("hash", "")!!.unhex())
    if (ok) { p.edit().putInt("fails", 0).putInt("lockouts", 0).apply(); unlockedUntil = System.currentTimeMillis() + SESSION_MS; return Result.Ok }
    val fails = p.getInt("fails", 0) + 1
    if (fails >= MAX_ATTEMPTS) {
      val lockouts = p.getInt("lockouts", 0) + 1
      val ms = 60_000L * (1 shl (lockouts - 1).coerceAtMost(6))   // 1, 2, 4 ... 64 minutes
      p.edit().putInt("fails", 0).putInt("lockouts", lockouts).putLong("lockedUntil", System.currentTimeMillis() + ms).apply()
      return Result.Locked(ms)
    }
    p.edit().putInt("fails", fails).apply()
    return Result.Wrong(MAX_ATTEMPTS - fails)
  }

  private fun hash(pin: String, salt: ByteArray) =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITER, 256)).encoded
  private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
  private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
