package com.example.isip.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.isip.ui.gallery.GalleryScreen
import com.example.isip.ui.search.SearchScreen
import com.example.isip.ui.organize.OrganizeScreen
import com.example.isip.ui.settings.SettingsScreen
import com.example.isip.ui.photo.PhotoDetailScreen
import com.example.isip.ui.navigation.Screen
import com.example.isip.ui.smartalbum.SmartAlbumScreen

@Composable
fun MainNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Gallery.route,
        modifier = modifier
    ) {
        composable(Screen.Gallery.route) {
            GalleryScreen(
                onPhotoClick = { photoId ->
                    navController.navigate(Screen.PhotoDetail().createRoute(photoId))
                },
                onSmartAlbumClick = { albumId ->
                    navController.navigate(Screen.SmartAlbum().createRoute(albumId))
                },
                onCreateSmartAlbumClick = {
                    navController.navigate(Screen.Organize.route) { launchSingleTop = true }
                }
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onPhotoClick = { photoId ->
                    navController.navigate(Screen.PhotoDetail().createRoute(photoId))
                }
            )
        }

        composable(Screen.Organize.route) {
            OrganizeScreen(
                onPhotoClick = { photoId ->
                    navController.navigate(Screen.PhotoDetail().createRoute(photoId))
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen()
        }

        composable(
            route = "photo/{photoId}",
            arguments = listOf(navArgument("photoId") { type = NavType.StringType })
        ) { backStackEntry ->
            val photoId = backStackEntry.arguments?.getString("photoId") ?: ""
            PhotoDetailScreen(
                photoId = photoId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "smart-album/{albumId}",
            arguments = listOf(navArgument("albumId") { type = NavType.LongType })
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getLong("albumId") ?: return@composable
            SmartAlbumScreen(
                albumId = albumId,
                onNavigateBack = { navController.popBackStack() },
                onPhotoClick = { photoId ->
                    navController.navigate(Screen.PhotoDetail().createRoute(photoId))
                }
            )
        }

        composable(Screen.Analysis.route) {
            // TODO: Implement AnalysisScreen
        }

        composable(
            route = "duplicate/{groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.StringType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
            // TODO: Implement DuplicateScreen
        }

        composable(
            route = "privacy/{alertId}",
            arguments = listOf(navArgument("alertId") { type = NavType.StringType })
        ) { backStackEntry ->
            val alertId = backStackEntry.arguments?.getString("alertId") ?: ""
            // TODO: Implement PrivacyScreen
        }
    }
}

