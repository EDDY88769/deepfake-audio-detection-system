package com.example.deepfakeaudiodetector

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val btnHome: Button = findViewById(R.id.btnHome)
        val btnQuery: Button = findViewById(R.id.btnQuery)
        val btnRegister: Button = findViewById(R.id.btnRegister)
        val btnSettings: Button = findViewById(R.id.btnSettings)

        val switchDarkMode: SwitchCompat = findViewById(R.id.switchDarkMode)
        val btnTutorial: Button = findViewById(R.id.btnTutorial)

        val currentMode = AppCompatDelegate.getDefaultNightMode()

        switchDarkMode.isChecked = (currentMode == AppCompatDelegate.MODE_NIGHT_YES ||
                currentMode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED)

        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_YES) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    Toast.makeText(this, "深色模式已開啟", Toast.LENGTH_SHORT).show()
                }
            } else {
                if (AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_NO) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    Toast.makeText(this, "深色模式已關閉", Toast.LENGTH_SHORT).show()
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
            val intent = Intent(this, RegisterActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }

        btnSettings.setOnClickListener {
            Toast.makeText(this, "您已在設定頁面", Toast.LENGTH_SHORT).show()
        }

        btnTutorial.setOnClickListener {
            showTutorialDialog()
        }


        val mainView = findViewById<android.view.View>(R.id.main)
        mainView?.let {
            ViewCompat.setOnApplyWindowInsetsListener(it) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
        }
    }

    private fun showTutorialDialog() {
        val builder = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_tutorial, null)
        builder.setView(dialogView)

        val dialog = builder.create()

        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseDialog)
        btnClose?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}