package id.cloudssd.core.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hash kata sandi PBKDF2-HMAC-SHA256 dengan salt acak.
 * Format: pbkdf2_sha256$<iterasi>$<salt b64>$<hash b64>
 * Tersedia di Android (API 26+) dan JVM tanpa dependensi tambahan.
 */
class PasswordHasher(private val iterations: Int = DEFAULT_ITERATIONS) {

    fun hash(password: CharArray): String {
        val salt = Tokens.randomBytes(16)
        val dk = derive(password, salt, iterations)
        val b64 = Base64.getEncoder().withoutPadding()
        return "pbkdf2_sha256\$$iterations\$${b64.encodeToString(salt)}\$${b64.encodeToString(dk)}"
    }

    fun verify(password: CharArray, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2_sha256") return false
        val iter = parts[1].toIntOrNull() ?: return false
        if (iter < 1000 || iter > 10_000_000) return false
        val dec = Base64.getDecoder()
        val salt = try { dec.decode(parts[2]) } catch (_: IllegalArgumentException) { return false }
        val expected = try { dec.decode(parts[3]) } catch (_: IllegalArgumentException) { return false }
        val actual = derive(password, salt, iter)
        return MessageDigest.isEqual(expected, actual)
    }

    /** Untuk membuang waktu yang sama ketika user tidak ditemukan (mencegah enumerasi user lewat timing). */
    fun dummyVerify(password: CharArray) {
        derive(password, DUMMY_SALT, iterations)
    }

    private fun derive(password: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iter, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        /** Seimbang untuk CPU Snapdragon 870 (~0,1–0,3 dtk per login). */
        const val DEFAULT_ITERATIONS = 120_000
        private val DUMMY_SALT = ByteArray(16) { it.toByte() }
    }
}

object Tokens {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** Token URL-safe (base64url tanpa padding). */
    fun urlToken(bytes: Int = 32): String = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes))

    fun sha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    fun numericCode(digits: Int): String {
        val sb = StringBuilder()
        repeat(digits) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
