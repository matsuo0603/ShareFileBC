package com.example.sharefilebc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // アプリ起動時は常にログイン画面に遷移
        startActivity(Intent(this, LoginActivity::class.java))
        finish() // MainActivityは不要になったら終了
    }
}