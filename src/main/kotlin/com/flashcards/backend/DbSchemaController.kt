package com.flashcards.backend

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

@RestController
@RequestMapping("/api")
class DbSchemaController(
    private val dataSource: DataSource
) {

    @GetMapping("/db-tables")
    fun getTables(): Map<String, Any> {
        val tables = mutableListOf<String>()

        dataSource.connection.use { connection ->
            val statement = connection.createStatement()
            val resultSet = statement.executeQuery(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                ORDER BY table_name
                """.trimIndent()
            )

            while (resultSet.next()) {
                tables.add(resultSet.getString("table_name"))
            }
        }

        return mapOf(
            "status" to "OK",
            "tables" to tables
        )
    }
}