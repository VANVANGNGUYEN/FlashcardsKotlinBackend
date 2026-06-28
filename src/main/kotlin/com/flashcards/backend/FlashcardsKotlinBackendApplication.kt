package com.flashcards.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FlashcardsKotlinBackendApplication

fun main(args: Array<String>) {
	runApplication<FlashcardsKotlinBackendApplication>(*args)
}
