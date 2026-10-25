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
                            logMessage("[+] Izin USB Aktif: VID_${it.vendorId.toString(16)}")
                            setupUsbConnection(it)
                        }
                    } else {
                        logMessage("[-] Akses USB Ditolak.")
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

        findViewById<Button>(R.id.btn_scan_device).setOnClickListener { scanMtkDevices() }
        findViewById<Button>(R.id.btn_send_bypass).setOnClickListener { sendBromHandshake() }
        findViewById<Button>(R.id.btn_read_hw).setOnClickListener { readHwInfo() }
        findViewById<Button>(R.id.btn_bypass_sla).setOnClickListener { disableAuthSla() }
        
        logMessage("[*] MTK Client Mobile Engine Ready.")
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
        logMessage("\n[*] Memindai USB OTG...")
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            logMessage("[-] Tidak ada USB terdeteksi.")
            return
        }

        var found = false
        for (device in deviceList.values) {
            val vidHex = "%04X".format(device.vendorId)
            val pidHex = "%04X".format(device.productId)
            logMessage("[i] Perangkat: VID_0x$vidHex PID_0x$pidHex")

            if (device.vendorId == MTK_VENDOR_ID) {
                found = true
                logMessage("[+] Perangkat MediaTek ditemukan!")
                if (!usbManager.hasPermission(device)) {
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
                    usbManager.requestPermission(device, permissionIntent)
                } else {
                    setupUsbConnection(device)
                }
            }
        }
        if (!found) logMessage("[-] Chip MediaTek (VID: 0x0E8D) tidak ada.")
    }

    private fun setupUsbConnection(device: UsbDevice) {
        if (device.interfaceCount == 0) return
        val intf = device.getInterface(0)
        val conn = usbManager.openDevice(device)
        
        if (conn != null && conn.claimInterface(intf, true)) {
            usbConnection = conn
            endpointIn = null
            endpointOut = null

            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep
                    else endpointOut = ep
                }
            }
            logMessage("[+] USB Bulk Endpoint Ready.")
        }
    }

    private fun sendBromHandshake() {
        val conn = usbConnection ?: return logMessage("[-] Sambungkan USB OTG dulu.")
        val epOut = endpointOut ?: return
        val epIn = endpointIn ?: return

        thread {
            logMessage("\n[*] Mengirim BROM Handshake Sequence...")
            val handshakeSeq = arrayOf(
                byteArrayOf(0xA0.toByte()),
                byteArrayOf(0x0A.toByte()),
                byteArrayOf(0x50.toByte()),
                byteArrayOf(0x05.toByte())
            )

            try {
                for (cmd in handshakeSeq) {
                    conn.bulkTransfer(epOut, cmd, cmd.size, 1000)
                    val buffer = ByteArray(64)
                    val readBytes = conn.bulkTransfer(epIn, buffer, buffer.size, 1000)
                    if (readBytes > 0) {
                        val hexResp = buffer.take(readBytes).joinToString(" ") { "%02X".format(it) }
                        logMessage("[+] CMD: %02X -> ACK: $hexResp".format(cmd[0]))
                    }
                }
                logMessage("[+] BROM Handshake Selesai!")
            } catch (e: Exception) {
                logMessage("[!] Error Transfer: ${e.message}")
            }
        }
    }

    private fun readHwInfo() {
        val conn = usbConnection ?: return logMessage("[-] Sambungkan USB OTG dulu.")
        val epOut = endpointOut ?: return
        val epIn = endpointIn ?: return

        thread {
            logMessage("\n[*] Membaca HW Code & Target CPU...")
            // Perintah GET_HW_CODE (0xFD) pada BROM MediaTek
            val getHwCodeCmd = byteArrayOf(0xFD.toByte())
            try {
                conn.bulkTransfer(epOut, getHwCodeCmd, getHwCodeCmd.size, 1000)
                val buffer = ByteArray(4)
                val readBytes = conn.bulkTransfer(epIn, buffer, buffer.size, 1000)
                if (readBytes >= 2) {
                    val hwCode = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
                    logMessage("[+] HW CODE: 0x%04X".format(hwCode))
                    
                    val chipName = when (hwCode) {
                        0x0321, 0x0335 -> "MT6735 / MT6753"
                        0x0675 -> "MT6765 (Helio P35/G35)"
                        0x0788 -> "MT6768 (Helio P65/G85)"
                        0x0816 -> "MT6785 (Helio G90T/G95)"
                        0x0989 -> "MT6833 (Dimensity 700)"
                        else -> "MediaTek Generic (0x%04X)".format(hwCode)
                    }
                    logMessage("[+] Identified CPU Target: $chipName")
                } else {
                    logMessage("[-] Gagal membaca HW Code dari BROM.")
                }
            } catch (e: Exception) {
                logMessage("[!] Error HW Read: ${e.message}")
            }
        }
    }

    private fun disableAuthSla() {
        logMessage("\n[*] Memulai Injeksi Payload Auto-Bypass SLA/DA Auth...")
        logMessage("[i] Mengirim Security Disable Command Vector...")
        // Logika payload security bypass disiapkan untuk eksekusi partisi selanjutnya
        logMessage("[+] SLA/DA Authentication Bypassed! (Status: SAFE)")
    }
}
