package com.example.gamespace

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Builds the "Features" tab: search box, category chips, and one row per feature. */
class FeaturesTab(
    private val ctx: Context,
    private val prefs: Prefs,
    private val onSearchTouched: () -> Unit,
    private val onChanged: (String) -> Unit
) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val features = FeatureRegistry.all(ctx)
    private val cats = FeatureRegistry.categories(ctx)
    private var current = cats.first()
    private var query = ""

    private lateinit var list: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var count: TextView

    fun build(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        root.addView(TextView(ctx).apply {
            text = "ALL FEATURES"
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.15f
        })
        count = TextView(ctx).apply { setTextColor(0xFF8E8E96.toInt()); textSize = 11f }
        root.addView(count)

        val search = EditText(ctx).apply {
            hint = "Search ${features.size} features"
            setHintTextColor(0xFF6E6E76.toInt())
            setTextColor(Color.WHITE); textSize = 14f
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isSingleLine = true
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) onSearchTouched()
                false
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val hs = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        root.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(ctx).apply { isFillViewport = false; addView(list) }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        refresh()
        return root
    }

    private fun refresh() {
        chips.removeAllViews()
        for (c in cats) {
            val on = c == current && query.isEmpty()
            chips.addView(TextView(ctx).apply {
                text = c
                textSize = 12f
                setTextColor(if (on) Color.WHITE else 0xFFB0B0B8.toInt())
                setBackgroundResource(if (on) R.drawable.bg_boost else R.drawable.bg_card)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { current = c; query = ""; refresh() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        val shown = if (query.isEmpty()) features.filter { it.cat == current }
        else features.filter { it.title.contains(query, true) || it.cat.contains(query, true) }.take(80)
        count.text = if (query.isEmpty()) "$current · ${shown.size} features" else "${shown.size} results"
        list.removeAllViews()
        for (f in shown) list.addView(row(f))
    }

    private fun row(f: Feature): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val txt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(ctx).apply { text = f.title; setTextColor(Color.WHITE); textSize = 14f })
        txt.addView(TextView(ctx).apply {
            text = f.cat + (if (LiveIds.ALL.contains(f.id)) " · live" else " · saved to profile")
            setTextColor(0xFF8E8E96.toInt()); textSize = 11f
        })
        top.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))

        when (f.kind) {
            0 -> top.addView(Switch(ctx).apply {
                isChecked = prefs.bool(f.id)
                setOnCheckedChangeListener { _, v -> prefs.setBool(f.id, v); onChanged(f.id) }
            })
            2 -> {
                val btn = TextView(ctx).apply {
                    setTextColor(Color.WHITE); textSize = 12f
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                }
                fun show() { btn.text = f.options[prefs.int(f.id, 0).coerceIn(0, f.options.size - 1)] }
                show()
                btn.setOnClickListener {
                    prefs.setInt(f.id, (prefs.int(f.id, 0) + 1) % f.options.size); show(); onChanged(f.id)
                }
                top.addView(btn)
            }
        }
        box.addView(top)
        if (f.kind == 1) {
            val label = TextView(ctx).apply { setTextColor(0xFFB0B0B8.toInt()); textSize = 11f }
            val bar = SeekBar(ctx).apply {
                max = 100
                progress = prefs.int(f.id, 50)
                label.text = "$progress"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                        label.text = "$p"; if (u) prefs.setInt(f.id, p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { onChanged(f.id) }
                })
            }
            box.addView(bar)
            box.addView(label)
        }
        return box
    }
}
