package ru.mercedesdoctorai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/** UI owns no Bluetooth streams; the service survives screen changes and backgrounding. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var content: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var page = 0
    private val updater = object : Runnable {
        override fun run() {
            draw()
            handler.postDelayed(this, 900)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermission()
        buildUi()
        handler.post(updater)
    }

    private fun buildUi() {
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 28, 22, 22)
        }
        val header = TextView(this).apply {
            text = "MERCEDES DOCTOR\nE200 · W212 · M274"
            textSize = 24f
            setPadding(0, 0, 0, 14)
        }
        status = TextView(this).apply { textSize = 14f; setPadding(0, 8, 0, 16) }
        content = TextView(this).apply { textSize = 18f; setPadding(0, 6, 0, 16) }
        scroll = ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Панель", "Поездки", "Ошибки", "ELM327", "MED40").forEachIndexed { index, label ->
            val b = Button(this).apply {
                text = label
                textSize = 11f
                setOnClickListener { page = index; draw() }
            }
            tabs.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        base.addView(header)
        base.addView(status)
        base.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        base.addView(tabs)
        setContentView(base)
        draw()
    }

    private fun send(action: String, mac: String? = null) {
        val intent = Intent(this, ObdService::class.java).apply {
            this.action = action
            if (mac != null) putExtra(ObdService.DEVICE, mac)
        }
        if (action == ObdService.CONNECT) startForegroundService(intent)
        else if (ObdService.instance != null) startService(intent)
        else Toast.makeText(this, "Сначала подключите ELM327", Toast.LENGTH_SHORT).show()
    }

    private fun addButton(label: String, onClick: () -> Unit) {
        // Content lives in a vertical container, installed by redrawPage().
        val root = pageContainer ?: return
        root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })
    }

    private var pageContainer: LinearLayout? = null
    private fun draw() {
        val service = ObdService.instance
        status.text = if (service == null) "Адаптер не подключён"
            else service.status + "\n" +
                "ЭБУ: " + (if (service.connected) "ответ подтверждён" else "нет подтверждения") +
                " · Измерений: " + service.samples
        val current = pageContainer
        if (current == null || current.tag != page) {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tag = page
            }
            scroll.removeAllViews()
            scroll.addView(panel)
            pageContainer = panel
            setupPage(panel, service)
        } else updateReadings(service)
    }

    private fun setupPage(panel: LinearLayout, service: ObdService?) {
        when (page) {
            0 -> {
                title(panel, "Параметры двигателя")
                readings = TextView(this).apply { textSize = 18f }
                panel.addView(readings)
                addButton("Обновить данные") { updateReadings(ObdService.instance) }
                addButton("Подключить ELM327") { chooseDevice() }
            }
            1 -> {
                title(panel, "Поездки и фоновая запись")
                addButton(if (service?.recording == true) "Остановить запись" else "Начать запись") {
                    send(ObdService.RECORD)
                    pageContainer = null
                    draw()
                }
                addButton("История и экспорт CSV") { showHistory() }
                title(panel, "При записи приложение можно свернуть.\nAndroid показывает постоянное уведомление.")
            }
            2 -> {
                title(panel, "Ошибки OBD-II · Mode 03")
                title(panel, service?.detail ?: "Для диагностики сначала подключите ЭБУ.")
                addButton("Считать стандартные DTC") {
                    send(ObdService.DTC)
                    Toast.makeText(this, "Запрос поставлен в очередь", Toast.LENGTH_SHORT).show()
                    pageContainer = null
                    Handler(Looper.getMainLooper()).postDelayed({ draw() }, 2200)
                }
                title(panel, "Это не полный скан блоков Mercedes и не заменяет Xentry.")
            }
            3 -> {
                title(panel, "ELM327 Bluetooth Classic / SPP")
                title(panel, "Сначала сопрягите адаптер в настройках Android.\n" +
                    (service?.detail ?: "Последний адаптер будет сохранён после выбора."))
                addButton("Выбрать сопряжённый ELM327") { chooseDevice() }
                addButton("Подключить сохранённый адаптер") { send(ObdService.CONNECT) }
                addButton("Экспорт журнала обмена ELM327") {
                    val file = ObdService.instance?.trace()
                    if (file != null) shareCsv(file)
                    else Toast.makeText(this, "Журнал пока пуст", Toast.LENGTH_SHORT).show()
                }
                addButton("Отключиться") { send(ObdService.DISCONNECT); pageContainer = null; draw() }
            }
            4 -> {
                title(panel, "MED40 · экспериментальная диагностика")
                title(panel, "Включается отдельно. Выполняется только пробный запрос чтения CAN 7E0 / PID 0100.\n" +
                    "Не заявляется доступ ко всем блокам MED40, запись параметров ECU или очистка ошибок.")
                title(panel, if (service?.med40 == true) "Пробный режим включён"
                    else "Пробный режим выключен")
                addButton(if (service?.med40 == true) "Выключить MED40" else "Включить пробу MED40") {
                    send(ObdService.MED40)
                    pageContainer = null
                    draw()
                }
            }
        }
        updateReadings(service)
    }
    private var readings: TextView? = null
    private fun updateReadings(service: ObdService?) {
        if (page == 0) readings?.text = when {
            service == null -> "Нет соединения. Откройте вкладку ELM327."
            service.values.isEmpty() -> "Нет подтверждённых PID.\n" + service.detail
            else -> service.values.entries.sortedBy { it.key }.joinToString("\n\n") {
                it.key + "  " + it.value
            }
        }
    }
    private fun title(root: LinearLayout, message: String) {
        root.addView(TextView(this).apply {
            text = message
            textSize = 18f
            setPadding(4, 8, 4, 14)
        })
    }
    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 30)
    }
    private fun chooseDevice() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_LONG).show()
                return
            }
            val devices = adapter.bondedDevices.toList()
            if (devices.isEmpty()) {
                Toast.makeText(this, "Сопрягите ELM327 в настройках Android", Toast.LENGTH_LONG).show()
                return
            }
            AlertDialog.Builder(this).setTitle("Сопряжённые устройства")
                .setItems(devices.map { (it.name ?: "Bluetooth") + "  " + it.address }.toTypedArray()) {
                    _, which -> send(ObdService.CONNECT, devices[which].address)
                }.show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Разрешите доступ к Bluetooth", Toast.LENGTH_LONG).show()
            requestBluetoothPermission()
        }
    }
    private fun showHistory() {
        val history = ObdService.instance?.history() ?: emptyList()
        if (history.isEmpty()) {
            Toast.makeText(this, "Записей пока нет", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Записи поездок")
            .setItems(history.map { it.name + " · " + (it.length()/1024) + " КБ" }.toTypedArray()) {
                _, index -> shareCsv(history[index])
            }.show()
    }
    private fun shareCsv(file: File) {
        val uri: Uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "txt") "text/plain" else "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Экспорт записи"))
    }
    override fun onDestroy() {
        handler.removeCallbacks(updater)
        super.onDestroy()
    }
}
