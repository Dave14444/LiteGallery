package org.iurl.litegallery

/**
 * Glide model for the thumbnail of a picture on an SMB share. Such a thumbnail is not read from
 * the picture itself but from the thumbnail cache on the share (see SmbThumbnailCache).
 */
object SmbThumbnailModel {

    /**
     * A new prefix makes Glide forget the thumbnails it keeps on the phone, e.g. after the
     * thumbnails on the share were made bigger ("2": 640 px instead of 384 px).
     */
    const val PREFIX = "smbthumb2://"

    fun of(url: String): String = PREFIX + url

    fun isModel(model: String): Boolean = model.startsWith(PREFIX)

    /** The picture's URL inside a thumbnail model. */
    fun urlOf(model: String): String = model.removePrefix(PREFIX)
}
