package com.example.gamespace

import android.content.Context

data class Feature(
    val id: String,
    val cat: String,
    val title: String,
    val kind: Int,            // 0 = toggle, 1 = slider, 2 = choice
    val options: List<String>
)

object FeatureRegistry {
    private var cache: List<Feature>? = null

    fun slug(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    fun all(ctx: Context): List<Feature> {
        cache?.let { return it }
        val list = ArrayList<Feature>()
        ctx.assets.open("features.tsv").bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split("|")
                if (parts.size < 2) continue
                val cat = parts[0]
                val title = parts[1]
                var kind = 0
                var opts: List<String> = emptyList()
                if (parts.size >= 3) {
                    val spec = parts[2]
                    if (spec == "s") kind = 1
                    else if (spec.startsWith("c:")) {
                        kind = 2
                        opts = spec.substring(2).split("/")
                    }
                }
                list.add(Feature(slug(cat) + "_" + slug(title), cat, title, kind, opts))
            }
        }
        cache = list
        return list
    }

    fun categories(ctx: Context): List<String> = all(ctx).map { it.cat }.distinct()
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("gamespace_profile", Context.MODE_PRIVATE)
    fun has(id: String) = sp.contains(id)
    fun bool(id: String, def: Boolean = false) = sp.getBoolean(id, def)
    fun setBool(id: String, v: Boolean) = sp.edit().putBoolean(id, v).apply()
    fun int(id: String, def: Int = 50) = sp.getInt(id, def)
    fun setInt(id: String, v: Int) = sp.edit().putInt(id, v).apply()
    fun clearAll() = sp.edit().clear().apply()
}
