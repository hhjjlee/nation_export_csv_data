package com.kosub.player.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kosub.player.Settings
import com.kosub.player.asr.ModelManager
import com.kosub.player.databinding.ActivityMainBinding
import com.kosub.player.databinding.ItemRecentBinding

/** 첫 화면: 동영상 열기, 최근 파일, 설정. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            play(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)
        binding.openButton.setOnClickListener { openDocument.launch(arrayOf("video/*", "audio/*")) }
        binding.settingsButton.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.recentList.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        val recent = settings.recent()
        binding.emptyText.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
        binding.recentList.adapter = RecentAdapter(recent)
        val model = ModelManager.find(settings.model)
        val modelText = if (ModelManager.isDownloaded(this, model)) "음성 인식 모델: ${model.id} (준비됨)"
        else "음성 인식 모델: ${model.id} (처음 자막을 만들 때 약 ${model.sizeMb}MB를 내려받습니다)"
        val folder = settings.saveFolder?.let { "선택한 폴더" } ?: "Download/KoSubPlayer"
        binding.infoText.text = "$modelText\n자막 저장 위치: $folder"
    }

    private fun play(uri: Uri) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private inner class RecentAdapter(private val items: List<Settings.Recent>) :
        RecyclerView.Adapter<RecentAdapter.Holder>() {
        inner class Holder(val b: ItemRecentBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemRecentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.b.recentName.text = item.name
            holder.b.recentName.setOnClickListener {
                val uri = Uri.parse(item.uri)
                val ok = try {
                    contentResolver.openFileDescriptor(uri, "r")?.close(); true
                } catch (_: Exception) {
                    false
                }
                if (ok) play(uri)
                else Toast.makeText(this@MainActivity, "파일을 다시 열 수 없습니다. '동영상 열기'로 다시 선택하세요.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
