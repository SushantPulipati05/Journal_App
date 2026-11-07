package dev.sushnt.journal

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform