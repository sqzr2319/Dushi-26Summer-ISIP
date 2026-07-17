package com.example.isip.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded

data class ManualTagWithAssetId(
    @Embedded val tag: ManualTagEntity,
    @ColumnInfo(name = "asset_id") val assetId: String
)
