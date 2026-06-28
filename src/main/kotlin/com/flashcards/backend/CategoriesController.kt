package com.flashcards.backend

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

@RestController
@RequestMapping("/api")
class CategoriesController(
    private val dataSource: DataSource
) {
    @GetMapping("/categories", "/sets/meta/categories")
    fun getCategories(): Map<String, Any> {
        return try {
            val categories = mutableListOf<Map<String, Any?>>()
            dataSource.connection.use { connection ->
                val sql = """
                    SELECT categoryid, categoryname, description
                    FROM categories
                    ORDER BY categoryname
                """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            categories.add(
                                mapOf(
                                    "categoryId" to resultSet.getInt("categoryid"),
                                    "categoryName" to resultSet.getString("categoryname"),
                                    "description" to resultSet.getString("description")
                                )
                            )
                        }
                    }
                }
            }
            mapOf("status" to "OK", "data" to categories)
        } catch (ex: Exception) {
            mapOf("status" to "ERROR", "message" to (ex.message ?: "Unable to load categories"))
        }
    }
}
