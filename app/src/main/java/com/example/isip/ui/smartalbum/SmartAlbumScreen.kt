package com.example.isip.ui.smartalbum

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.isip.ui.common.EmptyState
import com.example.isip.ui.common.ErrorState
import com.example.isip.ui.common.LoadingState
import com.example.isip.ui.gallery.PhotoGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartAlbumScreen(
    albumId: Long,
    onNavigateBack: () -> Unit,
    onPhotoClick: (String) -> Unit,
    viewModel: SmartAlbumViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(albumId) { viewModel.load(albumId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.name) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> LoadingState("正在匹配相册照片…")
                state.errorMessage != null -> ErrorState(
                    message = state.errorMessage ?: "加载失败",
                    onRetry = { viewModel.load(albumId) }
                )
                state.photos.isEmpty() -> EmptyState(
                    icon = Icons.Default.PhotoAlbum,
                    title = "相册暂时为空",
                    description = "完成照片分析后，符合规则的照片会自动出现在这里"
                )
                else -> Column(Modifier.fillMaxSize()) {
                    Text(
                        "${state.photos.size} 张照片 · ${state.ruleDescription}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PhotoGrid(
                        photos = state.photos,
                        selectedPhotoIds = emptySet(),
                        onPhotoClick = onPhotoClick,
                        onPhotoLongClick = onPhotoClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
