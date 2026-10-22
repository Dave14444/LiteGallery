package org.iurl.litegallery

/** Media file classification shared by every remote share protocol. */
object RemoteFileTypes {

    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
    val VIDEO_EXTENSIONS = setOf("mp4", "avi", "mov", "mkv", "3gp", "webm", "m4v", "flv")

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isImage(name: String): Boolean = IMAGE_EXTENSIONS.contains(extensionOf(name))

    fun isVideo(name: String): Boolean = VIDEO_EXTENSIONS.contains(extensionOf(name))

    fun isMedia(name: String): Boolean = isImage(name) || isVideo(name)

    /** Camera RAW files: not shown as pictures, but they can be opened in another app. */
    val RAW_EXTENSIONS = setOf(
        "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "dng",
        "orf", "rw2", "raf", "pef", "srw", "3fr", "erf", "kdc", "mrw", "x3f"
    )

    fun isRaw(name: String): Boolean = RAW_EXTENSIONS.contains(extensionOf(name))

    fun rawMimeType(name: String): String = when (extensionOf(name)) {
        "cr2" -> "image/x-canon-cr2"
        "cr3" -> "image/x-canon-cr3"
        "crw" -> "image/x-canon-crw"
        "nef" -> "image/x-nikon-nef"
        "nrw" -> "image/x-nikon-nrw"
        "arw" -> "image/x-sony-arw"
        "dng" -> "image/x-adobe-dng"
        "orf" -> "image/x-olympus-orf"
        "rw2" -> "image/x-panasonic-rw2"
        "raf" -> "image/x-fuji-raf"
        "pef" -> "image/x-pentax-pef"
        "srw" -> "image/x-samsung-srw"
        else -> "image/*"
    }

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
