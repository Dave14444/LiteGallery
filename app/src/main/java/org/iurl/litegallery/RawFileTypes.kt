package org.iurl.litegallery

/** Camera RAW files, told apart by their extension. */
object RawFileTypes {

    val EXTENSIONS = setOf(
        "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "dng",
        "orf", "rw2", "raf", "pef", "srw", "3fr", "erf", "kdc", "mrw", "x3f"
    )

    fun isRaw(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS
}
