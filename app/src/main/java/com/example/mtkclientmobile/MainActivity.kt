package com.example.mtkclientmobile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        tvConsoleLog = findViewById(R.id.tv_console_log)

        findViewById<Button>(R.id.btn_scan_device).setOnClickListener {
            scanMtkDevices()
        }

        findViewById<Button>(R.id.btn_send_bypass).setOnClickListener {
            sendBromHandshake()
        }
    }

    private fun logMessage(msg: String) {
        runOnUiThread {
            tvConsoleLog.append("$msg\n")
        }
    }

    private fun scanMtkDevices() {
        logMessage("Mencari perangkat MediaTek via OTG...")
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            logMessage("[-] Tidak ada perangkat USB yang terdeteksi.")
            return
        }

        var found = false
        for (device in deviceList.values) {
            logMessage("Ditemukan USB: VID=${device.vendorId.toString(16)}, PID=${device.productId.toString(16)}")
            if (device.vendorId == MTK_VENDOR_ID) {
                found = true
                logMessage("[+] Perangkat MediaTek teridentifikasi!")
                requestUsbPermission(device)
            }
        }
        if (!found) {
            logMessage("[-] Tidak ditemukan chip MediaTek (VID: 0x0E8D).")
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
        
        val intf = device.getInterface(0)
        usbConnection = usbManager.openDevice(device)
        if (usbConnection != null && usbConnection!!.claimInterface(intf, true)) {
            logMessage("[+] Berhasil klaim interface USB utama.")
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep
                    else endpointOut = ep
                }
            }
        } else {
            logMessage("[-] Gagal membuka koneksi USB / Izin ditolak.")
        }
    }

    private fun sendBromHandshake() {
        if (usbConnection == null || endpointOut == null) {
            logMessage("[-] Koneksi USB belum siap atau perangkat belum di-scan.")
            return
        }

        thread {
            logMessage("[*] Mengirim sinyal BROM Handshake / Payload...")
            val bromCmd = byteArrayOf(0xA0.toByte(), 0x0A.toByte(), 0x50.toByte(), 0x05.toByte())
            
            try {
                val sent = usbConnection!!.bulkTransfer(endpointOut, bromCmd, bromCmd.size, 1000)
                if (sent >= 0) {
                    logMessage("[+] Handshake terkirim ($sent bytes). Menunggu respons...")
                    if (endpointIn != null) {
                        val responseBuffer = ByteArray(64)
                        val readBytes = usbConnection!!.bulkTransfer(endpointIn, responseBuffer, responseBuffer.size, 2000)
                        if (readBytes > 0) {
                            val hexResp = responseBuffer.take(readBytes).joinToString(" ") { "%02X".format(it) }
                            logMessage("[+] Respons Diterima: $hexResp")
                        } else {
                            logMessage("[-] Tidak ada respons dari chip.")
                        }
                    }
                } else {
                    logMessage("[-] Gagal mengirim data lewat Bulk Out.")
                }
            } catch (e: Exception) {
                logMessage("[!] Error Eksekusi: ${e.message}")
            }
        }
    }
}
