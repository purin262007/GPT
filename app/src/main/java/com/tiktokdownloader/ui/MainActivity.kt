package com.tiktokdownloader.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.tiktokdownloader.R
import com.tiktokdownloader.databinding.ActivityMainBinding
import com.tiktokdownloader.util.TikTokUrlParser
import com.tiktokdownloader.util.VideoDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var videoDownloader: VideoDownloader
    private var lastDownloadedPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoDownloader = VideoDownloader(this)

        setupUI()
        handleIncomingIntent(intent)
        requestPermissionsIfNeeded()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingIntent(it) }
    }

    private fun setupUI() {
        binding.btnPaste.setOnClickListener {
            pasteFromClipboard()
        }

        binding.btnDownload.setOnClickListener {
            val url = binding.editTextUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val tiktokUrl = TikTokUrlParser.extractUrlFromText(url) ?: url
            if (!TikTokUrlParser.isValidTikTokUrl(tiktokUrl)) {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startDownload(tiktokUrl)
        }

        binding.btnOpenFile.setOnClickListener {
            openDownloadedFile()
        }

        binding.btnShare.setOnClickListener {
            shareDownloadedFile()
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = TikTokUrlParser.extractUrlFromText(sharedText)
            if (url != null) {
                binding.editTextUrl.setText(url)
                startDownload(url)
            } else {
                binding.editTextUrl.setText(sharedText)
            }
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: return
            val url = TikTokUrlParser.extractUrlFromText(text) ?: text
            binding.editTextUrl.setText(url)
        }
    }

    private fun startDownload(url: String) {
        binding.cardStatus.visibility = View.VISIBLE
        binding.layoutCompleteActions.visibility = View.GONE
        binding.progressBar.isIndeterminate = true
        binding.textStatus.text = getString(R.string.status_fetching)
        binding.btnDownload.isEnabled = false

        lifecycleScope.launch {
            try {
                // 動画情報を取得
                val videoInfo = videoDownloader.fetchVideoInfo(url)

                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = 0

                // ダウンロード
                val fileName = "${videoInfo.author}_${System.currentTimeMillis()}"
                val savedPath = videoDownloader.downloadVideo(
                    videoUrl = videoInfo.videoUrl,
                    fileName = fileName
                ) { progress ->
                    withContext(Dispatchers.Main) {
                        binding.progressBar.progress = progress
                        binding.textStatus.text = getString(R.string.status_downloading, progress)
                    }
                }

                lastDownloadedPath = savedPath

                withContext(Dispatchers.Main) {
                    binding.progressBar.progress = 100
                    binding.textStatus.text = getString(R.string.status_complete)
                    binding.textStatus.setTextColor(
                        ContextCompat.getColor(this@MainActivity, R.color.success_green)
                    )
                    binding.layoutCompleteActions.visibility = View.VISIBLE
                    binding.btnDownload.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.textStatus.text = getString(R.string.status_error, e.message)
                    binding.textStatus.setTextColor(
                        ContextCompat.getColor(this@MainActivity, R.color.error_red)
                    )
                    binding.progressBar.isIndeterminate = false
                    binding.progressBar.progress = 0
                    binding.btnDownload.isEnabled = true
                }
            }
        }
    }

    private fun openDownloadedFile() {
        val path = lastDownloadedPath ?: return

        try {
            val intent = Intent(Intent.ACTION_VIEW)
            if (path.startsWith("content://")) {
                intent.setDataAndType(Uri.parse(path), "video/mp4")
            } else {
                val file = File(path)
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
                intent.setDataAndType(uri, "video/mp4")
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "ファイルを開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDownloadedFile() {
        val path = lastDownloadedPath ?: return

        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "video/mp4"
            if (path.startsWith("content://")) {
                intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(path))
            } else {
                val file = File(path)
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
                intent.putExtra(Intent.EXTRA_STREAM, uri)
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "共有"))
        } catch (e: Exception) {
            Toast.makeText(this, "共有に失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_CODE
                )
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}
