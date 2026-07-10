package com.example.isip.ui.organize

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.isip.ui.common.EmptyState
import com.example.isip.ui.common.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeScreen(
    onPhotoClick: (String) -> Unit,
    onDuplicateGroupClick: (String) -> Unit,
    onPrivacyAlertClick: (String) -> Unit,
    viewModel: OrganizeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("整理") },
                actions = {
                    if (uiState.plan != null && uiState.selectedSuggestionIds.isNotEmpty()) {
                        TextButton(onClick = { viewModel.onEvent(OrganizeUiEvent.ExecuteSelected) }) {
                            Text("执行整理")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when {
                uiState.isGeneratingPlan -> {
                    LoadingState(message = "生成整理方案中...")
                }

                uiState.plan == null -> {
                    EmptyState(
                        icon = Icons.Default.FolderSpecial,
                        title = "暂无整理建议",
                        description = "请先在相册页面完成照片分析",
                        actionLabel = "开始分析",
                        onAction = { /* TODO: Navigate to gallery and start analysis */ }
                    )
                }

                else -> {
                    val plan = uiState.plan!!
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Summary card
                        item {
                            OrganizeSummaryCard(
                                categoriesCount = plan.categorySuggestions.size,
                                duplicatesCount = plan.duplicateGroups.size,
                                privacyAlertsCount = plan.privacyAlerts.size
                            )
                        }

                        // Category suggestions
                        if (plan.categorySuggestions.isNotEmpty()) {
                            item {
                                Text(
                                    text = "分类建议",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            items(plan.categorySuggestions) { category ->
                                CategorySuggestionCard(
                                    category = category,
                                    isSelected = category.id in uiState.selectedSuggestionIds,
                                    onToggleSelection = {
                                        viewModel.onEvent(OrganizeUiEvent.ToggleSuggestion(category.id))
                                    }
                                )
                            }
                        }

                        // Duplicate groups
                        if (plan.duplicateGroups.isNotEmpty()) {
                            item {
                                Text(
                                    text = "重复照片",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            items(plan.duplicateGroups) { group ->
                                DuplicateGroupCard(
                                    group = group,
                                    onClick = { onDuplicateGroupClick(group.id) }
                                )
                            }
                        }

                        // Privacy alerts
                        if (plan.privacyAlerts.isNotEmpty()) {
                            item {
                                Text(
                                    text = "隐私提醒",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            items(plan.privacyAlerts) { alert ->
                                PrivacyAlertCard(
                                    alert = alert,
                                    onClick = { onPrivacyAlertClick(alert.id) }
                                )
                            }
                        }
                    }
                }
            }
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
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "整理概览",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SummaryItem(
                    icon = Icons.Default.Category,
                    label = "分类建议",
                    count = categoriesCount
                )
                SummaryItem(
                    icon = Icons.Default.CopyAll,
                    label = "重复照片",
                    count = duplicatesCount
                )
                SummaryItem(
                    icon = Icons.Default.Warning,
                    label = "隐私提醒",
                    count = privacyAlertsCount
                )
            }
        }
    }
}

@Composable
private fun SummaryItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
