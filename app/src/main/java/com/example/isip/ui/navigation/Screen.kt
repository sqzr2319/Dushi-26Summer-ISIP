package com.example.isip.ui.navigation

sealed class Screen(val route: String) {
    data object Gallery : Screen("gallery")
    data object Search : Screen("search")
    data object Organize : Screen("organize")
    data object Settings : Screen("settings")
    data object Analysis : Screen("analysis")

    data class PhotoDetail(val photoId: String = "{photoId}") : Screen("photo/$photoId") {
        fun createRoute(photoId: String) = "photo/$photoId"
    }

    data class Duplicate(val groupId: String = "{groupId}") : Screen("duplicate/$groupId") {
        fun createRoute(groupId: String) = "duplicate/$groupId"
    }

    data class Privacy(val alertId: String = "{alertId}") : Screen("privacy/$alertId") {
        fun createRoute(alertId: String) = "privacy/$alertId"
    }
}
