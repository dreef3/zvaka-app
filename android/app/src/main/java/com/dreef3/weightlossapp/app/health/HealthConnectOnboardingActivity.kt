package com.dreef3.weightlossapp.app.health

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.dreef3.weightlossapp.app.MainActivity

class HealthConnectOnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
