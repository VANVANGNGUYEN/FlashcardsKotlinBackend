package com.flashcards.backend

import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

data class CardWriteRequest(
    val setId: Long?,
    val term: String?,
    val definition: String?,
    val example: String?,
    val exampleMeaning: String?,
    val pronunciation: String?,
    val partOfSpeech: String?,
    val imageUrl: String?
)

@RestController
@RequestMapping("/api/cards")
class CardsController(
    private val dataSource: DataSource
) {
    private val jwtService = JwtService()

    @PostMapping
    fun createCard(
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
        @RequestBody request: CardWriteRequest
    ): Map<String, Any> {
        val userId = authenticatedUserId(authorization) ?: return authError(authorization)
        val setId = request.setId ?: return error("setId is required")
        val term = request.term?.trim().orEmpty()
        val definition = request.definition?.trim().orEmpty()

        if (term.isBlank()) return error("term is required")
        if (definition.isBlank()) return error("definition is required")

        return try {
            dataSource.connection.use { connection ->
                if (!isSetOwner(connection, setId, userId)) {
                    return error("You do not own this set")
                }

                val sql = """
                    INSERT INTO flashcards(
                        setid,
                        term,
                        definition,
                        example,
                        examplemeaning,
                        pronunciation,
                        partofspeech,
                        imageurl
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING
                        cardid,
                        setid,
                        term,
                        definition,
                        example,
                        examplemeaning,
                        pronunciation,
                        partofspeech,
                        imageurl,
                        createdat
                """.trimIndent()

                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, setId)
                    statement.setString(2, term)
                    statement.setString(3, definition)
                    statement.setString(4, request.example?.trim())
                    statement.setString(5, request.exampleMeaning?.trim())
                    statement.setString(6, request.pronunciation?.trim())
                    statement.setString(7, request.partOfSpeech?.trim())
                    statement.setString(8, request.imageUrl?.trim())
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        mapOf(
                            "status" to "OK",
                            "data" to cardFromResultSet(resultSet)
                        )
                    }
                }
            }
        } catch (ex: Exception) {
            error(ex.message ?: "Unable to create card")
        }
    }

    @PutMapping("/{cardId:\\d+}")
    fun updateCard(
        @PathVariable cardId: Long,
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
        @RequestBody request: CardWriteRequest
    ): Map<String, Any> {
        val userId = authenticatedUserId(authorization) ?: return authError(authorization)
        val term = request.term?.trim().orEmpty()
        val definition = request.definition?.trim().orEmpty()

        if (term.isBlank()) return error("term is required")
        if (definition.isBlank()) return error("definition is required")

        return try {
            dataSource.connection.use { connection ->
                if (!isCardOwner(connection, cardId, userId)) {
                    return error("You do not own this card")
                }

                val sql = """
                    UPDATE flashcards
                    SET
                        term = ?,
                        definition = ?,
                        example = ?,
                        examplemeaning = ?,
                        pronunciation = ?,
                        partofspeech = ?,
                        imageurl = ?
                    WHERE cardid = ?
                    RETURNING
                        cardid,
                        setid,
                        term,
                        definition,
                        example,
                        examplemeaning,
                        pronunciation,
                        partofspeech,
                        imageurl,
                        createdat
                """.trimIndent()

                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, term)
                    statement.setString(2, definition)
                    statement.setString(3, request.example?.trim())
                    statement.setString(4, request.exampleMeaning?.trim())
                    statement.setString(5, request.pronunciation?.trim())
                    statement.setString(6, request.partOfSpeech?.trim())
                    statement.setString(7, request.imageUrl?.trim())
                    statement.setLong(8, cardId)
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            return error("Card not found")
                        }

                        mapOf(
                            "status" to "OK",
                            "data" to cardFromResultSet(resultSet)
                        )
                    }
                }
            }
        } catch (ex: Exception) {
            error(ex.message ?: "Unable to update card")
        }
    }

    @DeleteMapping("/{cardId:\\d+}")
    fun deleteCard(
        @PathVariable cardId: Long,
        @RequestHeader(name = "Authorization", required = false) authorization: String?
    ): Map<String, Any> {
        val userId = authenticatedUserId(authorization) ?: return authError(authorization)

        return try {
            dataSource.connection.use { connection ->
                if (!isCardOwner(connection, cardId, userId)) {
                    return error("You do not own this card")
                }

                connection.prepareStatement("DELETE FROM flashcards WHERE cardid = ?").use { statement ->
                    statement.setLong(1, cardId)
                    statement.executeUpdate()
                }

                mapOf(
                    "status" to "OK",
                    "message" to "Card deleted successfully"
                )
            }
        } catch (ex: Exception) {
            error(ex.message ?: "Unable to delete card")
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

    private fun isSetOwner(connection: java.sql.Connection, setId: Long, userId: Int): Boolean {
        val sql = "SELECT 1 FROM flashcardsets WHERE setid = ? AND userid = ? LIMIT 1"
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, setId)
            statement.setInt(2, userId)
            statement.executeQuery().use { resultSet ->
                return resultSet.next()
            }
        }
    }

    private fun isCardOwner(connection: java.sql.Connection, cardId: Long, userId: Int): Boolean {
        val sql = """
            SELECT 1
            FROM flashcards fc
            JOIN flashcardsets fs ON fc.setid = fs.setid
            WHERE fc.cardid = ?
              AND fs.userid = ?
            LIMIT 1
        """.trimIndent()

        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, cardId)
            statement.setInt(2, userId)
            statement.executeQuery().use { resultSet ->
                return resultSet.next()
            }
        }
    }

    private fun cardFromResultSet(resultSet: java.sql.ResultSet): Map<String, Any?> {
        return mapOf(
            "cardId" to resultSet.getInt("cardid"),
            "setId" to resultSet.getInt("setid"),
            "term" to resultSet.getString("term"),
            "definition" to resultSet.getString("definition"),
            "example" to resultSet.getString("example"),
            "exampleMeaning" to resultSet.getString("examplemeaning"),
            "pronunciation" to resultSet.getString("pronunciation"),
            "partOfSpeech" to resultSet.getString("partofspeech"),
            "imageUrl" to resultSet.getString("imageurl"),
            "createdAt" to resultSet.getTimestamp("createdat")?.toString()
        )
    }

    private fun error(message: String): Map<String, Any> {
        return mapOf(
            "status" to "ERROR",
            "message" to message
        )
    }
}
