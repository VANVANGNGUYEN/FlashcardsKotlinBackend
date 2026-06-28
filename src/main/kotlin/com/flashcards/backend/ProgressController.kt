package com.flashcards.backend

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

data class ProgressRequest(
    val setId: Long?,
    val totalCards: Int?,
    val rememberedCards: Int?,
    val notRememberedCards: Int?
)

@RestController
@RequestMapping("/api")
class ProgressController(
    private val dataSource: DataSource
) {
    private val jwtService = JwtService()

    @GetMapping("/progress/my", "/progress/me")
    fun getMyProgress(
        @RequestHeader(name = "Authorization", required = false) authorization: String?
    ): Map<String, Any> {
        val userId = authenticatedUserId(authorization) ?: return authError(authorization)

        return try {
            val items = mutableListOf<Map<String, Any?>>()
            if (!tableExists("studyprogress")) {
                return mapOf("status" to "OK", "data" to items)
            }

            dataSource.connection.use { connection ->
                val sql = """
                    SELECT
                        sp.setid,
                        fs.title,
                        SUM(COALESCE(sp.totalcards, 0)) AS totalcards,
                        SUM(COALESCE(sp.rememberedcards, 0)) AS rememberedcards,
                        SUM(COALESCE(sp.notrememberedcards, 0)) AS notrememberedcards,
                        MAX(COALESCE(sp.laststudiedat, sp.createdat)) AS laststudiedat
                    FROM studyprogress sp
                    LEFT JOIN flashcardsets fs ON sp.setid = fs.setid
                    WHERE sp.userid = ?
                    GROUP BY sp.setid, fs.title
                    ORDER BY MAX(COALESCE(sp.laststudiedat, sp.createdat)) DESC
                """.trimIndent()

                connection.prepareStatement(sql).use { statement ->
                    statement.setInt(1, userId)
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            items.add(
                                mapOf(
                                    "setId" to resultSet.getLong("setid"),
                                    "title" to resultSet.getString("title"),
                                    "totalCards" to resultSet.getInt("totalcards"),
                                    "rememberedCards" to resultSet.getInt("rememberedcards"),
                                    "notRememberedCards" to resultSet.getInt("notrememberedcards"),
                                    "lastStudiedAt" to resultSet.getTimestamp("laststudiedat")?.toString()
                                )
                            )
                        }
                    }
                }
            }
            mapOf("status" to "OK", "data" to items)
        } catch (ex: Exception) {
            mapOf("status" to "OK", "data" to emptyList<Map<String, Any?>>())
        }
    }

    @GetMapping("/stats/me")
    fun getMyStats(): Map<String, Any> {
        return mapOf(
            "status" to "OK",
            "data" to mapOf(
                "studiedSets" to 0,
                "rememberedCards" to 0,
                "totalCards" to 0
            )
        )
    }

    @PostMapping("/progress")
    fun saveProgress(
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
        @RequestBody request: ProgressRequest
    ): Map<String, Any> {
        val userId = authenticatedUserId(authorization) ?: return authError(authorization)
        val setId = request.setId ?: return error("setId is required")

        return try {
            if (!tableExists("studyprogress")) {
                return mapOf("status" to "OK", "data" to mapOf("saved" to false, "reason" to "studyprogress table not found"))
            }

            dataSource.connection.use { connection ->
                val sql = """
                    INSERT INTO studyprogress(userid, setid, totalcards, rememberedcards, notrememberedcards)
                    VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setInt(1, userId)
                    statement.setLong(2, setId)
                    statement.setInt(3, request.totalCards ?: 0)
                    statement.setInt(4, request.rememberedCards ?: 0)
                    statement.setInt(5, request.notRememberedCards ?: 0)
                    statement.executeUpdate()
                }
            }
            mapOf("status" to "OK", "data" to mapOf("saved" to true))
        } catch (ex: Exception) {
            mapOf("status" to "OK", "data" to mapOf("saved" to false, "message" to (ex.message ?: "Progress not saved")))
        }
    }

    private fun tableExists(tableName: String): Boolean {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name = ?
                    LIMIT 1
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, tableName)
                statement.executeQuery().use { resultSet ->
                    return resultSet.next()
                }
            }
        }
    }

    private fun authenticatedUserId(authorization: String?): Int? {
        val token = jwtService.tokenFromAuthorizationHeader(authorization) ?: return null
        return jwtService.userIdFromToken(token)
    }

    private fun authError(authorization: String?): Map<String, Any> {
        return if (authorization.isNullOrBlank()) {
            error("Missing Authorization token")
        } else {
            error("Invalid token")
        }
    }

    private fun error(message: String): Map<String, Any> {
        return mapOf("status" to "ERROR", "message" to message)
    }
}
