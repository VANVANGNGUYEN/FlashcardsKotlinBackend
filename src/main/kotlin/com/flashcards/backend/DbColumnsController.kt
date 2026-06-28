package com.flashcards.backend

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

@RestController
@RequestMapping("/api")
class DbColumnsController(
    private val dataSource: DataSource
) {

    @GetMapping("/db-columns/{tableName}")
    fun getColumns(@PathVariable tableName: String): Map<String, Any> {
        val columns = mutableListOf<Map<String, Any?>>()

        dataSource.connection.use { connection ->
            val sql = """
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                ORDER BY ordinal_position
            """.trimIndent()

            val statement = connection.prepareStatement(sql)
            statement.setString(1, tableName)

            val resultSet = statement.executeQuery()

            while (resultSet.next()) {
                columns.add(
                    mapOf(
                        "column" to resultSet.getString("column_name"),
                        "type" to resultSet.getString("data_type"),
                        "nullable" to resultSet.getString("is_nullable")
                    )
                )
            }
        }

        return mapOf(
            "status" to "OK",
            "table" to tableName,
            "columns" to columns
        )
    }
}