package com.flashcards.backend

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class HealthController {

    @GetMapping
    fun apiRoot(): Map<String, String> {
        return healthResponse()
    }

    @GetMapping("/health")
    fun health(): Map<String, String> {
        return healthResponse()
    }

    private fun healthResponse(): Map<String, String> {
        return mapOf(
            "status" to "OK",
            "message" to "Kotlin backend is running"
        )
    }
}
