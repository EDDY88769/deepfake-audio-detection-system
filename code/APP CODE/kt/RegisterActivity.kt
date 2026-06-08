package com.example.deepfakeaudiodetector

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.Spanned
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import java.io.IOException
import android.graphics.Color
import android.widget.TextView

class RegisterActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val baseUrl = "https://keegan-unpaved-noncannibalistically.ngrok-free.dev"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        
        val tvRegisterTitle: TextView = findViewById(R.id.tvRegisterTitle) 
        if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
            tvRegisterTitle.setTextColor(android.graphics.Color.WHITE)
        }

        val etRegUsername = findViewById<EditText>(R.id.etRegUsername)
        val etRegPassword = findViewById<EditText>(R.id.etRegPassword)
        val etRegConfirmPassword = findViewById<EditText>(R.id.etRegConfirmPassword)
        val btnSubmitRegister = findViewById<Button>(R.id.btnSubmitRegister)

        val btnHome = findViewById<Button>(R.id.btnHome)
        val btnQuery = findViewById<Button>(R.id.btnQuery)
        val btnRegister = findViewById<Button>(R.id.btnRegister)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        btnHome.setBackgroundColor(android.graphics.Color.LTGRAY)
        btnQuery.setBackgroundColor(android.graphics.Color.LTGRAY)
        btnRegister.setBackgroundColor(android.graphics.Color.DKGRAY)
        btnSettings.setBackgroundColor(android.graphics.Color.LTGRAY)

        val alphaNumericFilter = InputFilter { source, start, end, _, _, _ ->
            for (i in start until end) {
                if (!Character.isLetterOrDigit(source[i])) {
                    return@InputFilter ""
                }
            }
            null
        }

        etRegUsername.filters = arrayOf(alphaNumericFilter)
        etRegPassword.filters = arrayOf(alphaNumericFilter)
        etRegConfirmPassword.filters = arrayOf(alphaNumericFilter)

        btnSubmitRegister.setOnClickListener {
            val username = etRegUsername.text.toString().trim()
            val password = etRegPassword.text.toString()
            val confirmPassword = etRegConfirmPassword.text.toString()

            when {
                username.isEmpty() || password.isEmpty() -> {
                    Toast.makeText(this, "所有欄位都必須填寫", Toast.LENGTH_SHORT).show()
                }
                password != confirmPassword -> {
                    Toast.makeText(this, "兩次密碼輸入不一致", Toast.LENGTH_SHORT).show()
                }
                password.length < 6 -> {
                    Toast.makeText(this, "密碼長度至少需要6位", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    submitRegisterToServer(username, password)
                }
            }
        }

        btnHome.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }

        btnQuery.setOnClickListener {
            val intent = Intent(this, QueryActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        btnRegister.setOnClickListener {
            Toast.makeText(this, "您已在註冊頁面", Toast.LENGTH_SHORT).show()
        }

        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
    }

    private fun submitRegisterToServer(user: String, pass: String) {
        val formBody = FormBody.Builder()
            .add("username", user)
            .add("password", pass)
            .build()

        val request = Request.Builder()
            .url("$baseUrl/process-register")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(formBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@RegisterActivity, "連線失敗: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string() ?: ""

                runOnUiThread {
                    if (responseData.contains("註冊成功")) {
                        Toast.makeText(this@RegisterActivity, "註冊成功", Toast.LENGTH_LONG).show()

                        val intent = Intent(this@RegisterActivity, MainActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(intent)
                        finish()
                    } else if (responseData.contains("帳號已存在")) {
                        Toast.makeText(this@RegisterActivity, "註冊失敗：帳號已存在", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@RegisterActivity, "註冊失敗：伺服器錯誤", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }
}
