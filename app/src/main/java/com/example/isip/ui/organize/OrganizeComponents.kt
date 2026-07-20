package com.example.isip.ui.organize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.isip.ui.model.CategorySuggestion
import com.example.isip.ui.model.DuplicateGroup
import com.example.isip.ui.model.PrivacyAlert
import com.example.isip.ui.model.PrivacySeverity

@Composable
fun CategorySuggestionCard(
    category: CategorySuggestion,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onToggleSelection,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(category.categoryName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${category.photoCount} 张照片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (category.description.isNotBlank()) {
                    Text(
                        category.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
            Checkbox(checked = isSelected, onCheckedChange = { onToggleSelection() })
        }
    }
}

@Composable
fun DuplicateGroupCard(
    group: DuplicateGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("发现 ${group.photos.size} 张相似照片", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "相似度 ${(group.similarityScore * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(Icons.Default.ChevronRight, contentDescription = "查看详情")
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                group.photos.take(4).forEach { photo ->
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = null,
                        modifier = Modifier.size(62.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

@Composable
fun PrivacyAlertCard(
    alert: PrivacyAlert,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val severityColor = when (alert.severity) {
        PrivacySeverity.HIGH -> MaterialTheme.colorScheme.error
        PrivacySeverity.MEDIUM -> MaterialTheme.colorScheme.tertiary
        PrivacySeverity.LOW -> MaterialTheme.colorScheme.secondary
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = severityColor.copy(alpha = 0.10f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                color = severityColor.copy(alpha = 0.16f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "隐私提醒",
                    modifier = Modifier.padding(9.dp),
                    tint = severityColor
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(alert.alertType, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (alert.severity) {
                            PrivacySeverity.HIGH -> "高风险"
                            PrivacySeverity.MEDIUM -> "中风险"
                            PrivacySeverity.LOW -> "低风险"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = severityColor
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(alert.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Text(
                    "拍摄于 ${alert.photo.takenAtText}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "查看详情")
        }
    }
}
