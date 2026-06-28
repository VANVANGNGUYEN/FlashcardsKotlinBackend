package com.flashcards.backend

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class JwtService {
    private val secret: String =
        System.getenv("JWT_SECRET") ?: System.getProperty("jwt.secret") ?: "flashcards-local-dev-secret"

    fun createToken(userId: Int, email: String, role: String): String {
        val now = Instant.now().epochSecond
        val expiresAt = now + 60L * 60L * 24L * 7L
        val header = """{"alg":"HS256","typ":"JWT"}"""
        val payload = """{"sub":$userId,"email":"${escapeJson(email)}","role":"${escapeJson(role)}","iat":$now,"exp":$expiresAt}"""
        val unsigned = "${base64Url(header.toByteArray(StandardCharsets.UTF_8))}.${base64Url(payload.toByteArray(StandardCharsets.UTF_8))}"
        return "$unsigned.${sign(unsigned)}"
    }

    fun tokenFromAuthorizationHeader(authorization: String?): String? {
        val value = authorization?.trim() ?: return null
        if (!value.startsWith("Bearer ", ignoreCase = true)) return null
        return value.substringAfter(" ").trim().takeIf { it.isNotBlank() }
    }

    fun userIdFromAuthorizationHeader(authorization: String?): Int? {
        val token = tokenFromAuthorizationHeader(authorization) ?: return null
        return userIdFromToken(token)
    }

    fun userIdFromToken(token: String): Int? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val unsigned = "${parts[0]}.${parts[1]}"
        if (!constantTimeEquals(sign(unsigned), parts[2])) return null

        val payload = String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
        val exp = Regex(""""exp"\s*:\s*(\d+)""").find(payload)?.groupValues?.get(1)?.toLongOrNull()
        if (exp != null && exp < Instant.now().epochSecond) return null
        return Regex(""""sub"\s*:\s*(\d+)""").find(payload)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun sign(unsigned: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return base64Url(mac.doFinal(unsigned.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun base64Url(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var result = 0
        for (index in left.indices) {
            result = result or (left[index].code xor right[index].code)
        }
        return result == 0
    }
}
