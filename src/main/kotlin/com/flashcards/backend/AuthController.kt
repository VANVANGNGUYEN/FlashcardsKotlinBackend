package com.flashcards.backend

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

data class RegisterRequest(
    val fullName: String?,
    val email: String?,
    val password: String?
)

data class LoginRequest(
    val email: String?,
    val password: String?
)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val dataSource: DataSource
) {
    private val passwordEncoder = BCryptPasswordEncoder()
    private val jwtService = JwtService()

    @PostMapping("/register")
    fun register(@RequestBody request: RegisterRequest): Map<String, Any?> {
        return try {
            val fullName = request.fullName?.trim().orEmpty()
            val email = request.email?.trim()?.lowercase().orEmpty()
            val password = request.password.orEmpty()

            if (fullName.isBlank() || email.isBlank() || password.isBlank()) {
                return error("fullName, email and password are required")
            }
            if (password.length < 6) {
                return error("Password must be at least 6 characters")
            }

            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT 1 FROM users WHERE lower(email) = ? LIMIT 1").use { statement ->
                    statement.setString(1, email)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) {
                            return error("Email already exists")
                        }
                    }
                }

                val passwordHash = passwordEncoder.encode(password)
                val sql = """
                    INSERT INTO users(fullname, email, passwordhash, role, status)
                    VALUES (?, ?, ?, 'User', 'Active')
                    RETURNING userid, fullname, email, role, status
                """.trimIndent()

                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, fullName)
                    statement.setString(2, email)
                    statement.setString(3, passwordHash)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        return mapOf(
                            "status" to "OK",
                            "message" to "Register successful",
                            "user" to userFromResultSet(resultSet)
                        )
                    }
                }
            }
        } catch (ex: Exception) {
            error(ex.message ?: "Register failed")
        }
    }

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): Map<String, Any?> {
        return try {
            val email = request.email?.trim()?.lowercase().orEmpty()
            val password = request.password.orEmpty()

            if (email.isBlank() || password.isBlank()) {
                return error("email and password are required")
            }

            dataSource.connection.use { connection ->
                val sql = """
                    SELECT userid, fullname, email, passwordhash, role, status
                    FROM users
                    WHERE lower(email) = ?
                    LIMIT 1
                """.trimIndent()

                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, email)
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            return error("Invalid email or password")
                        }

                        val passwordHash = resultSet.getString("passwordhash")
                        if (!passwordEncoder.matches(password, passwordHash)) {
                            return error("Invalid email or password")
                        }

                        return mapOf(
                            "status" to "OK",
                            "message" to "Login successful",
                            "token" to jwtService.createToken(
                                resultSet.getInt("userid"),
                                resultSet.getString("email"),
                                resultSet.getString("role")
                            ),
                            "user" to userFromResultSet(resultSet)
                        )
                    }
                }
            }
        } catch (ex: Exception) {
            error(ex.message ?: "Login failed")
        }
    }

    private fun userFromResultSet(resultSet: java.sql.ResultSet): Map<String, Any?> {
        return mapOf(
            "userId" to resultSet.getInt("userid"),
            "fullName" to resultSet.getString("fullname"),
            "email" to resultSet.getString("email"),
            "role" to resultSet.getString("role"),
            "status" to resultSet.getString("status")
        )
    }

    private fun error(message: String): Map<String, Any?> {
        return mapOf(
            "status" to "ERROR",
            "message" to message
        )
    }
}
