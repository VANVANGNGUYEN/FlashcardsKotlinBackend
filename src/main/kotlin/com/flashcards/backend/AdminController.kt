package com.flashcards.backend

import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

data class AdminStatusRequest(val status: String?)
data class AdminRoleRequest(val role: String?)
data class AdminHideRequest(val isHidden: Boolean?)
data class AdminApprovalRequest(val status: String?, val reason: String?)
data class AdminCardReportStatusRequest(val status: String?, val adminNote: String?)

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val dataSource: DataSource
) {
    @GetMapping("/dashboard")
    fun dashboard(): Map<String, Any> = try {
        dataSource.connection.use { connection ->
            ok(
                mapOf(
                    "totalUsers" to count(connection, "SELECT COUNT(*) FROM users"),
                    "totalSets" to count(connection, "SELECT COUNT(*) FROM flashcardsets"),
                    "totalCards" to count(connection, "SELECT COUNT(*) FROM flashcards"),
                    "totalStudies" to count(connection, "SELECT COALESCE(SUM(studycount), 0) FROM flashcardsets"),
                    "pendingSets" to count(connection, "SELECT COUNT(*) FROM flashcardsets WHERE lower(approvalstatus) = 'pending'"),
                    "approvedSets" to count(connection, "SELECT COUNT(*) FROM flashcardsets WHERE lower(approvalstatus) = 'approved'"),
                    "rejectedSets" to count(connection, "SELECT COUNT(*) FROM flashcardsets WHERE lower(approvalstatus) = 'rejected'"),
                    "hiddenSets" to count(connection, "SELECT COUNT(*) FROM flashcardsets WHERE ishidden = true")
                )
            )
        }
    } catch (ex: Exception) {
        error(ex.message ?: "Unable to load admin dashboard")
    }

    @GetMapping("/users")
    fun users(): Map<String, Any> = try {
        val rows = mutableListOf<Map<String, Any?>>()
        dataSource.connection.use { connection ->
            val sql = """
                SELECT userid, fullname, email, role, status, avatarurl, createdat
                FROM users
                ORDER BY createdat DESC, userid DESC
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        rows.add(
                            mapOf(
                                "userId" to rs.getInt("userid"),
                                "fullName" to rs.getString("fullname"),
                                "email" to rs.getString("email"),
                                "role" to rs.getString("role"),
                                "status" to rs.getString("status"),
                                "avatarUrl" to rs.getString("avatarurl"),
                                "createdAt" to rs.getTimestamp("createdat")?.toString()
                            )
                        )
                    }
                }
            }
        }
        ok(rows)
    } catch (ex: Exception) {
        error(ex.message ?: "Unable to load users")
    }

    @PutMapping("/users/{userId:\\d+}/status")
    fun updateUserStatus(
        @PathVariable userId: Long,
        @RequestBody request: AdminStatusRequest
    ): Map<String, Any> {
        val status = request.status?.trim()?.takeIf { it in setOf("Active", "Locked", "Blocked", "Inactive") }
            ?: return error("Invalid status")
        return updateOne(
            "UPDATE users SET status = ? WHERE userid = ?",
            listOf(status, userId),
            "User not found",
            mapOf("userId" to userId, "status" to status)
        )
    }

    @PutMapping("/users/{userId:\\d+}/role")
    fun updateUserRole(
        @PathVariable userId: Long,
        @RequestBody request: AdminRoleRequest
    ): Map<String, Any> {
        val role = request.role?.trim()?.takeIf { it in setOf("User", "Admin") } ?: return error("Invalid role")
        return updateOne(
            "UPDATE users SET role = ? WHERE userid = ?",
            listOf(role, userId),
            "User not found",
            mapOf("userId" to userId, "role" to role)
        )
    }

    @GetMapping("/sets")
    fun sets(): Map<String, Any> = loadSets(null)

    @GetMapping("/pending-sets")
    fun pendingSets(): Map<String, Any> = loadSets("pending")

    @PutMapping("/sets/{setId:\\d+}/hide")
    fun updateSetHidden(
        @PathVariable setId: Long,
        @RequestBody request: AdminHideRequest
    ): Map<String, Any> {
        val isHidden = request.isHidden ?: return error("isHidden is required")
        return updateOne(
            "UPDATE flashcardsets SET ishidden = ? WHERE setid = ?",
            listOf(isHidden, setId),
            "Set not found",
            mapOf("setId" to setId, "isHidden" to isHidden)
        )
    }

    @PutMapping("/sets/{setId:\\d+}/approval")
    fun updateSetApproval(
        @PathVariable setId: Long,
        @RequestBody request: AdminApprovalRequest
    ): Map<String, Any> {
        val status = request.status?.trim()?.takeIf { it in setOf("Pending", "Approved", "Rejected") }
            ?: return error("Invalid approval status")
        return updateOne(
            "UPDATE flashcardsets SET approvalstatus = ?, rejectionreason = ? WHERE setid = ?",
            listOf(status, request.reason, setId),
            "Set not found",
            mapOf("setId" to setId, "approvalStatus" to status)
        )
    }

    @DeleteMapping("/sets/{setId:\\d+}")
    fun deleteSet(@PathVariable setId: Long): Map<String, Any> = try {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("DELETE FROM cardreports WHERE setid = ? OR cardid IN (SELECT cardid FROM flashcards WHERE setid = ?)").use {
                    it.setLong(1, setId)
                    it.setLong(2, setId)
                    it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM reports WHERE setid = ?").use {
                    it.setLong(1, setId)
                    it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM studyprogress WHERE setid = ?").use {
                    it.setLong(1, setId)
                    it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM quizresults WHERE setid = ?").use {
                    it.setLong(1, setId)
                    it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM gameresults WHERE setid = ?").use {
                    it.setLong(1, setId)
                    it.executeUpdate()
                }
                connection.prepareStatement("DELETE FROM flashcards WHERE setid = ?").use {
                    it.setLong(1, setId)
                    it.executeUpdate()
                }
                val deleted = connection.prepareStatement("DELETE FROM flashcardsets WHERE setid = ?").use {
                    it.setLong(1, setId)
                    it.executeUpdate()
                }
                if (deleted == 0) {
                    connection.rollback()
                    return error("Set not found")
                }
                connection.commit()
                ok(mapOf("setId" to setId, "deleted" to true))
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

    @GetMapping("/reports")
    fun reports(): Map<String, Any> = try {
        if (!tableExists("reports")) return notImplemented("reports")
        val rows = mutableListOf<Map<String, Any?>>()
        dataSource.connection.use { connection ->
            val sql = """
                SELECT r.reportid, r.userid, r.setid, r.reason, r.status, r.createdat,
                       u.fullname AS reportername,
                       fs.title AS settitle
                FROM reports r
                LEFT JOIN users u ON r.userid = u.userid
                LEFT JOIN flashcardsets fs ON r.setid = fs.setid
                ORDER BY r.createdat DESC, r.reportid DESC
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        rows.add(
                            mapOf(
                                "reportId" to rs.getInt("reportid"),
                                "userId" to rs.getInt("userid"),
                                "setId" to rs.getInt("setid"),
                                "reason" to rs.getString("reason"),
                                "status" to rs.getString("status"),
                                "createdAt" to rs.getTimestamp("createdat")?.toString(),
                                "reporterName" to rs.getString("reportername"),
                                "setTitle" to rs.getString("settitle")
                            )
                        )
                    }
                }
            }
        }
        ok(rows)
    } catch (ex: Exception) {
        error(ex.message ?: "Unable to load reports")
    }

    @PutMapping("/reports/{reportId:\\d+}/status")
    fun updateReportStatus(
        @PathVariable reportId: Long,
        @RequestBody request: AdminStatusRequest
    ): Map<String, Any> {
        val status = request.status?.trim()?.takeIf { it in setOf("Pending", "Resolved", "Rejected") }
            ?: return error("Invalid status")
        return updateOne(
            "UPDATE reports SET status = ? WHERE reportid = ?",
            listOf(status, reportId),
            "Report not found",
            mapOf("reportId" to reportId, "status" to status)
        )
    }

    @GetMapping("/card-reports")
    fun cardReports(): Map<String, Any> = try {
        if (!tableExists("cardreports")) return notImplemented("cardreports")
        val rows = mutableListOf<Map<String, Any?>>()
        dataSource.connection.use { connection ->
            val sql = """
                SELECT cr.cardreportid, cr.userid, cr.cardid, cr.setid, cr.reasontype,
                       cr.description, cr.status, cr.adminnote, cr.createdat, cr.updatedat,
                       u.fullname AS reportername,
                       fc.term, fc.definition,
                       fs.title AS settitle
                FROM cardreports cr
                LEFT JOIN users u ON cr.userid = u.userid
                LEFT JOIN flashcards fc ON cr.cardid = fc.cardid
                LEFT JOIN flashcardsets fs ON COALESCE(cr.setid, fc.setid) = fs.setid
                ORDER BY cr.createdat DESC, cr.cardreportid DESC
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        rows.add(
                            mapOf(
                                "cardReportId" to rs.getInt("cardreportid"),
                                "userId" to rs.getInt("userid"),
                                "cardId" to rs.getInt("cardid"),
                                "setId" to rs.getObject("setid"),
                                "reasonType" to rs.getString("reasontype"),
                                "description" to rs.getString("description"),
                                "status" to rs.getString("status"),
                                "adminNote" to rs.getString("adminnote"),
                                "createdAt" to rs.getTimestamp("createdat")?.toString(),
                                "updatedAt" to rs.getTimestamp("updatedat")?.toString(),
                                "reporterName" to rs.getString("reportername"),
                                "term" to rs.getString("term"),
                                "definition" to rs.getString("definition"),
                                "setTitle" to rs.getString("settitle")
                            )
                        )
                    }
                }
            }
        }
        ok(rows)
    } catch (ex: Exception) {
        error(ex.message ?: "Unable to load card reports")
    }

    @PutMapping("/card-reports/{cardReportId:\\d+}/status")
    fun updateCardReportStatus(
        @PathVariable cardReportId: Long,
        @RequestBody request: AdminCardReportStatusRequest
    ): Map<String, Any> {
        val status = request.status?.trim()?.takeIf { it in setOf("Pending", "Resolved", "Rejected") }
            ?: return error("Invalid status")
        return updateOne(
            "UPDATE cardreports SET status = ?, adminnote = ?, updatedat = NOW() WHERE cardreportid = ?",
            listOf(status, request.adminNote, cardReportId),
            "Card report not found",
            mapOf("cardReportId" to cardReportId, "status" to status)
        )
    }

    @GetMapping("/analytics")
    fun analytics(): Map<String, Any> = try {
        dataSource.connection.use { connection ->
            val summary = mapOf(
                "totalUsers" to count(connection, "SELECT COUNT(*) FROM users"),
                "totalSets" to count(connection, "SELECT COUNT(*) FROM flashcardsets"),
                "totalCards" to count(connection, "SELECT COUNT(*) FROM flashcards"),
                "pendingSets" to count(connection, "SELECT COUNT(*) FROM flashcardsets WHERE lower(approvalstatus) = 'pending'"),
                "pendingSetReports" to if (tableExists("reports")) count(connection, "SELECT COUNT(*) FROM reports WHERE lower(status) = 'pending'") else 0,
                "pendingCardReports" to if (tableExists("cardreports")) count(connection, "SELECT COUNT(*) FROM cardreports WHERE lower(status) = 'pending'") else 0
            )
            ok(
                mapOf(
                    "summary" to summary,
                    "daily" to dailyAnalytics(connection),
                    "topSets" to topSets(connection),
                    "topUsers" to topUsers(connection)
                )
            )
        }
    } catch (ex: Exception) {
        error(ex.message ?: "Unable to load analytics")
    }

    private fun loadSets(statusFilter: String?): Map<String, Any> = try {
        val rows = mutableListOf<Map<String, Any?>>()
        dataSource.connection.use { connection ->
            val where = if (statusFilter == null) "" else "WHERE lower(fs.approvalstatus) = ?"
            val sql = """
                SELECT fs.setid, fs.userid, fs.categoryid, fs.title, fs.description,
                       fs.ispublic, fs.ishidden, fs.viewcount, fs.studycount,
                       fs.approvalstatus, fs.rejectionreason, fs.createdat,
                       u.fullname AS authorname,
                       c.categoryname,
                       COUNT(fc.cardid) AS cardcount
                FROM flashcardsets fs
                LEFT JOIN users u ON fs.userid = u.userid
                LEFT JOIN categories c ON fs.categoryid = c.categoryid
                LEFT JOIN flashcards fc ON fs.setid = fc.setid
                $where
                GROUP BY fs.setid, fs.userid, fs.categoryid, fs.title, fs.description,
                         fs.ispublic, fs.ishidden, fs.viewcount, fs.studycount,
                         fs.approvalstatus, fs.rejectionreason, fs.createdat,
                         u.fullname, c.categoryname
                ORDER BY fs.createdat DESC, fs.setid DESC
            """.trimIndent()
            connection.prepareStatement(sql).use { statement ->
                if (statusFilter != null) statement.setString(1, statusFilter)
                statement.executeQuery().use { rs ->
                    while (rs.next()) {
                        rows.add(
                            mapOf(
                                "setId" to rs.getInt("setid"),
                                "userId" to rs.getInt("userid"),
                                "categoryId" to rs.getObject("categoryid"),
                                "title" to rs.getString("title"),
                                "description" to rs.getString("description"),
                                "isPublic" to rs.getBoolean("ispublic"),
                                "isHidden" to rs.getBoolean("ishidden"),
                                "viewCount" to rs.getInt("viewcount"),
                                "studyCount" to rs.getInt("studycount"),
                                "approvalStatus" to rs.getString("approvalstatus"),
                                "rejectionReason" to rs.getString("rejectionreason"),
                                "createdAt" to rs.getTimestamp("createdat")?.toString(),
                                "authorName" to rs.getString("authorname"),
                                "categoryName" to rs.getString("categoryname"),
                                "cardCount" to rs.getInt("cardcount")
                            )
                        )
                    }
                }
            }
        }
        ok(rows)
    } catch (ex: Exception) {
        error(ex.message ?: "Unable to load sets")
    }

    private fun dailyAnalytics(connection: java.sql.Connection): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        val sql = """
            SELECT day::date AS activitydate,
                   COALESCE(u.newusers, 0) AS newusers,
                   COALESCE(s.newsets, 0) AS newsets,
                   COALESCE(c.newcards, 0) AS newcards,
                   COALESCE(q.quizzes, 0) AS quizzes,
                   COALESCE(g.games, 0) AS games
            FROM generate_series(CURRENT_DATE - INTERVAL '13 days', CURRENT_DATE, INTERVAL '1 day') day
            LEFT JOIN (
                SELECT createdat::date AS d, COUNT(*) AS newusers FROM users GROUP BY createdat::date
            ) u ON u.d = day::date
            LEFT JOIN (
                SELECT createdat::date AS d, COUNT(*) AS newsets FROM flashcardsets GROUP BY createdat::date
            ) s ON s.d = day::date
            LEFT JOIN (
                SELECT createdat::date AS d, COUNT(*) AS newcards FROM flashcards GROUP BY createdat::date
            ) c ON c.d = day::date
            LEFT JOIN (
                SELECT takenat::date AS d, COUNT(*) AS quizzes FROM quizresults GROUP BY takenat::date
            ) q ON q.d = day::date
            LEFT JOIN (
                SELECT playedat::date AS d, COUNT(*) AS games FROM gameresults GROUP BY playedat::date
            ) g ON g.d = day::date
            ORDER BY day::date
        """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    rows.add(
                        mapOf(
                            "activityDate" to rs.getDate("activitydate").toString(),
                            "newUsers" to rs.getInt("newusers"),
                            "newSets" to rs.getInt("newsets"),
                            "newCards" to rs.getInt("newcards"),
                            "quizzes" to rs.getInt("quizzes"),
                            "games" to rs.getInt("games")
                        )
                    )
                }
            }
        }
        return rows
    }

    private fun topSets(connection: java.sql.Connection): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        val sql = """
            SELECT fs.setid, fs.title, fs.studycount, u.fullname AS authorname, COUNT(fc.cardid) AS cardcount
            FROM flashcardsets fs
            LEFT JOIN users u ON fs.userid = u.userid
            LEFT JOIN flashcards fc ON fs.setid = fc.setid
            GROUP BY fs.setid, fs.title, fs.studycount, u.fullname
            ORDER BY fs.studycount DESC, COUNT(fc.cardid) DESC, fs.createdat DESC
            LIMIT 10
        """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    rows.add(
                        mapOf(
                            "setId" to rs.getInt("setid"),
                            "title" to rs.getString("title"),
                            "authorName" to rs.getString("authorname"),
                            "studyCount" to rs.getInt("studycount"),
                            "cardCount" to rs.getInt("cardcount")
                        )
                    )
                }
            }
        }
        return rows
    }

    private fun topUsers(connection: java.sql.Connection): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        val sql = """
            SELECT u.userid, u.fullname, u.email,
                   COALESCE(sp.remembered, 0) AS studypoints,
                   COALESCE(q.quizpoints, 0) AS quizpoints,
                   COALESCE(g.gamepoints, 0) AS gamepoints
            FROM users u
            LEFT JOIN (
                SELECT userid, SUM(rememberedcards) AS remembered FROM studyprogress GROUP BY userid
            ) sp ON sp.userid = u.userid
            LEFT JOIN (
                SELECT userid, SUM(score) AS quizpoints FROM quizresults GROUP BY userid
            ) q ON q.userid = u.userid
            LEFT JOIN (
                SELECT userid, SUM(score) AS gamepoints FROM gameresults GROUP BY userid
            ) g ON g.userid = u.userid
            ORDER BY (COALESCE(sp.remembered, 0) + COALESCE(q.quizpoints, 0) + COALESCE(g.gamepoints, 0)) DESC,
                     u.createdat ASC
            LIMIT 10
        """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    val study = rs.getInt("studypoints")
                    val quiz = rs.getInt("quizpoints")
                    val game = rs.getInt("gamepoints")
                    rows.add(
                        mapOf(
                            "userId" to rs.getInt("userid"),
                            "fullName" to rs.getString("fullname"),
                            "email" to rs.getString("email"),
                            "studyPoints" to study,
                            "quizPoints" to quiz,
                            "gamePoints" to game,
                            "totalPoints" to study + quiz + game
                        )
                    )
                }
            }
        }
        return rows
    }

    private fun updateOne(
        sql: String,
        params: List<Any?>,
        notFoundMessage: String,
        data: Map<String, Any?>
    ): Map<String, Any> = try {
        dataSource.connection.use { connection ->
            val updated = connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
            if (updated == 0) error(notFoundMessage) else ok(data)
        }
    } catch (ex: Exception) {
        error(ex.message ?: "Update failed")
    }

    private fun count(connection: java.sql.Connection, sql: String): Long {
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                rs.next()
                return rs.getLong(1)
            }
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
                statement.executeQuery().use { rs -> return rs.next() }
            }
        }
    }

    private fun notImplemented(feature: String): Map<String, Any> {
        return mapOf("status" to "ERROR", "message" to "Chức năng chưa được triển khai: $feature")
    }

    private fun ok(data: Any): Map<String, Any> = mapOf("status" to "OK", "data" to data)

    private fun error(message: String): Map<String, Any> = mapOf("status" to "ERROR", "message" to message)
}
