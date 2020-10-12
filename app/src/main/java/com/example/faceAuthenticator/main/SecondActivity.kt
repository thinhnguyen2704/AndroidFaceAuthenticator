package com.example.faceAuthenticator.main

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.faceAuthenticator.R

class SecondActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_second)
        val backBtn = findViewById<Button>(R.id.backBtn)
        val text: TextView = findViewById(R.id.txt)
        text.text = "Cyber Security for Mobile Platforms is interesting!"
        backBtn.setOnClickListener {
            val backToMain = Intent(this@SecondActivity, MainActivity::class.java)
            startActivity(backToMain)
        }
    }
}