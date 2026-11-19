package kz.pressurediary

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DISPLAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy  HH:mm", Locale("ru"))
private const val CREATE_FILE_REQUEST = 701

 data class Reading(
    val dateTime: LocalDateTime,
    val systolic: Double,
    val diastolic: Double,
    val pulse: Double,
    val count: Int
)

class MainActivity : Activity() {
    private val recordsFile by lazy { File(filesDir, "pressure_diary.csv") }
    private lateinit var root: LinearLayout
    private lateinit var sysInput: EditText
    private lateinit var diaInput: EditText
    private lateinit var pulseInput: EditText
    private lateinit var timestampLabel: TextView
    private var selectedTab = "input"
    private var chartFrom = LocalDate.now().minusDays(7)
    private var chartTo = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureFile()
        showInput()
    }

    private fun ensureFile() {
        if (!recordsFile.exists()) recordsFile.writeText("date;time;systolic;diastolic;pulse;count\n", Charsets.UTF_8)
    }

    private fun dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

    private fun baseScreen(title: String): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(Color.rgb(247, 249, 250))
        }
        val heading = TextView(this).apply {
            text = title; textSize = 25f; setTextColor(Color.rgb(22, 55, 68));
            typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(12))
        }
        root.addView(heading)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        nav.addView(navButton("Ввод") { showInput() }, weightParams())
        nav.addView(navButton("История") { showHistory() }, weightParams())
        nav.addView(navButton("Графики") { showCharts() }, weightParams())
        root.addView(nav)
        setContentView(root)
        return content
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(8), dp(3), 0) }

    private fun navButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 12f; isAllCaps = false; setOnClickListener { action() }
    }

    private fun addLabel(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text; textSize = 15f; setTextColor(Color.rgb(65, 83, 92));
            setPadding(0, dp(8), 0, dp(3))
        })
    }

    private fun edit(parent: LinearLayout, hint: String, numeric: Boolean = true): EditText {
        val e = EditText(this).apply {
            this.hint = hint; textSize = 21f; isSingleLine = true
            inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL else android.text.InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Color.WHITE)
        }
        parent.addView(e, LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(0, 0, 0, dp(4)) })
        return e
    }

    private fun primaryButton(parent: LinearLayout, text: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            this.text = text; textSize = 16f; isAllCaps = false
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { setMargins(0, dp(14), 0, dp(6)) })
    }

    private fun showInput() {
        selectedTab = "input"
        val c = baseScreen("Дневник давления")
        timestampLabel = TextView(this).apply {
            textSize = 17f; setTextColor(Color.rgb(18, 91, 116)); typeface = Typeface.DEFAULT_BOLD
            text = "Время измерения: ${LocalDateTime.now().format(DISPLAY_FMT)}"
        }
        c.addView(timestampLabel)
        addLabel(c, "Верхнее давление (SYS), мм рт. ст.")
        sysInput = edit(c, "Например, 125")
        addLabel(c, "Нижнее давление (DIA), мм рт. ст.")
        diaInput = edit(c, "Например, 80")
        addLabel(c, "Пульс, уд/мин")
        pulseInput = edit(c, "Например, 72")
        primaryButton(c, "СОХРАНИТЬ ИЗМЕРЕНИЕ") { saveReading() }
        val info = TextView(this).apply {
            text = "Повторные замеры в течение 15 минут объединяются в одну запись. Время первого замера сохраняется."
            textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, dp(8), 0, 0)
        }
        c.addView(info)
        primaryButton(c, "ЭКСПОРТ CSV-ФАЙЛА") { exportCsv() }
    }

    private fun saveReading() {
        val s = sysInput.text.toString().replace(',', '.').toDoubleOrNull()
        val d = diaInput.text.toString().replace(',', '.').toDoubleOrNull()
        val p = pulseInput.text.toString().replace(',', '.').toDoubleOrNull()
        if (s == null || d == null || p == null || s <= 0 || d <= 0 || p <= 0) {
            Toast.makeText(this, "Введите корректные значения давления и пульса", Toast.LENGTH_LONG).show(); return
        }
        if (s > 300 || d > 200 || p > 250) {
            Toast.makeText(this, "Проверьте введённые значения", Toast.LENGTH_LONG).show(); return
        }
        val now = LocalDateTime.now()
        val all = readRecords().toMutableList()
        val last = all.lastOrNull()
        val elapsedSeconds = if (last != null) Duration.between(last.dateTime, now).seconds else Long.MAX_VALUE
        val result: Reading
        if (last != null && elapsedSeconds in 0..900) {
            val newCount = last.count + 1
            result = Reading(
                last.dateTime,
                (last.systolic * last.count + s) / newCount,
                (last.diastolic * last.count + d) / newCount,
                (last.pulse * last.count + p) / newCount,
                newCount
            )
            all[all.lastIndex] = result
            rewriteRecords(all)
        } else {
            result = Reading(now, s, d, p, 1)
            appendRecord(result)
        }
        sysInput.text.clear(); diaInput.text.clear(); pulseInput.text.clear()
        val msg = if (result.count > 1) "Замер объединён. Значений в группе: ${result.count}\nСреднее: ${fmt(result.systolic)}/${fmt(result.diastolic)}, пульс ${fmt(result.pulse)}" else "Измерение сохранено"
        AlertDialog.Builder(this).setTitle("Готово").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)

    private fun csvLine(r: Reading): String = "${r.dateTime.toLocalDate().format(DATE_FMT)};${r.dateTime.toLocalTime().format(TIME_FMT)};${fmt(r.systolic)};${fmt(r.diastolic)};${fmt(r.pulse)};${r.count}"

    private fun appendRecord(r: Reading) {
        ensureFile(); recordsFile.appendText(csvLine(r) + "\n", Charsets.UTF_8)
    }

    private fun rewriteRecords(records: List<Reading>) {
        val temp = File(filesDir, "pressure_diary.tmp")
        temp.bufferedWriter(Charsets.UTF_8).use { out ->
            out.write("date;time;systolic;diastolic;pulse;count\n")
            records.forEach { out.write(csvLine(it)); out.newLine() }
        }
        if (!temp.renameTo(recordsFile)) throw IllegalStateException("Не удалось атомарно обновить файл журнала")
    }

    private fun readRecords(): List<Reading> {
        ensureFile()
        return recordsFile.readLines(Charsets.UTF_8).drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val a = line.split(';')
            if (a.size != 6) null else try {
                Reading(LocalDate.parse(a[0], DATE_FMT).atTime(java.time.LocalTime.parse(a[1], TIME_FMT)), a[2].toDouble(), a[3].toDouble(), a[4].toDouble(), a[5].toInt().coerceAtLeast(1))
            } catch (_: Exception) { null }
        }
    }

    private fun showHistory() {
        selectedTab = "history"
        val c = baseScreen("История измерений")
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val records = readRecords().asReversed()
        if (records.isEmpty()) list.addView(TextView(this).apply { text = "Пока нет сохранённых измерений"; textSize = 16f })
        records.forEach { r ->
            val item = TextView(this).apply {
                text = "${r.dateTime.format(DISPLAY_FMT)}\n${fmt(r.systolic)} / ${fmt(r.diastolic)} мм рт. ст.   Пульс: ${fmt(r.pulse)}\nЗамеров объединено: ${r.count}"
                textSize = 16f; setTextColor(Color.rgb(28, 48, 57)); setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(Color.WHITE)
            }
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
        }
        scroll.addView(list); c.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        primaryButton(c, "ЭКСПОРТ CSV-ФАЙЛА") { exportCsv() }
    }

    private fun showCharts() {
        selectedTab = "charts"
        val c = baseScreen("Графики давления")
        addLabel(c, "Начало периода (ГГГГ-ММ-ДД)")
        val from = edit(c, "${chartFrom.format(DATE_FMT)}", false).apply { setText(chartFrom.format(DATE_FMT)); textSize = 16f }
        addLabel(c, "Конец периода (ГГГГ-ММ-ДД)")
        val to = edit(c, "${chartTo.format(DATE_FMT)}", false).apply { setText(chartTo.format(DATE_FMT)); textSize = 16f }
        val chartHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(chartHolder, LinearLayout.LayoutParams(-1, 0, 1f))
        fun draw() {
            try {
                val start = LocalDate.parse(from.text.toString().trim(), DATE_FMT)
                val end = LocalDate.parse(to.text.toString().trim(), DATE_FMT)
                if (end.isBefore(start)) throw IllegalArgumentException()
                chartFrom = start; chartTo = end
                val data = readRecords().filter { !it.dateTime.toLocalDate().isBefore(start) && !it.dateTime.toLocalDate().isAfter(end) }
                chartHolder.removeAllViews()
                if (data.isEmpty()) chartHolder.addView(TextView(this).apply { text = "За выбранный период записей нет"; textSize = 16f })
                else {
                    chartHolder.addView(TextView(this).apply { text = "Давление: верхнее и нижнее"; textSize = 15f; setPadding(0, dp(8), 0, 0) })
                    chartHolder.addView(LineChartView(data, false), LinearLayout.LayoutParams(-1, dp(220)))
                    chartHolder.addView(TextView(this).apply { text = "Пульс"; textSize = 15f; setPadding(0, dp(8), 0, 0) })
                    chartHolder.addView(LineChartView(data, true), LinearLayout.LayoutParams(-1, dp(170)))
                }
            } catch (_: Exception) { Toast.makeText(this, "Проверьте даты. Формат: ГГГГ-ММ-ДД", Toast.LENGTH_LONG).show() }
        }
        primaryButton(c, "ПОСТРОИТЬ ГРАФИК") { draw() }
        draw()
    }

    private fun exportCsv() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"; putExtra(Intent.EXTRA_TITLE, "pressure_diary.csv")
        }
        startActivityForResult(intent, CREATE_FILE_REQUEST)
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CREATE_FILE_REQUEST && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { output -> recordsFile.inputStream().use { input -> input.copyTo(output) } }
                Toast.makeText(this, "CSV-файл экспортирован", Toast.LENGTH_LONG).show()
            } catch (e: Exception) { Toast.makeText(this, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    inner class LineChartView(private val data: List<Reading>, private val pulseOnly: Boolean) : View(this@MainActivity) {
        private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; strokeWidth = dp(1).toFloat(); style = Paint.Style.STROKE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = dp(10).toFloat() }
        private val sysPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 75, 65); strokeWidth = dp(2).toFloat(); style = Paint.Style.STROKE }
        private val diaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 126, 160); strokeWidth = dp(2).toFloat(); style = Paint.Style.STROKE }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(38).toFloat(); val right = width - dp(10).toFloat(); val top = dp(12).toFloat(); val bottom = height - dp(25).toFloat()
            if (data.isEmpty() || right <= left || bottom <= top) return
            val values = if (pulseOnly) data.map { it.pulse } else data.flatMap { listOf(it.systolic, it.diastolic) }
            var lo = values.minOrNull() ?: 0.0; var hi = values.maxOrNull() ?: 1.0
            val pad = max(5.0, (hi - lo) * 0.12); lo -= pad; hi += pad
            for (i in 0..4) {
                val y = top + (bottom - top) * i / 4f
                canvas.drawLine(left, y, right, y, axis)
                val label = String.format(Locale.US, "%.0f", hi - (hi - lo) * i / 4.0)
                canvas.drawText(label, 0f, y + dp(4), text)
            }
            fun drawSeries(selector: (Reading) -> Double, paint: Paint) {
                val path = Path()
                data.forEachIndexed { index, r ->
                    val x = if (data.size == 1) (left + right) / 2 else left + (right - left) * index / (data.size - 1)
                    val y = bottom - ((selector(r) - lo) / (hi - lo)).toFloat() * (bottom - top)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    canvas.drawCircle(x, y, dp(2.5).toFloat(), paint.apply { style = Paint.Style.FILL })
                }
                paint.style = Paint.Style.STROKE; canvas.drawPath(path, paint)
            }
            if (pulseOnly) drawSeries({ it.pulse }, diaPaint) else { drawSeries({ it.systolic }, sysPaint); drawSeries({ it.diastolic }, diaPaint) }
            canvas.drawText(data.first().dateTime.format(DateTimeFormatter.ofPattern("dd.MM", Locale.ROOT)), left, height - dp(5).toFloat(), text)
            val endLabel = data.last().dateTime.format(DateTimeFormatter.ofPattern("dd.MM", Locale.ROOT))
            val tw = text.measureText(endLabel); canvas.drawText(endLabel, right - tw, height - dp(5).toFloat(), text)
        }
    }
}
