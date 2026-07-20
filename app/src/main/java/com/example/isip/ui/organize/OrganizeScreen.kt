package com.example.isip.ui.organize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.isip.ui.common.EmptyState
import com.example.isip.ui.common.ErrorState
import com.example.isip.ui.common.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeScreen(
    onPhotoClick: (String) -> Unit,
    onDuplicateGroupClick: (String) -> Unit,
    onPrivacyAlertClick: (String) -> Unit,
    viewModel: OrganizeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("整理") },
                actions = {
                    IconButton(
                        onClick = { viewModel.onEvent(OrganizeUiEvent.GeneratePlan) },
                        enabled = !uiState.isGeneratingPlan && !uiState.isExecutingCleanup
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新生成")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isGeneratingPlan -> LoadingState("正在分析相册并生成建议…")
                uiState.isExecutingCleanup -> LoadingState("正在执行清理…")
                uiState.errorMessage != null -> ErrorState(
                    message = uiState.errorMessage ?: "生成整理建议失败",
                    onRetry = { viewModel.onEvent(OrganizeUiEvent.GeneratePlan) }
                )
                uiState.plan == null -> EmptyState(
                    icon = Icons.Default.AutoAwesome,
                    title = "还没有整理建议",
                    description = "完成照片分析后，这里会展示智能相册、重复照片和隐私提醒。"
                )
                else -> {
                    val plan = uiState.plan!!
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

                        if (plan.categorySuggestions.isNotEmpty()) {
                            item { SectionTitle("智能相册建议", "选择后可批量创建相册") }
                            items(plan.categorySuggestions, key = { it.id }) { category ->
                                CategorySuggestionCard(
                                    category = category,
                                    isSelected = category.id in uiState.selectedSuggestionIds,
                                    onToggleSelection = {
                                        viewModel.onEvent(OrganizeUiEvent.ToggleSuggestion(category.id))
                                    }
                                )
                            }
                        }

                        if (plan.duplicateGroups.isNotEmpty()) {
                            item { SectionTitle("重复照片", "检查后再决定是否清理") }
                            items(plan.duplicateGroups, key = { it.id }) { group ->
                                DuplicateGroupCard(group = group, onClick = { onDuplicateGroupClick(group.id) })
                            }
                        }

                        if (plan.privacyAlerts.isNotEmpty()) {
                            item { SectionTitle("隐私提醒", "敏感内容不会自动删除") }
                            items(plan.privacyAlerts, key = { it.id }) { alert ->
                                PrivacyAlertCard(alert = alert, onClick = { onPrivacyAlertClick(alert.id) })
                            }
                        }

                        if (uiState.selectedSuggestionIds.isNotEmpty()) {
                            item {
                                androidx.compose.material3.Button(
                                    onClick = { viewModel.onEvent(OrganizeUiEvent.ExecuteSelected) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                                    Text("创建已选相册（${uiState.selectedSuggestionIds.size}）")
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
private fun SectionTitle(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
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
private fun SummaryItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(count.toString(), style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
