package com.metro.start

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/** Alphabetical app list with coloured letter squares. */
class AppListAdapter(private val host: StartActivity) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Each row is a Char (letter header) or an AppInfo. */
    var rows: List<Any> = emptyList()
        private set
    val headerPos = HashMap<Char, Int>()

    fun submit(all: List<AppInfo>, query: String) {
        val out = ArrayList<Any>()
        headerPos.clear()
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) {
            var cur = ' '
            for (a in all) {
                if (a.letter != cur) {
                    cur = a.letter
                    headerPos[cur] = out.size
                    out.add(cur)
                }
                out.add(a)
            }
        } else {
            // Word starts first, then anything containing the query.
            val starts = all.filter { a -> a.name.split(' ', '-', '.').any { it.startsWith(q) } }
            out.addAll(starts)
            out.addAll(all.filter { it.name.contains(q) && it !in starts })
        }
        rows = out
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (rows[position] is Char) 0 else 1

    class HeaderHolder(val frame: FrameLayout, val square: TextView) : RecyclerView.ViewHolder(frame)
    class AppHolder(val row: LinearLayout, val icon: IconSquare, val name: TextView) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val c = parent.context
        return if (viewType == 0) {
            val frame = FrameLayout(c).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, c.dp(64))
            }
            val sq = c.text("", 26f, Metro.light, android.graphics.Color.WHITE).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setPadding(c.dp(6), 0, 0, c.dp(1))
                includeFontPadding = false
                isClickable = true
                setOnClickListener { host.showJumpGrid() }
            }
            Tilt.attach(sq, 10f)
            frame.addView(sq, FrameLayout.LayoutParams(c.dp(48), c.dp(48), Gravity.CENTER_VERTICAL).apply {
                leftMargin = c.dp(20)
            })
            HeaderHolder(frame, sq)
        } else {
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, c.dp(64))
                setPadding(c.dp(20), 0, c.dp(20), 0)
                isClickable = true
                isLongClickable = true
            }
            val icon = IconSquare(c)
            val name = c.text("", 22f, Metro.light).apply {
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(icon, LinearLayout.LayoutParams(c.dp(48), c.dp(48)))
            row.addView(name, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { leftMargin = c.dp(14) })
            Tilt.attach(row, 2f)
            AppHolder(row, icon, name)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = rows[position]
        if (holder is HeaderHolder && item is Char) {
            holder.square.text = item.toString()
            holder.square.setBackgroundColor(Look.letterColor(item))
        } else if (holder is AppHolder && item is AppInfo) {
            holder.icon.app = item
            holder.icon.color = host.iconColor(item)
            holder.name.text = item.name
            holder.row.setOnClickListener { v -> host.onAppTap(item, v) }
            holder.row.setOnLongClickListener { v ->
                host.onAppLongPress(item, v)
                true
            }
        }
    }

    fun visibleRows(rv: RecyclerView): List<View> = (0 until rv.childCount).map { rv.getChildAt(it) }
}
