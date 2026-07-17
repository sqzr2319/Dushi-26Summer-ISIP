package com.example.isip.data.media

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import com.example.isip.data.PhotoRepository
import com.example.isip.domain.skill.DeletePhotoSkill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Creates Android's system-owned confirmation UI for the exact requested photo IDs. */
class MediaStoreDeleteConfirmation(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    fun createIntentSender(photoIds: List<String>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = photoIds.distinct().mapNotNull { id ->
            id.toLongOrNull()?.let {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
            }
        }
        require(uris.size == photoIds.distinct().size) { "包含无效的 MediaStore photoId" }
        return MediaStore.createDeleteRequest(resolver, uris).intentSender
    }
}

/**
 * Performs the delete after app-level and system-level confirmation have completed.
 * A missing platform grant is reported as failure; it is never bypassed.
 */
class MediaStorePhotoDeletionGateway(
    context: Context,
    private val repository: PhotoRepository
) : DeletePhotoSkill.ConfirmedPhotoDeletionGateway {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun deleteAfterUserConfirmation(
        photoIds: List<String>
    ): DeletePhotoSkill.DeleteResult = withContext(Dispatchers.IO) {
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        photoIds.distinct().forEach { id ->
            val numericId = id.toLongOrNull()
            if (numericId == null) {
                failed += id
                return@forEach
            }
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, numericId)
            try {
                if (resolver.delete(uri, null, null) > 0) deleted += id else failed += id
            } catch (_: RecoverableSecurityException) {
                failed += id
            } catch (_: SecurityException) {
                failed += id
            }
        }
        repository.removeDeletedPhotoRecords(deleted)
        DeletePhotoSkill.DeleteResult(
            requestedIds = photoIds,
            deletedIds = deleted,
            failedIds = failed
        )
    }

    /** Android 11+ createDeleteRequest performs deletion in the system UI. */
    override suspend fun recordSystemDeletion(
        photoIds: List<String>
    ): DeletePhotoSkill.DeleteResult {
        val ids = photoIds.filter(String::isNotBlank).distinct()
        repository.removeDeletedPhotoRecords(ids)
        return DeletePhotoSkill.DeleteResult(
            requestedIds = ids,
            deletedIds = ids,
            failedIds = emptyList()
        )
    }
}
