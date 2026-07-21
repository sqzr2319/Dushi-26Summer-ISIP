package com.example.isip.ui.gallery

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.isip.data.media.MediaStoreDeleteConfirmation
import com.example.isip.ui.common.ConfirmDialog
import com.example.isip.ui.common.EmptyState
import com.example.isip.ui.common.ErrorState
import com.example.isip.ui.common.LoadingState
import com.example.isip.ui.common.PermissionRequestContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onPhotoClick: (String) -> Unit,
    onSmartAlbumClick: (Long) -> Unit,
    onCreateSmartAlbumClick: () -> Unit,
    viewModel: GalleryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requestedPermissions = remember {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    fun hasPhotoPermission() = requestedPermissions.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.onEvent(GalleryUiEvent.PermissionResult(result.values.any { it }))
    }
    val requestPhotoPermission = {
        permissionLauncher.launch(requestedPermissions)
    }
    val systemDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        uiState.pendingDeleteRequest?.let { request ->
            viewModel.onEvent(
                GalleryUiEvent.DeleteConfirmationResult(
                    requestId = request.requestId,
                    approved = result.resultCode == Activity.RESULT_OK,
                    systemDeleteCompleted = result.resultCode == Activity.RESULT_OK
                )
            )
        }
    }

    LaunchedEffect(Unit) {
        val granted = hasPhotoPermission()
        viewModel.onEvent(GalleryUiEvent.PermissionResult(granted))
        if (granted) viewModel.refreshPhotos()
    }

    LaunchedEffect(uiState.pendingDeleteRequest) {
        val request = uiState.pendingDeleteRequest ?: return@LaunchedEffect
        val sender = runCatching {
            MediaStoreDeleteConfirmation(context).createIntentSender(request.photoIds)
        }.getOrNull()
        if (sender != null) {
            systemDeleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            viewModel.onEvent(
                GalleryUiEvent.DeleteConfirmationResult(request.requestId, true, false)
            )
        }
    }

    val filteredPhotos = remember(uiState.photos, uiState.activeCategory) {
        viewModel.getFilteredPhotos()
    }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (uiState.selectedPhotoIds.isNotEmpty()) {
                TopAppBar(
                    title = { Text("已选择 ${uiState.selectedPhotoIds.size} 张") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.onEvent(GalleryUiEvent.ClearSelection) }) {
                            Icon(Icons.Default.Close, contentDescription = "取消选择")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("相册") },
                    actions = {
                        IconButton(
                            onClick = { viewModel.onEvent(GalleryUiEvent.StartAnalysis) },
                            enabled = uiState.analysisProgress == null
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "开始分析")
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                !uiState.permissionGranted -> PermissionRequestContent(
                    onRequestPermission = requestPhotoPermission
                )
                uiState.isLoading -> LoadingState("正在加载相册…")
                uiState.errorMessage != null -> ErrorState(
                    message = uiState.errorMessage ?: "加载失败",
                    onRetry = { viewModel.refreshPhotos() }
                )
                uiState.photos.isEmpty() -> EmptyState(
                    icon = Icons.Default.Photo,
                    title = "暂无照片",
                    description = "设备相册中还没有可查看的照片"
                )
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    SmartAlbumSection(
                        albums = uiState.smartAlbums,
                        onAlbumClick = onSmartAlbumClick,
                        onCreateClick = onCreateSmartAlbumClick
                    )
                    CategoryFilterRow(
                        categories = listOf("全部", "人物", "风景", "截图", "文档"),
                        selectedCategory = uiState.activeCategory,
                        onCategorySelected = { viewModel.onEvent(GalleryUiEvent.SelectCategory(it)) }
                    )
                    uiState.analysisProgress?.let { progress ->
                        AnalysisStatusBar(
                            progress = progress,
                            onPauseClick = { viewModel.onEvent(GalleryUiEvent.PauseAnalysis) },
                            onResumeClick = { viewModel.onEvent(GalleryUiEvent.ResumeAnalysis) }
                        )
                    }
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = viewModel::refreshPhotos,
                        state = rememberPullToRefreshState(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (filteredPhotos.isEmpty()) {
                            EmptyState(
                                icon = Icons.Default.Photo,
                                title = "暂无匹配照片",
                                description = "当前分类下没有照片"
                            )
                        } else {
                            PhotoGrid(
                                photos = filteredPhotos,
                                selectedPhotoIds = uiState.selectedPhotoIds,
                                onPhotoClick = { photoId ->
                                    if (uiState.selectedPhotoIds.isNotEmpty()) {
                                        viewModel.onEvent(GalleryUiEvent.TogglePhotoSelection(photoId))
                                    } else {
                                        onPhotoClick(photoId)
                                    }
                                },
                                onPhotoLongClick = { photoId ->
                                    viewModel.onEvent(GalleryUiEvent.TogglePhotoSelection(photoId))
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = "确认删除",
            message = "确定删除已选择的 ${uiState.selectedPhotoIds.size} 张照片吗？此操作无法撤销。",
            confirmText = "删除",
            onConfirm = {
                viewModel.onEvent(GalleryUiEvent.DeleteSelected)
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}
