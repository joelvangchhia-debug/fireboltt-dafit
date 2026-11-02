package com.example.dafitclone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    companion object {
        const val TARGET_NAME = "BSW083"
        val SERVICE_UUID: UUID = UUID.fromString("0000fee7-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val REQ_PERMS = 1
    }

    // UI
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var deviceList: ListView
    private lateinit var showAll: CheckBox
    private lateinit var rawInput: EditText

    // BLE
    private val btAdapter: BluetoothAdapter by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private val rows = ArrayList<String>()
    private lateinit var listAdapter: ArrayAdapter<String>
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Descriptor write queue (Android allows only one GATT op at a time)
    private val descQueue = java.util.ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()
    private var descBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        scroll = findViewById(R.id.scroll)
        deviceList = findViewById(R.id.deviceList)
        showAll = findViewById(R.id.showAll)
        rawInput = findViewById(R.id.rawInput)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        deviceList.adapter = listAdapter
        deviceList.setOnItemClickListener { _, _, pos, _ ->
            val dev = found.values.elementAt(pos)
            stopScan()
            connect(dev)
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener { ensurePermsThen { startScan() } }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { gatt?.disconnect() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { logView.text = "" }
        findViewById<Button>(R.id.btnSniffAll).setOnClickListener { subscribeAll() }

        findViewById<Button>(R.id.btnTime).setOnClickListener {
            val c = Calendar.getInstance()
            val data = byteArrayOf(
                (c.get(Calendar.YEAR) - 2000).toByte(),
                (c.get(Calendar.MONTH) + 1).toByte(),
                c.get(Calendar.DAY_OF_MONTH).toByte(),
                c.get(Calendar.HOUR_OF_DAY).toByte(),
                c.get(Calendar.MINUTE).toByte(),
                c.get(Calendar.SECOND).toByte()
            )
            send(buildPacket(0x01, data))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            send(buildPacket(0x02, byteArrayOf()))
        }
        findViewById<Button>(R.id.btnWallpaper).setOnClickListener {
            // Placeholder payload - replace with what you sniff from Da Fit
            send(buildPacket(0x20, byteArrayOf(0x00, 0x01, 0x02, 0x03)))
        }
        findViewById<Button>(R.id.btnSendRaw).setOnClickListener {
            val bytes = parseHex(rawInput.text.toString())
            if (bytes.isEmpty()) log("Raw input empty/invalid") else send(bytes)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        gatt?.close()
    }

    // ---------------- Permissions ----------------

    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermsThen(action: () -> Unit) {
        val missing = requiredPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMS && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED })
            startScan()
        else log("Permissions denied")
    }

    // ---------------- Scan ----------------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name
            if (!showAll.isChecked && name != TARGET_NAME) return
            val addr = r.device.address
            val row = "${name ?: "(no name)"}\n$addr   ${r.rssi} dBm"
            runOnUiThread {
                if (!found.containsKey(addr)) {
                    found[addr] = r.device
                    rows.add(row)
                } else {
                    rows[found.keys.indexOf(addr)] = row
                }
                listAdapter.notifyDataSetChanged()
            }
        }

        override fun onScanFailed(errorCode: Int) = log("Scan failed: $errorCode")
    }

    private fun startScan() {
        if (!btAdapter.isEnabled) { log("Bluetooth is off"); return }
        found.clear(); rows.clear(); listAdapter.notifyDataSetChanged()
        btAdapter.bluetoothLeScanner.startScan(scanCb)
        scanning = true
        status("Scanning...")
        handler.postDelayed({ stopScan() }, 15_000)
    }

    private fun stopScan() {
        if (!scanning) return
        btAdapter.bluetoothLeScanner.stopScan(scanCb)
        scanning = false
        status("Scan stopped")
    }

    // ---------------- Connect ----------------

    private fun connect(dev: BluetoothDevice) {
        gatt?.close()
        status("Connecting to ${dev.address}...")
        gatt = dev.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            log("Connection state=$newState status=$st")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Connected, discovering services...")
                handler.post { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                status("Disconnected")
                writeChar = null; notifyChar = null
                descQueue.clear(); descBusy = false
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            log("Services discovered, status=$st")
            // nRF-style dump of everything
            for (s in g.services) {
                log("SERVICE ${s.uuid}")
                for (c in s.characteristics)
                    log("   CHAR ${c.uuid} [${propsToString(c.properties)}]")
            }

            val svc = g.getService(SERVICE_UUID)
            if (svc == null) { log("!! Service FEE7 not found"); return }

            // Pick by properties (UUIDs vary: fec7/fec8/fee7...)
            writeChar = svc.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            notifyChar = svc.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            }
            log("Write char : ${writeChar?.uuid}")
            log("Notify char: ${notifyChar?.uuid}")

            notifyChar?.let { enqueueNotify(g, it) }
            status("Ready")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            log("CCCD write ${d.characteristic.uuid} status=$st")
            descBusy = false
            pumpDesc(g)
        }

        // Called on all API levels (on 33+ the new overload delegates here)
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            log("RX ${c.uuid.short()}  ${v.hex()}")
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            log("Write done status=$st")
        }
    }

    // ---------------- Notifications ----------------

    private fun enqueueNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        val d = c.getDescriptor(CCCD_UUID) ?: run { log("No CCCD on ${c.uuid}"); return }
        g.setCharacteristicNotification(c, true)
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        descQueue.add(d to v)
        pumpDesc(g)
    }

    private fun pumpDesc(g: BluetoothGatt) {
        if (descBusy) return
        val (d, v) = descQueue.poll() ?: return
        descBusy = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { d.value = v; g.writeDescriptor(d) }
        }
        if (!ok) { log("writeDescriptor failed ${d.characteristic.uuid}"); descBusy = false; pumpDesc(g) }
    }

    /** Sniffer helper: subscribe to EVERY notify/indicate characteristic on the watch. */
    private fun subscribeAll() {
        val g = gatt ?: run { log("Not connected"); return }
        for (s in g.services) for (c in s.characteristics) {
            if (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            ) enqueueNotify(g, c)
        }
    }

    // ---------------- Sending ----------------

    /**
     * [0xAB, 0x00, length, cmd, data..., checksum]
     * length = cmd + data bytes (CHANGE HERE if the real protocol counts differently)
     * checksum = sum of all previous bytes & 0xFF
     */
    private fun buildPacket(cmd: Int, data: ByteArray): ByteArray {
        val body = byteArrayOf(0xAB.toByte(), 0x00, (1 + data.size).toByte(), cmd.toByte()) + data
        val sum = body.sumOf { it.toInt() and 0xFF } and 0xFF
        return body + sum.toByte()
    }

    private fun send(bytes: ByteArray) {
        val g = gatt
        val c = writeChar
        if (g == null || c == null) { log("Not ready (no write characteristic)"); return }
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        log("TX ${c.uuid.short()}  ${bytes.hex()}")
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, type)
        } else {
            @Suppress("DEPRECATION")
            run { c.writeType = type; c.value = bytes; g.writeCharacteristic(c) }
        }
    }

    // ---------------- Helpers ----------------

    private fun status(s: String) = runOnUiThread { statusView.text = s }

    private fun log(s: String) = runOnUiThread {
        logView.append("[${ts.format(Date())}] $s\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    private fun UUID.short() = toString().substring(4, 8)

    private fun parseHex(s: String): ByteArray {
        val clean = s.replace(Regex("[^0-9A-Fa-f]"), "")
        if (clean.length < 2 || clean.length % 2 != 0) return ByteArray(0)
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun propsToString(p: Int): String {
        val l = ArrayList<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) l.add("READ")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) l.add("WRITE_NR")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) l.add("WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) l.add("NOTIFY")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) l.add("INDICATE")
        return l.joinToString(",")
    }
}
