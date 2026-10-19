package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.iurl.litegallery.databinding.ActivityPhotoEditorBinding
import org.iurl.litegallery.theme.ThemeVariant
import java.io.File
import java.io.IOException

/**
 * Crops and rotates one picture, on the phone or on a network share. The result is saved as a
 * copy next to the original (default) or replaces the original.
 */
class PhotoEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val RESULT_SAVED_PATH = "result_saved_path"
        const val RESULT_REPLACED = "result_replaced"

        private const val PREVIEW_MAX_SIDE = 2048
        private const val WORK_DIRECTORY = "photo_edit"

        fun supports(path: String): Boolean = PhotoEditing.supports(path)
    }

    private class AspectOption(val label: String, val ratio: (CropImageView) -> Float?)

    private lateinit var binding: ActivityPhotoEditorBinding
    private lateinit var aspectOptions: List<AspectOption>

    private var sourcePath = ""
    private var loaded: PhotoEditing.Loaded? = null
    private var rotation = 0
    private var rotatedPreview: Bitmap? = null
    private var selectedAspect = 0
    private var sourceLastModified = 0L
    private var isSaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyTheme(this)
        ThemeHelper.applyPackTheme(this, ThemeVariant.NoActionBar)

        super.onCreate(savedInstanceState)
        ThemeHelper.captureCustomThemeGeneration(this)
        binding = ActivityPhotoEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.editorRoot) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            windowInsets
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!isSaving) finish()
                }
            }
        )

        sourcePath = intent.getStringExtra(EXTRA_PATH).orEmpty()
        if (!canEdit(sourcePath)) {
            Toast.makeText(this, R.string.photo_editor_unsupported, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupToolbar()
        setupAspectChips()
        setupButtons()
        ThemeHelper.applyRuntimeCustomColors(this)
        loadPicture()
    }

    override fun onDestroy() {
        if (isFinishing) {
            File(cacheDir, WORK_DIRECTORY).deleteRecursively()
        }
        super.onDestroy()
    }

    private fun canEdit(path: String): Boolean {
        if (path.isBlank() || !PhotoEditing.supports(path)) return false
        val source = MediaSourceRegistry.forPath(path)
        return source == null || source.supportsFileOperations(path)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            if (!isSaving) finish()
        }
        binding.toolbar.inflateMenu(R.menu.photo_editor_menu)
        binding.toolbar.menu.findItem(R.id.action_save_edit)?.isEnabled = false
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_save_edit) {
                showSaveDialog()
                true
            } else {
                false
            }
        }
    }

    private fun setupAspectChips() {
        aspectOptions = listOf(
            AspectOption(getString(R.string.photo_editor_ratio_free)) { null },
            AspectOption(getString(R.string.photo_editor_ratio_original)) { it.imageAspect() },
            AspectOption("1:1") { 1f },
            AspectOption("4:3") { 4f / 3f },
            AspectOption("3:4") { 3f / 4f },
            AspectOption("16:9") { 16f / 9f },
            AspectOption("9:16") { 9f / 16f }
        )

        aspectOptions.forEachIndexed { index, option ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = option.label
                isCheckable = true
                isChecked = index == selectedAspect
                setOnClickListener {
                    selectedAspect = index
                    binding.cropView.setAspect(option.ratio(binding.cropView))
                }
            }
            binding.aspectChipGroup.addView(chip)
        }
    }

    private fun setupButtons() {
        binding.rotateLeftButton.setOnClickListener { rotate(-90) }
        binding.rotateRightButton.setOnClickListener { rotate(90) }
        binding.resetButton.setOnClickListener {
            val image = loaded ?: return@setOnClickListener
            rotation = 0
            selectedAspect = 0
            (binding.aspectChipGroup.getChildAt(0) as? Chip)?.isChecked = true
            showPreview(image)
        }
    }

    // ---------------------------------------------------------------- loading

    private fun loadPicture() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { prepare() }
                loaded = image
                showPreview(image)
                binding.toolbar.menu.findItem(R.id.action_save_edit)?.isEnabled = true
            } catch (e: Exception) {
                android.util.Log.e("PhotoEditorActivity", "Could not open $sourcePath", e)
                Toast.makeText(this@PhotoEditorActivity, R.string.photo_editor_failed, Toast.LENGTH_LONG).show()
                finish()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /** Makes the picture available as a local file (downloads it from a share) and opens it. */
    private fun prepare(): PhotoEditing.Loaded {
        val source = MediaSourceRegistry.forPath(sourcePath)
        val localFile: File
        if (source == null) {
            localFile = File(sourcePath)
            sourceLastModified = localFile.lastModified()
        } else {
            sourceLastModified = source.lastModified(applicationContext, sourcePath)
            val workDirectory = File(cacheDir, WORK_DIRECTORY)
            workDirectory.deleteRecursively()
            workDirectory.mkdirs()
            localFile = File(workDirectory, "source_${fileNameOf(sourcePath)}")
            source.openInput(applicationContext, sourcePath).use { input ->
                localFile.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
        }
        return PhotoEditing.load(localFile, PREVIEW_MAX_SIDE)
    }

    // ---------------------------------------------------------------- preview

    private fun rotate(delta: Int) {
        val image = loaded ?: return
        rotation = ((rotation + delta) % 360 + 360) % 360
        showPreview(image)
    }

    /** Shows the preview turned by the current rotation, with a fresh frame. */
    private fun showPreview(image: PhotoEditing.Loaded) {
        val previous = rotatedPreview
        val bitmap = if (rotation == 0) {
            image.preview
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(image.preview, 0, 0, image.preview.width, image.preview.height, matrix, true)
        }
        rotatedPreview = bitmap

        binding.cropView.setBitmap(bitmap)
        binding.cropView.setAspect(aspectOptions[selectedAspect].ratio(binding.cropView))

        if (previous != null && previous !== image.preview && previous !== bitmap) {
            previous.recycle()
        }
    }

    // ---------------------------------------------------------------- saving

    private fun showSaveDialog() {
        if (isSaving || loaded == null) return

        var choice = 0
        AlertDialog.Builder(this)
            .setTitle(R.string.photo_editor_save)
            .setSingleChoiceItems(
                arrayOf(
                    getString(R.string.photo_editor_save_copy),
                    getString(R.string.photo_editor_replace_original)
                ),
                0
            ) { _, which -> choice = which }
            .setPositiveButton(R.string.photo_editor_save) { _, _ -> save(replace = choice == 1) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun save(replace: Boolean) {
        val image = loaded ?: return

        // Writing to the phone's storage needs all-files access.
        if (MediaSourceRegistry.forPath(sourcePath) == null && !StorageAccess.ensure(this)) return

        isSaving = true
        val crop = binding.cropView.cropFraction()
        val rotationNow = rotation
        val progressDialog = AlertDialog.Builder(this)
            .setMessage(R.string.photo_editor_saving)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val savedPath = withContext(Dispatchers.IO) {
                    performSave(image, replace, crop, rotationNow)
                }
                Toast.makeText(this@PhotoEditorActivity, R.string.photo_editor_saved, Toast.LENGTH_SHORT).show()
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtra(RESULT_SAVED_PATH, savedPath)
                        .putExtra(RESULT_REPLACED, replace)
                )
                finish()
            } catch (e: Exception) {
                android.util.Log.e("PhotoEditorActivity", "Could not save the edited picture", e)
                Toast.makeText(this@PhotoEditorActivity, R.string.photo_editor_failed, Toast.LENGTH_LONG).show()
            } finally {
                isSaving = false
                if (progressDialog.isShowing) progressDialog.dismiss()
            }
        }
    }

    /** Renders the crop, then stores it as a copy or in place of the original. Returns the path. */
    private fun performSave(
        image: PhotoEditing.Loaded,
        replace: Boolean,
        crop: RectF,
        rotationNow: Int
    ): String {
        val workDirectory = File(cacheDir, WORK_DIRECTORY).apply { mkdirs() }
        val name = fileNameOf(sourcePath)
        val output = File(workDirectory, "edited_$name")
        PhotoEditing.render(image, rotationNow, crop, output, sourceLastModified)

        val source = MediaSourceRegistry.forPath(sourcePath)
        if (source == null) {
            val original = File(sourcePath)
            val savedPath: String
            if (replace) {
                // Write next to the original first, then swap, so a failure never damages it.
                val temp = File(original.parentFile, ".${original.name}.editing")
                output.copyTo(temp, overwrite = true)
                if (sourceLastModified > 0L) temp.setLastModified(sourceLastModified)
                if (!temp.renameTo(original)) {
                    temp.delete()
                    throw IOException("Could not replace ${original.name}")
                }
                savedPath = original.absolutePath
            } else {
                val folder = original.parentFile ?: throw IOException("No folder for ${original.name}")
                savedPath = output.inputStream().use { input ->
                    FileTransfer.writeLocalFile(folder, copyName(name), input, sourceLastModified)
                } ?: throw IOException("Could not save the copy")
            }
            MediaScannerConnection.scanFile(applicationContext, arrayOf(savedPath), null, null)
            return savedPath
        }

        if (replace) {
            val replaced = output.inputStream().use { input ->
                source.replaceFile(applicationContext, sourcePath, input, sourceLastModified)
            }
            if (!replaced) throw IOException("Could not replace the original")
            return sourcePath
        }

        val folder = source.parentFolder(sourcePath) ?: throw IOException("No folder for $sourcePath")
        return output.inputStream().use { input ->
            source.writeNew(applicationContext, folder, copyName(name), input, sourceLastModified)
        } ?: throw IOException("Could not save the copy")
    }

    private fun fileNameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** "photo.jpg" -> "photo (edited).jpg" */
    private fun copyName(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        return "$base (${getString(R.string.photo_editor_edited_suffix)})$extension"
    }
}
