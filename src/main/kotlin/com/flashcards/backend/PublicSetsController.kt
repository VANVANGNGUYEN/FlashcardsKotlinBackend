package com.flashcards.backend

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

data class SetWriteRequest(
    val title: String?,
    val description: String?,
    val categoryId: Int?,
    val isPublic: Boolean?
)

@RestController
@RequestMapping("/api/sets")
class PublicSetsController(
    private val dataSource: DataSource
) {
    private val jwtService = JwtService()

    @GetMapping("/public")
    fun getPublicSets(): Map<String, Any> {
        val sets = mutableListOf<Map<String, Any?>>()

        dataSource.connection.use { connection ->
            val sql = """
                SELECT 
                    fs.setid,
                    fs.userid,
                    fs.categoryid,
                    fs.title,
                    fs.description,
                    fs.ispublic,
                    fs.ishidden,
                    fs.viewcount,
                    fs.studycount,
                    fs.approvalstatus,
                    fs.createdat,
                    u.fullname AS authorname,
                    c.categoryname,
                    COUNT(fc.cardid) AS cardcount
                FROM flashcardsets fs
                JOIN users u ON fs.userid = u.userid
                LEFT JOIN categories c ON fs.categoryid = c.categoryid
                LEFT JOIN flashcards fc ON fs.setid = fc.setid
                WHERE fs.ispublic = true
                  AND fs.ishidden = false
                GROUP BY 
                    fs.setid,
                    fs.userid,
                    fs.categoryid,
                    fs.title,
                    fs.description,
                    fs.ispublic,
                    fs.ishidden,
                    fs.viewcount,
                    fs.studycount,
                    fs.approvalstatus,
                    fs.createdat,
                    u.fullname,
                    c.categoryname
                ORDER BY fs.createdat DESC, fs.setid DESC
                LIMIT 20
            """.trimIndent()

            val statement = connection.prepareStatement(sql)
            val resultSet = statement.executeQuery()

            while (resultSet.next()) {
                sets.add(
                    mapOf(
                        "setId" to resultSet.getInt("setid"),
                        "userId" to resultSet.getInt("userid"),
                        "categoryId" to resultSet.getObject("categoryid"),
                        "title" to resultSet.getString("title"),
                        "description" to resultSet.getString("description"),
                        "isPublic" to resultSet.getBoolean("ispublic"),
                        "isHidden" to resultSet.getBoolean("ishidden"),
                        "viewCount" to resultSet.getInt("viewcount"),
                        "studyCount" to resultSet.getInt("studycount"),
                        "approvalStatus" to resultSet.getString("approvalstatus"),
                        "createdAt" to resultSet.getTimestamp("createdat")?.toString(),
                        "authorName" to resultSet.getString("authorname"),
                        "categoryName" to resultSet.getString("categoryname"),
                        "cardCount" to resultSet.getInt("cardcount")
                    )
                )
            }
        }

        return mapOf(
            "status" to "OK",
            "data" to sets
        )
    }

    @GetMapping("/my")
    fun getMySets(
        @RequestHeader(name = "Authorization", required = false) authorization: String?
    ): Map<String, Any> {
        val token = jwtService.tokenFromAuthorizationHeader(authorization)
            ?: return if (authorization.isNullOrBlank()) {
                error("Missing Authorization token")
            } else {
                error("Invalid token")
            }
        val userId = jwtService.userIdFromToken(token)
            ?: return error("Invalid token")

        return try {
            val sets = mutableListOf<Map<String, Any?>>()

            dataSource.connection.use { connection ->
                val sql = """
                    SELECT
                        fs.setid,
                        fs.userid,
                        fs.categoryid,
                        fs.title,
                        fs.description,
                        fs.ispublic,
                        fs.ishidden,
                        fs.viewcount,
                        fs.studycount,
                        fs.clonefromsetid,
                        fs.approvalstatus,
                        fs.rejectionreason,
                        fs.createdat,
                        c.categoryname,
                        COUNT(fc.cardid) AS cardcount
                    FROM flashcardsets fs
                    LEFT JOIN categories c ON fs.categoryid = c.categoryid
                    LEFT JOIN flashcards fc ON fs.setid = fc.setid
                    WHERE fs.userid = ?
                    GROUP BY
                        fs.setid,
                        fs.userid,
                        fs.categoryid,
                        fs.title,
                        fs.description,
                        fs.ispublic,
                        fs.ishidden,
                        fs.viewcount,
                        fs.studycount,
                        fs.clonefromsetid,
                        fs.approvalstatus,
                        fs.rejectionreason,
                        fs.createdat,
                        c.categoryname
                    ORDER BY fs.createdat DESC, fs.setid DESC
                """.trimIndent()

                connection.prepareStatement(sql).use { statement ->
                    statement.setInt(1, userId)
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            sets.add(setFromResultSet(resultSet))
                        }
                    }
                }
            }

            mapOf(
                "status" to "OK",
                "data" to sets
            )
        } catch (ex: Exception) {
            error(ex.message ?: "Unable to load my sets")
        }
    }

    @PostMapping
    fun createSet(
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
        @RequestBody request: SetWriteRequest
    ): Map<String, Any> {
        val userId = jwtService.userIdFromAuthorizationHeader(authorization)
            ?: return error("Missing or invalid token")

        val title = request.title?.trim().orEmpty()
        if (title.isBlank()) {
            return error("title is required")
        }

        return try {
            val isPublic = request.isPublic ?: true
            val approvalStatus = if (isPublic) "Pending" else "Private"

            dataSource.connection.use { connection ->
                val sql = """
                    INSERT INTO flashcardsets(
                        userid,
                        categoryid,
                        title,
                        description,
                        ispublic,
                        ishidden,
                        viewcount,
                        studycount,
                        approvalstatus
                    )
                    VALUES (?, ?, ?, ?, ?, false, 0, 0, ?)
                    RETURNING
                        setid,
                        userid,
                        categoryid,
                        title,
                        description,
                        ispublic,
                        ishidden,
                        viewcount,
                        studycount,
                        clonefromsetid,
                        approvalstatus,
                        rejectionreason,
                        createdat,
                        NULL::text AS categoryname,
                        0 AS cardcount
                """.trimIndent()

                connection.prepareStatement(sql).use { statement ->
                    statement.setInt(1, userId)
                    statement.setObject(2, request.categoryId)
                    statement.setString(3, title)
                    statement.setString(4, request.description?.trim())
                    statement.setBoolean(5, isPublic)
                    statement.setString(6, approvalStatus)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        mapOf(
                            "status" to "OK",
                            "data" to setFromResultSet(resultSet)
                        )
                    }
                }
            }
        } catch (ex: Exception) {
            error(ex.message ?: "Unable to create set")
        }
    }

    @PutMapping("/{setId:\\d+}")
    fun updateSet(
        @PathVariable setId: Long,
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
        @RequestBody request: SetWriteRequest
    ): Map<String, Any> {
        val userId = jwtService.userIdFromAuthorizationHeader(authorization)
            ?: return error("Missing or invalid token")

        val title = request.title?.trim().orEmpty()
        if (title.isBlank()) {
            return error("title is required")
        }

        return try {
            val isPublic = request.isPublic ?: true
            val approvalStatus = if (isPublic) "Pending" else "Private"

            dataSource.connection.use { connection ->
                val sql = """
                    UPDATE flashcardsets
                    SET
                        title = ?,
                        description = ?,
                        categoryid = ?,
                        ispublic = ?,
                        approvalstatus = ?,
                        rejectionreason = NULL
                    WHERE setid = ?
                      AND userid = ?
                    RETURNING
                        setid,
                        userid,
                        categoryid,
                        title,
                        description,
                        ispublic,
                        ishidden,
                        viewcount,
                        studycount,
                        clonefromsetid,
                        approvalstatus,
                        rejectionreason,
                        createdat,
                        (
                            SELECT categoryname
                            FROM categories
                            WHERE categories.categoryid = flashcardsets.categoryid
                        ) AS categoryname,
                        (
                            SELECT COUNT(*)
                            FROM flashcards
                            WHERE flashcards.setid = flashcardsets.setid
                        ) AS cardcount
                """.trimIndent()

                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, title)
                    statement.setString(2, request.description?.trim())
                    statement.setObject(3, request.categoryId)
                    statement.setBoolean(4, isPublic)
                    statement.setString(5, approvalStatus)
                    statement.setLong(6, setId)
                    statement.setInt(7, userId)
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            return error("Set not found or you are not the owner")
                        }

                        mapOf(
                            "status" to "OK",
                            "data" to setFromResultSet(resultSet)
                        )
                    }
                }
            }
        } catch (ex: Exception) {
            error(ex.message ?: "Unable to update set")
        }
    }

    @DeleteMapping("/{setId:\\d+}")
    fun deleteSet(
        @PathVariable setId: Long,
        @RequestHeader(name = "Authorization", required = false) authorization: String?
    ): Map<String, Any> {
        val userId = jwtService.userIdFromAuthorizationHeader(authorization)
            ?: return error("Missing or invalid token")

        return try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement("SELECT userid FROM flashcardsets WHERE setid = ?").use { statement ->
                        statement.setLong(1, setId)
                        statement.executeQuery().use { resultSet ->
                            if (!resultSet.next()) {
                                connection.rollback()
                                return error("Set not found")
                            }
                            if (resultSet.getInt("userid") != userId) {
                                connection.rollback()
                                return error("You are not the owner of this set")
                            }
                        }
                    }

                    connection.prepareStatement("DELETE FROM flashcards WHERE setid = ?").use { statement ->
                        statement.setLong(1, setId)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement("DELETE FROM flashcardsets WHERE setid = ? AND userid = ?").use { statement ->
                        statement.setLong(1, setId)
                        statement.setInt(2, userId)
                        statement.executeUpdate()
                    }

                    connection.commit()
                    mapOf(
                        "status" to "OK",
                        "message" to "Set deleted successfully"
                    )
                } catch (ex: Exception) {
                    connection.rollback()
                    throw ex
                } finally {
                    connection.autoCommit = true
                }
            }
        } catch (ex: Exception) {
            error(ex.message ?: "Unable to delete set")
        }
    }

    @GetMapping(value = ["/{setId:\\d+}", "/detail/{setId:\\d+}"])
    fun getSetDetail(@PathVariable setId: Long): Map<String, Any> {
        return try {
            dataSource.connection.use { connection ->
                val sql = """
                    SELECT
                        fs.setid,
                        fs.userid,
                        fs.categoryid,
                        fs.title,
                        fs.description,
                        fs.ispublic,
                        fs.ishidden,
                        fs.viewcount,
                        fs.studycount,
                        fs.clonefromsetid,
                        fs.approvalstatus,
                        fs.rejectionreason,
                        fs.createdat,
                        u.fullname AS authorname,
                        c.categoryname,
                        COUNT(fc.cardid) AS cardcount
                    FROM flashcardsets fs
                    JOIN users u ON fs.userid = u.userid
                    LEFT JOIN categories c ON fs.categoryid = c.categoryid
                    LEFT JOIN flashcards fc ON fs.setid = fc.setid
                    WHERE fs.setid = ?
                    GROUP BY
                        fs.setid,
                        fs.userid,
                        fs.categoryid,
                        fs.title,
                        fs.description,
                        fs.ispublic,
                        fs.ishidden,
                        fs.viewcount,
                        fs.studycount,
                        fs.clonefromsetid,
                        fs.approvalstatus,
                        fs.rejectionreason,
                        fs.createdat,
                        u.fullname,
                        c.categoryname
                """.trimIndent()

                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, setId)
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            return mapOf(
                                "status" to "ERROR",
                                "message" to "Set not found"
                            )
                        }

                        val set = mapOf(
                            "setId" to resultSet.getInt("setid"),
                            "userId" to resultSet.getInt("userid"),
                            "categoryId" to resultSet.getObject("categoryid"),
                            "title" to resultSet.getString("title"),
                            "description" to resultSet.getString("description"),
                            "isPublic" to resultSet.getBoolean("ispublic"),
                            "isHidden" to resultSet.getBoolean("ishidden"),
                            "viewCount" to resultSet.getInt("viewcount"),
                            "studyCount" to resultSet.getInt("studycount"),
                            "cloneFromSetId" to resultSet.getObject("clonefromsetid"),
                            "approvalStatus" to resultSet.getString("approvalstatus"),
                            "rejectionReason" to resultSet.getString("rejectionreason"),
                            "createdAt" to resultSet.getTimestamp("createdat")?.toString(),
                            "authorName" to resultSet.getString("authorname"),
                            "categoryName" to resultSet.getString("categoryname"),
                            "cardCount" to resultSet.getInt("cardcount")
                        )

                        mapOf(
                            "status" to "OK",
                            "data" to set
                        )
                    }
                }
            }
        } catch (ex: Exception) {
            mapOf(
                "status" to "ERROR",
                "message" to (ex.message ?: "Unable to load set detail")
            )
        }
    }

    @GetMapping(value = ["/{setId:\\d+}/cards", "/detail/{setId:\\d+}/cards"])
    fun getSetCards(@PathVariable setId: Long): Map<String, Any> {
        return try {
            val cards = mutableListOf<Map<String, Any?>>()

            dataSource.connection.use { connection ->
                val sql = """
                    SELECT
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
                    FROM flashcards
                    WHERE setid = ?
                    ORDER BY cardid
                """.trimIndent()

                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, setId)
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            cards.add(
                                mapOf(
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
                            )
                        }
                    }
                }
            }

            mapOf(
                "status" to "OK",
                "data" to cards
            )
        } catch (ex: Exception) {
            mapOf(
                "status" to "ERROR",
                "message" to (ex.message ?: "Unable to load set cards")
            )
        }
    }

    private fun setFromResultSet(resultSet: java.sql.ResultSet): Map<String, Any?> {
        return mapOf(
            "setId" to resultSet.getInt("setid"),
            "userId" to resultSet.getInt("userid"),
            "categoryId" to resultSet.getObject("categoryid"),
            "title" to resultSet.getString("title"),
            "description" to resultSet.getString("description"),
            "isPublic" to resultSet.getBoolean("ispublic"),
            "isHidden" to resultSet.getBoolean("ishidden"),
            "viewCount" to resultSet.getInt("viewcount"),
            "studyCount" to resultSet.getInt("studycount"),
            "cloneFromSetId" to resultSet.getObject("clonefromsetid"),
            "approvalStatus" to resultSet.getString("approvalstatus"),
            "rejectionReason" to resultSet.getString("rejectionreason"),
            "createdAt" to resultSet.getTimestamp("createdat")?.toString(),
            "categoryName" to resultSet.getString("categoryname"),
            "cardCount" to resultSet.getInt("cardcount")
        )
    }

    private fun error(message: String): Map<String, Any> {
        return mapOf(
            "status" to "ERROR",
            "message" to message
        )
    }
}
