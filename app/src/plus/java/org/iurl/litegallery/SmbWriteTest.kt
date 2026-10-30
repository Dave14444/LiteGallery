package org.iurl.litegallery

import android.app.AlertDialog
import android.content.Context
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Test write access" for a folder on a share: which saved connection (and so which login)
 * the app uses there, how the server took the login (as a user or as a guest), and whether a
 * small test file can be created, renamed and deleted. Each step shows the server's answer,
 * so missing rights can be told apart from other problems.
 */
object SmbWriteTest {

    fun start(activity: AppCompatActivity, folderUrl: String) {
        val smb = SmbPath.parse(folderUrl) ?: return
        val appContext = activity.applicationContext

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.smb_write_test_title)
            .setMessage(R.string.smb_write_test_running)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
        ThemeHelper.applyRuntimeCustomColors(dialog)

        activity.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    report(appContext, smb, SmbClient.writeTest(appContext, smb.host, smb.share, smb.path))
                } catch (e: Exception) {
                    android.util.Log.e("SmbWriteTest", "Write test failed", e)
                    appContext.getString(R.string.smb_write_test_failed, SmbClient.describeError(e))
                }
            }
            if (dialog.isShowing) dialog.setMessage(text)
        }
    }

    internal fun report(context: Context, smb: SmbPath, result: SmbClient.WriteTestResult): String {
        val name = result.connectionName
            ?: return context.getString(R.string.smb_write_test_no_connection, smb.share)
        val lines = mutableListOf<String>()
        lines += context.getString(R.string.smb_write_test_connection, name.ifBlank { smb.host })
        lines += if (result.configuredAsGuest) {
            context.getString(R.string.smb_write_test_login_guest)
        } else {
            context.getString(R.string.smb_write_test_login_user, result.userName)
        }
        lines += context.getString(R.string.smb_write_test_folder, "${smb.share}/${smb.path}".trimEnd('/'))

        if (result.loginError != null) {
            lines += context.getString(R.string.smb_write_test_login_failed, result.loginError)
            return lines.joinToString("\n")
        }
        result.serverTookGuest?.let { guest ->
            lines += context.getString(
                if (guest) R.string.smb_write_test_server_guest else R.string.smb_write_test_server_user
            )
        }
        lines += ""
        lines += context.getString(R.string.smb_write_test_write, stepText(context, result.write))
        result.sizeRead?.let { size ->
            val text = when {
                size < 0L -> context.getString(R.string.smb_write_test_unreadable)
                size == result.expectedSize -> context.getString(R.string.smb_write_test_ok)
                else -> context.getString(
                    R.string.smb_write_test_size_wrong,
                    Formatter.formatShortFileSize(context, size),
                    Formatter.formatShortFileSize(context, result.expectedSize)
                )
            }
            lines += context.getString(R.string.smb_write_test_size, text)
        }
        if (result.write.tried && result.write.error == null) {
            val kept = when (result.dateKept) {
                true -> R.string.smb_write_test_yes
                false -> R.string.smb_write_test_no
                null -> R.string.smb_write_test_unreadable
            }
            lines += context.getString(R.string.smb_write_test_date, context.getString(kept))
        }
        lines += context.getString(R.string.smb_write_test_rename, stepText(context, result.rename))
        lines += context.getString(R.string.smb_write_test_delete, stepText(context, result.delete))
        lines += context.getString(R.string.smb_write_test_folder_create, stepText(context, result.folder))
        lines += if (result.existingName == null) {
            context.getString(R.string.smb_write_test_existing_none)
        } else {
            context.getString(R.string.smb_write_test_existing, result.existingName, stepText(context, result.existing))
        }
        return lines.joinToString("\n")
    }

    private fun stepText(context: Context, step: SmbClient.TestStep): String = when {
        !step.tried -> context.getString(R.string.smb_write_test_skipped)
        step.error == null -> context.getString(R.string.smb_write_test_ok)
        else -> context.getString(R.string.smb_write_test_error, step.error)
    }
}
