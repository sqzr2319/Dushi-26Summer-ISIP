package com.example.isip.ui.photo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.isip.ui.common.ErrorState
import com.example.isip.ui.common.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailScreen(
    photoId: String,
    onNavigateBack: () -> Unit,
    viewModel: PhotoDetailViewModel = viewModel()
) {
    LaunchedEffect(photoId) {
        viewModel.loadPhoto(photoId)
    }

    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("照片详情") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { /* TODO: Share */ }) {
                        Icon(Icons.Default.Share, "分享")
                    }
                    IconButton(onClick = { viewModel.onEvent(PhotoDetailUiEvent.Delete) }) {
                        Icon(Icons.Default.Delete, "删除")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when {
                uiState.isLoading -> {
                    LoadingState(message = "加载中...")
                }

                uiState.errorMessage != null -> {
                    ErrorState(
                        message = uiState.errorMessage ?: "未知错误",
                        onRetry = { viewModel.loadPhoto(photoId) }
                    )
                }

                uiState.photo != null -> {
                    val photo = uiState.photo!!
                    var tagInput by remember(photo.id) { mutableStateOf("") }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Photo preview
                        AsyncImage(
                            model = photo.uri,
                            contentDescription = "照片",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentScale = ContentScale.Fit
                        )

                        // Basic info
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            InfoRow(
                                icon = Icons.Default.DateRange,
                                label = "拍摄时间",
                                value = photo.takenAtText
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            if (photo.hasPrivacyAlert) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Warning,
                                            "隐私提醒",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "此照片包含隐私敏感信息",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            Text(
                                text = "手动标签",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = tagInput,
                                    onValueChange = { tagInput = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    label = { Text("例如：旅行") }
                                )
                                Button(
                                    onClick = {
                                        viewModel.onEvent(PhotoDetailUiEvent.AddTag(tagInput))
                                        tagInput = ""
                                    },
                                    enabled = tagInput.trim().isNotEmpty() && !uiState.isAddingTag
                                ) {
                                    Text(if (uiState.isAddingTag) "添加中" else "添加")
                                }
                            }
                            Spacer(modifier = Modifier.height(20.dp))

                            // Analysis results
                            if (photo.isAnalyzed) {
                                Text(
                                    text = "AI 分析结果",
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Categories
                                if (photo.categories.isNotEmpty()) {
                                    Text(
                                        text = "分类",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        photo.categories.forEach { category ->
                                            AssistChip(
                                                onClick = { },
                                                label = { Text(category) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                // Tags
                                if (photo.tags.isNotEmpty()) {
                                    Text(
                                        text = "标签",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        photo.tags.forEach { tag ->
                                            SuggestionChip(
                                                onClick = { },
                                                label = { Text(tag) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                }

                                // OCR result (if available)
                                uiState.ocrText?.let { ocrText ->
                                    OcrTextBlock(
                                        text = ocrText,
                                        onCopy = { viewModel.onEvent(PhotoDetailUiEvent.CopyOcrText) }
                                    )
                                }

                                // Description (if available)
                                uiState.description?.let { description ->
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "描述",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Spacer(modifier = Modifier.height(20.dp))
                                OutlinedButton(
                                    onClick = { viewModel.onEvent(PhotoDetailUiEvent.Reanalyze) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !uiState.isAnalyzing
                                ) {
                                    Text(if (uiState.isAnalyzing) "Qwen3.5 正在分析…" else "重新使用 AI 模型分析")
                                }
                            } else {
                                Button(
                                    onClick = { viewModel.onEvent(PhotoDetailUiEvent.StartAnalysis) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !uiState.isAnalyzing
                                ) {
                                    Text(if (uiState.isAnalyzing) "正在分析…" else "开始分析此照片")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
