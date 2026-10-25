package com.example.mtkclientmobile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var tvConsoleLog: TextView
    private val MTK_VENDOR_ID = 0x0E8D
    
    private var usbConnection: UsbDeviceConnection? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private val ACTION_USB_PERMISSION = "com.example.mtkclientmobile.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            logMessage("[+] Izin USB diberikan untuk Perangkat: VID_${it.vendorId.toString(16)}")
                            setupUsbConnection(it)
                        }
                    } else {
                        logMessage("[-] Izin USB ditolak oleh pengguna.")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        tvConsoleLog = findViewById(R.id.tv_console_log)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        registerReceiver(usbReceiver, filter)

        findViewById<Button>(R.id.btn_scan_device).setOnClickListener {
            scanMtkDevices()
        }

        findViewById<Button>(R.id.btn_send_bypass).setOnClickListener {
            sendBromHandshake()
        }
        
        logMessage("[*] MTK Client Engine Siap. Sambungkan HP MTK via OTG.")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        usbConnection?.close()
    }

    private fun logMessage(msg: String) {
        runOnUiThread {
            tvConsoleLog.append("$msg\n")
        }
    }

    private fun scanMtkDevices() {
        logMessage("\n[*] Memindai port USB OTG...")
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            logMessage("[-] Tidak ada perangkat OTG terdeteksi.")
            return
        }

        var found = false
        for (device in deviceList.values) {
            val vidHex = "%04X".format(device.vendorId)
            val pidHex = "%04X".format(device.productId)
            logMessage("[i] USB Ditemukan: VID_0x$vidHex PID_0x$pidHex")

            if (device.vendorId == MTK_VENDOR_ID) {
                found = true
                logMessage("[+] Target MediaTek Ditemukan! Meminta akses USB...")
                if (!usbManager.hasPermission(device)) {
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                    usbManager.requestPermission(device, permissionIntent)
                } else {
                    logMessage("[+] Izin USB sudah ada.")
                    setupUsbConnection(device)
                }
            }
        }
        if (!found) {
            logMessage("[-] Chipset MediaTek (VID: 0x0E8D) belum terhubung.")
        }
    }

    private fun setupUsbConnection(device: UsbDevice) {
        if (device.interfaceCount == 0) {
            logMessage("[-] Error: Tidak ada Interface pada perangkat USB.")
            return
        }

        val intf = device.getInterface(0)
        val conn = usbManager.openDevice(device)
        
        if (conn != null && conn.claimInterface(intf, true)) {
            usbConnection = conn
            logMessage("[+] Interface USB 0 berhasil diklaim.")
            
            endpointIn = null
            endpointOut = null

            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        endpointIn = ep
                    } else {
                        endpointOut = ep
                    }
                }
            }
            logMessage("[+] Endpoint Bulk IN & OUT Siap Komunikasi.")
        } else {
            logMessage("[-] Gagal Klaim Interface USB.")
        }
    }

    private fun sendBromHandshake() {
        val conn = usbConnection
        val epOut = endpointOut
        val epIn = endpointIn

        if (conn == null || epOut == null || epIn == null) {
            logMessage("[-] Koneksi USB belum siap. Lakukan SCAN OTG DEVICE dulu.")
            return
        }

        thread {
            logMessage("\n[*] Memulai BROM Handshake Sequence...")
            val handshakeSequence = arrayOf(
                byteArrayOf(0xA0.toByte()),
                byteArrayOf(0x0A.toByte()),
                byteArrayOf(0x50.toByte()),
                byteArrayOf(0x05.toByte())
            )

            try {
                for (cmd in handshakeSequence) {
                    val sent = conn.bulkTransfer(epOut, cmd, cmd.size, 1000)
                    if (sent >= 0) {
                        val buffer = ByteArray(64)
                        val readBytes = conn.bulkTransfer(epIn, buffer, buffer.size, 1000)
                        if (readBytes > 0) {
                            val hexResp = buffer.take(readBytes).joinToString(" ") { "%02X".format(it) }
                            logMessage("[+] TX: %02X | RX: $hexResp".format(cmd[0]))
                        } else {
                            logMessage("[-] TX: %02X | No Response".format(cmd[0]))
                        }
                    } else {
                        logMessage("[-] Gagal Mengirim Byte: %02X".format(cmd[0]))
                    }
                }
                logMessage("[+] Handshake selesai. Perangkat siap untuk eksekusi payload!")
            } catch (e: Exception) {
                logMessage("[!] Error Transfer Data: ${e.message}")
            }
        }
    }
}
