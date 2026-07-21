package com.example.isip.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.isip.ui.model.SmartAlbumUiModel

@Composable
fun SmartAlbumSection(
    albums: List<SmartAlbumUiModel>,
    onAlbumClick: (Long) -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("智能相册", style = MaterialTheme.typography.titleMedium)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (albums.isEmpty()) {
                item("create-smart-album") { EmptySmartAlbumCard(onCreateClick) }
            } else {
                items(albums, key = { "gallery-album:${it.id}" }) { album ->
                    SmartAlbumCard(album = album, onClick = { onAlbumClick(album.id) })
                }
            }
        }
    }
}

@Composable
private fun SmartAlbumCard(album: SmartAlbumUiModel, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(196.dp).height(142.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        AlbumCover(album, Modifier.fillMaxWidth().height(86.dp))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(album.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${album.photoCount} 张 · ${album.ruleDescription}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AlbumCover(album: SmartAlbumUiModel, modifier: Modifier = Modifier) {
    val covers = album.photos.take(2)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (covers.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            covers.forEach { photo ->
                AsyncImage(
                    model = photo.uri,
                    contentDescription = album.name,
                    modifier = Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.extraSmall),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@Composable
private fun EmptySmartAlbumCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(320.dp).height(92.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Column {
                Text("创建智能相册", style = MaterialTheme.typography.titleSmall)
                Text("前往整理页选择分类并确认创建", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
