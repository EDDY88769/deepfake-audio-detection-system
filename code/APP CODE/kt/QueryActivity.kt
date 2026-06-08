package com.example.deepfakeaudiodetector

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class QueryActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://keegan-unpaved-noncannibalistically.ngrok-free.dev/get-records"
    private lateinit var tvQueryResult: TextView

    private var currentUsername: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_query)

        // 在 onCreate 內的 setContentView(R.layout.activity_query) 之後加入：
        val tvQueryTitle: TextView = findViewById(R.id.tvQueryTitle) // 請改成你查詢頁標題的實體 ID
        if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
            tvQueryTitle.setTextColor(android.graphics.Color.WHITE)
        }

        currentUsername = intent.getStringExtra("EXTRA_USERNAME") ?: ""

        val btnFetchHistory: Button = findViewById(R.id.btnFetchHistory)
        tvQueryResult = findViewById(R.id.tvQueryResult)

        if (currentUsername.isEmpty()) {
            tvQueryResult.text = "提示：未偵測到登入狀態，請返回首頁進行登入。"
        } else {
            tvQueryResult.text = "當前帳號：$currentUsername\n點擊上方按鈕開始讀取歷史紀錄..."
        }

        val btnHome: Button = findViewById(R.id.btnHome)
        val btnQuery: Button = findViewById(R.id.btnQuery)
        val btnRegister: Button = findViewById(R.id.btnRegister)
        val btnSettings: Button = findViewById(R.id.btnSettings)

        btnHome.setBackgroundColor(android.graphics.Color.LTGRAY)
        btnQuery.setBackgroundColor(android.graphics.Color.DKGRAY)
        btnRegister.setBackgroundColor(android.graphics.Color.LTGRAY)
        btnSettings.setBackgroundColor(android.graphics.Color.LTGRAY)

        btnFetchHistory.setOnClickListener {
            if (currentUsername.isEmpty()) {
                Toast.makeText(this, "無有效帳號資訊，無法查詢", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fetchHistoryFromServer()
        }

        btnHome.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra("EXTRA_USERNAME", currentUsername) 
            }
            startActivity(intent)
        }

        btnQuery.setOnClickListener {
            Toast.makeText(this, "已在查詢頁面", Toast.LENGTH_SHORT).show()
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

    private fun fetchHistoryFromServer() {
        runOnUiThread { tvQueryResult.text = "正在讀取 [ $currentUsername ] 的歷史紀錄..." }

        val formBody = FormBody.Builder()
            .add("username", currentUsername)
            .build()

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(formBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { tvQueryResult.text = "連線失敗: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""

                try {
                    val jsonObject = JSONObject(responseBody)
                    val success = jsonObject.optBoolean("success", false)

                    if (success) {
                        val dataArray = jsonObject.optJSONArray("data")

                        if (dataArray == null || dataArray.length() == 0) {
                            runOnUiThread { tvQueryResult.text = "====== 歷史辨識紀錄 ======\n\n帳號 [ $currentUsername ] 目前尚無任何上傳紀錄。" }
                            return
                        }

                        val resultText = StringBuilder()
                        resultText.append("====== 歷史辨識紀錄 ======\n\n")

                        for (i in 0 until dataArray.length()) {
                            val record = dataArray.getJSONObject(i)
                            val filename = record.optString("filename", "未知檔案")
                            val result = record.optString("result", "無辨識結果")
                            val createdAt = record.optString("created_at", "")

                            val formattedTime = formatJsonDate(createdAt)

                            resultText.append("【紀錄 ${i + 1}】\n")
                            resultText.append("時間：$formattedTime\n")
                            resultText.append("檔名：$filename\n")
                            resultText.append("結果：$result\n")
                            resultText.append("----------------------------------------\n")
                        }

                        runOnUiThread {
                            tvQueryResult.text = resultText.toString()
                        }

                    } else {
                        val message = jsonObject.optString("message", "查詢失敗")
                        runOnUiThread { tvQueryResult.text = "查詢失敗：$message" }
                    }

                } catch (e: Exception) {
                    runOnUiThread {
                        tvQueryResult.text = "解析數據失敗，請確認伺服器狀態。\n錯誤訊息: ${e.message}"
                    }
                }
            }
        })
    }

    private fun formatJsonDate(dateStr: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            inputFormat.timeZone = TimeZone.getTimeZone("UTC")
            val date = inputFormat.parse(dateStr)

            val outputFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
            outputFormat.timeZone = TimeZone.getDefault()
            if (date != null) outputFormat.format(date) else dateStr
        } catch (e: Exception) {
            dateStr
        }
    }
}
