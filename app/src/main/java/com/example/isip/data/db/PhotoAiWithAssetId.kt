package com.example.isip.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded

/** Projection used to read each persisted analysis result with its MediaStore asset ID. */
data class PhotoAiWithAssetId(
    @Embedded val analysis: PhotoAiEntity,
    @ColumnInfo(name = "asset_id") val assetId: String
)
