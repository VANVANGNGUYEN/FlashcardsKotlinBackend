package com.flashcards.backend

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

@RestController
@RequestMapping("/api")
class DbTestController(
    private val dataSource: DataSource
) {

    @GetMapping("/db-test")
    fun dbTest(): Map<String, Any> {
        dataSource.connection.use { connection ->
            val statement = connection.createStatement()
            val resultSet = statement.executeQuery("SELECT current_database(), current_user")

            resultSet.next()

            return mapOf(
                "status" to "OK",
                "message" to "Connected to Neon PostgreSQL",
                "database" to resultSet.getString(1),
                "user" to resultSet.getString(2)
            )
        }
    }
}