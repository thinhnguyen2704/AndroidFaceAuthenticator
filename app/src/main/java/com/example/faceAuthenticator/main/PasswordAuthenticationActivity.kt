package com.example.faceAuthenticator.main

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.example.faceAuthenticator.R

class PasswordAuthenticationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password_authentication)
        val passwordTxt = findViewById<EditText>(R.id.pwdTxt)
        val password = "42037"
        val authBtn = findViewById<Button>(R.id.pwdAuthBtn)
        authBtn.setOnClickListener {
            if (passwordTxt.text.toString() == password) {
                val success = Intent(this@PasswordAuthenticationActivity, SecondActivity::class.java)
                startActivity(success)
            } else {
                val fail = Intent(this@PasswordAuthenticationActivity, MainActivity::class.java)
                val error = "You entered the wrong password! Please try again!"
                fail.putExtra("error", error)
                startActivity(fail)
            }
        }
    }
}