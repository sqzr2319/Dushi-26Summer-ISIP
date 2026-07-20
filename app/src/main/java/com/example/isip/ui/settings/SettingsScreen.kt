package com.example.isip.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.isip.data.InferenceMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var showModeDialog by remember { mutableStateOf(false) }
    var clearAction by remember { mutableStateOf<ClearAction?>(null) }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.onEvent(SettingsUiEvent.MediaPermissionResult(result.values.any { it }))
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onEvent(SettingsUiEvent.MicPermissionResult(granted))
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(SettingsUiEvent.DismissMessage)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onEvent(SettingsUiEvent.Refresh)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.padding(paddingValues),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item { SettingsSectionHeader("权限管理") }
            item {
                SettingRow(
                    icon = Icons.Default.PhotoLibrary,
                    title = "相册访问权限",
                    description = if (uiState.hasMediaPermission) "已授权" else "未授权，点击申请",
                    onClick = {
                        mediaPermissionLauncher.launch(mediaPermissions())
                    }
                )
            }
            item {
                SettingRow(
                    icon = Icons.Default.Mic,
                    title = "麦克风权限",
                    description = if (uiState.hasMicPermission) "已授权" else "未授权，点击申请",
                    onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                )
            }

            item { SettingsSectionHeader("AI 模型") }
            item {
                SettingRow(
                    icon = Icons.Default.Memory,
                    title = "推理模式",
                    description = uiState.inferenceMode.title,
                    onClick = { showModeDialog = true }
                )
            }
            item {
                SettingRow(
                    icon = Icons.Default.Info,
                    title = "模型状态",
                    description = uiState.modelStatus
                )
            }

            item { SettingsSectionHeader("隐私控制") }
            item {
                SwitchSettingRow(
                    icon = Icons.Default.Cloud,
                    title = "允许云端兜底",
                    description = "当前版本暂无云端服务，设置会保留",
                    checked = uiState.allowCloudFallback,
                    onCheckedChange = { viewModel.onEvent(SettingsUiEvent.SetCloudFallback(it)) }
                )
            }
            item {
                SwitchSettingRow(
                    icon = Icons.Default.Shield,
                    title = "敏感照片保护",
                    description = "分析时保留隐私保护策略",
                    checked = uiState.protectSensitivePhotos,
                    onCheckedChange = { viewModel.onEvent(SettingsUiEvent.SetSensitiveProtection(it)) }
                )
            }

            item { SettingsSectionHeader("存储管理") }
            item {
                SettingRow(
                    icon = Icons.Default.Storage,
                    title = "分析缓存",
                    description = if (uiState.isBusy) "清理中…" else uiState.cacheSize,
                    onClick = { if (!uiState.isBusy) clearAction = ClearAction.Cache }
                )
            }
            item {
                SettingRow(
                    icon = Icons.Default.DeleteSweep,
                    title = "清除所有分析结果",
                    description = "不会删除原始照片",
                    onClick = { if (!uiState.isBusy) clearAction = ClearAction.Analysis }
                )
            }

            item { SettingsSectionHeader("关于") }
            item {
                SettingRow(
                    icon = Icons.Default.Info,
                    title = "版本信息",
                    description = uiState.versionName
                )
            }
        }
    }

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("选择推理模式") },
            text = {
                Column {
                    InferenceMode.values().forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.onEvent(SettingsUiEvent.ChangeInferenceMode(mode))
                                    showModeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.inferenceMode == mode,
                                onClick = {
                                    viewModel.onEvent(SettingsUiEvent.ChangeInferenceMode(mode))
                                    showModeDialog = false
                                }
                            )
                            Text(mode.title, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) { Text("取消") }
            }
        )
    }

    clearAction?.let { action ->
        val title = if (action == ClearAction.Cache) "清理分析缓存" else "清除分析结果"
        AlertDialog(
            onDismissRequest = { clearAction = null },
            title = { Text(title) },
            text = { Text("只会删除本地分析数据，不会删除相册中的原始照片。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onEvent(
                        if (action == ClearAction.Cache) SettingsUiEvent.ClearCache
                        else SettingsUiEvent.ClearAllAnalysis
                    )
                    clearAction = null
                }) { Text("确认清理") }
            },
            dismissButton = {
                TextButton(onClick = { clearAction = null }) { Text("取消") }
            }
        )
    }
}

private enum class ClearAction { Cache, Analysis }

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onClick != null) {
            Icon(Icons.Default.ChevronRight, contentDescription = "打开", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
