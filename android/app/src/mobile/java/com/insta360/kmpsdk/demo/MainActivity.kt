package com.insta360.kmpsdk.demo

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.insta360.kmpsdk.demo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var nativeFailureAnnounced = false

    override fun onPostResume() {
        super.onPostResume()
        com.insta360.kmpsdk.demo.touchscene.OpenCvRuntimeStatus.onFallback = {
            runOnUiThread {
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) showNativeFailureIfNeeded()
            }
        }
        showNativeFailureIfNeeded()
    }

    override fun onPause() {
        com.insta360.kmpsdk.demo.touchscene.OpenCvRuntimeStatus.onFallback = null
        super.onPause()
    }

    private fun showNativeFailureIfNeeded() {
        if (!nativeFailureAnnounced &&
            (com.insta360.kmpsdk.demo.touchscene.OpenCvRuntimeStatus.result.isFailure ||
                com.insta360.kmpsdk.demo.touchscene.OpenCvRuntimeStatus.fallbackObserved)) {
            nativeFailureAnnounced = true
            android.widget.Toast.makeText(this,
                if (com.insta360.kmpsdk.demo.touchscene.OpenCvRuntimeStatus.result.isFailure)
                    com.insta360.kmpsdk.demo.touchscene.OpenCvRuntimeStatus.FAILURE_MESSAGE
                else com.insta360.kmpsdk.demo.touchscene.OpenCvRuntimeStatus.PROCESSING_FAILURE_MESSAGE,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController
        binding.bottomNav.setupWithNavController(navController)
        navController.addOnDestinationChangedListener { _, _, _ ->
            binding.bottomNav.visibility = View.GONE
        }
    }
}
