package com.naenwa.remote.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.naenwa.remote.R
import com.naenwa.remote.databinding.ItemDeviceBinding
import com.naenwa.remote.model.Device

class DeviceAdapter(
    private val onDeviceClick: (Device) -> Unit,
    private val onDeviceLongClick: (Device) -> Unit
) : ListAdapter<Device, DeviceAdapter.DeviceViewHolder>(DeviceDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeviceViewHolder(
        private val binding: ItemDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: Device) {
            binding.tvDeviceName.text = device.deviceName

            // Status
            val statusColor = if (device.isOnline) {
                ContextCompat.getColor(binding.root.context, R.color.status_connected)
            } else {
                ContextCompat.getColor(binding.root.context, R.color.status_disconnected)
            }
            binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(statusColor)
            binding.tvDeviceStatus.text = if (device.isOnline) "Online" else "Offline"

            // Button
            binding.btnConnect.isEnabled = device.isOnline
            binding.btnConnect.text = if (device.isOnline) "Connect" else "Offline"
            binding.btnConnect.setOnClickListener {
                onDeviceClick(device)
            }

            // Long click for options
            binding.root.setOnLongClickListener {
                onDeviceLongClick(device)
                true
            }

            binding.root.setOnClickListener {
                onDeviceClick(device)
            }
        }
    }

    class DeviceDiffCallback : DiffUtil.ItemCallback<Device>() {
        override fun areItemsTheSame(oldItem: Device, newItem: Device): Boolean {
            return oldItem.deviceId == newItem.deviceId
        }

        override fun areContentsTheSame(oldItem: Device, newItem: Device): Boolean {
            return oldItem == newItem
        }
    }
}
