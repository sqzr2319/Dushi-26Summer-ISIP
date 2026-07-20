package com.example.isip.ui.photo

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(photoId) { viewModel.loadPhoto(photoId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("照片详情") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    uiState.photo?.let { photo ->
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/*"
                                putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(photo.uri))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享照片"))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "分享")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                uiState.isLoading -> LoadingState("正在加载照片…")
                uiState.errorMessage != null -> ErrorState(
                    message = uiState.errorMessage ?: "照片加载失败",
                    onRetry = { viewModel.loadPhoto(photoId) }
                )
                uiState.photo != null -> PhotoDetailContent(
                    photo = uiState.photo!!,
                    ocrText = uiState.ocrText,
                    description = uiState.description,
                    isAnalyzing = uiState.isAnalyzing,
                    isAddingTag = uiState.isAddingTag,
                    onAddTag = { viewModel.onEvent(PhotoDetailUiEvent.AddTag(it)) },
                    onCopyOcr = { viewModel.onEvent(PhotoDetailUiEvent.CopyOcrText) },
                    onAnalyze = { viewModel.onEvent(PhotoDetailUiEvent.StartAnalysis) },
                    onReanalyze = { viewModel.onEvent(PhotoDetailUiEvent.Reanalyze) }
                )
            }
        }
    }
}

@Composable
private fun PhotoDetailContent(
    photo: com.example.isip.ui.model.PhotoUiModel,
    ocrText: String?,
    description: String?,
    isAnalyzing: Boolean,
    isAddingTag: Boolean,
    onAddTag: (String) -> Unit,
    onCopyOcr: () -> Unit,
    onAnalyze: () -> Unit,
    onReanalyze: () -> Unit
) {
    var tagInput by remember(photo.id) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = "照片预览",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)),
            contentScale = ContentScale.Fit
        )

        Column(modifier = Modifier.padding(18.dp)) {
            Text("照片信息", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(10.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    InfoRow(Icons.Default.CalendarToday, "拍摄时间", photo.takenAtText)
                    if (photo.categories.isNotEmpty()) {
                        Spacer(Modifier.size(10.dp))
                        InfoRow(Icons.Default.Info, "分类", photo.categories.joinToString("、"))
                    }
                }
            }

            if (photo.hasPrivacyAlert) {
                Card(
                    modifier = Modifier.padding(top = 14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = "隐私提醒", tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Text("这张照片可能包含敏感信息。", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            SectionLabel("手动标签")
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
                    placeholder = { Text("添加标签") }
                )
                Button(
                    onClick = {
                        onAddTag(tagInput)
                        tagInput = ""
                    },
                    enabled = tagInput.trim().isNotEmpty() && !isAddingTag
                ) { Text(if (isAddingTag) "添加中" else "添加") }
            }

            if (photo.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    photo.tags.forEach { tag -> SuggestionChip(onClick = {}, label = { Text(tag) }) }
                }
            }

            SectionLabel("AI 分析")
            if (photo.isAnalyzed) {
                if (photo.categories.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        photo.categories.forEach { category -> AssistChip(onClick = {}, label = { Text(category) }) }
                    }
                }
                description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                ocrText?.let {
                    OcrTextBlock(text = it, onCopy = onCopyOcr, modifier = Modifier.padding(top = 14.dp))
                }
                OutlinedButton(
                    onClick = onReanalyze,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    enabled = !isAnalyzing
                ) { Text(if (isAnalyzing) "正在分析…" else "重新分析") }
            } else {
                Text(
                    "这张照片还没有分析结果。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onAnalyze,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    enabled = !isAnalyzing
                ) { Text(if (isAnalyzing) "正在分析…" else "开始分析") }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall
    )
}

@Composable
private fun InfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text("$label  ", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
