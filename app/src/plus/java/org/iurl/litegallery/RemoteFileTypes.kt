package org.iurl.litegallery

/** Media file classification shared by every remote share protocol. */
object RemoteFileTypes {

    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    val VIDEO_EXTENSIONS = setOf("mp4", "avi", "mov", "mkv", "3gp", "webm", "m4v", "flv")

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isImage(name: String): Boolean = IMAGE_EXTENSIONS.contains(extensionOf(name))

    fun isVideo(name: String): Boolean = VIDEO_EXTENSIONS.contains(extensionOf(name))

    fun isMedia(name: String): Boolean = isImage(name) || isVideo(name)

    fun mimeType(name: String): String {
        val ext = extensionOf(name)
        return if (isVideo(name)) {
            when (ext) {
                "mp4" -> "video/mp4"
                "avi" -> "video/x-msvideo"
                "mov" -> "video/quicktime"
                "mkv" -> "video/x-matroska"
                "3gp" -> "video/3gpp"
                "webm" -> "video/webm"
                "m4v" -> "video/mp4"
                "flv" -> "video/x-flv"
                else -> "video/*"
            }
        } else {
            when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "heic" -> "image/heic"
                "heif" -> "image/heif"
                else -> "image/*"
            }
        }
    }
}
