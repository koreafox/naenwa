package com.naenwa.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.naenwa.remote.adapter.DeviceAdapter
import com.naenwa.remote.auth.AuthManager
import com.naenwa.remote.databinding.ActivityDeviceListBinding
import com.naenwa.remote.model.Device
import com.naenwa.remote.model.QrCodeData
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class DeviceListActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DeviceListActivity"
    }

    private lateinit var binding: ActivityDeviceListBinding
    private lateinit var authManager: AuthManager
    private lateinit var deviceAdapter: DeviceAdapter

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startQrScanner()
        } else {
            Toast.makeText(this, "Camera permission required for QR scanning", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authManager = AuthManager(this)

        setupUI()
        loadDevices()
    }

    private fun setupUI() {
        // User info
        binding.tvUserName.text = authManager.savedUserName ?: "User"
        binding.tvUserEmail.text = authManager.savedUserEmail ?: ""

        // Logout
        binding.btnLogout.setOnClickListener {
            authManager.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // Device list
        deviceAdapter = DeviceAdapter(
            onDeviceClick = { device -> connectToDevice(device) },
            onDeviceLongClick = { device -> showDeviceOptions(device) }
        )
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@DeviceListActivity)
            adapter = deviceAdapter
        }

        // Add device button
        binding.btnAddDevice.setOnClickListener {
            checkCameraPermissionAndScan()
        }
    }

    private fun loadDevices() {
        showLoading(true)

        lifecycleScope.launch {
            val devices = authManager.getDevices()
            showLoading(false)

            if (devices.isEmpty()) {
                binding.layoutEmpty.visibility = View.VISIBLE
                binding.rvDevices.visibility = View.GONE
            } else {
                binding.layoutEmpty.visibility = View.GONE
                binding.rvDevices.visibility = View.VISIBLE
                deviceAdapter.submitList(devices)
            }
        }
    }

    private fun connectToDevice(device: Device) {
        if (!device.isOnline) {
            Toast.makeText(this, "${device.deviceName} is offline", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val url = authManager.getDeviceUrl(device.deviceId)
            if (url != null) {
                val intent = Intent(this@DeviceListActivity, MainActivity::class.java).apply {
                    putExtra("server_url", url)
                    putExtra("device_name", device.deviceName)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this@DeviceListActivity, "Failed to get device URL", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeviceOptions(device: Device) {
        AlertDialog.Builder(this)
            .setTitle(device.deviceName)
            .setItems(arrayOf("Remove Device")) { _, which ->
                when (which) {
                    0 -> removeDevice(device)
                }
            }
            .show()
    }

    private fun removeDevice(device: Device) {
        AlertDialog.Builder(this)
            .setTitle("Remove Device")
            .setMessage("Are you sure you want to remove ${device.deviceName}?")
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    if (authManager.removeDevice(device.deviceId)) {
                        loadDevices()
                    } else {
                        Toast.makeText(this@DeviceListActivity, "Failed to remove device", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkCameraPermissionAndScan() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                startQrScanner()
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startQrScanner() {
        val intent = Intent(this, QrScannerActivity::class.java)
        qrScannerLauncher.launch(intent)
    }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrData = result.data?.getStringExtra("qr_data")
            if (qrData != null) {
                processQrCode(qrData)
            }
        }
    }

    private fun processQrCode(qrData: String) {
        try {
            val data = Json.decodeFromString<QrCodeData>(qrData)
            Log.d(TAG, "QR Code scanned: ${data.deviceId}, ${data.name}")

            // 기기 등록
            lifecycleScope.launch {
                showLoading(true)
                val success = authManager.registerDevice(data.deviceId, data.name)
                showLoading(false)

                if (success) {
                    Toast.makeText(this@DeviceListActivity, "${data.name} added!", Toast.LENGTH_SHORT).show()
                    loadDevices()
                } else {
                    Toast.makeText(this@DeviceListActivity, "Failed to add device", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid QR code: ${e.message}")
            Toast.makeText(this, "Invalid QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressLoading.visibility = if (show) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        loadDevices() // Refresh device status
    }
}
