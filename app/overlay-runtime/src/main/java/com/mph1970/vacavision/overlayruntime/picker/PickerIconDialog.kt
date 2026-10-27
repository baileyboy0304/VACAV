package com.mph1970.vacavision.overlayruntime.picker

import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.BuildConfig

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlinx.coroutines.*

/** A bounded, remote-focusable grid; all icons are available without a network connection. */
class PickerIconDialog(context: Context, private val current: String, private val dashboardName: String, private val picked: (String) -> Unit) : Dialog(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var names = emptyList<String>()
    private var filtered = emptyList<String>()
    private var page = 0
    private var category = 0
    private lateinit var search: EditText
    private lateinit var grid: LinearLayout
    private lateinit var info: TextView
    private lateinit var categoryButton: Button
    private lateinit var previous: Button
    private lateinit var next: Button
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 16f; isFocusableInTouchMode = true
        setTextColor(Color.WHITE)
        fun paint(focus: Boolean) { background = GradientDrawable().apply {
            setColor(if (focus) 0xff17434a.toInt() else 0xff26353e.toInt())
            cornerRadius = dp(6).toFloat(); setStroke(dp(2), if (focus) 0xff59d4c7.toInt() else 0xff334852.toInt())
        } }
        paint(false); setOnFocusChangeListener { _, focused -> paint(focused) }
        setOnClickListener { action() }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setTitle("Choose picker icon")
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(0xff101820.toInt())
        }
        body.addView(TextView(context).apply {
            text = "Home Assistant icons · $current"; textSize = 19f; setTextColor(Color.WHITE)
        })
        body.addView(TextView(context).apply {
            text = "Filter icons · updates as you type"; textSize = 16f; setTextColor(Color.LTGRAY)
        })
        search = EditText(context).apply {
            hint = "Type an icon name, or clear to browse all"; isSingleLine = true; textSize = 18f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            contentDescription = "Filter icons"
            setText(dashboardName.trim())
            setSelectAllOnFocus(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (::grid.isInitialized) filter() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
        val controls = LinearLayout(context)
        fun control(button: Button) { controls.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }) }
        categoryButton = button("Category: All") { category = (category + 1) % CATEGORIES.size; filter() }
        previous = button("Previous") { if (page > 0) { page--; render() } }
        next = button("Next") { if ((page + 1) * PAGE_SIZE < filtered.size) { page++; render() } }
        control(categoryButton); control(previous); control(next)
        control(button("Use default") { picked(""); dismiss() })
        control(button("Cancel") { dismiss() })
        body.addView(controls)
        info = TextView(context).apply { text = "Loading icons…"; textSize = 16f; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, dp(8)) }
        body.addView(info)
        grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(grid)
        setContentView(body)
        window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        scope.launch {
            try {
                names = withContext(Dispatchers.IO) { HaPickerIcons.names(context) }
                filter()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                info.text = "Could not load the icon library. Close and retry."
            }
        }
    }
    override fun onStart() {
        super.onStart()
        window?.setLayout((context.resources.displayMetrics.widthPixels * .94f).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        categoryButton.requestFocusFromTouch()
    }
    private fun filter() {
        page = 0; categoryButton.text = "Category: ${CATEGORIES[category]}"
        filtered = filterNames(names, search.text.toString(), CATEGORIES[category]); render()
    }
    private fun render() {
        info.text = if (filtered.isEmpty()) "No matching icons — edit or clear the filter, or change category" else
            "${filtered.size} icons · Page ${page + 1}/${(filtered.size + PAGE_SIZE - 1) / PAGE_SIZE} · Arrows to browse, Select to choose"
        grid.removeAllViews()
        val visible = filtered.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        for (rowIndex in 0 until 2) {
            val row = LinearLayout(context)
            for (column in 0 until 6) {
                val icon = visible.getOrNull(rowIndex * 6 + column)
                val tile = if (icon == null) View(context) else button(icon.substringAfter(':')) { picked(icon); dismiss() }.apply {
                    textSize = 12f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                    contentDescription = icon
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    val picture = HaPickerIcons.drawable(context, icon)?.apply { setBounds(0, 0, dp(34), dp(34)) }
                    setCompoundDrawables(null, picture, null, null)
                }
                row.addView(tile, LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            grid.addView(row)
        }
    }
    override fun dismiss() { scope.cancel(); super.dismiss() }
    companion object {
        private const val PAGE_SIZE = 12
        private val separators = Regex("[^\\p{L}\\p{N}]+")
        private val modifiers = setOf("big", "small", "large", "little", "tiny", "my", "the", "dashboard", "view", "icon", "icons")
        val CATEGORIES = listOf("All", "Weather", "Media", "Home", "Lighting", "Climate", "Travel")
        fun filterNames(names: List<String>, query: String, category: String): List<String> {
            val words = query.trim().lowercase().removePrefix("mdi:").split(separators).filter { it.isNotEmpty() }.distinct()
            val terms = words.filterNot { it in modifiers }.ifEmpty { words }
            val categoryTerms = when (category) {
                "Weather" -> listOf("weather", "sun", "moon", "cloud", "snow", "rain", "waves", "tide")
                "Media" -> listOf("music", "play", "pause", "video", "television", "speaker", "radio", "headphone")
                "Home" -> listOf("home", "door", "window", "lock", "camera", "alarm", "sofa", "bed")
                "Lighting" -> listOf("light", "lamp", "led", "brightness")
                "Climate" -> listOf("thermo", "temperature", "fan", "air", "heat", "cool", "water")
                "Travel" -> listOf("car", "bus", "train", "plane", "map", "bike", "walk", "ferry")
                else -> emptyList()
            }
            // Dashboard titles often contain room/size words absent from MDI names. Match any
            // meaningful word, then rank icons matching more words (and exact words) first.
            return names.asSequence()
                .filter { name -> categoryTerms.isEmpty() || categoryTerms.any { name.contains(it) } }
                .mapNotNull { name ->
                    if (terms.isEmpty()) return@mapNotNull name to 0
                    val iconName = name.substringAfter(':').lowercase()
                    val iconWords = iconName.split(separators)
                    var score = 0
                    for (term in terms) {
                        val singular = if (term.length > 4 && term.endsWith("s") && !term.endsWith("ss")) term.dropLast(1) else term
                        score += when {
                            term == iconWords.firstOrNull() -> 120
                            term in iconWords -> 100
                            iconWords.any { it.contains(singular) } -> 50
                            else -> 0
                        }
                    }
                    if (score == 0) null else name to (score + if (iconName == words.joinToString("-")) 10000 else 0)
                }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .map { it.first }.toList()
        }
    }
}
