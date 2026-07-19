# Plus Edition NFS 支援 — 設計文件

日期：2026-07-20
狀態：已與需求方確認（brainstorming 流程產出）

## 目標

在 **Plus edition** 新增 NFS 網路分享支援，功能與現有 SMB 完全對等：瀏覽資料夾、縮圖、
影片串流播放（含 seek）、重新命名、伺服器書籤。`core` flavor 維持完全無網路，不受任何影響。

## 已確認的需求決策

| 決策點 | 結論 |
| --- | --- |
| 協定版本 | NFSv3（純 Java 函式庫；不做 v4，架構保留換底層的空間） |
| 功能範圍 | 與 SMB 完全對等 |
| UI 呈現 | 首頁單一「網路分享」入口，伺服器列表同時列出 SMB 與 NFS |
| Export 指定 | 自動列出伺服器 exports（等同 `showmount -e`），保留手動輸入後備 |
| 架構 | 方案 A：抽出協定無關的 `RemoteFileClient` 層，SMB/NFS 共用 UI 與媒體管線 |

## 函式庫選型

**[com.emc.ecs:nfs-client 1.1.0](https://github.com/EMCECS/nfs-client-java)**

- 純 Java NFSv3（RFC 1813），無 native code，符合現有 smbj 的整合模式。
- Apache License 2.0，與本專案 GPL v3 相容。
- `Nfs3` / `Nfs3File` 支援按位移隨機讀取，可支撐 Media3 串流 seek。
- 具 `usePrivilegedPorts` 選項：Android 應用無法綁定 <1024 的 port，一律設 `false`。
  NAS 端需允許非特權連接埠（Synology / QNAP 皆有此選項），相關錯誤提示見錯誤處理一節。
- 認證使用 `CredentialUnix(uid, gid)`（NFSv3 AUTH_UNIX，無帳號密碼概念）。

僅以 `plusImplementation` 引入，`core` flavor 不含任何 NFS 程式碼。

## 架構

現有 main source set 的 `MediaSource` 介面接縫不變。plus flavor 內部沿「協定無關」
的線重新切分：

```
main:   MediaSource (介面，不變)  ←  MediaSourceRegistry  ←  MediaSourceBootstrap (flavor 各自實作)
plus:   NetworkMediaSource ─┬─ NetworkBrowseActivity (UI)
                            ├─ RemoteMediaScanner
                            ├─ RemoteModelLoader (Glide)
                            ├─ RemoteDataSource (Media3)
                            └─ RemoteFileClient (介面)
                                   ├─ SmbFileClient (包現有 SmbClient 邏輯)
                                   └─ NfsFileClient (包 EMC nfs-client) ── NfsExportLister
```

### 新抽象層

- **`RemoteFileClient`（介面）**：`listFiles`、`openInputStream`、
  `openRandomAccess → RemoteRandomAccessFile`（`size()` / `readAt(offset, buf, bufOff, len)` /
  `close()`）、`getFileSize`、`rename`、`fileExists`、`testConnection`、`disconnect`。
  檔案列舉回傳 `RemoteFileInfo`（取代 `SmbFileInfo`，欄位相同）。
- **`SmbFileClient`**：現有 `SmbClient` 改為實作此介面，行為零變化。
- **`NfsFileClient`（新）**：包 `Nfs3`/`Nfs3File`。連線（mount 結果）以 `host|export`
  為鍵快取於 `ConcurrentHashMap`（mount 需經 portmapper + MOUNT + FSINFO，成本高）；
  stale 連線偵測與單次重試比照 SMB 的 `executeWithRetry` 模式。
- **`NfsExportLister`（新，獨立小元件）**：查詢伺服器 export 清單。優先使用函式庫
  公開的 EXPORT 呼叫；若未公開則自寫最小 SUNRPC 查詢（portmapper GETPORT +
  MOUNT program 的 EXPORT procedure，約 200 行，XDR 編解碼為純函數、可單元測試）。

### 路徑格式

`nfs://host/volume1/photo/sub/file.jpg` — export 與其內部路徑直接相連（同 libnfs 慣例）。

Export 路徑本身含斜線，無法純語法切分邊界。**由 `NfsFileClient` 內部以「該主機已儲存
設定中最長 export 前綴比對」解析**；同主機多個 export 時取最長相符者；無相符設定時
回報明確錯誤（無設定本來就無法連線，此限制可接受）。

`SmbPath` 保留不動；新增 `NfsPath`（組裝/拆解 `nfs://` URL、檔名/副檔名/父層等 helper，
形狀比照 `SmbPath`）；新增共用入口 `RemotePath.isRemote(path)` 判斷兩種 scheme。

### 設定模型與儲存

`SmbConfig` → **`NetworkServerConfig`**，新增欄位：

- `protocol: String` — `"smb"` 或 `"nfs"`
- `export: String` — NFS export 路徑（如 `/volume1/photo`）
- `uid: Int` / `gid: Int` — NFS 認證身分，預設 0/0，UI 藏於進階選項

既有 `port` 欄位對 NFS 不適用（連接埠由 portmapper 決定），NFS 項目忽略此欄位。

`SmbConfigStore` → **`NetworkConfigStore`**：**沿用原 SharedPreferences 檔名
（`smb_servers`）與 JSON 陣列格式**。舊資料缺 `protocol` 欄位時解析為 `"smb"` —
純加法欄位，無需遷移程式，既有書籤與設定備份/還原自然相容
（舊版 app 匯入新備份時會忽略 NFS 欄位，該項目退化為 SMB 設定，可接受）。

### UI

`SmbBrowseActivity` → **`NetworkBrowseActivity`**（760 行，重構但流程不變）：

- 伺服器列表：每筆顯示協定標示（SMB / NFS）。
- 新增/編輯伺服器對話框：頂部 SMB/NFS 切換。
  - SMB：host、port、share、guest/帳號密碼（現有欄位不變）。
  - NFS：host、export（下拉，由 `NfsExportLister` 查詢填入；查詢失敗或為空時
    引導手動輸入）、進階區塊收合 uid/gid。
- 「測試連線」對 NFS 的定義：portmapper 查得到 MOUNT 服務且 EXPORT 查詢成功回應。
- Layout：`dialog_smb_connect.xml` 擴充為單一 layout，依協定切換顯示欄位群組。

### 媒體管線

- **Glide**：`SmbModelLoader` → `RemoteModelLoader`，同時受理 `smb://` 與 `nfs://`
  圖片 URL，經 `RemoteFileClient.openInputStream()` 載入。`MediaSourceBootstrap`
  註冊處同步更新。
- **Media3**：`SmbDataSource` → `RemoteDataSource`，把 smbj `File` 依賴換成
  `RemoteRandomAccessFile`；**既有 4MB 讀取緩衝與自適應 chunk（64KB 起倍增）邏輯
  原封保留**。NFSv3 READ 為無狀態按位移讀取，seek 天然支援。
- **掃描**：`SmbMediaScanner` → `RemoteMediaScanner`，泛化於 client 介面之上。
- **`SmbMediaSource` → 單一 `NetworkMediaSource`**：`handles()` 受理兩種 scheme、
  `homeEntry()` 計數所有伺服器、`browseIntent()` 指向 `NetworkBrowseActivity`、
  `rename()` 依 scheme 分流至對應 client。首頁維持單一入口。

## 資料流

1. **瀏覽**：`NetworkBrowseActivity` → 依伺服器協定取 client → `listFiles()` →
   `RemoteFileInfo` 列表 → 縮圖由 Glide 經 `RemoteModelLoader` 載入。
2. **看圖**：viewer 收到 `nfs://…` → `MediaSourceRegistry` 路由至 `NetworkMediaSource` →
   Glide 同上。
3. **播片**：`NetworkMediaSource.createExoMediaSource()` → `ProgressiveMediaSource` +
   `RemoteDataSourceFactory` → `RemoteRandomAccessFile.readAt()`。
4. **重命名**：viewer → `NetworkMediaSource.rename()` → 對應 client → `RenameOutcome`。

所有 NFS I/O 於 `Dispatchers.IO` 執行，比照現況。連線快取於 `shutdown()` 與手動
斷線時釋放。

## 錯誤處理

沿用現有 SMB 的錯誤呈現機制（toast / 對話框）。NFS 專屬錯誤給出可行動提示：

| 情境 | 偵測 | 使用者提示 |
| --- | --- | --- |
| Portmapper / NFS 服務連不上（port 111/2049） | 連線逾時 / 拒絕 | 確認 NAS 已啟用 NFS 服務、防火牆放行 |
| Mount 被拒 | MOUNT 狀態碼 EACCES / AUTH_ERR | 檢查該 export 的 IP 允許清單與權限設定 |
| 非特權 port 被拒 | mount 失敗且伺服器要求 secure port | 在 NAS 的 NFS 設定啟用「允許非特權連接埠」 |
| 列不到 exports | EXPORT 回空清單 | 引導改用手動輸入 export 路徑 |
| 讀檔權限不足 | NFS3ERR_ACCES | 調整進階 UID/GID 或 export 的 squash 設定 |

連線中斷：偵測 stale → 清快取 → 重連一次（比照 SMB `executeWithRetry`）。

## 測試策略

單元測試（`testPlus`，JVM / Robolectric，不需真實 NAS）：

- `NfsPath`：URL 組裝/拆解、最長 export 前綴比對（含同主機多 export、無相符設定失敗案例）。
- `NetworkConfigStore`：新欄位 round-trip；舊版 JSON（無 `protocol`）解析為 SMB 的相容性測試。
- `NfsExportLister`：自寫 SUNRPC 時，XDR request 編碼與 response 解析的純函數測試。
- `RemoteModelLoader`：`nfs://` 圖片判斷（比照 `SmbModelLoaderTest`）。
- 既有 SMB 測試（`SmbPathTest`、`SmbConfigStoreTest`、`SmbModelLoaderTest`）改名後
  全數維持綠燈 — 重構不破壞 SMB 的主要防線。

真實 NFS I/O 不寫自動化整合測試（比照現有 SMB 做法），以下列**手動驗收清單**驗證：

1. 新增 NFS 伺服器：export 自動列出、測試連線成功。
2. 瀏覽資料夾與縮圖載入。
3. 開啟圖片檢視。
4. 影片串流播放並拖動進度條 seek。
5. 重新命名檔案後列表與檢視器同步更新。
6. 錯誤情境至少驗證：NFS 服務關閉、export IP 不允許、非特權 port 被拒三種提示。
7. SMB 既有功能全部重測一輪（回歸）。
8. `core` flavor 編譯通過且無任何網路程式碼／權限變化。

## 實作階段

每階段結束時 SMB 功能完整可用、測試綠燈即 commit（細部 commit 拆分於實作計畫決定）：

1. **純重構**：抽出 `RemoteFileClient` 介面與 `RemoteRandomAccessFile`，各元件改名
   （`NetworkBrowseActivity`、`RemoteModelLoader`、`RemoteDataSource`、
   `RemoteMediaScanner`、`NetworkMediaSource`、`NetworkConfigStore`），SMB 走新介面，
   行為零變化。
2. **NFS 底層**：加入依賴、`NfsFileClient`、`NfsPath`、config 擴充、`NfsExportLister`，
   附單元測試。**本階段開頭先以最小 spike 驗證 EMC nfs-client 於 Android 實機可用**
   （見風險一節）。
3. **UI 接線**：對話框協定切換、export 偵測、伺服器列表協定標示。
4. **媒體管線**：Glide / Media3 / scanner / rename 對 `nfs://` 全通。
5. **收尾**：ProGuard 規則（`proguard-smb.pro` 更名為 `proguard-network.pro` 並加入
   EMC client 的 keep 規則）、新增字串比照既有 SMB 字串的語系覆蓋範圍翻譯、
   README 更新（editions 表格與功能清單加入 NFS）。

## 範圍界線（明確不做）

- NFSv4 / Kerberos 認證
- NFS 寫入上傳（僅讀取 + 重命名，與 SMB 對等）
- WebDAV / FTP 等其他協定（架構已預留插入點但不實作）
- Export 之外的伺服器層瀏覽
- `core` flavor 任何變動

## 風險

- **EMC nfs-client 於 Android 的實際相容性**：函式庫使用標準 java.net/nio，理論可行
  但無官方 Android 支援。實作階段 2 開頭以最小 spike（實機 mount + 讀檔）驗證；
  失敗備案為 dCache nfs4j 或自寫最小 NFSv3 READ 子集，屆時回頭修訂本設計。
- **NAS 端設定依賴**：非特權 port、IP 允許清單、squash 設定皆可能導致連線失敗，
  以錯誤處理一節的可行動提示緩解。
