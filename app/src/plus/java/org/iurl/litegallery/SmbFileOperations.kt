package org.iurl.litegallery

import android.content.Context

/**
 * Delete, copy and move for files on SMB shares. All functions perform blocking
 * network I/O: call them from Dispatchers.IO.
 */
object SmbFileOperations {

    enum class Mode { COPY, MOVE }

    data class Result(val done: Int, val failed: Int)

    fun delete(
        context: Context,
        urls: List<String>,
        onProgress: (done: Int, total: Int) -> Unit
    ): Result {
        var done = 0
        var failed = 0
        urls.forEachIndexed { index, url ->
            val src = SmbPath.parse(url)
            val ok = src != null && SmbClient.deleteFile(context, src.host, src.share, src.path)
            if (ok) done++ else failed++
            onProgress(index + 1, urls.size)
        }
        return Result(done, failed)
    }

    fun transfer(
        context: Context,
        urls: List<String>,
        destFolderUrl: String,
        mode: Mode,
        onProgress: (done: Int, total: Int) -> Unit
    ): Result {
        val dest = SmbPath.parse(destFolderUrl) ?: return Result(0, urls.size)
        var done = 0
        var failed = 0

        urls.forEachIndexed { index, url ->
            val src = SmbPath.parse(url)
            val ok = if (src == null) {
                false
            } else if (mode == Mode.MOVE && isSameFolder(src, dest)) {
                true // already in the destination folder, nothing to do
            } else {
                val targetPath = uniqueTargetPath(context, dest, src.fileName)
                if (targetPath == null) {
                    false
                } else if (mode == Mode.COPY) {
                    copy(context, src, dest, targetPath)
                } else {
                    move(context, src, dest, targetPath)
                }
            }
            if (ok) done++ else failed++
            onProgress(index + 1, urls.size)
        }
        return Result(done, failed)
    }

    data class UploadResult(val copiedPaths: List<String>, val failed: Int)

    /** Copies local files (file paths or content:// URIs) into [destFolderUrl]. */
    fun upload(
        context: Context,
        items: List<MediaItemSkeleton>,
        destFolderUrl: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): UploadResult {
        val dest = SmbPath.parse(destFolderUrl) ?: return UploadResult(emptyList(), items.size)
        val copied = mutableListOf<String>()
        var failed = 0

        items.forEachIndexed { index, item ->
            val targetPath = uniqueTargetPath(context, dest, item.name)
            var ok = false
            if (targetPath != null) {
                try {
                    openLocalStream(context, item.path).use { input ->
                        ok = SmbClient.writeStream(context, dest.host, dest.share, targetPath, input)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SmbFileOperations", "Upload failed: ${item.path}", e)
                }
            }
            if (ok) copied.add(item.path) else failed++
            onProgress(index + 1, items.size)
        }
        return UploadResult(copied, failed)
    }

    private fun openLocalStream(context: Context, path: String): java.io.InputStream =
        if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(android.net.Uri.parse(path))
                ?: throw java.io.IOException("Cannot open $path")
        } else {
            java.io.FileInputStream(path)
        }

    private fun isSameShare(a: SmbPath, b: SmbPath): Boolean =
        a.host.equals(b.host, ignoreCase = true) && a.share.equals(b.share, ignoreCase = true)

    private fun isSameFolder(src: SmbPath, destFolder: SmbPath): Boolean =
        isSameShare(src, destFolder) && src.parentPath == destFolder.path

    private fun copy(context: Context, src: SmbPath, dest: SmbPath, targetPath: String): Boolean =
        SmbClient.copyFile(
            context,
            src.host, src.share, src.path,
            dest.host, dest.share, targetPath
        )

    private fun move(context: Context, src: SmbPath, dest: SmbPath, targetPath: String): Boolean {
        // Same share: a rename is instant and needs no data transfer.
        if (isSameShare(src, dest) &&
            SmbClient.rename(context, src.host, src.share, src.path, targetPath)
        ) {
            return true
        }
        // Different share/host (or rename not possible): copy, then delete the original.
        if (!copy(context, src, dest, targetPath)) return false
        return SmbClient.deleteFile(context, src.host, src.share, src.path)
    }

    /** Destination path for [fileName] in [dest]; adds " (1)", " (2)", ... if the name is taken. */
    private fun uniqueTargetPath(context: Context, dest: SmbPath, fileName: String): String? {
        fun pathFor(name: String) = if (dest.path.isEmpty()) name else "${dest.path}/$name"

        if (!SmbClient.fileExists(context, dest.host, dest.share, pathFor(fileName))) {
            return pathFor(fileName)
        }
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        for (i in 1..999) {
            val name = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            if (!SmbClient.fileExists(context, dest.host, dest.share, pathFor(name))) {
                return pathFor(name)
            }
        }
        return null
    }
}
