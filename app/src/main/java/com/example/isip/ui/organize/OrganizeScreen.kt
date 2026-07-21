package com.example.isip.ui.organize

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.isip.data.media.MediaStoreDeleteConfirmation
import com.example.isip.data.model.CleanupReview
import com.example.isip.ui.common.EmptyState
import com.example.isip.ui.common.ErrorState
import com.example.isip.ui.common.LoadingState
import com.example.isip.ui.model.BatchActionPreviewUi
import com.example.isip.ui.model.OrganizationPlanUiModel
import com.example.isip.ui.model.TagSuggestionUi
import com.example.isip.ui.model.PhotoUiModel
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.RoundedCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeScreen(
    onPhotoClick: (String) -> Unit,
    viewModel: OrganizeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val deleteConfirmation = remember(context) {
        MediaStoreDeleteConfirmation(context)
    }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        uiState.pendingDeleteRequest?.let { request ->
            val approved = result.resultCode == Activity.RESULT_OK
            viewModel.onEvent(
                OrganizeUiEvent.CleanupConfirmationResult(
                    requestId = request.requestId,
                    approved = approved,
                    systemDeleteCompleted = approved
                )
            )
        }
    }

    LaunchedEffect(uiState.pendingDeleteRequest?.requestId, uiState.launchedDeleteRequestId) {
        val request = uiState.pendingDeleteRequest ?: return@LaunchedEffect
        if (uiState.launchedDeleteRequestId == request.requestId) return@LaunchedEffect
        viewModel.onEvent(OrganizeUiEvent.DeleteConfirmationLaunched(request.requestId))
        val sender = runCatching { deleteConfirmation.createIntentSender(request.photoIds) }.getOrNull()
        if (sender == null) {
            // Android 10 及以下没有批量系统授权页；前一层应用复核即为明确授权。
            viewModel.onEvent(
                OrganizeUiEvent.CleanupConfirmationResult(request.requestId, true, false)
            )
        } else {
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    uiState.actionPreview?.let { preview ->
        AlbumActionPreviewDialog(
            preview = preview,
            onConfirm = { viewModel.onEvent(OrganizeUiEvent.ConfirmSelected) },
            onDismiss = { viewModel.onEvent(OrganizeUiEvent.DismissActionPreview) }
        )
    }
    uiState.pendingTagSuggestion?.let { suggestion ->
        TagActionPreviewDialog(
            suggestion = suggestion,
            onConfirm = { viewModel.onEvent(OrganizeUiEvent.ConfirmTagSuggestion) },
            onDismiss = { viewModel.onEvent(OrganizeUiEvent.DismissTagSuggestion) }
        )
    }
    uiState.duplicateComparison?.let { comparison ->
        DuplicateComparisonDialog(
            comparison = comparison,
            onSelectKeep = { viewModel.onEvent(OrganizeUiEvent.SelectDuplicateKeep(it)) },
            onConfirm = { viewModel.onEvent(OrganizeUiEvent.ConfirmDuplicateComparison) },
            onDismiss = { viewModel.onEvent(OrganizeUiEvent.DismissDuplicateComparison) }
        )
    }
    uiState.similarComparison?.let { comparison ->
        SimilarComparisonDialog(
            group = comparison,
            onPhotoClick = { photoId ->
                viewModel.onEvent(OrganizeUiEvent.DismissSimilarComparison)
                onPhotoClick(photoId)
            },
            onDismiss = { viewModel.onEvent(OrganizeUiEvent.DismissSimilarComparison) }
        )
    }
    uiState.cleanupReview?.takeIf { uiState.pendingDeleteRequest == null }?.let { review ->
        CleanupReviewDialog(
            review = review,
            plan = uiState.plan,
            onConfirm = { viewModel.onEvent(OrganizeUiEvent.RequestReviewedDeletion) },
            onDismiss = { viewModel.onEvent(OrganizeUiEvent.DismissCleanupReview) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("整理") },
                actions = {
                    IconButton(
                        onClick = { viewModel.onEvent(OrganizeUiEvent.GeneratePlan) },
                        enabled = !uiState.isGeneratingPlan && !uiState.isExecutingCleanup
                    ) { Icon(Icons.Default.Refresh, contentDescription = "重新生成") }
                }
            )
        },
        bottomBar = {
            if (uiState.selectedSuggestionIds.isNotEmpty() &&
                !uiState.isGeneratingPlan && !uiState.isExecutingCleanup) {
                Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
                    Button(
                        onClick = { viewModel.onEvent(OrganizeUiEvent.PreviewSelected) },
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("下一步：预览 ${uiState.selectedSuggestionIds.size} 个相册操作")
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                uiState.isGeneratingPlan -> LoadingState("正在分析相册并生成建议…")
                uiState.isExecutingCleanup -> LoadingState("正在执行已批准的操作…")
                uiState.errorMessage != null && uiState.plan == null -> ErrorState(
                    message = uiState.errorMessage.orEmpty(),
                    onRetry = { viewModel.onEvent(OrganizeUiEvent.GeneratePlan) }
                )
                uiState.plan == null -> EmptyState(
                    icon = Icons.Default.AutoAwesome,
                    title = "还没有整理建议",
                    description = "完成照片分析后，这里会展示相册、重复照片、相似照片、标签和隐私建议。"
                )
                else -> PlanContent(
                    plan = uiState.plan!!,
                    state = uiState,
                    onEvent = viewModel::onEvent,
                    onPhotoClick = onPhotoClick
                )
            }
        }
    }
}

@Composable
private fun PlanContent(
    plan: OrganizationPlanUiModel,
    state: OrganizeUiState,
    onEvent: (OrganizeUiEvent) -> Unit,
    onPhotoClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            OrganizeSummaryCard(
                categoriesCount = plan.categorySuggestions.size,
                duplicatesCount = plan.duplicateGroups.size,
                privacyAlertsCount = plan.privacyAlerts.size
            )
        }
        state.completionMessage?.let { message -> item { StatusCard(message, false) } }
        state.errorMessage?.let { message -> item { StatusCard(message, true) } }

        if (state.smartAlbums.isNotEmpty()) {
            item { SectionTitle("已整理的智能相册", "创建结果保存在应用中，并按规则持续匹配照片") }
            items(state.smartAlbums, key = { "saved-album:${it.id}" }) { album ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(album.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${album.photoCount} 张照片 · ${album.ruleDescription}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PhotoPreviewRow(album.photos, Modifier.padding(top = 10.dp))
                    }
                }
            }
        }

        if (plan.suggestions.isNotEmpty()) {
            item { SectionTitle("整理建议", "建议本身不会修改相册") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        plan.suggestions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
        if (plan.categorySuggestions.isNotEmpty()) {
            item { SectionTitle("智能相册建议", "选择后先预览，再批准创建") }
            items(plan.categorySuggestions, key = { "album-suggestion:${it.id}" }) { category ->
                CategorySuggestionCard(
                    category,
                    category.id in state.selectedSuggestionIds,
                    { onEvent(OrganizeUiEvent.ToggleSuggestion(category.id)) }
                )
            }
        }
        if (plan.duplicateGroups.isNotEmpty()) {
            item { SectionTitle("相同/高度重复照片", "100分为内容完全相同；94分以上为高度重复") }
            items(plan.duplicateGroups, key = { "duplicate:${it.id}" }) { group ->
                DuplicateGroupCard(group = group, onClick = {
                    onEvent(OrganizeUiEvent.OpenDuplicateComparison(group))
                })
            }
        }
        if (plan.similarGroups.isNotEmpty()) {
            item { SectionTitle("明显相似照片", "视觉相似分数 85–94，通常是连拍或同一场景") }
            items(plan.similarGroups, key = { "similar:${it.id}" }) { group ->
                Card(
                    onClick = { onEvent(OrganizeUiEvent.OpenSimilarComparison(group)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "${group.photos.size} 张明显相似照片 · 视觉分数 ${similarityScore(group.similarityScore)}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(group.reason, style = MaterialTheme.typography.bodySmall)
                        PhotoPreviewRow(group.photos, Modifier.padding(top = 10.dp))
                        Text("点击展开比较", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (plan.tagSuggestions.isNotEmpty()) {
            item { SectionTitle("标签建议", "建议标签尚未写入照片") }
            items(plan.tagSuggestions, key = { "tag:${it.photo.id}" }) { suggestion ->
                Card(
                    onClick = { onEvent(OrganizeUiEvent.PreviewTagSuggestion(suggestion)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(suggestion.tags.joinToString("  ") { "#$it" }, color = MaterialTheme.colorScheme.primary)
                        suggestion.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("点击预览并批准写入", style = MaterialTheme.typography.labelSmall)
                        PhotoPreviewRow(listOf(suggestion.photo), Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        if (plan.privacyAlerts.isNotEmpty()) {
            item { SectionTitle("隐私提醒", "敏感内容不会被自动删除") }
            items(plan.privacyAlerts, key = { "privacy:${it.id}" }) { alert ->
                PrivacyAlertCard(alert, onClick = { onPhotoClick(alert.photo.id) })
            }
        }
        if (plan.categorySuggestions.isEmpty() && plan.duplicateGroups.isEmpty() &&
            plan.similarGroups.isEmpty() && plan.tagSuggestions.isEmpty() && plan.privacyAlerts.isEmpty()) {
            item { StatusCard("相册已经很整洁，当前没有待处理项目", false) }
        }
    }
}

@Composable
private fun AlbumActionPreviewDialog(
    preview: BatchActionPreviewUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批准${preview.actionType}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(preview.description)
                Text("涉及 ${preview.affectedPhotoCount} 张照片", color = MaterialTheme.colorScheme.primary)
                PhotoPreviewRow(preview.affectedPhotos)
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("批准并执行") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun TagActionPreviewDialog(
    suggestion: TagSuggestionUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批准写入标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PhotoPreviewRow(listOf(suggestion.photo))
                Text(suggestion.tags.joinToString("  ") { it.removePrefix("#").let { tag -> "#$tag" } })
                Text("标签会保存到应用数据库，并用于搜索和智能相册匹配。")
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("批准并写入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CleanupReviewDialog(
    review: CleanupReview,
    plan: OrganizationPlanUiModel?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val photosById = plan?.duplicateGroups.orEmpty().flatMap { it.photos }.associateBy { it.id }
    val deletePhotos = review.deleteIds.mapNotNull(photosById::get)
    val keepCount = review.items.mapNotNull { it.keepId }.distinct().size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除前复核") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Text("保留 $keepCount 张，删除 ${review.deleteIds.size} 张") }
                item { Text("预计释放 ${formatBytes(review.reclaimableBytes)}", color = MaterialTheme.colorScheme.primary) }
                if (deletePhotos.isNotEmpty()) {
                    item { Text("待删除照片", style = MaterialTheme.typography.titleSmall) }
                    item { PhotoPreviewRow(deletePhotos) }
                }
                items(review.warnings) { warning ->
                    Text("⚠ $warning", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                item {
                    Text(
                        "批准后还会显示 Android 系统删除确认；任一层取消都不会删除照片。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = review.deleteIds.isNotEmpty()) { Text("批准删除") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("返回") } }
    )
}

@Composable
private fun DuplicateComparisonDialog(
    comparison: com.example.isip.ui.model.DuplicateComparisonUi,
    onSelectKeep: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val exactContent = comparison.group.similarityScore >= 0.999f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要保留的照片") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        if (exactContent) {
                            "文件内容指纹完全一致。系统只会删除未选中的副本，并在执行前再次复核。"
                        } else {
                            "视觉相似分数 ${similarityScore(comparison.group.similarityScore)}，属于高度重复。请选择要保留的照片。"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                items(comparison.group.photos, key = { "duplicate-compare:${it.id}" }) { photo ->
                    ComparisonPhotoRow(
                        photo = photo,
                        selected = photo.id == comparison.selectedKeepId,
                        onClick = { onSelectKeep(photo.id) },
                        showSelection = true,
                        selectionLabel = if (photo.id == comparison.group.recommendedKeepId) "推荐保留" else null
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("下一步：复核删除") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SimilarComparisonDialog(
    group: com.example.isip.ui.model.SimilarGroupUi,
    onPhotoClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("比较相似照片") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "视觉相似分数 ${similarityScore(group.similarityScore)}，通常是连拍或同一场景。不会自动删除，点击照片可查看原图。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                items(group.photos, key = { "similar-compare:${it.id}" }) { photo ->
                    ComparisonPhotoRow(photo = photo, onClick = { onPhotoClick(photo.id) })
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("完成比较") } }
    )
}

@Composable
private fun ComparisonPhotoRow(
    photo: PhotoUiModel,
    selected: Boolean = false,
    onClick: () -> Unit,
    showSelection: Boolean = false,
    selectionLabel: String? = null
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = photo.uri,
                contentDescription = "对比照片",
                modifier = Modifier.size(76.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${photo.width} × ${photo.height} · ${formatBytes(photo.sizeBytes)}")
                Text(
                    photo.takenAtText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                selectionLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (showSelection) {
                RadioButton(selected = selected, onClick = onClick)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun similarityScore(score: Float): Int = (score.coerceIn(0f, 1f) * 100).toInt()

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusCard(message: String, isError: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isError) Icons.Default.Warning else Icons.Default.CheckCircle, contentDescription = null)
            Spacer(Modifier.size(10.dp))
            Text(message)
        }
    }
}

@Composable
fun OrganizeSummaryCard(
    categoriesCount: Int,
    duplicatesCount: Int,
    privacyAlertsCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("整理概览", style = MaterialTheme.typography.titleMedium)
            }
            Row(
                modifier = Modifier.padding(top = 18.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryItem(Icons.Default.Category, "相册建议", categoriesCount)
                SummaryItem(Icons.Default.CopyAll, "重复照片", duplicatesCount)
                SummaryItem(Icons.Default.Warning, "隐私提醒", privacyAlertsCount)
            }
        }
    }
}

@Composable
private fun SummaryItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(count.toString(), style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
