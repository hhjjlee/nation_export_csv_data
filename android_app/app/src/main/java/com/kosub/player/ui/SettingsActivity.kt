package com.kosub.player.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.kosub.player.R
import com.kosub.player.Settings
import com.kosub.player.asr.ModelManager

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, SettingsFragment()).commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private lateinit var settings: Settings

        private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    requireContext().contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: SecurityException) {
                }
                settings.setSaveFolder(uri)
                updateFolderSummary()
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            settings = Settings(requireContext())

            findPreference<EditTextPreference>(Settings.KEY_CLAUDE_KEY)?.apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    if (p.text.isNullOrBlank()) "입력 안 됨 — console.anthropic.com 에서 발급 (사용량 과금)" else "입력됨 (••••${p.text!!.takeLast(4)})"
                }
            }
            findPreference<Preference>(Settings.KEY_SAVE_FOLDER)?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("자막 저장 폴더")
                    .setMessage("동영상이 있는 폴더(예: DCIM/Camera, Movies, Download)를 고르면 다른 플레이어에서도 자막이 자동으로 인식됩니다.")
                    .setPositiveButton("폴더 고르기") { _, _ -> pickFolder.launch(null) }
                    .setNeutralButton("기본값(Download/KoSubPlayer)") { _, _ ->
                        settings.setSaveFolder(null); updateFolderSummary()
                    }
                    .setNegativeButton("취소", null)
                    .show()
                true
            }
            findPreference<Preference>("model_storage")?.setOnPreferenceClickListener {
                showModels(); true
            }
            updateFolderSummary()
        }

        private fun updateFolderSummary() {
            val uri = settings.saveFolder
            val name = uri?.let { DocumentFile.fromTreeUri(requireContext(), it)?.name }
            findPreference<Preference>(Settings.KEY_SAVE_FOLDER)?.summary =
                if (uri == null) "내장 메모리/Download/KoSubPlayer (기본값)" else "선택한 폴더: ${name ?: uri.lastPathSegment}"
        }

        private fun showModels() {
            val ctx = requireContext()
            val downloaded = ModelManager.MODELS.filter { ModelManager.isDownloaded(ctx, it) }
            if (downloaded.isEmpty()) {
                AlertDialog.Builder(ctx).setMessage("내려받은 모델이 없습니다.").setPositiveButton("확인", null).show()
                return
            }
            val labels = downloaded.map { "${it.id} (${it.sizeMb}MB) 지우기" }.toTypedArray()
            AlertDialog.Builder(ctx)
                .setTitle("내려받은 모델")
                .setItems(labels) { _, which ->
                    ModelManager.delete(ctx, downloaded[which])
                }
                .setNegativeButton("닫기", null)
                .show()
        }
    }
}
