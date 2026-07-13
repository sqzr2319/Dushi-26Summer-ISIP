package com.example.isip.ui.gallery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.isip.ui.common.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onPhotoClick: (String) -> Unit,
    onAnalysisClick: () -> Unit,
    viewModel: GalleryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
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
    fun hasPhotoPermission(): Boolean = requestedPermissions.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onEvent(GalleryUiEvent.PermissionResult(results.values.any { it }))
    }
    val requestPhotoPermission = { permissionLauncher.launch(requestedPermissions) }

    LaunchedEffect(Unit) {
        viewModel.onEvent(GalleryUiEvent.PermissionResult(hasPhotoPermission()))
    }
    val filteredPhotos = remember(uiState.photos, uiState.activeCategory) {
        viewModel.getFilteredPhotos()
    }

    var showDeleteDialog by remember { mutableStateOf(false) }

    // Returning from PhotoDetailScreen resumes this destination but reuses its
    // ViewModel. Refresh so newly saved analysis results become visible.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasPhotoPermission()
                viewModel.onEvent(GalleryUiEvent.PermissionResult(granted))
                if (granted) viewModel.refreshPhotos()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            if (uiState.selectedPhotoIds.isNotEmpty()) {
                TopAppBar(
                    title = { Text("已选择 ${uiState.selectedPhotoIds.size} 张") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.onEvent(GalleryUiEvent.ClearSelection) }) {
                            Icon(Icons.Default.Close, "取消选择")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, "删除")
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
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "分析尚未使用当前模型的照片"
                            )
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when {
                !uiState.permissionGranted -> {
                    PermissionRequestContent(
                        onRequestPermission = requestPhotoPermission
                    )
                }

                uiState.isLoading -> {
                    LoadingState(message = "加载相册中...")
                }

                uiState.errorMessage != null -> {
                    ErrorState(
                        message = uiState.errorMessage ?: "未知错误",
                        onRetry = requestPhotoPermission
                    )
                }

                uiState.photos.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.Photo,
                        title = "暂无照片",
                        description = "您的设备上还没有照片"
                    )
                }

                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Category filter chips
                        CategoryFilterRow(
                            categories = listOf("全部", "人物", "风景", "截图", "文档"),
                            selectedCategory = uiState.activeCategory,
                            onCategorySelected = { viewModel.onEvent(GalleryUiEvent.SelectCategory(it)) }
                        )

                        // Analysis progress bar
                        uiState.analysisProgress?.let { progress ->
                            AnalysisStatusBar(
                                progress = progress,
                                onPauseClick = { viewModel.onEvent(GalleryUiEvent.PauseAnalysis) },
                                onResumeClick = { viewModel.onEvent(GalleryUiEvent.ResumeAnalysis) }
                            )
                        }

                        // Photo grid
                        if (filteredPhotos.isEmpty()) {
                            EmptyState(
                                icon = Icons.Default.Photo,
                                title = "无照片",
                                description = "该分类下暂无照片"
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
            message = "确定要删除选中的 ${uiState.selectedPhotoIds.size} 张照片吗？此操作无法撤销。",
            confirmText = "删除",
            onConfirm = {
                viewModel.onEvent(GalleryUiEvent.DeleteSelected)
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}
