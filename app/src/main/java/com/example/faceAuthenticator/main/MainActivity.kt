package com.example.faceAuthenticator.main

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.faceAuthenticator.R

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val errorText: TextView = findViewById(R.id.error)
        val button1: Button = findViewById(R.id.button1)
        val button2: Button = findViewById(R.id.button2)
        val error = intent.getStringExtra("error")
        errorText.text = error
        button1.setOnClickListener {
            val pwdIntent = Intent(this@MainActivity, PasswordAuthenticationActivity::class.java)
            startActivity(pwdIntent)
        }
        button2.setOnClickListener {
            val faceIntent = Intent(this@MainActivity, FaceAuthenticationActivity::class.java)
            startActivity(faceIntent)
        }
    }
}