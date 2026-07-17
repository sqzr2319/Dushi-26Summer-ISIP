package com.example.isip.data

import android.content.Context
import android.provider.MediaStore
import com.example.isip.data.model.VideoAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only MediaStore source used by [com.example.isip.domain.skill.FindDuplicateVideosSkill]. */
class VideoRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun getAllVideos(): List<VideoAsset> = withContext(Dispatchers.IO) {
        val result = mutableListOf<VideoAsset>()
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            PROJECTION,
            null,
            null,
            "${MediaStore.Video.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val width = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val height = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val taken = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            while (cursor.moveToNext()) {
                val assetId = cursor.getLong(id).toString()
                result += VideoAsset(
                    id = assetId,
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon()
                        .appendPath(assetId).build().toString(),
                    displayName = cursor.getString(name).orEmpty(),
                    sizeBytes = cursor.getLong(size),
                    durationMs = cursor.getLong(duration),
                    width = cursor.getInt(width),
                    height = cursor.getInt(height),
                    dateTaken = cursor.getLong(taken),
                    contentHash = null
                )
            }
        }
        result
    }

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_TAKEN
        )
    }
}
