package org.iurl.litegallery

/** Camera RAW files, told apart by their extension. */
object RawFileTypes {

    val EXTENSIONS = setOf(
        "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "dng",
        "orf", "rw2", "raf", "pef", "srw", "3fr", "erf", "kdc", "mrw", "x3f"
    )

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isRaw(name: String): Boolean = extensionOf(name) in EXTENSIONS

    /** The type other apps (e.g. Snapseed) know the file by. */
    fun mimeType(name: String): String = when (extensionOf(name)) {
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
}
