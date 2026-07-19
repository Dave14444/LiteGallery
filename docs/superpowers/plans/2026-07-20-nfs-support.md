# Plus Edition NFS Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add NFSv3 network-share support to the Plus edition with full feature parity with SMB (browse, thumbnails, video streaming with seek, rename, server bookmarks), behind a single merged "Network Shares" home entry.

**Architecture:** Extract a protocol-agnostic `RemoteFileClient` layer inside the `plus` flavor so SMB and NFS share the browse UI, Glide loader, Media3 DataSource, scanner, and config store. SMB keeps its existing `SmbClient` internals behind a thin `SmbFileClient` adapter; NFS is implemented by `NfsFileClient` on top of the EMC nfs-client library. Spec: `docs/superpowers/specs/2026-07-20-nfs-support-design.md`.

**Tech Stack:** Kotlin (JVM target 1.8), Android minSdk 24, smbj 0.14.0 (existing), `com.emc.ecs:nfs-client:1.1.0` (new, pure-Java NFSv3, Apache-2.0), Glide 4.15.1, Media3 1.2.0, JUnit4 + Robolectric 4.12.2.

## Global Constraints

- All new/changed production code lives in `app/src/plus/` — never touch `app/src/core/` or `app/src/main/` (the `MediaSource` seam in main stays exactly as is).
- The NFS dependency is `plusImplementation 'com.emc.ecs:nfs-client:1.1.0'` — never plain `implementation`.
- Saved-server storage keeps SharedPreferences file name `smb_servers` and key `servers_json`. Legacy JSON entries without a `protocol` field must parse as SMB.
- NFS URL format: `nfs://host/volume1/photo/sub/file.jpg` (export path and inner path joined). Export boundary is resolved by longest-prefix match against saved configs.
- NFS auth: AUTH_UNIX via `CredentialUnix(uid, gid, emptySet())`; `usePrivilegedPorts` is never enabled (Android cannot bind ports < 1024).
- Kotlin `jvmTarget 1.8` — no Java 9+ APIs.
- Unit tests: run `.\gradlew.bat testPlusDebugUnitTest` (PowerShell, repo root). Full check: `.\gradlew.bat testCoreDebugUnitTest testPlusDebugUnitTest`.
- Commits: Conventional Commit format, simple English, detailed body, **no `Co-Authored-By` trailer**.
- After every task the SMB feature set must still work and the full unit-test suite must be green.

---

## File Map (final state)

| File | Responsibility |
| --- | --- |
| `app/src/plus/java/org/iurl/litegallery/NetworkServerConfig.kt` | Saved-server model, SMB+NFS fields, protocol constants (renamed from `SmbConfig.kt`) |
| `app/src/plus/java/org/iurl/litegallery/NetworkConfigStore.kt` | SharedPreferences persistence (renamed from `SmbConfigStore.kt`) |
| `app/src/plus/java/org/iurl/litegallery/RemoteFileClient.kt` | `RemoteFileClient` + `RemoteRandomAccessFile` + `RemoteFileInfo` + `RemoteClients` router + `RemotePath` |
| `app/src/plus/java/org/iurl/litegallery/RemoteFileTypes.kt` | Shared media extension / MIME classification |
| `app/src/plus/java/org/iurl/litegallery/SmbFileClient.kt` | SMB adapter (delegates to unchanged `SmbClient`) |
| `app/src/plus/java/org/iurl/litegallery/SmbClient.kt` | Unchanged smbj wrapper (only config type + extension-set references updated) |
| `app/src/plus/java/org/iurl/litegallery/SmbPath.kt` | Unchanged |
| `app/src/plus/java/org/iurl/litegallery/NfsPath.kt` | nfs:// URL parse/build + export prefix matching |
| `app/src/plus/java/org/iurl/litegallery/NfsExportLister.kt` | `showmount -e` equivalent (minimal ONC RPC over TCP) |
| `app/src/plus/java/org/iurl/litegallery/NfsFileClient.kt` | NFS `RemoteFileClient` over EMC nfs-client |
| `app/src/plus/java/org/iurl/litegallery/RemoteModelLoader.kt` | Glide loader for smb:// + nfs:// images (renamed from `SmbModelLoader.kt`) |
| `app/src/plus/java/org/iurl/litegallery/RemoteDataSource.kt` | Media3 DataSource over `RemoteRandomAccessFile` (renamed from `SmbDataSource.kt`) |
| `app/src/plus/java/org/iurl/litegallery/RemoteMediaScanner.kt` | Folder scanning over `RemoteClients` (renamed from `SmbMediaScanner.kt`) |
| `app/src/plus/java/org/iurl/litegallery/NetworkMediaSource.kt` | Single `MediaSource` for both schemes (renamed from `SmbMediaSource.kt`) |
| `app/src/plus/java/org/iurl/litegallery/NetworkBrowseActivity.kt` | Shared browse UI (renamed from `SmbBrowseActivity.kt`) |
| `app/src/plus/res/layout/activity_network_browse.xml`, `item_network_browse.xml`, `item_network_server.xml`, `dialog_network_connect.xml` | Renamed layouts |
| `app/proguard-network.pro` | Renamed from `proguard-smb.pro`, plus EMC keep rules |

---

### Task 1: Rename config to `NetworkServerConfig` / `NetworkConfigStore` and add protocol fields

**Files:**
- Rename: `app/src/plus/java/org/iurl/litegallery/SmbConfig.kt` → `NetworkServerConfig.kt`
- Rename: `app/src/plus/java/org/iurl/litegallery/SmbConfigStore.kt` → `NetworkConfigStore.kt`
- Modify: `app/src/plus/java/org/iurl/litegallery/SmbClient.kt` (type references only)
- Modify: `app/src/plus/java/org/iurl/litegallery/SmbBrowseActivity.kt` (type references only)
- Modify: `app/src/plus/java/org/iurl/litegallery/SmbMediaSource.kt` (type references only)
- Rename test: `app/src/testPlus/java/org/iurl/litegallery/SmbConfigStoreTest.kt` → `NetworkConfigStoreTest.kt`

**Interfaces:**
- Produces: `NetworkServerConfig(id, displayName, host, protocol, share, path, port, username, password, isGuest, export, uid, gid)` with `isAuthenticated: Boolean`, `isNfs: Boolean`, `rootUrl(): String`, constants `NetworkServerConfig.PROTOCOL_SMB = "smb"`, `NetworkServerConfig.PROTOCOL_NFS = "nfs"`. `NetworkConfigStore` with the same API as the old `SmbConfigStore` (`getAllServers`, `getServerByHost`, `getServerById`, `addServer`, `updateServer`, `deleteServer`).

- [ ] **Step 1: Write the failing tests** — rename `SmbConfigStoreTest.kt` to `NetworkConfigStoreTest.kt` (use `git mv`), rename the class inside to `NetworkConfigStoreTest`, replace every `SmbConfigStore` → `NetworkConfigStore` and `SmbConfig` → `NetworkServerConfig`, then add these tests to the class:

```kotlin
    @Test
    fun getAllServers_parsesLegacyEntriesWithoutProtocolAsSmb() {
        context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "servers_json",
                """[{"id":"legacy","displayName":"NAS","host":"nas.local","share":"Photos",""" +
                    """"path":"","port":445,"username":"u","password":"p","isGuest":false}]"""
            )
            .commit()

        val servers = NetworkConfigStore.getAllServers(context)

        assertEquals(1, servers.size)
        assertEquals(NetworkServerConfig.PROTOCOL_SMB, servers[0].protocol)
        assertEquals("", servers[0].export)
        assertEquals(0, servers[0].uid)
        assertEquals(0, servers[0].gid)
    }

    @Test
    fun addServer_roundTripsNfsFields() {
        val saved = NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(
                id = "",
                displayName = "NAS NFS",
                host = "nas.local",
                protocol = NetworkServerConfig.PROTOCOL_NFS,
                export = "/volume1/photo",
                uid = 1000,
                gid = 100
            )
        )

        val loaded = NetworkConfigStore.getServerById(context, saved.id)

        assertEquals(NetworkServerConfig.PROTOCOL_NFS, loaded?.protocol)
        assertEquals("/volume1/photo", loaded?.export)
        assertEquals(1000, loaded?.uid)
        assertEquals(100, loaded?.gid)
        assertEquals(true, loaded?.isNfs)
    }

    @Test
    fun rootUrl_buildsProtocolSpecificRoot() {
        assertEquals(
            "nfs://nas.local/volume1/photo",
            NetworkServerConfig(
                id = "a", displayName = "", host = "nas.local",
                protocol = NetworkServerConfig.PROTOCOL_NFS, export = "/volume1/photo"
            ).rootUrl()
        )
        assertEquals(
            "smb://nas.local/Photos/Trips",
            NetworkServerConfig(
                id = "b", displayName = "", host = "nas.local", share = "Photos", path = "Trips"
            ).rootUrl()
        )
        assertEquals(
            "smb://nas.local",
            NetworkServerConfig(id = "c", displayName = "", host = "nas.local").rootUrl()
        )
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat testPlusDebugUnitTest --tests "org.iurl.litegallery.NetworkConfigStoreTest"`
Expected: FAIL — compilation error, `Unresolved reference: NetworkConfigStore`.

- [ ] **Step 3: Implement the config model** — `git mv` `SmbConfig.kt` → `NetworkServerConfig.kt` with this full content:

```kotlin
package org.iurl.litegallery

/**
 * Data model for a saved network server connection (SMB or NFS).
 */
data class NetworkServerConfig(
    val id: String,           // Unique ID (UUID)
    val displayName: String,  // User-friendly display name
    val host: String,         // IP or hostname
    val protocol: String = PROTOCOL_SMB,
    val share: String = "",   // SMB: the share to connect to
    val path: String = "",    // SMB: the path within the share
    val port: Int = 445,      // SMB port; ignored for NFS (the portmapper decides ports)
    val username: String = "",
    val password: String = "",
    val isGuest: Boolean = false,
    val export: String = "",  // NFS: exported path, e.g. "/volume1/photo"
    val uid: Int = 0,         // NFS: AUTH_UNIX uid
    val gid: Int = 0          // NFS: AUTH_UNIX gid
) {
    val isAuthenticated: Boolean
        get() = !isGuest && username.isNotBlank()

    val isNfs: Boolean
        get() = protocol == PROTOCOL_NFS

    /** Root browse URL for this server entry. */
    fun rootUrl(): String = if (isNfs) {
        "nfs://$host$export"
    } else if (share.isNotBlank()) {
        "smb://$host/$share" + (if (path.isNotBlank()) "/$path" else "")
    } else {
        "smb://$host"
    }

    companion object {
        const val PROTOCOL_SMB = "smb"
        const val PROTOCOL_NFS = "nfs"
    }
}
```

- [ ] **Step 4: Implement the store** — `git mv` `SmbConfigStore.kt` → `NetworkConfigStore.kt`; rename the object to `NetworkConfigStore`, replace every `SmbConfig` with `NetworkServerConfig`, keep `PREFS_NAME = "smb_servers"` and `KEY_SERVERS = "servers_json"` unchanged (add a comment: `// Legacy name kept on purpose so existing SMB bookmarks survive the rename.`), and replace `serializeServer`/`parseServer` with:

```kotlin
    private fun serializeServer(config: NetworkServerConfig): JSONObject {
        return JSONObject().apply {
            put("id", config.id)
            put("displayName", config.displayName)
            put("host", config.host)
            put("protocol", config.protocol)
            put("share", config.share)
            put("path", config.path)
            put("port", config.port)
            put("username", config.username)
            put("password", config.password)
            put("isGuest", config.isGuest)
            put("export", config.export)
            put("uid", config.uid)
            put("gid", config.gid)
        }
    }

    private fun parseServer(json: JSONObject): NetworkServerConfig? {
        return try {
            NetworkServerConfig(
                id = json.optString("id", UUID.randomUUID().toString()),
                displayName = json.optString("displayName", ""),
                host = json.optString("host", ""),
                // Entries saved before the NFS feature have no protocol field: they are SMB.
                protocol = json.optString("protocol", NetworkServerConfig.PROTOCOL_SMB),
                share = json.optString("share", ""),
                path = json.optString("path", ""),
                port = json.optInt("port", 445),
                username = json.optString("username", ""),
                password = json.optString("password", ""),
                isGuest = json.optBoolean("isGuest", false),
                export = json.optString("export", ""),
                uid = json.optInt("uid", 0),
                gid = json.optInt("gid", 0)
            )
        } catch (e: Exception) {
            null
        }
    }
```

- [ ] **Step 5: Fix all references** — in `SmbClient.kt`, `SmbBrowseActivity.kt`, and `SmbMediaSource.kt` replace every occurrence of `SmbConfigStore` → `NetworkConfigStore` and every Kotlin type usage `SmbConfig` → `NetworkServerConfig`. Watch out in `SmbClient.kt`: the import alias `com.hierynomus.smbj.SmbConfig as SmbjConfig` refers to the **smbj library** class and must NOT be touched; only the LiteGallery `SmbConfig` type usages change (`resolveConfig`, `testConnection`, `getOrCreateConnection`, `getOrCreateSession`, `getOrCreateShareWithConfig` signatures).

- [ ] **Step 6: Run the full plus test suite**

Run: `.\gradlew.bat testPlusDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass including the three new ones.

- [ ] **Step 7: Commit**

```bash
git add -A app/src/plus app/src/testPlus
git commit -m "refactor(plus): make saved-server config protocol-aware

Rename SmbConfig/SmbConfigStore to NetworkServerConfig/NetworkConfigStore
and add protocol, export, uid, and gid fields in preparation for NFS
support. The SharedPreferences file name and JSON key are unchanged, and
entries without a protocol field parse as SMB, so existing bookmarks
keep working without migration code."
```

---

### Task 2: Extract the protocol-agnostic remote client seam

**Files:**
- Create: `app/src/plus/java/org/iurl/litegallery/RemoteFileClient.kt`
- Create: `app/src/plus/java/org/iurl/litegallery/RemoteFileTypes.kt`
- Create: `app/src/plus/java/org/iurl/litegallery/SmbFileClient.kt`
- Modify: `app/src/plus/java/org/iurl/litegallery/SmbClient.kt` (extension sets only)
- Test: `app/src/testPlus/java/org/iurl/litegallery/RemoteClientsTest.kt`

**Interfaces:**
- Consumes: `NetworkServerConfig` (Task 1), `SmbPath`, `SmbClient` (unchanged).
- Produces (used by every later task):
  - `RemoteFileInfo(name: String, url: String, isDirectory: Boolean, size: Long, lastModified: Long, isMedia: Boolean, isVideo: Boolean)`
  - `interface RemoteRandomAccessFile : Closeable { val size: Long; fun readAt(fileOffset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int }` (`readAt` returns bytes read or `-1` at EOF)
  - `interface RemoteFileClient { listFiles(context, folderUrl): List<RemoteFileInfo>; openInputStream(context, fileUrl): InputStream; openRandomAccess(context, fileUrl): RemoteRandomAccessFile; getFileSize(context, fileUrl): Long; rename(context, oldUrl, newUrl): Boolean; fileExists(context, fileUrl): Boolean; testConnection(config: NetworkServerConfig): Boolean; disconnect(host: String); disconnectAll() }`
  - `object RemoteClients { forUrl(url): RemoteFileClient?; forProtocol(protocol): RemoteFileClient?; all(): List<RemoteFileClient> }`
  - `object RemotePath { isRemote(path): Boolean }`
  - `object RemoteFileTypes { IMAGE_EXTENSIONS; VIDEO_EXTENSIONS; extensionOf(name); isImage(name); isVideo(name); isMedia(name); mimeType(name) }`
  - `object SmbFileClient : RemoteFileClient`

- [ ] **Step 1: Write the failing test** — create `app/src/testPlus/java/org/iurl/litegallery/RemoteClientsTest.kt`:

```kotlin
package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteClientsTest {

    @Test
    fun forUrl_routesSmbUrlsToSmbFileClient() {
        assertSame(SmbFileClient, RemoteClients.forUrl("smb://host/share/file.jpg"))
    }

    @Test
    fun forUrl_returnsNullForLocalAndUnknownSchemes() {
        assertNull(RemoteClients.forUrl("/storage/emulated/0/DCIM/a.jpg"))
        assertNull(RemoteClients.forUrl("content://media/external/images/media/1"))
    }

    @Test
    fun forProtocol_routesSmbProtocol() {
        assertSame(SmbFileClient, RemoteClients.forProtocol(NetworkServerConfig.PROTOCOL_SMB))
        assertNull(RemoteClients.forProtocol("gopher"))
    }

    @Test
    fun isRemote_matchesSmbAndNfsSchemesOnly() {
        assertTrue(RemotePath.isRemote("smb://host/share"))
        assertTrue(RemotePath.isRemote("nfs://host/volume1/photo"))
        assertFalse(RemotePath.isRemote("/sdcard/x.jpg"))
        assertFalse(RemotePath.isRemote("https://example.com/a.jpg"))
    }

    @Test
    fun fileTypes_classifyByExtension() {
        assertTrue(RemoteFileTypes.isImage("photo.JPG"))
        assertTrue(RemoteFileTypes.isVideo("clip.mkv"))
        assertTrue(RemoteFileTypes.isMedia("photo.heic"))
        assertFalse(RemoteFileTypes.isMedia("document.pdf"))
        assertEquals("video/mp4", RemoteFileTypes.mimeType("clip.m4v"))
        assertEquals("image/jpeg", RemoteFileTypes.mimeType("photo.jpeg"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testPlusDebugUnitTest --tests "org.iurl.litegallery.RemoteClientsTest"`
Expected: FAIL — `Unresolved reference: RemoteClients`.

- [ ] **Step 3: Create `RemoteFileTypes.kt`** (full content):

```kotlin
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
```

- [ ] **Step 4: Create `RemoteFileClient.kt`** (full content):

```kotlin
package org.iurl.litegallery

import android.content.Context
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** A file or folder entry on a remote share, addressed by its full URL. */
data class RemoteFileInfo(
    val name: String,
    val url: String,            // full URL, e.g. smb://host/share/dir/a.jpg or nfs://host/export/dir/a.jpg
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,     // epoch millis
    val isMedia: Boolean,
    val isVideo: Boolean
)

/** Random-access read handle for a remote file (used by the Media3 DataSource). */
interface RemoteRandomAccessFile : Closeable {

    /** Total file size in bytes. */
    val size: Long

    /**
     * Read up to [length] bytes at absolute [fileOffset] into [buffer] starting at
     * [bufferOffset]. Returns the number of bytes read, or -1 at end of file.
     */
    @Throws(IOException::class)
    fun readAt(fileOffset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int
}

/**
 * Protocol-agnostic client for a remote file share. Implementations: [SmbFileClient]
 * (wraps SmbClient/smbj) and NfsFileClient (wraps the EMC NFSv3 client). All methods
 * address files by their full remote URL and perform blocking I/O — call them from
 * Dispatchers.IO.
 */
interface RemoteFileClient {

    fun listFiles(context: Context, folderUrl: String): List<RemoteFileInfo>

    @Throws(IOException::class)
    fun openInputStream(context: Context, fileUrl: String): InputStream

    @Throws(IOException::class)
    fun openRandomAccess(context: Context, fileUrl: String): RemoteRandomAccessFile

    /** File size in bytes, or -1 if it cannot be determined. */
    fun getFileSize(context: Context, fileUrl: String): Long

    fun rename(context: Context, oldUrl: String, newUrl: String): Boolean

    fun fileExists(context: Context, fileUrl: String): Boolean

    fun testConnection(config: NetworkServerConfig): Boolean

    fun disconnect(host: String)

    fun disconnectAll()
}

/** Routes a remote URL or a protocol name to the client that owns it. */
object RemoteClients {

    fun forUrl(url: String): RemoteFileClient? = when {
        SmbPath.isSmb(url) -> SmbFileClient
        else -> null
    }

    fun forProtocol(protocol: String): RemoteFileClient? = when (protocol) {
        NetworkServerConfig.PROTOCOL_SMB -> SmbFileClient
        else -> null
    }

    fun all(): List<RemoteFileClient> = listOf(SmbFileClient)
}

/** Path helpers shared by every remote protocol. */
object RemotePath {

    /** True if [path] belongs to any remote share protocol. */
    fun isRemote(path: String): Boolean =
        path.startsWith("smb://") || path.startsWith("nfs://")
}
```

(NFS branches are added to `RemoteClients` in Task 9 — do NOT reference `NfsFileClient` or `NfsPath` yet, they do not exist.)

- [ ] **Step 5: Create `SmbFileClient.kt`** (full content):

```kotlin
package org.iurl.litegallery

import android.content.Context
import java.io.IOException
import java.io.InputStream

/**
 * [RemoteFileClient] adapter for SMB. Parses smb:// URLs and delegates to [SmbClient],
 * which keeps all smbj connection handling unchanged.
 */
object SmbFileClient : RemoteFileClient {

    override fun listFiles(context: Context, folderUrl: String): List<RemoteFileInfo> {
        val smbPath = SmbPath.parse(folderUrl) ?: return emptyList()
        return SmbClient.listFiles(context, smbPath.host, smbPath.share, smbPath.path).map { info ->
            RemoteFileInfo(
                name = info.name,
                url = SmbPath.toUrl(smbPath.host, smbPath.share, info.path),
                isDirectory = info.isDirectory,
                size = info.size,
                lastModified = info.lastModified,
                isMedia = info.isMedia,
                isVideo = info.isVideo
            )
        }
    }

    override fun openInputStream(context: Context, fileUrl: String): InputStream {
        val smbPath = SmbPath.parse(fileUrl) ?: throw IOException("Invalid SMB URL: $fileUrl")
        return SmbClient.openInputStream(context, smbPath.host, smbPath.share, smbPath.path)
    }

    override fun openRandomAccess(context: Context, fileUrl: String): RemoteRandomAccessFile {
        val smbPath = SmbPath.parse(fileUrl) ?: throw IOException("Invalid SMB URL: $fileUrl")
        val file = SmbClient.openFile(context, smbPath.host, smbPath.share, smbPath.path)
        val fileSize = file.fileInformation.standardInformation.endOfFile
        return object : RemoteRandomAccessFile {
            override val size: Long = fileSize

            override fun readAt(fileOffset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int =
                file.read(buffer, fileOffset, bufferOffset, length)

            override fun close() = file.close()
        }
    }

    override fun getFileSize(context: Context, fileUrl: String): Long {
        val smbPath = SmbPath.parse(fileUrl) ?: return -1L
        return SmbClient.getFileSize(context, smbPath.host, smbPath.share, smbPath.path)
    }

    override fun rename(context: Context, oldUrl: String, newUrl: String): Boolean {
        val oldPath = SmbPath.parse(oldUrl) ?: return false
        val newPath = SmbPath.parse(newUrl) ?: return false
        if (!oldPath.host.equals(newPath.host, ignoreCase = true) || oldPath.share != newPath.share) {
            return false
        }
        return SmbClient.rename(context, oldPath.host, oldPath.share, oldPath.path, newPath.path)
    }

    override fun fileExists(context: Context, fileUrl: String): Boolean {
        val smbPath = SmbPath.parse(fileUrl) ?: return false
        return SmbClient.fileExists(context, smbPath.host, smbPath.share, smbPath.path)
    }

    override fun testConnection(config: NetworkServerConfig): Boolean =
        SmbClient.testConnection(config)

    override fun disconnect(host: String) = SmbClient.disconnect(host)

    override fun disconnectAll() = SmbClient.disconnectAll()
}
```

- [ ] **Step 6: Deduplicate extension sets in `SmbClient.kt`** — replace the two set declarations near the top:

```kotlin
    private val imageExtensions = RemoteFileTypes.IMAGE_EXTENSIONS
    private val videoExtensions = RemoteFileTypes.VIDEO_EXTENSIONS
```

- [ ] **Step 7: Run tests**

Run: `.\gradlew.bat testPlusDebugUnitTest`
Expected: BUILD SUCCESSFUL, `RemoteClientsTest` and all existing tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/plus app/src/testPlus
git commit -m "refactor(plus): add protocol-agnostic remote file client seam

Introduce RemoteFileClient, RemoteRandomAccessFile, RemoteFileInfo,
RemoteFileTypes, RemotePath, and the RemoteClients router. SMB is
adapted through the new SmbFileClient object, which parses smb:// URLs
and delegates to the unchanged SmbClient. This seam lets the browse UI,
Glide loader, Media3 DataSource, and scanner work against any protocol
so NFS can plug in later without duplicating them."
```

---

### Task 3: Generalize the Glide loader to `RemoteModelLoader`

**Files:**
- Rename: `app/src/plus/java/org/iurl/litegallery/SmbModelLoader.kt` → `RemoteModelLoader.kt`
- Modify: `app/src/plus/java/org/iurl/litegallery/MediaSourceBootstrap.kt`
- Modify: `app/src/plus/java/org/iurl/litegallery/SmbMediaSource.kt` (`canLoadThumbnail`)
- Rename test: `app/src/testPlus/java/org/iurl/litegallery/SmbModelLoaderTest.kt` → `RemoteModelLoaderTest.kt`

**Interfaces:**
- Consumes: `RemoteClients.forUrl`, `RemotePath.isRemote`, `RemoteFileTypes.isImage` (Task 2).
- Produces: `RemoteModelLoader` with `companion fun isRemoteImage(path: String): Boolean`; `RemoteModelLoaderFactory(context)`; `RemoteImageFetcher`.

- [ ] **Step 1: Write the failing test** — `git mv` the test file, rename the class to `RemoteModelLoaderTest`, and replace its content's test methods with:

```kotlin
    @Test
    fun isRemoteImage_acceptsSmbAndNfsImageExtensions() {
        assertTrue(RemoteModelLoader.isRemoteImage("smb://host/share/photo.JPG"))
        assertTrue(RemoteModelLoader.isRemoteImage("smb://host/share/folder/image.heic"))
        assertTrue(RemoteModelLoader.isRemoteImage("nfs://host/volume1/photo/image.png"))
        assertFalse(RemoteModelLoader.isRemoteImage("smb://host/share/video.mp4"))
        assertFalse(RemoteModelLoader.isRemoteImage("nfs://host/volume1/video.mp4"))
        assertFalse(RemoteModelLoader.isRemoteImage("smb://host/share/no-extension"))
        assertFalse(RemoteModelLoader.isRemoteImage("/storage/emulated/0/DCIM/photo.jpg"))
    }

    @Test
    fun handles_returnsTrueOnlyForRemoteImages() {
        assertTrue(loader.handles("smb://host/share/photo.webp"))
        assertTrue(loader.handles("nfs://host/volume1/photo.webp"))
        assertFalse(loader.handles("smb://host/share/movie.mkv"))
        assertFalse(loader.handles("content://media/external/images/media/1"))
    }

    @Test
    fun buildLoadData_returnsFetcherOnlyForRemoteImages() {
        assertNotNull(loader.buildLoadData("smb://host/share/photo.png", 100, 100, Options()))
        assertNotNull(loader.buildLoadData("nfs://host/volume1/photo.png", 100, 100, Options()))
        assertNull(loader.buildLoadData("smb://host/share/movie.mp4", 100, 100, Options()))
        assertNull(loader.buildLoadData("/storage/emulated/0/DCIM/photo.png", 100, 100, Options()))
    }
```

Also update the field/`setUp` to `private lateinit var loader: RemoteModelLoader` / `loader = RemoteModelLoader(context)`.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat testPlusDebugUnitTest --tests "org.iurl.litegallery.RemoteModelLoaderTest"`
Expected: FAIL — `Unresolved reference: RemoteModelLoader`.

- [ ] **Step 3: Implement** — `git mv` `SmbModelLoader.kt` → `RemoteModelLoader.kt` with this full content:

```kotlin
package org.iurl.litegallery

import android.content.Context
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.InputStream

/**
 * Glide ModelLoader for loading images from remote shares. Intercepts String models
 * starting with a remote scheme (smb://, nfs://) and loads them via [RemoteClients].
 */
class RemoteModelLoader(private val context: Context) : ModelLoader<String, InputStream> {

    companion object {
        /** Check if a remote URL points to an image (not a video). */
        fun isRemoteImage(path: String): Boolean =
            RemotePath.isRemote(path) && RemoteFileTypes.isImage(path)
    }

    override fun buildLoadData(
        model: String,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream>? {
        if (!isRemoteImage(model)) {
            return null // Only handle remote image paths; videos can't be decoded as images
        }
        return ModelLoader.LoadData(ObjectKey(model), RemoteImageFetcher(context, model))
    }

    override fun handles(model: String): Boolean = isRemoteImage(model)
}

/**
 * Glide DataFetcher that reads image data from remote shares.
 */
class RemoteImageFetcher(
    private val context: Context,
    private val url: String
) : DataFetcher<InputStream> {

    private var inputStream: InputStream? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        try {
            val client = RemoteClients.forUrl(url)
            if (client == null) {
                callback.onLoadFailed(Exception("No remote client for URL: $url"))
                return
            }
            val stream = client.openInputStream(context, url)
            inputStream = stream
            callback.onDataReady(stream)
        } catch (e: Exception) {
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        try {
            inputStream?.close()
        } catch (_: Exception) {
        }
        inputStream = null
    }

    override fun cancel() {
        // No-op: remote reads are synchronous
    }

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.REMOTE
}

/**
 * Factory for creating RemoteModelLoader instances.
 * Registered with Glide by MediaSourceBootstrap.
 */
class RemoteModelLoaderFactory(private val context: Context) :
    ModelLoaderFactory<String, InputStream> {

    override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<String, InputStream> {
        return RemoteModelLoader(context)
    }

    override fun teardown() {
        // No-op
    }
}
```

- [ ] **Step 4: Update references** — in `MediaSourceBootstrap.kt` replace `SmbModelLoaderFactory(app)` with `RemoteModelLoaderFactory(app)` and update the comment to mention remote shares generally. In `SmbMediaSource.kt` replace `SmbModelLoader.isSmbImage(path)` with `RemoteModelLoader.isRemoteImage(path)`.

- [ ] **Step 5: Run tests**

Run: `.\gradlew.bat testPlusDebugUnitTest`
Expected: BUILD SUCCESSFUL, all pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/plus app/src/testPlus
git commit -m "refactor(plus): generalize Glide loader to all remote schemes

Rename SmbModelLoader to RemoteModelLoader and route stream opening
through the RemoteClients seam instead of calling SmbClient directly.
The loader now recognizes both smb:// and nfs:// image URLs, so NFS
thumbnails work as soon as an NFS client is registered."
```

---

### Task 4: Generalize the Media3 DataSource to `RemoteDataSource`

**Files:**
- Rename: `app/src/plus/java/org/iurl/litegallery/SmbDataSource.kt` → `RemoteDataSource.kt`
- Modify: `app/src/plus/java/org/iurl/litegallery/SmbMediaSource.kt` (`createExoMediaSource`)

**Interfaces:**
- Consumes: `RemoteClients.forUrl(url).openRandomAccess(context, url)` → `RemoteRandomAccessFile` (Task 2).
- Produces: `RemoteDataSource(context)`, `RemoteDataSourceFactory(context)` (a `DataSource.Factory`).

- [ ] **Step 1: Implement** — `git mv` `SmbDataSource.kt` → `RemoteDataSource.kt`. Keep the read-ahead buffer and adaptive chunk logic **byte-for-byte identical**; only the file handle type changes. The full changed sections:

Class declaration and fields (replaces the old declaration through `currentChunkSize`):

```kotlin
/**
 * Custom ExoPlayer DataSource that reads from remote shares (SMB, NFS) via the
 * RemoteFileClient seam. Uses RemoteRandomAccessFile.readAt directly for proper
 * random access and seeking.
 */
class RemoteDataSource(private val context: Context) : BaseDataSource(/* isNetwork= */ true) {

    private var remoteFile: RemoteRandomAccessFile? = null
    private var bytesRemaining: Long = 0
    private var opened = false
    private var currentUri: Uri? = null
    private var currentFileOffset: Long = 0

    // Read-ahead buffer to eliminate small network reads
    private val readBuffer = ByteArray(4 * 1024 * 1024) // 4MB max for higher throughput
    private var bufferFileOffset: Long = -1L
    private var bufferValidLength: Int = 0
    private var currentChunkSize: Int = 65536 // Adaptive chunk size starting at 64KB
```

`open()` — replace the URL parsing and file-opening block (everything from `val uri = dataSpec.uri` down to `currentFileOffset = dataSpec.position`) with:

```kotlin
        val uri = dataSpec.uri
        val url = uri.toString()

        try {
            val client = RemoteClients.forUrl(url)
                ?: throw java.io.IOException("No remote client for URL: $url")
            val file = client.openRandomAccess(context, url)
            val fileSize = file.size
            remoteFile = file
            currentFileOffset = dataSpec.position
```

The `bytesRemaining` calculation, chunk-size reset, buffer invalidation, `transferStarted`, and the catch block stay identical, except the catch message becomes `"Failed to open remote file: ${e.message}"`.

`read()` — replace `val file = smbFile ?: return C.RESULT_END_OF_INPUT` with `val file = remoteFile ?: return C.RESULT_END_OF_INPUT`, and replace the network read line

```kotlin
            val bytesRead = file.read(readBuffer, currentFileOffset, 0, bytesToReadFromNetwork)
```

with

```kotlin
            val bytesRead = file.readAt(currentFileOffset, readBuffer, 0, bytesToReadFromNetwork)
```

In the catch block change `"SMB read interrupted"` → `"Remote read interrupted"` and `"Failed to read SMB file: …"` → `"Failed to read remote file: …"`.

`close()` — replace `smbFile?.close()` / `smbFile = null` with `remoteFile?.close()` / `remoteFile = null`, log tag string to `"RemoteDataSource"`.

Factory at the bottom:

```kotlin
/**
 * Factory for creating RemoteDataSource instances.
 */
class RemoteDataSourceFactory(private val context: Context) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return RemoteDataSource(context)
    }
}
```

- [ ] **Step 2: Update the media source** — in `SmbMediaSource.kt`, `createExoMediaSource` uses `RemoteDataSourceFactory(context)` instead of `SmbDataSourceFactory(context)`.

- [ ] **Step 3: Compile and run tests**

Run: `.\gradlew.bat testPlusDebugUnitTest`
Expected: BUILD SUCCESSFUL (this file has no unit tests; the suite guards the refactor).

- [ ] **Step 4: Commit**

```bash
git add app/src/plus
git commit -m "refactor(plus): generalize Media3 DataSource over remote seam

Rename SmbDataSource to RemoteDataSource and swap the smbj file handle
for the RemoteRandomAccessFile abstraction. The 4MB read-ahead buffer
and adaptive chunk sizing logic are preserved unchanged, so SMB
streaming behavior is identical while NFS streaming becomes possible
once its client lands."
```

---

### Task 5: Generalize scanner and media source (`RemoteMediaScanner`, `NetworkMediaSource`)

**Files:**
- Rename: `app/src/plus/java/org/iurl/litegallery/SmbMediaScanner.kt` → `RemoteMediaScanner.kt`
- Rename: `app/src/plus/java/org/iurl/litegallery/SmbMediaSource.kt` → `NetworkMediaSource.kt`
- Modify: `app/src/plus/java/org/iurl/litegallery/MediaSourceBootstrap.kt`
- Modify: `app/src/plus/java/org/iurl/litegallery/SmbBrowseActivity.kt` (scanner type + method names)

**Interfaces:**
- Consumes: `RemoteClients`, `RemoteFileInfo`, `RemoteFileTypes` (Task 2), `RemoteDataSourceFactory` (Task 4).
- Produces: `RemoteMediaScanner(context)` with `suspend scanMediaInFolder(folderUrl): List<MediaItem>`, `suspend scanSkeletonsInFolder(folderUrl): List<MediaItemSkeleton>`, `scanMediaInFolderStreamed(folderUrl): Flow<LoadEvent>`, `suspend listDirectory(folderUrl): List<RemoteFileInfo>`; `object NetworkMediaSource : MediaSource`.

- [ ] **Step 1: Implement `RemoteMediaScanner.kt`** — `git mv` then full content:

```kotlin
package org.iurl.litegallery

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Scans remote shares (SMB, NFS) for media files and folders via the RemoteClients seam.
 */
class RemoteMediaScanner(private val context: Context) {

    /**
     * Scan a remote directory for media items (files only, not recursive).
     */
    suspend fun scanMediaInFolder(folderUrl: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val client = RemoteClients.forUrl(folderUrl) ?: return@withContext emptyList()
        val items = mutableListOf<MediaItem>()

        try {
            for (file in client.listFiles(context, folderUrl)) {
                if (file.isDirectory || !file.isMedia) continue

                items.add(
                    MediaItem(
                        id = MediaItem.NO_MEDIASTORE_ID,
                        name = file.name,
                        path = file.url,
                        dateModified = file.lastModified,
                        size = file.size,
                        mimeType = RemoteFileTypes.mimeType(file.name),
                        duration = 0, // Cannot easily get duration without downloading
                        width = 0,
                        height = 0
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("RemoteMediaScanner", "Failed to scan remote media in folder: $folderUrl", e)
        }

        items.sortedByDescending { it.dateModified }
    }

    /**
     * Scan a remote directory directly to lightweight skeleton representations.
     */
    suspend fun scanSkeletonsInFolder(folderUrl: String): List<MediaItemSkeleton> = withContext(Dispatchers.IO) {
        val fullItems = scanMediaInFolder(folderUrl)
        fullItems.map { item ->
            MediaItemSkeleton(
                id = item.id,
                path = item.path,
                name = item.name,
                dateModified = item.dateModified,
                size = item.size,
                isVideo = item.isVideo
            )
        }
    }

    /**
     * Stream scanned remote media items chunk by chunk using a Coroutine Flow.
     */
    fun scanMediaInFolderStreamed(folderUrl: String): Flow<LoadEvent> = flow {
        val skeletons = scanSkeletonsInFolder(folderUrl)
        MediaScanner.streamSkeletonListLoadEvents(skeletons).collect { emit(it) }
    }

    /**
     * List directories and media files in a remote folder (for browsing).
     */
    suspend fun listDirectory(folderUrl: String): List<RemoteFileInfo> = withContext(Dispatchers.IO) {
        val client = RemoteClients.forUrl(folderUrl) ?: return@withContext emptyList()
        try {
            client.listFiles(context, folderUrl)
                .sortedWith(compareByDescending<RemoteFileInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            android.util.Log.e("RemoteMediaScanner", "Failed to list directory: $folderUrl", e)
            emptyList()
        }
    }
}
```

- [ ] **Step 2: Implement `NetworkMediaSource.kt`** — `git mv` then full content:

```kotlin
package org.iurl.litegallery

import android.content.Context
import android.content.Intent
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * [MediaSource] implementation for network shares (SMB and NFS).
 *
 * Only present in the `plus` flavor. It works through the protocol-agnostic
 * [RemoteClients] seam and is registered with [MediaSourceRegistry] at startup
 * by `MediaSourceBootstrap`.
 */
object NetworkMediaSource : MediaSource {

    override fun handles(path: String): Boolean = RemotePath.isRemote(path)

    override fun homeEntry(context: Context): MediaFolder {
        val serverCount = NetworkConfigStore.getAllServers(context).size
        return MediaFolder(
            name = context.getString(R.string.smb_browse_title),
            // Sentinel path routed back to this source via handles(); kept as "smb://"
            // for compatibility with folder entries persisted by earlier versions.
            path = "smb://",
            itemCount = serverCount,
            thumbnail = null
        )
    }

    override fun browseIntent(context: Context): Intent =
        Intent(context, SmbBrowseActivity::class.java)

    override val folderIconRes: Int = R.drawable.ic_network

    override fun canLoadThumbnail(path: String): Boolean = RemoteModelLoader.isRemoteImage(path)

    override suspend fun scanFolder(context: Context, folderPath: String): List<MediaItem> =
        RemoteMediaScanner(context).scanMediaInFolder(folderPath)

    override fun scanFolderStreamed(context: Context, folderPath: String): Flow<LoadEvent> =
        RemoteMediaScanner(context).scanMediaInFolderStreamed(folderPath)

    override fun createExoMediaSource(
        context: Context,
        path: String
    ): androidx.media3.exoplayer.source.MediaSource {
        val dataSourceFactory = RemoteDataSourceFactory(context)
        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(
                androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(path))
            )
    }

    override suspend fun rename(
        context: Context,
        path: String,
        newBaseName: String,
        extension: String
    ): RenameOutcome = withContext(Dispatchers.IO) {
        val client = RemoteClients.forUrl(path) ?: return@withContext RenameOutcome.Failed
        val parentUrl = path.substringBeforeLast('/', "")
        if (parentUrl.isBlank()) return@withContext RenameOutcome.Failed
        val newFileName = if (extension.isNotEmpty()) "$newBaseName.$extension" else newBaseName
        val newUrl = "$parentUrl/$newFileName"

        when {
            client.fileExists(context, newUrl) -> RenameOutcome.AlreadyExists
            client.rename(context, path, newUrl) -> RenameOutcome.Success(newUrl)
            else -> RenameOutcome.Failed
        }
    }

    override fun shutdown() {
        RemoteClients.all().forEach { it.disconnectAll() }
    }
}
```

(`browseIntent` still points at `SmbBrowseActivity`; Task 6 renames the activity and updates this line. `homeEntry` still uses `R.string.smb_browse_title`; Task 11 switches the string.)

- [ ] **Step 3: Update references** — in `MediaSourceBootstrap.kt` replace `MediaSourceRegistry.register(SmbMediaSource)` with `MediaSourceRegistry.register(NetworkMediaSource)`. In `SmbBrowseActivity.kt` replace the field `private lateinit var smbMediaScanner: SmbMediaScanner` with `private lateinit var remoteMediaScanner: RemoteMediaScanner`, its initialization with `remoteMediaScanner = RemoteMediaScanner(this)`, and the call `smbMediaScanner.listSmbDirectory(smbAddress)` with `remoteMediaScanner.listDirectory(smbAddress)`.

  The activity's compile now breaks in two places that used `SmbClient.SmbFileInfo`; update them to the new listing type:
  - `SmbBrowseAdapter` generic types: replace every `SmbClient.SmbFileInfo` in the adapter, view holder, and diff callback with `RemoteFileInfo`.
  - In the adapter's `bind`, the thumbnail model block becomes simply `val model = item.url` (delete the `shareHost`/`shareName` properties and their assignments in `navigateTo`, and delete the `host`/`share` locals).
  - In the activity's `onItemClick` directory branch, replace the manual `smb://…` string building with `navigateTo(item.url)` (delete the `SmbPath.parse(currentPath)` block).
  - In `openMediaViewer(item: RemoteFileInfo)`, replace the body's URL building with `val targetUrl = item.url` (delete the `SmbPath.parse`/`SmbPath.toUrl` lines).
  - The diff callback compares `oldItem.url == newItem.url` instead of `oldItem.path == newItem.path`.

- [ ] **Step 4: Run tests**

Run: `.\gradlew.bat testCoreDebugUnitTest testPlusDebugUnitTest`
Expected: BUILD SUCCESSFUL — both flavors compile (core proves the main-source seam is untouched) and all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/plus
git commit -m "refactor(plus): route scanner and media source through remote seam

Rename SmbMediaScanner to RemoteMediaScanner and SmbMediaSource to
NetworkMediaSource. Folder scanning, streamed scanning, browsing,
renaming, video source creation, and shutdown now go through the
RemoteClients router, and browse listings carry full URLs so the
adapter no longer rebuilds smb:// paths from host/share parts."
```

---

### Task 6: Rename the browse activity and its layouts

**Files:**
- Rename: `app/src/plus/java/org/iurl/litegallery/SmbBrowseActivity.kt` → `NetworkBrowseActivity.kt`
- Rename: `app/src/plus/res/layout/activity_smb_browse.xml` → `activity_network_browse.xml`
- Rename: `app/src/plus/res/layout/item_smb_browse.xml` → `item_network_browse.xml`
- Rename: `app/src/plus/res/layout/item_smb_server.xml` → `item_network_server.xml`
- Modify: `app/src/plus/AndroidManifest.xml`
- Modify: `app/src/plus/java/org/iurl/litegallery/NetworkMediaSource.kt` (`browseIntent`)

**Interfaces:**
- Produces: `NetworkBrowseActivity` (class), `NetworkBrowseAdapter`, companion const `NetworkBrowseActivity.EXTRA_START_PATH = "extra_start_path"`, view binding `ActivityNetworkBrowseBinding` (generated from the renamed layout).

- [ ] **Step 1: Rename layouts with git mv** (content unchanged in this task):

```bash
git mv app/src/plus/res/layout/activity_smb_browse.xml app/src/plus/res/layout/activity_network_browse.xml
git mv app/src/plus/res/layout/item_smb_browse.xml app/src/plus/res/layout/item_network_browse.xml
git mv app/src/plus/res/layout/item_smb_server.xml app/src/plus/res/layout/item_network_server.xml
git mv app/src/plus/java/org/iurl/litegallery/SmbBrowseActivity.kt app/src/plus/java/org/iurl/litegallery/NetworkBrowseActivity.kt
```

- [ ] **Step 2: Apply the mechanical renames** inside `NetworkBrowseActivity.kt` (every occurrence):

| Old | New |
| --- | --- |
| `class SmbBrowseActivity` | `class NetworkBrowseActivity` |
| `this@SmbBrowseActivity` | `this@NetworkBrowseActivity` |
| `ActivitySmbBrowseBinding` (import + usage) | `ActivityNetworkBrowseBinding` |
| `EXTRA_SMB_PATH = "extra_smb_path"` | `EXTRA_START_PATH = "extra_start_path"` |
| `intent.getStringExtra(EXTRA_SMB_PATH)` | `intent.getStringExtra(EXTRA_START_PATH)` |
| `SmbBrowseAdapter` | `NetworkBrowseAdapter` |
| `SmbFileViewHolder` | `RemoteFileViewHolder` |
| `SmbFileDiffCallback` | `RemoteFileDiffCallback` |
| `R.layout.item_smb_browse` | `R.layout.item_network_browse` |
| `R.layout.item_smb_server` | `R.layout.item_network_server` |
| `LinearLayoutManager(this@SmbBrowseActivity)` | `LinearLayoutManager(this@NetworkBrowseActivity)` |
| log tag `"SmbBrowseActivity"` | `"NetworkBrowseActivity"` |

Update the class KDoc to: `Activity for browsing network shares (SMB and NFS). Provides an address bar for manual entry, saved-server bookmarks, and a file list for navigation.`

- [ ] **Step 3: Update the manifest** — `app/src/plus/AndroidManifest.xml` activity entry becomes:

```xml
        <activity
            android:name=".NetworkBrowseActivity"
            android:exported="false"
            android:label="@string/smb_browse_title"
            android:parentActivityName=".MainActivity"
            android:theme="@style/Theme.LiteGallery.Pack.FirstLight.NoActionBar" />
```

- [ ] **Step 4: Update `NetworkMediaSource.browseIntent`** to `Intent(context, NetworkBrowseActivity::class.java)`.

- [ ] **Step 5: Run tests and build both flavors**

Run: `.\gradlew.bat testCoreDebugUnitTest testPlusDebugUnitTest assemblePlusDebug assembleCoreDebug`
Expected: BUILD SUCCESSFUL. This completes the pure-refactor phase — SMB behavior is unchanged.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/plus
git commit -m "refactor(plus): rename browse activity to NetworkBrowseActivity

Rename SmbBrowseActivity and its layouts to protocol-neutral names and
switch the browse adapter to the RemoteFileInfo listing type with full
URLs. This finishes the seam-extraction phase: the plus flavor now has
no SMB-specific names outside SmbClient, SmbPath, and SmbFileClient,
and SMB behavior is unchanged."
```

---

### Task 7: Add the NFS dependency and `NfsPath`

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/plus/java/org/iurl/litegallery/NfsPath.kt`
- Test: `app/src/testPlus/java/org/iurl/litegallery/NfsPathTest.kt`

**Interfaces:**
- Produces: `data class NfsPath(host: String, fullPath: String)` — `fullPath` starts with `/` (or is `""` for a bare host) and is the export path + inner path joined. Members: `fullUrl: String`, `fileName: String`, `fileNameWithoutExtension: String`, `fileExtension: String`, `parentFolderPath: String`, `innerPath(export: String): String?`. Companion: `isNfs(path): Boolean`, `parse(url): NfsPath?`, `toUrl(host, fullPath): String`, `matchExport(exports: List<String>, fullPath: String): String?`.

- [ ] **Step 1: Add the dependency** — in `app/build.gradle`, directly under the smbj lines:

```groovy
    // NFS client (plus flavor only — pure-Java NFSv3, Apache-2.0)
    plusImplementation 'com.emc.ecs:nfs-client:1.1.0'
```

Run: `.\gradlew.bat assemblePlusDebug`
Expected: BUILD SUCCESSFUL. If it fails with duplicate `META-INF` resources, add the reported path to the existing `packagingOptions.resources.excludes` list in `app/build.gradle` and rerun.

- [ ] **Step 2: Write the failing test** — create `NfsPathTest.kt`:

```kotlin
package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfsPathTest {

    @Test
    fun parse_validFileUrlExposesDerivedProperties() {
        val path = NfsPath.parse("nfs://nas.local/volume1/photo/Trips/img.name.jpg")

        requireNotNull(path)
        assertEquals("nas.local", path.host)
        assertEquals("/volume1/photo/Trips/img.name.jpg", path.fullPath)
        assertEquals("nfs://nas.local/volume1/photo/Trips/img.name.jpg", path.fullUrl)
        assertEquals("img.name.jpg", path.fileName)
        assertEquals("img.name", path.fileNameWithoutExtension)
        assertEquals("jpg", path.fileExtension)
        assertEquals("nfs://nas.local/volume1/photo/Trips", path.parentFolderPath)
    }

    @Test
    fun parse_hostRootAndTrailingSlashes() {
        assertEquals(NfsPath("nas", ""), NfsPath.parse("nfs://nas"))
        assertEquals(NfsPath("nas", ""), NfsPath.parse("nfs://nas/"))
        assertEquals(NfsPath("nas", "/volume1"), NfsPath.parse("nfs://nas/volume1/"))
    }

    @Test
    fun parse_rejectsInvalidUrls() {
        assertNull(NfsPath.parse("smb://host/share/file.jpg"))
        assertNull(NfsPath.parse("nfs://"))
        assertNull(NfsPath.parse("nfs:///volume1"))
    }

    @Test
    fun isNfs_matchesOnlyNfsScheme() {
        assertTrue(NfsPath.isNfs("nfs://host/volume1"))
        assertFalse(NfsPath.isNfs("NFS://host/volume1"))
        assertFalse(NfsPath.isNfs("/storage/emulated/0/a.jpg"))
    }

    @Test
    fun innerPath_stripsExportPrefix() {
        val path = NfsPath("h", "/volume1/photo/a/b.jpg")
        assertEquals("/a/b.jpg", path.innerPath("/volume1/photo"))
        assertEquals("/", NfsPath("h", "/volume1/photo").innerPath("/volume1/photo"))
        assertNull(path.innerPath("/volume1/music"))
        // A path in a sibling folder must not match by raw string prefix.
        assertNull(NfsPath("h", "/volume1/photos2/x.jpg").innerPath("/volume1/photo"))
    }

    @Test
    fun matchExport_selectsLongestMatchingPrefix() {
        val exports = listOf("/volume1", "/volume1/photo")
        assertEquals("/volume1/photo", NfsPath.matchExport(exports, "/volume1/photo/x.jpg"))
        assertEquals("/volume1", NfsPath.matchExport(exports, "/volume1/music/y.mp4"))
        assertEquals("/volume1/photo", NfsPath.matchExport(exports, "/volume1/photo"))
        assertNull(NfsPath.matchExport(exports, "/volume2/z.jpg"))
        assertNull(NfsPath.matchExport(emptyList(), "/volume1/photo/x.jpg"))
    }

    @Test
    fun toUrl_joinsHostAndFullPath() {
        assertEquals("nfs://nas/volume1/photo", NfsPath.toUrl("nas", "/volume1/photo"))
        assertEquals("nfs://nas", NfsPath.toUrl("nas", ""))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `.\gradlew.bat testPlusDebugUnitTest --tests "org.iurl.litegallery.NfsPathTest"`
Expected: FAIL — `Unresolved reference: NfsPath`.

- [ ] **Step 4: Implement `NfsPath.kt`** (full content):

```kotlin
package org.iurl.litegallery

/**
 * Helper for parsing and constructing NFS paths.
 * Format: nfs://host/exported/path/inner/path/file.ext
 *
 * The export path and the path inside the export are joined in the URL (the same
 * convention libnfs uses). Because export paths themselves contain slashes, the
 * boundary cannot be recovered from the URL alone — [matchExport] resolves it
 * against the export paths of the saved server configs for the host.
 */
data class NfsPath(
    val host: String,
    val fullPath: String  // "/export/inner/file.ext", or "" for a bare host
) {
    /** Full nfs:// URL */
    val fullUrl: String
        get() = "nfs://$host$fullPath"

    /** File name extracted from the path */
    val fileName: String
        get() = fullPath.substringAfterLast('/')

    /** File name without extension */
    val fileNameWithoutExtension: String
        get() = fileName.substringBeforeLast('.', fileName)

    /** File extension */
    val fileExtension: String
        get() = fileName.substringAfterLast('.', "")

    /** Full parent folder path as nfs:// URL */
    val parentFolderPath: String
        get() = "nfs://$host" + fullPath.substringBeforeLast('/', "")

    /**
     * The path inside [export] (starting with "/", "/" for the export root itself),
     * or null when this path is not inside that export.
     */
    fun innerPath(export: String): String? = when {
        fullPath == export -> "/"
        fullPath.startsWith("$export/") -> fullPath.removePrefix(export)
        else -> null
    }

    companion object {
        /**
         * Parse an nfs:// URL string. Returns null if the format is invalid.
         */
        fun parse(nfsUrl: String): NfsPath? {
            if (!isNfs(nfsUrl)) return null
            val rest = nfsUrl.removePrefix("nfs://").trimEnd('/')
            if (rest.isBlank()) return null
            val slash = rest.indexOf('/')
            if (slash == 0) return null
            return if (slash < 0) {
                NfsPath(rest, "")
            } else {
                NfsPath(rest.substring(0, slash), rest.substring(slash))
            }
        }

        /** Build a full nfs:// URL. [fullPath] must start with "/" or be empty. */
        fun toUrl(host: String, fullPath: String): String = "nfs://$host$fullPath"

        /** Check if a path string is an NFS path. */
        fun isNfs(path: String): Boolean = path.startsWith("nfs://")

        /**
         * From [exports], pick the longest one that [fullPath] lives inside
         * (segment-aware, so "/volume1/photo" does not match "/volume1/photos2/x").
         */
        fun matchExport(exports: List<String>, fullPath: String): String? =
            exports
                .filter { it.isNotBlank() && (fullPath == it || fullPath.startsWith("$it/")) }
                .maxByOrNull { it.length }
    }
}
```

- [ ] **Step 5: Run tests**

Run: `.\gradlew.bat testPlusDebugUnitTest --tests "org.iurl.litegallery.NfsPathTest"`
Expected: PASS (all 7 tests).

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle app/src/plus app/src/testPlus
git commit -m "feat(plus): add NFS client dependency and nfs:// path parsing

Add the pure-Java EMC nfs-client 1.1.0 library (plus flavor only) and
the NfsPath helper. NFS URLs join the export path and the path inside
the export; NfsPath.matchExport resolves the export boundary by
longest-prefix match against saved configs, since the boundary cannot
be derived from the URL alone."
```

---

### Task 8: Implement `NfsExportLister` (showmount -e equivalent)

**Files:**
- Create: `app/src/plus/java/org/iurl/litegallery/NfsExportLister.kt`
- Test: `app/src/testPlus/java/org/iurl/litegallery/NfsExportListerTest.kt`

**Interfaces:**
- Produces: `object NfsExportLister { fun listExports(host: String, timeoutMs: Int = 5000): List<String> }` (blocking; throws `NfsExportLister.ExportListException`). Internal XDR helpers (unit-tested): `encodeUInt`, `decodeUInt`, `encodeString`, `decodeString`, `buildRpcCallHeader`, `parseExportBody`, `stripReplyHeader`.

- [ ] **Step 1: Write the failing tests** — create `NfsExportListerTest.kt`:

```kotlin
package org.iurl.litegallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class NfsExportListerTest {

    private fun bytes(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Test
    fun buildRpcCallHeader_encodesAuthNoneCall() {
        val header = NfsExportLister.buildRpcCallHeader(
            xid = 7, program = 100005, version = 3, procedure = 5
        )

        assertEquals(40, header.size)
        assertEquals(7, NfsExportLister.decodeUInt(header, 0))        // xid
        assertEquals(0, NfsExportLister.decodeUInt(header, 4))        // msg_type CALL
        assertEquals(2, NfsExportLister.decodeUInt(header, 8))        // RPC version
        assertEquals(100005, NfsExportLister.decodeUInt(header, 12))  // program
        assertEquals(3, NfsExportLister.decodeUInt(header, 16))       // version
        assertEquals(5, NfsExportLister.decodeUInt(header, 20))       // procedure
        assertEquals(0, NfsExportLister.decodeUInt(header, 24))       // cred flavor AUTH_NONE
        assertEquals(0, NfsExportLister.decodeUInt(header, 28))       // cred length
        assertEquals(0, NfsExportLister.decodeUInt(header, 32))       // verf flavor AUTH_NONE
        assertEquals(0, NfsExportLister.decodeUInt(header, 36))       // verf length
    }

    @Test
    fun encodeAndDecodeString_roundTripWithPadding() {
        val encoded = NfsExportLister.encodeString("/data")   // 5 bytes -> padded to 8

        assertEquals(12, encoded.size)                        // 4 length + 8 padded value
        val (value, next) = NfsExportLister.decodeString(encoded, 0)
        assertEquals("/data", value)
        assertEquals(12, next)
    }

    @Test
    fun parseExportBody_readsDirPathsAndSkipsGroups() {
        val body = bytes(
            NfsExportLister.encodeUInt(1),                    // entry follows
            NfsExportLister.encodeString("/volume1/photo"),
            NfsExportLister.encodeUInt(1),                    // group follows
            NfsExportLister.encodeString("*"),
            NfsExportLister.encodeUInt(0),                    // end of groups
            NfsExportLister.encodeUInt(1),                    // entry follows
            NfsExportLister.encodeString("/data"),
            NfsExportLister.encodeUInt(0),                    // end of groups
            NfsExportLister.encodeUInt(0)                     // end of entries
        )

        assertEquals(listOf("/volume1/photo", "/data"), NfsExportLister.parseExportBody(body))
    }

    @Test
    fun parseExportBody_emptyListIsValid() {
        assertEquals(emptyList<String>(), NfsExportLister.parseExportBody(NfsExportLister.encodeUInt(0)))
    }

    @Test
    fun stripReplyHeader_returnsBodyOfAcceptedSuccessReply() {
        val reply = bytes(
            NfsExportLister.encodeUInt(99),                   // xid
            NfsExportLister.encodeUInt(1),                    // REPLY
            NfsExportLister.encodeUInt(0),                    // MSG_ACCEPTED
            NfsExportLister.encodeUInt(0),                    // verf flavor
            NfsExportLister.encodeUInt(0),                    // verf length
            NfsExportLister.encodeUInt(0),                    // accept_stat SUCCESS
            NfsExportLister.encodeUInt(2049)                  // result body
        )

        val body = NfsExportLister.stripReplyHeader(reply)

        assertEquals(2049, NfsExportLister.decodeUInt(body, 0))
    }

    @Test
    fun stripReplyHeader_throwsOnDeniedOrTruncatedReply() {
        val denied = bytes(
            NfsExportLister.encodeUInt(99),
            NfsExportLister.encodeUInt(1),
            NfsExportLister.encodeUInt(1)                     // MSG_DENIED
        )
        assertThrows(NfsExportLister.ExportListException::class.java) {
            NfsExportLister.stripReplyHeader(denied)
        }
        assertThrows(NfsExportLister.ExportListException::class.java) {
            NfsExportLister.stripReplyHeader(NfsExportLister.encodeUInt(99))
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat testPlusDebugUnitTest --tests "org.iurl.litegallery.NfsExportListerTest"`
Expected: FAIL — `Unresolved reference: NfsExportLister`.

- [ ] **Step 3: Implement `NfsExportLister.kt`** (full content):

```kotlin
package org.iurl.litegallery

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Lists the exported directories of an NFS server — the equivalent of `showmount -e`.
 *
 * The EMC nfs-client library exposes no MOUNT EXPORT call, so this implements the
 * minimal subset of ONC RPC (RFC 5531) needed for two TCP calls:
 *  1. PORTMAP (program 100000 v2) GETPORT — find the MOUNT service TCP port.
 *  2. MOUNT (program 100005 v3) EXPORT — read the export list.
 * Both calls use AUTH_NONE and TCP record marking.
 */
object NfsExportLister {

    private const val PORTMAP_PORT = 111
    private const val PORTMAP_PROGRAM = 100000
    private const val PORTMAP_VERSION = 2
    private const val PORTMAP_PROC_GETPORT = 3
    private const val MOUNT_PROGRAM = 100005
    private const val MOUNT_VERSION = 3
    private const val MOUNT_PROC_EXPORT = 5
    private const val IPPROTO_TCP = 6
    private const val MSG_TYPE_CALL = 0
    private const val MSG_TYPE_REPLY = 1
    private const val REPLY_STAT_ACCEPTED = 0
    private const val ACCEPT_STAT_SUCCESS = 0
    private const val MAX_FRAGMENT_BYTES = 1 shl 20
    private val LAST_FRAGMENT = 0x80000000.toInt()

    class ExportListException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * Query [host] for its NFS export paths. Blocking network I/O — call from
     * Dispatchers.IO. Throws [ExportListException] when the server cannot be
     * reached or replies unexpectedly.
     */
    fun listExports(host: String, timeoutMs: Int = 5000): List<String> {
        val mountPort = queryMountPort(host, timeoutMs)
        if (mountPort <= 0 || mountPort > 65535) {
            throw ExportListException("MOUNT service not registered on $host")
        }
        val call = buildRpcCallHeader(Random.nextInt(), MOUNT_PROGRAM, MOUNT_VERSION, MOUNT_PROC_EXPORT)
        return parseExportBody(rpcCall(host, mountPort, timeoutMs, call))
    }

    private fun queryMountPort(host: String, timeoutMs: Int): Int {
        val call = buildRpcCallHeader(Random.nextInt(), PORTMAP_PROGRAM, PORTMAP_VERSION, PORTMAP_PROC_GETPORT) +
            encodeUInt(MOUNT_PROGRAM) + encodeUInt(MOUNT_VERSION) + encodeUInt(IPPROTO_TCP) + encodeUInt(0)
        return decodeUInt(rpcCall(host, PORTMAP_PORT, timeoutMs, call), 0)
    }

    // --- XDR encoding/decoding (pure functions, unit tested) ---

    internal fun encodeUInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).putInt(value).array()

    internal fun decodeUInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) throw ExportListException("Truncated RPC reply")
        return ByteBuffer.wrap(bytes, offset, 4).int
    }

    /** Encode an XDR string: length prefix + bytes + zero padding to a 4-byte boundary. */
    internal fun encodeString(value: String): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        val padded = (raw.size + 3) / 4 * 4
        val out = ByteArray(4 + padded)
        System.arraycopy(encodeUInt(raw.size), 0, out, 0, 4)
        System.arraycopy(raw, 0, out, 4, raw.size)
        return out
    }

    /** Decode an XDR string. Returns the value and the offset right after its padding. */
    internal fun decodeString(bytes: ByteArray, offset: Int): Pair<String, Int> {
        val length = decodeUInt(bytes, offset)
        if (length < 0 || offset + 4 + length > bytes.size) {
            throw ExportListException("Truncated RPC string")
        }
        val value = String(bytes, offset + 4, length, Charsets.UTF_8)
        val padded = (length + 3) / 4 * 4
        return value to (offset + 4 + padded)
    }

    /** RPC call header with AUTH_NONE credential and verifier (RFC 5531 section 9). */
    internal fun buildRpcCallHeader(xid: Int, program: Int, version: Int, procedure: Int): ByteArray =
        encodeUInt(xid) +
            encodeUInt(MSG_TYPE_CALL) +
            encodeUInt(2) +                    // RPC protocol version
            encodeUInt(program) +
            encodeUInt(version) +
            encodeUInt(procedure) +
            encodeUInt(0) + encodeUInt(0) +    // credential: AUTH_NONE, zero-length body
            encodeUInt(0) + encodeUInt(0)      // verifier: AUTH_NONE, zero-length body

    /**
     * Parse a MOUNT EXPORT reply body: an XDR linked list of {dirpath, group list}.
     * Returns the dirpaths; group entries are read past and discarded.
     */
    internal fun parseExportBody(body: ByteArray): List<String> {
        val exports = mutableListOf<String>()
        var offset = 0
        while (true) {
            val entryFollows = decodeUInt(body, offset)
            offset += 4
            if (entryFollows == 0) break
            val (dirPath, afterPath) = decodeString(body, offset)
            offset = afterPath
            exports.add(dirPath)
            while (true) {
                val groupFollows = decodeUInt(body, offset)
                offset += 4
                if (groupFollows == 0) break
                val (_, afterGroup) = decodeString(body, offset)
                offset = afterGroup
            }
        }
        return exports
    }

    /** Validate an RPC reply header and return the result body that follows it. */
    internal fun stripReplyHeader(reply: ByteArray): ByteArray {
        var offset = 4                                       // xid: one call per connection, not checked
        if (decodeUInt(reply, offset) != MSG_TYPE_REPLY) {
            throw ExportListException("Not an RPC reply")
        }
        offset += 4
        if (decodeUInt(reply, offset) != REPLY_STAT_ACCEPTED) {
            throw ExportListException("RPC call denied by server")
        }
        offset += 4
        offset += 4                                          // verifier flavor
        val verifierLength = decodeUInt(reply, offset)
        offset += 4 + (verifierLength + 3) / 4 * 4
        val acceptStat = decodeUInt(reply, offset)
        if (acceptStat != ACCEPT_STAT_SUCCESS) {
            throw ExportListException("RPC call failed with accept_stat $acceptStat")
        }
        offset += 4
        return reply.copyOfRange(offset, reply.size)
    }

    // --- TCP transport with record marking (RFC 5531 section 11) ---

    private fun rpcCall(host: String, port: Int, timeoutMs: Int, callBody: ByteArray): ByteArray {
        try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                val out = DataOutputStream(socket.getOutputStream())
                out.writeInt(LAST_FRAGMENT or callBody.size)
                out.write(callBody)
                out.flush()
                return stripReplyHeader(readRecord(DataInputStream(socket.getInputStream())))
            }
        } catch (e: ExportListException) {
            throw e
        } catch (e: IOException) {
            throw ExportListException("RPC call to $host:$port failed: ${e.message}", e)
        }
    }

    private fun readRecord(input: DataInputStream): ByteArray {
        val record = java.io.ByteArrayOutputStream()
        while (true) {
            val marker = try {
                input.readInt()
            } catch (e: EOFException) {
                throw ExportListException("Connection closed by server", e)
            }
            val length = marker and LAST_FRAGMENT.inv()
            if (length < 0 || length > MAX_FRAGMENT_BYTES) {
                throw ExportListException("Unreasonable RPC fragment size: $length")
            }
            val chunk = ByteArray(length)
            input.readFully(chunk)
            record.write(chunk)
            if (marker and LAST_FRAGMENT != 0) break
        }
        return record.toByteArray()
    }
}
```

- [ ] **Step 4: Run tests**

Run: `.\gradlew.bat testPlusDebugUnitTest --tests "org.iurl.litegallery.NfsExportListerTest"`
Expected: PASS (all 6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/plus app/src/testPlus
git commit -m "feat(plus): add NFS export listing via minimal ONC RPC

Implement NfsExportLister, the equivalent of showmount -e. It speaks
just enough ONC RPC over TCP for two calls: a portmapper GETPORT to
locate the MOUNT service and a MOUNT v3 EXPORT to read the export
list. The EMC nfs-client library does not expose this call. XDR
encoding, reply parsing, and header validation are pure functions
covered by unit tests."
```

---

### Task 9: Implement `NfsFileClient` and register it with `RemoteClients`

**Files:**
- Create: `app/src/plus/java/org/iurl/litegallery/NfsFileClient.kt`
- Modify: `app/src/plus/java/org/iurl/litegallery/RemoteFileClient.kt` (`RemoteClients` routing)
- Test: `app/src/testPlus/java/org/iurl/litegallery/NfsFileClientTest.kt`
- Modify test: `app/src/testPlus/java/org/iurl/litegallery/RemoteClientsTest.kt`

**Interfaces:**
- Consumes: `NfsPath` (Task 7), `NetworkConfigStore`/`NetworkServerConfig` (Task 1), `RemoteFileClient`/`RemoteFileInfo`/`RemoteRandomAccessFile`/`RemoteFileTypes` (Task 2), EMC classes `com.emc.ecs.nfsclient.nfs.nfs3.Nfs3`, `com.emc.ecs.nfsclient.nfs.io.Nfs3File`, `com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream`, `com.emc.ecs.nfsclient.rpc.CredentialUnix`.
- Produces: `object NfsFileClient : RemoteFileClient` with `class NfsException : IOException` and `internal fun resolveTarget(context, url): Target` (`internal data class Target(config, export, innerPath)`).

- [ ] **Step 1: Write the failing tests** — create `NfsFileClientTest.kt`:

```kotlin
package org.iurl.litegallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class NfsFileClientTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearStore()
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(
                id = "short", displayName = "vol", host = "nas.local",
                protocol = NetworkServerConfig.PROTOCOL_NFS, export = "/volume1"
            )
        )
        NetworkConfigStore.addServer(
            context,
            NetworkServerConfig(
                id = "long", displayName = "photo", host = "nas.local",
                protocol = NetworkServerConfig.PROTOCOL_NFS, export = "/volume1/photo",
                uid = 1000, gid = 100
            )
        )
    }

    @After
    fun tearDown() = clearStore()

    @Test
    fun resolveTarget_matchesLongestExportAndStripsInnerPath() {
        val target = NfsFileClient.resolveTarget(context, "nfs://nas.local/volume1/photo/Trips/a.jpg")

        assertEquals("/volume1/photo", target.export)
        assertEquals("/Trips/a.jpg", target.innerPath)
        assertEquals("long", target.config.id)
        assertEquals(1000, target.config.uid)
    }

    @Test
    fun resolveTarget_usesShorterExportWhenLongerDoesNotMatch() {
        val target = NfsFileClient.resolveTarget(context, "nfs://nas.local/volume1/music/b.mp4")

        assertEquals("/volume1", target.export)
        assertEquals("/music/b.mp4", target.innerPath)
    }

    @Test
    fun resolveTarget_throwsWhenHostHasNoNfsConfig() {
        assertThrows(NfsFileClient.NfsException::class.java) {
            NfsFileClient.resolveTarget(context, "nfs://unknown.host/volume1/a.jpg")
        }
    }

    @Test
    fun resolveTarget_throwsWhenNoExportMatchesPath() {
        assertThrows(NfsFileClient.NfsException::class.java) {
            NfsFileClient.resolveTarget(context, "nfs://nas.local/volume9/a.jpg")
        }
    }

    @Test
    fun resolveTarget_throwsForInvalidUrl() {
        assertThrows(NfsFileClient.NfsException::class.java) {
            NfsFileClient.resolveTarget(context, "smb://nas.local/share/a.jpg")
        }
    }

    private fun clearStore() {
        context.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
```

Also update `RemoteClientsTest.kt` — replace `forUrl_returnsNullForLocalAndUnknownSchemes` and `forProtocol_routesSmbProtocol` with:

```kotlin
    @Test
    fun forUrl_routesNfsUrlsToNfsFileClient() {
        assertSame(NfsFileClient, RemoteClients.forUrl("nfs://host/volume1/photo/file.jpg"))
    }

    @Test
    fun forUrl_returnsNullForLocalAndUnknownSchemes() {
        assertNull(RemoteClients.forUrl("/storage/emulated/0/DCIM/a.jpg"))
        assertNull(RemoteClients.forUrl("content://media/external/images/media/1"))
    }

    @Test
    fun forProtocol_routesBothProtocols() {
        assertSame(SmbFileClient, RemoteClients.forProtocol(NetworkServerConfig.PROTOCOL_SMB))
        assertSame(NfsFileClient, RemoteClients.forProtocol(NetworkServerConfig.PROTOCOL_NFS))
        assertNull(RemoteClients.forProtocol("gopher"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat testPlusDebugUnitTest --tests "org.iurl.litegallery.NfsFileClientTest"`
Expected: FAIL — `Unresolved reference: NfsFileClient`.

- [ ] **Step 3: Implement `NfsFileClient.kt`** (full content). Note: the EMC API signatures below were verified against the library source (`Nfs3(server, exportedPath, credential, maximumRetries)`, `Nfs3File(nfs, path)`, `listFiles(): List<Nfs3File>`, `read(offset, size, bytes, position): NfsReadResponse` with `getBytesRead()`/`isEof()`, `lengthEx()`, `renameTo(destination)`, `nullCall()`). If compilation reveals a drifted signature, adapt the call site, not the seam.

```kotlin
package org.iurl.litegallery

import android.content.Context
import com.emc.ecs.nfsclient.nfs.io.Nfs3File
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3
import com.emc.ecs.nfsclient.rpc.CredentialUnix
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * [RemoteFileClient] for NFSv3 servers, backed by the EMC nfs-client library.
 *
 * URLs look like nfs://host/volume1/photo/sub/file.jpg — the export path and the
 * path inside the export are joined. The export boundary cannot be derived from
 * the URL alone, so it is resolved against the saved NFS server configs for the
 * host (longest matching export prefix, see [NfsPath.matchExport]).
 *
 * Mounting an export costs several RPC round trips (portmapper + MOUNT + FSINFO),
 * so mounted [Nfs3] handles are cached per host/export/identity. NFSv3 reads are
 * stateless, which is why [RemoteRandomAccessFile.close] has nothing to release.
 */
object NfsFileClient : RemoteFileClient {

    private const val MAX_RETRIES = 2

    // Cache: "host|export|uid|gid" -> mounted Nfs3 handle
    private val mounts = ConcurrentHashMap<String, Nfs3>()

    class NfsException(message: String, cause: Throwable? = null) : IOException(message, cause)

    internal data class Target(
        val config: NetworkServerConfig,
        val export: String,
        val innerPath: String
    )

    internal fun resolveTarget(context: Context, url: String): Target {
        val nfsPath = NfsPath.parse(url) ?: throw NfsException("Invalid NFS URL: $url")
        val configs = NetworkConfigStore.getAllServers(context)
            .filter { it.isNfs && it.host.equals(nfsPath.host, ignoreCase = true) }
        if (configs.isEmpty()) {
            throw NfsException("No saved NFS server for host: ${nfsPath.host}")
        }
        val export = NfsPath.matchExport(configs.map { it.export }, nfsPath.fullPath)
            ?: throw NfsException("No saved export matches ${nfsPath.fullPath} on ${nfsPath.host}")
        val config = configs.first { it.export == export }
        val innerPath = nfsPath.innerPath(export)
            ?: throw NfsException("Path ${nfsPath.fullPath} is outside export $export")
        return Target(config, export, innerPath)
    }

    private fun mountKey(config: NetworkServerConfig): String =
        "${config.host}|${config.export}|${config.uid}|${config.gid}"

    @Synchronized
    private fun getOrCreateMount(config: NetworkServerConfig): Nfs3 {
        mounts[mountKey(config)]?.let { return it }
        val nfs = Nfs3(
            config.host,
            config.export,
            CredentialUnix(config.uid, config.gid, emptySet()),
            MAX_RETRIES
        )
        mounts[mountKey(config)] = nfs
        return nfs
    }

    /**
     * Run [block] on the file at [url]. If the first attempt fails with an
     * IOException the cached mount is dropped and the call retried once with a
     * fresh mount, mirroring SmbClient.executeWithRetry.
     */
    private fun <T> withFile(context: Context, url: String, block: (Nfs3File) -> T): T {
        val target = resolveTarget(context, url)
        return try {
            block(Nfs3File(getOrCreateMount(target.config), target.innerPath))
        } catch (e: NfsException) {
            throw e
        } catch (e: IOException) {
            android.util.Log.w("NfsFileClient", "NFS mount appears stale, retrying...", e)
            mounts.remove(mountKey(target.config))
            block(Nfs3File(getOrCreateMount(target.config), target.innerPath))
        }
    }

    override fun listFiles(context: Context, folderUrl: String): List<RemoteFileInfo> {
        val target = resolveTarget(context, folderUrl)
        try {
            return withFile(context, folderUrl) { dir ->
                dir.listFiles().mapNotNull { child ->
                    val name = child.name
                    if (name.startsWith(".")) return@mapNotNull null
                    val isDirectory = child.isDirectory
                    RemoteFileInfo(
                        name = name,
                        url = NfsPath.toUrl(target.config.host, target.export + child.path),
                        isDirectory = isDirectory,
                        size = if (isDirectory) 0L else child.length(),
                        lastModified = child.lastModified(),
                        isMedia = !isDirectory && RemoteFileTypes.isMedia(name),
                        isVideo = !isDirectory && RemoteFileTypes.isVideo(name)
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NfsFileClient", "Failed to list files: $folderUrl", e)
            throw if (e is NfsException) e else NfsException("Failed to list files: ${e.message}", e)
        }
    }

    override fun openInputStream(context: Context, fileUrl: String): InputStream =
        withFile(context, fileUrl) { file -> NfsFileInputStream(file) }

    override fun openRandomAccess(context: Context, fileUrl: String): RemoteRandomAccessFile =
        withFile(context, fileUrl) { file ->
            val fileSize = file.lengthEx()
            object : RemoteRandomAccessFile {
                override val size: Long = fileSize

                override fun readAt(fileOffset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
                    val response = file.read(fileOffset, length, buffer, bufferOffset)
                    val bytesRead = response.bytesRead
                    return if (bytesRead <= 0 && response.isEof) -1 else bytesRead
                }

                override fun close() {
                    // NFSv3 reads are stateless: no handle to release.
                }
            }
        }

    override fun getFileSize(context: Context, fileUrl: String): Long = try {
        withFile(context, fileUrl) { it.lengthEx() }
    } catch (e: Exception) {
        android.util.Log.e("NfsFileClient", "Failed to get file size: $fileUrl", e)
        -1L
    }

    override fun rename(context: Context, oldUrl: String, newUrl: String): Boolean = try {
        val oldTarget = resolveTarget(context, oldUrl)
        val newTarget = resolveTarget(context, newUrl)
        if (!oldTarget.config.host.equals(newTarget.config.host, ignoreCase = true) ||
            oldTarget.export != newTarget.export
        ) {
            false
        } else {
            val mount = getOrCreateMount(oldTarget.config)
            Nfs3File(mount, oldTarget.innerPath).renameTo(Nfs3File(mount, newTarget.innerPath))
        }
    } catch (e: Exception) {
        android.util.Log.e("NfsFileClient", "Failed to rename: $oldUrl -> $newUrl", e)
        false
    }

    override fun fileExists(context: Context, fileUrl: String): Boolean = try {
        withFile(context, fileUrl) { it.exists() }
    } catch (e: Exception) {
        false
    }

    override fun testConnection(config: NetworkServerConfig): Boolean = try {
        // Constructing Nfs3 performs the mount (portmapper + MOUNT + FSINFO);
        // nullCall additionally proves the NFS program answers.
        Nfs3(config.host, config.export, CredentialUnix(config.uid, config.gid, emptySet()), 1)
            .nullCall()
        true
    } catch (e: Exception) {
        android.util.Log.e("NfsFileClient", "Connection test failed for ${config.host}:${config.export}", e)
        false
    }

    override fun disconnect(host: String) {
        mounts.keys.filter { it.startsWith("$host|") }.forEach { mounts.remove(it) }
    }

    override fun disconnectAll() {
        mounts.clear()
    }
}
```

- [ ] **Step 4: Register in `RemoteClients`** — in `RemoteFileClient.kt` replace the `RemoteClients` object with:

```kotlin
/** Routes a remote URL or a protocol name to the client that owns it. */
object RemoteClients {

    fun forUrl(url: String): RemoteFileClient? = when {
        SmbPath.isSmb(url) -> SmbFileClient
        NfsPath.isNfs(url) -> NfsFileClient
        else -> null
    }

    fun forProtocol(protocol: String): RemoteFileClient? = when (protocol) {
        NetworkServerConfig.PROTOCOL_SMB -> SmbFileClient
        NetworkServerConfig.PROTOCOL_NFS -> NfsFileClient
        else -> null
    }

    fun all(): List<RemoteFileClient> = listOf(SmbFileClient, NfsFileClient)
}
```

- [ ] **Step 5: Run the full suite**

Run: `.\gradlew.bat testPlusDebugUnitTest`
Expected: BUILD SUCCESSFUL — `NfsFileClientTest` (5 tests), updated `RemoteClientsTest`, and everything else pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/plus app/src/testPlus
git commit -m "feat(plus): implement NFS file client over EMC nfs-client

Add NfsFileClient, the NFSv3 implementation of RemoteFileClient. It
resolves the export boundary of nfs:// URLs by longest-prefix match
against saved server configs, caches mounted Nfs3 handles per
host/export/identity, retries once on stale mounts, and maps NFS reads
onto the RemoteRandomAccessFile seam used by the Media3 DataSource.
RemoteClients now routes nfs:// URLs and the nfs protocol to it, which
turns on NFS thumbnails, folder scans, streaming, and rename through
the already-generalized pipeline."
```

---

### Task 10: Protocol-aware connect dialog

**Files:**
- Rename: `app/src/plus/res/layout/dialog_smb_connect.xml` → `dialog_network_connect.xml`
- Modify: `app/src/plus/java/org/iurl/litegallery/NetworkBrowseActivity.kt` (`showAddServerDialog`, `showEditServerDialog`, `showConnectDialog`, plus two new helpers)
- Modify: `app/src/plus/res/values/strings.xml`, `values-zh-rTW/strings.xml`, `values-zh-rCN/strings.xml`

**Interfaces:**
- Consumes: `NfsExportLister.listExports` (Task 8), `RemoteClients.forProtocol(...).testConnection(config)` (Tasks 2/9), `NetworkServerConfig` (Task 1).
- Produces: dialog view IDs used by the activity: `protocolGroup`, `protocolSmbRadio`, `protocolNfsRadio`, `smbFieldsGroup`, `nfsFieldsGroup`, `exportEditText`, `listExportsButton`, `nfsAdvancedCheckBox`, `nfsAdvancedGroup`, `uidEditText`, `gidEditText` (existing IDs `hostEditText`, `displayNameEditText`, `guestCheckBox`, `usernameEditText`, `passwordEditText` unchanged).

- [ ] **Step 1: Rename and extend the dialog layout** — `git mv dialog_smb_connect.xml dialog_network_connect.xml`, then replace its content with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp">

    <RadioGroup
        android:id="@+id/protocolGroup"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginBottom="8dp">

        <RadioButton
            android:id="@+id/protocolSmbRadio"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:checked="true"
            android:text="@string/protocol_smb" />

        <RadioButton
            android:id="@+id/protocolNfsRadio"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="@string/protocol_nfs" />
    </RadioGroup>

    <EditText
        android:id="@+id/hostEditText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/smb_host_hint"
        android:inputType="text"
        android:singleLine="true"
        android:visibility="gone"
        android:layout_marginBottom="8dp" />

    <EditText
        android:id="@+id/displayNameEditText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/smb_display_name_hint"
        android:inputType="text"
        android:singleLine="true"
        android:layout_marginBottom="8dp" />

    <LinearLayout
        android:id="@+id/smbFieldsGroup"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <CheckBox
            android:id="@+id/guestCheckBox"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:buttonTint="?attr/colorPrimary"
            android:text="@string/smb_guest_login"
            android:layout_marginBottom="8dp" />

        <EditText
            android:id="@+id/usernameEditText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="@string/smb_username_hint"
            android:inputType="text"
            android:singleLine="true"
            android:layout_marginBottom="8dp" />

        <EditText
            android:id="@+id/passwordEditText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="@string/smb_password_hint"
            android:inputType="textPassword"
            android:singleLine="true" />
    </LinearLayout>

    <LinearLayout
        android:id="@+id/nfsFieldsGroup"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:visibility="gone">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:layout_marginBottom="8dp">

            <AutoCompleteTextView
                android:id="@+id/exportEditText"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:hint="@string/nfs_export_hint"
                android:inputType="text"
                android:singleLine="true" />

            <Button
                android:id="@+id/listExportsButton"
                style="?android:attr/borderlessButtonStyle"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/nfs_list_exports" />
        </LinearLayout>

        <CheckBox
            android:id="@+id/nfsAdvancedCheckBox"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:buttonTint="?attr/colorPrimary"
            android:text="@string/nfs_show_advanced" />

        <LinearLayout
            android:id="@+id/nfsAdvancedGroup"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:visibility="gone">

            <EditText
                android:id="@+id/uidEditText"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:hint="@string/nfs_uid_hint"
                android:inputType="number"
                android:singleLine="true"
                android:layout_marginEnd="8dp" />

            <EditText
                android:id="@+id/gidEditText"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:hint="@string/nfs_gid_hint"
                android:inputType="number"
                android:singleLine="true" />
        </LinearLayout>
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 2: Add strings.** Append to `app/src/plus/res/values/strings.xml` before `</resources>`:

```xml
    <!-- Network protocol selection and NFS fields (plus flavor only) -->
    <string name="protocol_smb">SMB</string>
    <string name="protocol_nfs">NFS</string>
    <string name="nfs_export_hint">Export path (e.g. /volume1/photo)</string>
    <string name="nfs_list_exports">List exports</string>
    <string name="nfs_no_exports_found">No exports found — enter the path manually</string>
    <string name="nfs_show_advanced">Advanced (UID/GID)</string>
    <string name="nfs_uid_hint">UID</string>
    <string name="nfs_gid_hint">GID</string>
    <string name="nfs_enter_export">Please choose or enter an export path</string>
    <string name="nfs_error_unreachable">NFS service unreachable. Check that NFS is enabled on the server and the firewall allows ports 111 and 2049.</string>
    <string name="nfs_error_mount_denied">Mount denied. Check the export\'s IP allowlist and permissions, and allow connections from non-privileged ports in the NAS NFS settings.</string>
    <string name="nfs_error_access">Read permission denied. Adjust the advanced UID/GID values or the export\'s squash setting.</string>
```

Append to `values-zh-rTW/strings.xml`:

```xml
    <!-- Network protocol selection and NFS fields (plus flavor only) -->
    <string name="protocol_smb">SMB</string>
    <string name="protocol_nfs">NFS</string>
    <string name="nfs_export_hint">匯出路徑（例如 /volume1/photo）</string>
    <string name="nfs_list_exports">列出匯出點</string>
    <string name="nfs_no_exports_found">找不到匯出點，請手動輸入路徑</string>
    <string name="nfs_show_advanced">進階（UID/GID）</string>
    <string name="nfs_uid_hint">UID</string>
    <string name="nfs_gid_hint">GID</string>
    <string name="nfs_enter_export">請選擇或輸入匯出路徑</string>
    <string name="nfs_error_unreachable">無法連上 NFS 服務。請確認伺服器已啟用 NFS，且防火牆放行 111 與 2049 連接埠。</string>
    <string name="nfs_error_mount_denied">掛載被拒。請檢查匯出點的 IP 允許清單與權限，並在 NAS 的 NFS 設定允許非特權連接埠。</string>
    <string name="nfs_error_access">讀取權限不足。請調整進階 UID/GID 設定或匯出點的 squash 設定。</string>
```

Append to `values-zh-rCN/strings.xml`:

```xml
    <!-- Network protocol selection and NFS fields (plus flavor only) -->
    <string name="protocol_smb">SMB</string>
    <string name="protocol_nfs">NFS</string>
    <string name="nfs_export_hint">导出路径（例如 /volume1/photo）</string>
    <string name="nfs_list_exports">列出导出点</string>
    <string name="nfs_no_exports_found">找不到导出点，请手动输入路径</string>
    <string name="nfs_show_advanced">高级（UID/GID）</string>
    <string name="nfs_uid_hint">UID</string>
    <string name="nfs_gid_hint">GID</string>
    <string name="nfs_enter_export">请选择或输入导出路径</string>
    <string name="nfs_error_unreachable">无法连接 NFS 服务。请确认服务器已启用 NFS，且防火墙放行 111 与 2049 端口。</string>
    <string name="nfs_error_mount_denied">挂载被拒。请检查导出点的 IP 允许列表与权限，并在 NAS 的 NFS 设置中允许非特权端口。</string>
    <string name="nfs_error_access">读取权限不足。请调整高级 UID/GID 设置或导出点的 squash 设置。</string>
```

- [ ] **Step 3: Add dialog helpers to `NetworkBrowseActivity`.** Add these two private functions (near the dialog functions):

```kotlin
    /** Wire the SMB/NFS radio switch and the export-listing button of the connect dialog. */
    private fun bindProtocolSwitch(dialogView: View) {
        val smbRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.protocolSmbRadio)
        val nfsRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.protocolNfsRadio)
        val smbGroup = dialogView.findViewById<View>(R.id.smbFieldsGroup)
        val nfsGroup = dialogView.findViewById<View>(R.id.nfsFieldsGroup)
        val advancedCheck = dialogView.findViewById<android.widget.CheckBox>(R.id.nfsAdvancedCheckBox)
        val advancedGroup = dialogView.findViewById<View>(R.id.nfsAdvancedGroup)
        val hostEdit = dialogView.findViewById<EditText>(R.id.hostEditText)
        val exportEdit = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.exportEditText)
        val listExportsButton = dialogView.findViewById<android.widget.Button>(R.id.listExportsButton)

        val applyProtocol = {
            val nfs = nfsRadio.isChecked
            smbGroup.visibility = if (nfs) View.GONE else View.VISIBLE
            nfsGroup.visibility = if (nfs) View.VISIBLE else View.GONE
        }
        smbRadio.setOnCheckedChangeListener { _, _ -> applyProtocol() }
        nfsRadio.setOnCheckedChangeListener { _, _ -> applyProtocol() }
        applyProtocol()

        advancedCheck.setOnCheckedChangeListener { _, isChecked ->
            advancedGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        listExportsButton.setOnClickListener {
            val host = hostEdit.text.toString().trim().substringBefore('/')
            if (host.isBlank()) {
                Toast.makeText(this, R.string.smb_enter_host, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            listExportsButton.isEnabled = false
            lifecycleScope.launch {
                try {
                    val exports = withContext(Dispatchers.IO) { NfsExportLister.listExports(host) }
                    if (exports.isEmpty()) {
                        Toast.makeText(this@NetworkBrowseActivity, R.string.nfs_no_exports_found, Toast.LENGTH_SHORT).show()
                    } else {
                        exportEdit.setAdapter(
                            android.widget.ArrayAdapter(
                                this@NetworkBrowseActivity,
                                android.R.layout.simple_dropdown_item_1line,
                                exports
                            )
                        )
                        exportEdit.showDropDown()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NetworkBrowseActivity", "Export listing failed for $host", e)
                    Toast.makeText(
                        this@NetworkBrowseActivity,
                        getString(R.string.smb_connection_failed, e.message ?: "Unknown"),
                        Toast.LENGTH_SHORT
                    ).show()
                } finally {
                    listExportsButton.isEnabled = true
                }
            }
        }
    }

    /**
     * Read a NetworkServerConfig from the dialog fields, or null (after showing a
     * toast) when required fields are missing. [existingId] is empty for new servers.
     */
    private fun readConfigFromDialog(dialogView: View, existingId: String): NetworkServerConfig? {
        val nfsSelected = dialogView.findViewById<android.widget.RadioButton>(R.id.protocolNfsRadio).isChecked
        val hostInput = dialogView.findViewById<EditText>(R.id.hostEditText).text.toString().trim()
        val displayName = dialogView.findViewById<EditText>(R.id.displayNameEditText).text.toString().trim()

        if (hostInput.isBlank()) {
            Toast.makeText(this, R.string.smb_enter_host, Toast.LENGTH_SHORT).show()
            return null
        }

        if (nfsSelected) {
            val export = dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.exportEditText)
                .text.toString().trim().trimEnd('/')
            if (export.isBlank() || !export.startsWith("/")) {
                Toast.makeText(this, R.string.nfs_enter_export, Toast.LENGTH_SHORT).show()
                return null
            }
            val host = hostInput.removePrefix("nfs://").substringBefore('/')
            return NetworkServerConfig(
                id = existingId,
                displayName = displayName.ifBlank { host },
                host = host,
                protocol = NetworkServerConfig.PROTOCOL_NFS,
                export = export,
                uid = dialogView.findViewById<EditText>(R.id.uidEditText).text.toString().toIntOrNull() ?: 0,
                gid = dialogView.findViewById<EditText>(R.id.gidEditText).text.toString().toIntOrNull() ?: 0
            )
        }

        var parsedHost = hostInput
        var parsedShare = ""
        var parsedPath = ""
        val smbPath = SmbPath.parse(if (hostInput.startsWith("smb://")) hostInput else "smb://$hostInput")
        if (smbPath != null) {
            parsedHost = smbPath.host
            parsedShare = smbPath.share
            parsedPath = smbPath.path
        } else {
            val normalized = if (hostInput.startsWith("smb://")) hostInput else "smb://$hostInput"
            parsedHost = normalized.removePrefix("smb://").substringBefore("/")
        }
        return NetworkServerConfig(
            id = existingId,
            displayName = displayName.ifBlank { parsedHost },
            host = parsedHost,
            protocol = NetworkServerConfig.PROTOCOL_SMB,
            share = parsedShare,
            path = parsedPath,
            username = dialogView.findViewById<EditText>(R.id.usernameEditText).text.toString().trim(),
            password = dialogView.findViewById<EditText>(R.id.passwordEditText).text.toString(),
            isGuest = dialogView.findViewById<android.widget.CheckBox>(R.id.guestCheckBox).isChecked
        )
    }
```

- [ ] **Step 4: Rewrite the three dialog functions.** Replace `showAddServerDialog`, `showEditServerDialog`, and `showConnectDialog` entirely with:

```kotlin
    /** Save-with-test shared by add and edit. Runs testConnection for the config's protocol. */
    private fun testAndSaveServer(config: NetworkServerConfig, isNew: Boolean, onSuccess: () -> Unit) {
        val client = RemoteClients.forProtocol(config.protocol)
        if (client == null) {
            Toast.makeText(this, R.string.smb_connection_test_failed, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            try {
                val success = withContext(Dispatchers.IO) { client.testConnection(config) }
                if (success) {
                    if (isNew) {
                        NetworkConfigStore.addServer(this@NetworkBrowseActivity, config)
                    } else {
                        NetworkConfigStore.updateServer(this@NetworkBrowseActivity, config)
                    }
                    refreshSavedServers()
                    Toast.makeText(this@NetworkBrowseActivity, R.string.smb_server_saved, Toast.LENGTH_SHORT).show()
                    onSuccess()
                } else {
                    val hint = if (config.isNfs) R.string.nfs_error_mount_denied else R.string.smb_connection_test_failed
                    Toast.makeText(this@NetworkBrowseActivity, hint, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@NetworkBrowseActivity,
                    getString(R.string.smb_connection_failed, e.message ?: "Unknown"),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun showAddServerDialog(prefillNfsHost: String? = null) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_network_connect, null)
        val hostEdit = dialogView.findViewById<EditText>(R.id.hostEditText)
        hostEdit.visibility = View.VISIBLE
        bindProtocolSwitch(dialogView)

        val guestCheckBox = dialogView.findViewById<android.widget.CheckBox>(R.id.guestCheckBox)
        val usernameEdit = dialogView.findViewById<EditText>(R.id.usernameEditText)
        val passwordEdit = dialogView.findViewById<EditText>(R.id.passwordEditText)
        guestCheckBox.setOnCheckedChangeListener { _, isChecked ->
            usernameEdit.isEnabled = !isChecked
            passwordEdit.isEnabled = !isChecked
        }

        if (prefillNfsHost != null) {
            dialogView.findViewById<android.widget.RadioButton>(R.id.protocolNfsRadio).isChecked = true
            hostEdit.setText(prefillNfsHost)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.smb_add_server)
            .setView(dialogView)
            .setPositiveButton(R.string.smb_save) { _, _ ->
                val config = readConfigFromDialog(dialogView, existingId = "") ?: return@setPositiveButton
                testAndSaveServer(config, isNew = true) {}
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditServerDialog(server: NetworkServerConfig) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_network_connect, null)
        val hostEdit = dialogView.findViewById<EditText>(R.id.hostEditText)
        val displayNameEdit = dialogView.findViewById<EditText>(R.id.displayNameEditText)
        hostEdit.visibility = View.VISIBLE
        bindProtocolSwitch(dialogView)

        val smbRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.protocolSmbRadio)
        val nfsRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.protocolNfsRadio)
        // The protocol of a saved server is fixed; hide the switch when editing.
        dialogView.findViewById<View>(R.id.protocolGroup).visibility = View.GONE
        if (server.isNfs) nfsRadio.isChecked = true else smbRadio.isChecked = true

        displayNameEdit.setText(server.displayName)
        if (server.isNfs) {
            hostEdit.setText(server.host)
            dialogView.findViewById<android.widget.AutoCompleteTextView>(R.id.exportEditText).setText(server.export)
            dialogView.findViewById<EditText>(R.id.uidEditText).setText(server.uid.toString())
            dialogView.findViewById<EditText>(R.id.gidEditText).setText(server.gid.toString())
            if (server.uid != 0 || server.gid != 0) {
                dialogView.findViewById<android.widget.CheckBox>(R.id.nfsAdvancedCheckBox).isChecked = true
            }
        } else {
            val fullAddress = if (server.share.isNotBlank()) {
                val p = if (server.path.isNotBlank()) "/${server.path}" else ""
                "${server.host}/${server.share}$p"
            } else server.host
            hostEdit.setText(fullAddress)
            val usernameEdit = dialogView.findViewById<EditText>(R.id.usernameEditText)
            val passwordEdit = dialogView.findViewById<EditText>(R.id.passwordEditText)
            val guestCheckBox = dialogView.findViewById<android.widget.CheckBox>(R.id.guestCheckBox)
            usernameEdit.setText(server.username)
            passwordEdit.setText(server.password)
            guestCheckBox.isChecked = server.isGuest
            usernameEdit.isEnabled = !server.isGuest
            passwordEdit.isEnabled = !server.isGuest
            guestCheckBox.setOnCheckedChangeListener { _, isChecked ->
                usernameEdit.isEnabled = !isChecked
                passwordEdit.isEnabled = !isChecked
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.smb_edit_server)
            .setView(dialogView)
            .setPositiveButton(R.string.smb_save) { _, _ ->
                val config = readConfigFromDialog(dialogView, existingId = server.id) ?: return@setPositiveButton
                testAndSaveServer(config, isNew = false) {}
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** SMB credential prompt used when navigating to an unknown SMB host from the address bar. */
    private fun showConnectDialog(host: String, share: String, path: String = "", defaultName: String = host, onSuccess: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_network_connect, null)
        // This prompt is SMB-only: hide the protocol switch and keep SMB fields.
        dialogView.findViewById<View>(R.id.protocolGroup).visibility = View.GONE
        val usernameEdit = dialogView.findViewById<EditText>(R.id.usernameEditText)
        val passwordEdit = dialogView.findViewById<EditText>(R.id.passwordEditText)
        val displayNameEdit = dialogView.findViewById<EditText>(R.id.displayNameEditText)
        val guestCheckBox = dialogView.findViewById<android.widget.CheckBox>(R.id.guestCheckBox)

        displayNameEdit.setText(defaultName)

        guestCheckBox.setOnCheckedChangeListener { _, isChecked ->
            usernameEdit.isEnabled = !isChecked
            passwordEdit.isEnabled = !isChecked
            if (isChecked) {
                usernameEdit.setText("")
                passwordEdit.setText("")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.smb_connect_to, host))
            .setView(dialogView)
            .setPositiveButton(R.string.smb_connect) { _, _ ->
                val config = NetworkServerConfig(
                    id = "",
                    displayName = displayNameEdit.text.toString().trim(),
                    host = host,
                    share = share,
                    path = path,
                    username = usernameEdit.text.toString().trim(),
                    password = passwordEdit.text.toString(),
                    isGuest = guestCheckBox.isChecked
                )
                testAndSaveServer(config, isNew = true, onSuccess = onSuccess)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
```

Delete the old bodies of all three functions (the new `testAndSaveServer` replaces their duplicated test-and-save blocks). `showAddServerDialog()` is still called with no arguments from `setupSavedServers` — the new default parameter keeps that call site working.

- [ ] **Step 5: Build and run tests**

Run: `.\gradlew.bat testPlusDebugUnitTest assemblePlusDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/plus
git commit -m "feat(plus): protocol-aware server dialog with NFS export listing

Extend the connect dialog with an SMB/NFS switch. NFS servers are
configured with host and export path, with a button that queries the
server's export list through NfsExportLister and offers the results in
a dropdown, plus optional advanced UID/GID fields. Saving tests the
connection through the protocol's RemoteFileClient. The duplicated
test-and-save logic of the three dialogs is unified in one helper."
```

---

### Task 11: Wire NFS into the browse flows, error hints, and titles

**Files:**
- Modify: `app/src/plus/java/org/iurl/litegallery/NetworkBrowseActivity.kt`
- Modify: `app/src/plus/java/org/iurl/litegallery/RemoteMediaScanner.kt` (`listDirectory` error propagation)
- Modify: `app/src/plus/java/org/iurl/litegallery/NetworkMediaSource.kt` (`homeEntry` title)
- Modify: `app/src/plus/res/layout/item_network_server.xml` (protocol badge)
- Modify: `app/src/plus/res/layout/activity_network_browse.xml` (address hint)
- Modify: `app/src/plus/AndroidManifest.xml` (label)
- Modify: `app/src/plus/res/values/strings.xml` + zh-rTW + zh-rCN (`network_browse_title`, `network_address_hint`)

**Interfaces:**
- Consumes: `NfsPath`, `NfsFileClient.NfsException`, `NfsExportLister.ExportListException`, `NetworkServerConfig.rootUrl()`.
- Produces: user-visible NFS browsing; no new APIs.

- [ ] **Step 1: Let listing errors reach the UI** — in `RemoteMediaScanner.listDirectory`, remove the try/catch so exceptions propagate (the browse activity already catches around it and shows a toast; swallowing them here showed "empty folder" instead of the real error):

```kotlin
    /**
     * List directories and media files in a remote folder (for browsing).
     * Listing failures propagate so the browse UI can show the cause.
     */
    suspend fun listDirectory(folderUrl: String): List<RemoteFileInfo> = withContext(Dispatchers.IO) {
        val client = RemoteClients.forUrl(folderUrl)
            ?: throw java.io.IOException("Unsupported address: $folderUrl")
        client.listFiles(context, folderUrl)
            .sortedWith(compareByDescending<RemoteFileInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
    }
```

- [ ] **Step 2: Actionable NFS error hints** — add to `NetworkBrowseActivity`:

```kotlin
    /** Best-effort mapping of an NFS failure to an actionable hint string, or null. */
    private fun nfsErrorHintRes(e: Throwable): Int? {
        var t: Throwable? = e
        while (t != null) {
            val message = t.message ?: ""
            when {
                t is java.net.ConnectException || t is java.net.SocketTimeoutException ||
                    t is java.net.NoRouteToHostException || t is java.net.UnknownHostException ||
                    t is NfsExportLister.ExportListException ->
                    return R.string.nfs_error_unreachable
                message.contains("NFS3ERR_ACCES", ignoreCase = true) ||
                    message.contains("ACCES", ignoreCase = true) ->
                    return R.string.nfs_error_access
                message.contains("mount", ignoreCase = true) ->
                    return R.string.nfs_error_mount_denied
            }
            t = t.cause
        }
        return null
    }
```

In `navigateTo`'s catch block, replace the Toast with:

```kotlin
                val hintRes = if (NfsPath.isNfs(smbAddress)) nfsErrorHintRes(e) else null
                val message = if (hintRes != null) {
                    getString(hintRes)
                } else {
                    getString(R.string.smb_connection_failed, e.message ?: "Unknown error")
                }
                Toast.makeText(this@NetworkBrowseActivity, message, Toast.LENGTH_LONG).show()
```

(Also rename the `navigateTo` parameter from `smbAddress` to `address` and update its internal references — it now carries both schemes.)

- [ ] **Step 3: Address bar accepts nfs://** — in `setupAddressBar`'s connect-button listener, replace the normalization + credential-check block with:

```kotlin
            val normalized = when {
                address.startsWith("smb://") || address.startsWith("nfs://") -> address
                address.startsWith("\\\\") || address.startsWith("//") ->
                    "smb://${address.removePrefix("\\\\").removePrefix("//").replace('\\', '/')}"
                else -> "smb://$address"
            }

            if (binding.addressEditText.text.toString() != normalized) {
                binding.addressEditText.setText(normalized)
                binding.addressEditText.setSelection(normalized.length)
            }

            if (NfsPath.isNfs(normalized)) {
                val nfsPath = NfsPath.parse(normalized)
                if (nfsPath == null) {
                    Toast.makeText(this, getString(R.string.smb_invalid_address), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val hasConfig = NetworkConfigStore.getAllServers(this).any {
                    it.isNfs && it.host.equals(nfsPath.host, ignoreCase = true) &&
                        NfsPath.matchExport(listOf(it.export), nfsPath.fullPath) != null
                }
                if (hasConfig) {
                    navigateTo(normalized)
                } else {
                    // No saved export covers this URL: collect one via the add dialog.
                    showAddServerDialog(prefillNfsHost = nfsPath.host)
                }
                return@setOnClickListener
            }

            // SMB flow (unchanged below)
            val smbPath = SmbPath.parse(normalized)
```

…and keep the rest of the existing SMB logic (`smbPath == null` toast, exact-config check, host-config check, `showConnectDialog`) as is, substituting `normalized` for the old `smbAddress` variable name.

- [ ] **Step 4: Saved-server rows show protocol and open NFS roots** — in `item_network_server.xml`, add before the `serverNameText` TextView (inside the row's text container, matching the file's existing style/attribute conventions):

```xml
            <TextView
                android:id="@+id/serverProtocolBadge"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textAppearance="?attr/textAppearanceLabelSmall"
                android:textColor="?attr/colorPrimary"
                android:text="@string/protocol_smb" />
```

In `setupSavedServersList`, after `nameText.text = …` add / replace the host line and click handler:

```kotlin
            val badge = itemView.findViewById<TextView>(R.id.serverProtocolBadge)
            badge.setText(if (server.isNfs) R.string.protocol_nfs else R.string.protocol_smb)

            if (server.isNfs) {
                hostText.text = "${server.host}${server.export} (uid ${server.uid})"
            } else {
                val sharePart = if (server.share.isNotBlank()) "/${server.share}" else ""
                val pathPart = if (server.path.isNotBlank()) "/${server.path}" else ""
                val connectionInfo = if (server.isGuest) "(Guest)" else "(${server.username})"
                hostText.text = "${server.host}$sharePart$pathPart $connectionInfo"
            }

            itemView.setOnClickListener {
                if (server.isNfs) {
                    binding.addressEditText.setText(server.rootUrl())
                    navigateTo(server.rootUrl())
                } else {
                    val address = if (server.share.isNotBlank()) {
                        val p = if (server.path.isNotBlank()) "/${server.path}" else ""
                        "smb://${server.host}/${server.share}$p"
                    } else {
                        "smb://${server.host}"
                    }
                    binding.addressEditText.setText(address)
                    if (server.share.isNotBlank()) {
                        binding.connectButton.performClick()
                    } else {
                        Toast.makeText(this, getString(R.string.smb_enter_share_name), Toast.LENGTH_SHORT).show()
                    }
                }
            }
```

The delete handler's `SmbClient.disconnect(server.host)` becomes:

```kotlin
                        RemoteClients.forProtocol(server.protocol)?.disconnect(server.host)
```

- [ ] **Step 5: Star button and navigate-up understand NFS** — in `updateStarButtonState` and the `saveServerButton` click listener, compute saved-state generically. Replace the body of `updateStarButtonState` with:

```kotlin
        val address = binding.addressEditText.text.toString().trim().trimEnd('/')
        if (address.isBlank()) {
            setSaveServerButtonSaved(false)
            return
        }
        val normalized = if (RemotePath.isRemote(address)) address else "smb://$address"
        val isSaved = NetworkConfigStore.getAllServers(this).any {
            it.rootUrl().equals(normalized, ignoreCase = true)
        }
        setSaveServerButtonSaved(isSaved)
```

In the `saveServerButton` listener, before the SMB-specific logic, add:

```kotlin
            if (NfsPath.isNfs(address)) {
                val nfsPath = NfsPath.parse(address) ?: return@setOnClickListener
                val saved = NetworkConfigStore.getAllServers(this).find {
                    it.isNfs && it.rootUrl().equals(address.trimEnd('/'), ignoreCase = true)
                }
                if (saved != null) {
                    NetworkConfigStore.deleteServer(this, saved.id)
                    Toast.makeText(this, getString(R.string.smb_server_removed), Toast.LENGTH_SHORT).show()
                    updateStarButtonState()
                    refreshSavedServers()
                } else {
                    showAddServerDialog(prefillNfsHost = nfsPath.host)
                }
                return@setOnClickListener
            }
```

Replace `navigateUp` with:

```kotlin
    private fun navigateUp() {
        val parent = when {
            SmbPath.isSmb(currentPath) ->
                SmbPath.parse(currentPath)?.let { if (it.path.isBlank()) null else it.parentFolderPath }
            NfsPath.isNfs(currentPath) -> nfsParentOrNull(currentPath)
            else -> null
        }
        if (parent == null) goHome() else navigateTo(parent)
    }

    /** Parent URL of an NFS folder, or null when at (or above) the saved export root. */
    private fun nfsParentOrNull(url: String): String? {
        val nfsPath = NfsPath.parse(url) ?: return null
        val exports = NetworkConfigStore.getAllServers(this)
            .filter { it.isNfs && it.host.equals(nfsPath.host, ignoreCase = true) }
            .map { it.export }
        val export = NfsPath.matchExport(exports, nfsPath.fullPath) ?: return null
        if (nfsPath.fullPath == export) return null
        val parent = nfsPath.fullPath.substringBeforeLast('/', "")
        return if (parent.isEmpty() || NfsPath.matchExport(exports, parent) == null) {
            null
        } else {
            NfsPath.toUrl(nfsPath.host, parent)
        }
    }
```

- [ ] **Step 6: Neutral titles** — string changes:
  - In all three `strings.xml` files delete `smb_browse_title` and `smb_address_hint`, and add:
    - values: `<string name="network_browse_title">Network Shares</string>` and `<string name="network_address_hint">smb:// or nfs:// address</string>`
    - values-zh-rTW: `<string name="network_browse_title">網路分享</string>` and `<string name="network_address_hint">smb:// 或 nfs:// 位址</string>`
    - values-zh-rCN: `<string name="network_browse_title">网络分享</string>` and `<string name="network_address_hint">smb:// 或 nfs:// 地址</string>`
  - Replace every `R.string.smb_browse_title` reference with `R.string.network_browse_title` (`NetworkBrowseActivity.setupToolbar`, `goHome`, `navigateTo`'s fallback title, `NetworkMediaSource.homeEntry`) and `@string/smb_browse_title` → `@string/network_browse_title` in `app/src/plus/AndroidManifest.xml`.
  - In `activity_network_browse.xml` replace `@string/smb_address_hint` with `@string/network_address_hint`.
  - In `navigateTo`, replace the SmbPath-based toolbar title with the generic form:

```kotlin
        supportActionBar?.title = address.trimEnd('/').substringAfterLast('/')
            .ifBlank { getString(R.string.network_browse_title) }
```

- [ ] **Step 7: Build, test both flavors**

Run: `.\gradlew.bat testCoreDebugUnitTest testPlusDebugUnitTest assemblePlusDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add -A app/src/plus
git commit -m "feat(plus): browse, bookmark, and error UX for NFS shares

Wire NFS into the browse activity: the address bar accepts nfs://
URLs, saved-server rows show a protocol badge and open NFS export
roots directly, the star button bookmarks or removes NFS servers, and
up-navigation stops at the export root. Listing errors now propagate
to the UI, and NFS failures map to actionable hints (service
unreachable, mount denied or non-privileged port blocked, permission
denied). The home entry and browse screen adopt the protocol-neutral
'Network Shares' title."
```

- [ ] **Step 9: HUMAN CHECKPOINT — real-NAS verification.** Stop and ask the human partner to run the app against their NAS before continuing:

```powershell
.\gradlew.bat installPlusDebug
```

Verify, in order: (1) add an NFS server — export list populates and test connection succeeds; (2) browse folders with thumbnails; (3) open a photo; (4) play a video and drag the seek bar; (5) rename a file; (6) unplug/disable NFS and confirm the actionable error toasts; (7) existing SMB server still browses and streams. If the EMC library misbehaves on-device (spec risk section), report findings and stop — the fallback decision (dCache nfs4j or a minimal hand-rolled NFSv3 READ subset) needs a spec revision first.

---

### Task 12: ProGuard rules for release builds

**Files:**
- Rename: `app/proguard-smb.pro` → `app/proguard-network.pro`
- Modify: `app/build.gradle`

- [ ] **Step 1: Rename and extend** — `git mv app/proguard-smb.pro app/proguard-network.pro`, change the header comment to `# Network-share keep rules — applied only to the 'plus' flavor (see app/build.gradle).` and append:

```
# EMC nfs-client (pure-Java NFSv3)
-keep class com.emc.ecs.nfsclient.** { *; }
-dontwarn com.emc.ecs.nfsclient.**
```

- [ ] **Step 2: Update the build file** — in `app/build.gradle` the plus flavor line becomes `proguardFiles 'proguard-network.pro'` and its comment becomes `// Adds SMB + NFS network shares (INTERNET permission, smbj, and nfs-client live only here).`

- [ ] **Step 3: Verify the release build**

Run: `.\gradlew.bat assemblePlusRelease`
Expected: BUILD SUCCESSFUL (R8 completes without missing-class errors).

- [ ] **Step 4: Commit**

```bash
git add -A app/proguard-network.pro app/proguard-smb.pro app/build.gradle
git commit -m "chore(plus): cover NFS client in release shrink rules

Rename proguard-smb.pro to proguard-network.pro and keep the EMC
nfs-client classes, matching the existing blanket keep for smbj. The
plus release build compiles and shrinks cleanly with both network
stacks."
```

---

### Task 13: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Apply the documentation changes:**
  - Editions table Plus row: `Adds optional **SMB / NFS** network-share browsing/streaming (requests `INTERNET`)` and the "Use it if" cell mentions NFS NAS exports.
  - The sentence under the table: `only the optional network-share features (SMB and NFS) — and the network permission they require — are exclusive to **Plus**.`
  - The blockquote in Privacy First: mention `SMB / NFS network-share support`.
  - "Network Shares (Plus edition)" feature section becomes:

```markdown
### Network Shares (Plus edition)
- **SMB browsing** - Browse photos and videos on SMB / Windows network shares
- **NFS browsing** - Browse photos and videos on NFSv3 exports (auto-lists server exports; the NAS must allow connections from non-privileged ports)
- **Streaming playback** - Stream videos directly from a share with seek support
- **Saved servers** - Bookmark servers: guest or username/password login for SMB, export path with optional UID/GID for NFS
```

  - Permissions section: `**Plus edition** additionally requests INTERNET and ACCESS_NETWORK_STATE, used solely for SMB / NFS network shares.`
  - "Current unit test focus" list: change the SMB line to `SMB and NFS path parsing, network server config persistence, NFS export-list RPC encoding, and remote Glide image detection (plus flavor)`.
  - Build-variant table texts mentioning "SMB support": change to "SMB/NFS support".

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: document NFS support in the Plus edition

Update the editions table, feature list, permissions notes, and test
overview to cover NFSv3 network shares alongside SMB, including the
non-privileged-port requirement on the NAS side."
```

---

### Task 14: Final verification (HUMAN)

- [ ] **Step 1: Full automated check**

Run: `.\gradlew.bat testCoreDebugUnitTest testPlusDebugUnitTest assembleCoreRelease assemblePlusRelease`
Expected: BUILD SUCCESSFUL — both flavors, unit tests green.

- [ ] **Step 2: Manual acceptance checklist** (human partner, from the spec):
  1. Add an NFS server: exports auto-listed, connection test passes.
  2. Browse folders; thumbnails load.
  3. Open an image in the viewer; swipe between items.
  4. Stream a video and seek with the progress bar.
  5. Rename a file; list and viewer stay in sync.
  6. Error hints: NFS service stopped, export IP not allowed, non-privileged port rejected.
  7. Full SMB regression: browse, thumbnails, stream + seek, rename, bookmarks.
  8. Core flavor: builds and contains no network code or permissions (inspect merged manifest of `coreRelease`).

- [ ] **Step 3: Wrap up** — when the checklist passes, use the superpowers:finishing-a-development-branch skill to decide merge/PR handling.

---

## Self-Review Notes

- Spec coverage: library + privileged-ports (Task 7/9, hint strings Task 10), URL format + longest-prefix (Tasks 7/9), config compat (Task 1), single home entry (Tasks 5/11), export auto-list with manual fallback (Tasks 8/10), streaming seek via preserved buffer logic (Task 4/9), rename parity (Tasks 5/9), error table (Tasks 10/11 — the "non-privileged port" and "mount denied" rows share one hint string because NFS error replies do not reliably distinguish them), tests (Tasks 1,2,3,7,8,9), phases/commits (each task commits), ProGuard (Task 12), README (Task 13), manual checklist + on-device risk gate (Tasks 11/14).
- Type consistency: `RemoteFileInfo.url`, `RemoteRandomAccessFile.readAt(fileOffset, buffer, bufferOffset, length)`, `NetworkServerConfig.rootUrl()`, `NfsFileClient.resolveTarget` are used with identical signatures across tasks.
- EMC API signatures were verified against the library source; Task 9 carries the adapt-at-call-site instruction if a signature drifted.
