package expo.modules.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/** Reads app-owned audio off the JS thread; never edits the original audio. */
object AuralisLocalMetadata {
  private fun owned(context: Context, uri: String): File {
    val parsed = Uri.parse(uri)
    require(parsed.scheme == "file") { "Only local application files are accepted" }
    val file = File(requireNotNull(parsed.path)).canonicalFile
    require(listOf(context.filesDir, context.cacheDir).any { file.path.startsWith(it.canonicalPath + File.separator) }) { "File outside application storage" }
    return file
  }
  fun read(context: Context, uri: String, coverUri: String): Map<String, Any> {
    val source = owned(context, uri)
    val cover = owned(context, coverUri)
    val result = mutableMapOf<String, Any>()
    val reader = MediaMetadataRetriever()
    try {
      reader.setDataSource(source.path)
      for ((name, key) in listOf("title" to MediaMetadataRetriever.METADATA_KEY_TITLE, "artist" to MediaMetadataRetriever.METADATA_KEY_ARTIST, "album" to MediaMetadataRetriever.METADATA_KEY_ALBUM, "genre" to MediaMetadataRetriever.METADATA_KEY_GENRE)) {
        reader.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { result[name] = it.take(1000) }
      }
      reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.let { if (it > 0 && it.isFinite()) result["durationSeconds"] = it / 1000 }
      // A bad image must not discard valid text metadata or fail the import.
      try {
        reader.embeddedPicture?.takeIf { it.size <= 16 * 1024 * 1024 }?.let { bytes ->
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
          if (bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) {
            val options = BitmapFactory.Options()
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 1200) options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { bitmap ->
              val factor = minOf(1.0, 600.0 / maxOf(bitmap.width, bitmap.height))
              val thumbnail = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * factor).toInt()), maxOf(1, (bitmap.height * factor).toInt()), true)
              try { cover.parentFile?.mkdirs(); cover.outputStream().use { check(thumbnail.compress(Bitmap.CompressFormat.PNG, 100, it)) }; result["coverUrl"] = Uri.fromFile(cover).toString() }
              finally { if (thumbnail !== bitmap) thumbnail.recycle(); bitmap.recycle() }
            }
          }
        }
      } catch (_: Exception) { cover.delete() }
    } finally { reader.release() }
    return result
  }
}
