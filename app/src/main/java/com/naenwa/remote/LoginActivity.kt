package com.naenwa.remote

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.naenwa.remote.auth.AuthManager
import com.naenwa.remote.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"
        // Web client ID from google-services.json (client_type: 3)
        private const val WEB_CLIENT_ID = "939750314714-qp4q05ablp3milag2fhp0ivktaiusade.apps.googleusercontent.com"
    }

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authManager: AuthManager
    private lateinit var googleSignInClient: GoogleSignInClient

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleSignInResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = AuthManager(this)

        // Already logged in?
        if (authManager.isLoggedIn) {
            navigateToDeviceList()
            return
        }

        setupGoogleSignIn()
        setupUI()
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun setupUI() {
        binding.btnGoogleSignIn.setOnClickListener {
            startGoogleSignIn()
        }

        // Show version
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            binding.tvVersion.text = "v$versionName"
        } catch (e: Exception) {
            binding.tvVersion.text = "v1.0"
        }
    }

    private fun startGoogleSignIn() {
        showLoading(true)
        setStatus("Signing in...")

        // Sign out first to show account picker
        googleSignInClient.signOut().addOnCompleteListener {
            val signInIntent = googleSignInClient.signInIntent
            signInLauncher.launch(signInIntent)
        }
    }

    private fun handleSignInResult(data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken

            if (idToken != null) {
                firebaseAuthWithGoogle(idToken)
            } else {
                showError("Failed to get ID token")
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Google sign-in failed: ${e.statusCode}", e)
            showError("Google sign-in failed: ${e.message}")
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        setStatus("Authenticating...")

        lifecycleScope.launch {
            val result = authManager.signInWithGoogle(idToken)

            result.fold(
                onSuccess = { user ->
                    setStatus("Welcome, ${user.displayName}!")
                    Log.d(TAG, "Sign-in successful: ${user.email}")

                    // Navigate to device list
                    navigateToDeviceList()
                },
                onFailure = { e ->
                    showError("Authentication failed: ${e.message}")
                }
            )
        }
    }

    private fun navigateToDeviceList() {
        val intent = Intent(this, DeviceListActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun showLoading(show: Boolean) {
        runOnUiThread {
            binding.layoutLoading.visibility = if (show) View.VISIBLE else View.GONE
            binding.btnGoogleSignIn.visibility = if (show) View.GONE else View.VISIBLE
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            binding.tvStatus.text = message
            binding.tvStatus.visibility = View.VISIBLE
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            showLoading(false)
            binding.tvStatus.text = message
            binding.tvStatus.setTextColor(getColor(R.color.error))
            binding.tvStatus.visibility = View.VISIBLE
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
}
