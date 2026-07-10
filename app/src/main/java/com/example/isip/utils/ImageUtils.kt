package com.example.photoagent.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/**
 * 图片处理工具类
 */
object ImageUtils {

    /**
     * 加载图片并压缩到目标尺寸
     * @param filePath 图片文件路径
     * @param targetWidth 目标宽度
     * @param targetHeight 目标高度
     * @return 压缩后的Bitmap
     */
    fun loadAndResizeImage(filePath: String, targetWidth: Int = 448, targetHeight: Int = 448): Bitmap? {
        val file = File(filePath)
        if (!file.exists()) return null

        // 先获取图片尺寸（不加载到内存）
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, options)

        // 计算采样率
        val sampleSize = calculateSampleSize(options, targetWidth, targetHeight)

        // 加载压缩后的图片
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565  // 降低内存占用
        }
        var bitmap = BitmapFactory.decodeFile(filePath, decodeOptions)

        // 修正旋转
        bitmap = rotateImageIfNeeded(bitmap, filePath)

        return bitmap
    }

    /**
     * 计算采样率
     */
    private fun calculateSampleSize(options: BitmapFactory.Options, targetWidth: Int, targetHeight: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > targetHeight || width > targetWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= targetHeight && halfWidth / inSampleSize >= targetWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 根据EXIF信息旋转图片
     */
    private fun rotateImageIfNeeded(bitmap: Bitmap, filePath: String): Bitmap {
        try {
            val exif = ExifInterface(filePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }

            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            return bitmap
        }
    }

    /**
     * Bitmap转Base64
     */
    fun bitmapToBase64(bitmap: Bitmap): String {
        import android.util.Base64
        import java.io.ByteArrayOutputStream

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}