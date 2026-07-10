package com.example.isip.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") })
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Permissions section
            item {
                SettingsSectionHeader("权限管理")
            }
            item {
                SettingRow(
                    icon = Icons.Default.PhotoLibrary,
                    title = "相册访问权限",
                    description = if (uiState.hasMediaPermission) "已授权" else "未授权",
                    onClick = { viewModel.onEvent(SettingsUiEvent.RequestMediaPermission) }
                )
            }
            item {
                SettingRow(
                    icon = Icons.Default.Mic,
                    title = "麦克风权限",
                    description = if (uiState.hasMicPermission) "已授权" else "未授权",
                    onClick = { viewModel.onEvent(SettingsUiEvent.RequestMicPermission) }
                )
            }

            // Model settings section
            item {
                SettingsSectionHeader("AI 模型设置")
            }
            item {
                SettingRow(
                    icon = Icons.Default.Memory,
                    title = "推理模式",
                    description = uiState.inferenceMode,
                    onClick = { viewModel.onEvent(SettingsUiEvent.ChangeInferenceMode) }
                )
            }
            item {
                SettingRow(
                    icon = Icons.Default.Info,
                    title = "模型状态",
                    description = if (uiState.modelLoaded) "已加载" else "未加载"
                )
            }

            // Privacy section
            item {
                SettingsSectionHeader("隐私控制")
            }
            item {
                SwitchSettingRow(
                    icon = Icons.Default.Cloud,
                    title = "允许云端降级",
                    description = "本地模型失败时使用云端 API",
                    checked = uiState.allowCloudFallback,
                    onCheckedChange = { viewModel.onEvent(SettingsUiEvent.ToggleCloudFallback) }
                )
            }
            item {
                SwitchSettingRow(
                    icon = Icons.Default.Shield,
                    title = "敏感照片不上传",
                    description = "隐私照片不会发送到云端",
                    checked = uiState.protectSensitivePhotos,
                    onCheckedChange = { viewModel.onEvent(SettingsUiEvent.ToggleSensitiveProtection) }
                )
            }

            // Storage section
            item {
                SettingsSectionHeader("存储管理")
            }
            item {
                SettingRow(
                    icon = Icons.Default.Storage,
                    title = "分析缓存",
                    description = uiState.cacheSize,
                    onClick = { viewModel.onEvent(SettingsUiEvent.ClearCache) }
                )
            }
            item {
                SettingRow(
                    icon = Icons.Default.DeleteSweep,
                    title = "清除所有分析结果",
                    description = "不会删除照片本身",
                    onClick = { viewModel.onEvent(SettingsUiEvent.ClearAllAnalysis) }
                )
            }

            // About section
            item {
                SettingsSectionHeader("关于")
            }
            item {
                SettingRow(
                    icon = Icons.Default.Info,
                    title = "版本信息",
                    description = uiState.versionName
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick ?: {}
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SwitchSettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
