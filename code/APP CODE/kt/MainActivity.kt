package com.example.deepfakeaudiodetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val cookieStore = HashMap<String, List<Cookie>>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: ArrayList()
            }
        })
        .build()

    private val baseUrl = "https://keegan-unpaved-noncannibalistically.ngrok-free.dev"

    private lateinit var tvStatus: TextView
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button

    private var savedUsername: String = ""

    private var isRecording = false
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private var audioFile: File? = null

    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioUri: Uri? = null
    private var currentAudioFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvMainTitle: TextView = findViewById(R.id.tvTitle)
        if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
            tvMainTitle.setTextColor(android.graphics.Color.WHITE)
        }

        tvStatus = findViewById(R.id.tvStatus)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)

        val btnPickFile: Button = findViewById(R.id.btnPickFile)
        val btnRecord: Button = findViewById(R.id.btnRecord)
        val btnPlayAudio: Button = findViewById(R.id.btnPlayAudio)
        val btnHome: Button = findViewById(R.id.btnHome)
        val btnQuery: Button = findViewById(R.id.btnQuery)
        val btnRegister: Button = findViewById(R.id.btnRegister)
        val btnSettings: Button = findViewById(R.id.btnSettings)

        btnHome.setBackgroundColor(android.graphics.Color.DKGRAY)
        btnQuery.setBackgroundColor(android.graphics.Color.LTGRAY)
        btnRegister.setBackgroundColor(android.graphics.Color.LTGRAY)
        btnSettings.setBackgroundColor(android.graphics.Color.LTGRAY)
        
        val alphaNumericFilter = InputFilter { source, start, end, _, _, _ ->
            for (i in start until end) {
                if (!Character.isLetterOrDigit(source[i])) {
                    return@InputFilter ""
                }
            }
            null
        }

        etUsername.filters = arrayOf(alphaNumericFilter)
        etPassword.filters = arrayOf(alphaNumericFilter)

        val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { selectedUri ->
                currentAudioUri = selectedUri
                currentAudioFile = null

                val fileName = getFileNameFromUri(selectedUri)
                val extension = fileName.substringAfterLast(".", "").lowercase()

                if (extension == "mp3" || extension == "mov" || extension == "wav") {
                    updateStatus("正在上傳...")
                    thread {
                        val directFile = File(externalCacheDir, "direct_upload.$extension")
                        val copySuccess = copyUriToFile(selectedUri, directFile)

                        runOnUiThread {
                            if (copySuccess && directFile.exists()) {
                                currentAudioFile = directFile 
                                uploadAudioFile(directFile)
                            } else {
                                updateStatus("讀取原始檔案失敗")
                            }
                        }
                    }
                } else {
                    updateStatus("正在上傳...")
                    thread {
                        val convertedWavFile = File(externalCacheDir, "converted_upload.wav")
                        val success = decodeAudioToWav(selectedUri, convertedWavFile)

                        runOnUiThread {
                            if (success && convertedWavFile.exists()) {
                                currentAudioFile = convertedWavFile
                                uploadAudioFile(convertedWavFile)
                            } else {
                                updateStatus("上傳失敗")
                            }
                        }
                    }
                }
            }
        }

        btnLogin.setOnClickListener { loginToServer() }

        btnPickFile.setOnClickListener { pickFileLauncher.launch("audio/*") }

        btnPlayAudio.setOnClickListener { playCurrentAudio() }

        btnRecord.setOnClickListener {
            if (!isRecording) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
                } else {
                    startWavRecording(btnRecord)
                }
            } else {
                stopWavRecording(btnRecord)
            }
        }

        btnHome.setOnClickListener {
            Toast.makeText(this, "已在首頁", Toast.LENGTH_SHORT).show()
        }

        btnQuery.setOnClickListener {
            val intent = Intent(this, QueryActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra("EXTRA_USERNAME", savedUsername)
            }
            startActivity(intent)
        }

        btnRegister.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        }

        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "unknown.mp3"
    }

    private fun copyUriToFile(uri: Uri, destFile: File): Boolean {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun loginToServer() {
        updateStatus("正在登入...")
        val user = etUsername.text.toString().trim()
        val pass = etPassword.text.toString()
        val formBody = FormBody.Builder().add("username", user).add("password", pass).build()
        val request = Request.Builder()
            .url("$baseUrl/process-login")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(formBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { updateStatus("連線失敗: ${e.message}") }
            override fun onResponse(call: Call, response: Response) {
                if (response.request.url.toString().contains("login-success")) {
                    updateStatus("登入成功")
                    savedUsername = user
                    runOnUiThread {
                        etUsername.visibility = View.GONE
                        etPassword.visibility = View.GONE
                        btnLogin.visibility = View.GONE
                    }
                } else { updateStatus("登入失敗") }
            }
        })
    }

    private fun startWavRecording(button: Button) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            audioFile = File(externalCacheDir, "recorded_audio.wav")
            val tempPcmFile = File(cacheDir, "temp.pcm")
            isRecording = true
            button.text = "停止錄音並檢測"
            updateStatus("正在錄音...")

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, channelConfig, audioFormat, bufferSize
            )

            audioRecord.startRecording()

            thread {
                val os = FileOutputStream(tempPcmFile)
                val data = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord.read(data, 0, bufferSize)
                    if (read > 0) os.write(data, 0, read)
                }
                os.close()
                audioRecord.stop()
                audioRecord.release()

                audioFile?.let { wavFile ->
                    pcmToWav(tempPcmFile, wavFile, sampleRate)
                    currentAudioFile = wavFile
                    currentAudioUri = null
                    uploadAudioFile(wavFile)
                }
            }
        } catch (e: Exception) {
            updateStatus("錄音失敗: ${e.message}")
        }
    }

    private fun stopWavRecording(button: Button) {
        try {
            isRecording = false
            button.text = "錄製聲音並檢測"
            updateStatus("錄音結束，正在上傳")
        } catch (e: Exception) {
            updateStatus("停止錄音失敗")
        }
    }

    private fun decodeAudioToWav(uri: Uri, outputFile: File): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val tempPcm = File(cacheDir, "decode_temp.pcm")
        var pcmTargetSampleRate = 16000

        try {
            extractor.setDataSource(applicationContext, uri, null)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex == -1) return false
            extractor.selectTrack(trackIndex)

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                pcmTargetSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val fos = FileOutputStream(tempPcm)
            val info = MediaCodec.BufferInfo()
            var isEOS = false
            val kTimeoutUs: Long = 10000

            while ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                if (!isEOS) {
                    val inIndex = codec.dequeueInputBuffer(kTimeoutUs)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: break
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, kTimeoutUs)
                if (outIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outIndex) ?: break
                    val chunk = ByteArray(info.size)
                    buffer.get(chunk)
                    buffer.clear()
                    fos.write(chunk)
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
            fos.close()

            pcmToWav(tempPcm, outputFile, pcmTargetSampleRate)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { codec?.stop(); codec?.release() } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
            if (tempPcm.exists()) tempPcm.delete()
        }
    }

    private fun pcmToWav(pcmFile: File, wavFile: File, rate: Int) {
        val pcmSize = pcmFile.length()
        val wavSize = pcmSize + 36
        val buffer = ByteArray(bufferSize.coerceAtLeast(4096))

        val fis = FileInputStream(pcmFile)
        val fos = FileOutputStream(wavFile)

        val header = ByteArray(44)
        header[0] = 'R'.toByte(); header[1] = 'I'.toByte(); header[2] = 'F'.toByte(); header[3] = 'F'.toByte()
        header[4] = (wavSize and 0xff).toByte(); header[5] = ((wavSize shr 8) and 0xff).toByte()
        header[6] = ((wavSize shr 16) and 0xff).toByte(); header[7] = ((wavSize shr 24) and 0xff).toByte()
        header[8] = 'W'.toByte(); header[9] = 'A'.toByte(); header[10] = 'V'.toByte(); header[11] = 'E'.toByte()
        header[12] = 'f'.toByte(); header[13] = 'm'.toByte(); header[14] = 't'.toByte(); header[15] = ' '.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = 2; header[23] = 0
        header[24] = (rate and 0xff).toByte(); header[25] = ((rate shr 8) and 0xff).toByte()
        header[26] = ((rate shr 16) and 0xff).toByte(); header[27] = ((rate shr 24) and 0xff).toByte()
        val byteRate = rate * 2 * 2
        header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte(); header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2; header[33] = 0
        header[34] = 16; header[35] = 0
        header[36] = 'd'.toByte(); header[37] = 'a'.toByte(); header[38] = 't'.toByte(); header[39] = 'a'.toByte()
        header[40] = (pcmSize and 0xff).toByte(); header[41] = ((pcmSize shr 8) and 0xff).toByte()
        header[42] = ((pcmSize shr 16) and 0xff).toByte(); header[43] = ((pcmSize shr 24) and 0xff).toByte()

        fos.write(header, 0, 44)

        var bytesRead: Int
        while (fis.read(buffer).also { bytesRead = it } != -1) {
            fos.write(buffer, 0, bytesRead)
        }

        fis.close()
        fos.close()
        pcmFile.delete()
    }

    private fun playCurrentAudio() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                if (currentAudioUri != null) {
                    setDataSource(this@MainActivity, currentAudioUri!!)
                } else if (currentAudioFile != null && currentAudioFile!!.exists()) {
                    setDataSource(currentAudioFile!!.absolutePath)
                } else {
                    Toast.makeText(this@MainActivity, "目前沒有可播放的音檔", Toast.LENGTH_SHORT).show()
                    return
                }
                prepare()
                start()
                updateStatus("正在播放音訊...")
            }

            mediaPlayer?.setOnCompletionListener {
                updateStatus("播放結束")
                it.release()
                mediaPlayer = null
            }
        } catch (e: Exception) {
            updateStatus("播放失敗: ${e.message}")
        }
    }

    private fun uploadAudioFile(file: File) {
        runOnUiThread { updateStatus("正在上傳...") }

        val mimeType = when (file.extension.lowercase()) {
            "mp3" -> "audio/mp3"
            "mov" -> "video/quicktime"
            else -> "audio/wav"
        }

        val multipartBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("username", savedUsername)
            .addFormDataPart("audio", file.name, RequestBody.create(mimeType.toMediaTypeOrNull(), file))
            .build()

        val request = Request.Builder().url("$baseUrl/upload-audio")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(multipartBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { updateStatus("上傳失敗: ${e.message}") }
            override fun onResponse(call: Call, response: Response) {
                parseAndDisplayHtml(response.body?.string() ?: "")
            }
        })
    }

    private fun parseAndDisplayHtml(rawHtml: String) {
        var finalResult = "解析辨識結果失敗"
        try {
            if (rawHtml.contains("真實機率")) {
                val afterReal = rawHtml.substringAfter("真實機率:")
                val cleanData = afterReal.substringBefore("</div>").trim()
                finalResult = "真實機率:$cleanData"
            } else {
                finalResult = rawHtml.replace(Regex("<[^>]*>"), "").trim()
            }
        } catch (e: Exception) {
            finalResult = "解析錯誤: ${e.message}"
        }

        updateStatus("檢測完成！\n$finalResult")
    }

    override fun onStop() {
        super.onStop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { tvStatus.text = "目前狀態: $msg" }
    }
}
