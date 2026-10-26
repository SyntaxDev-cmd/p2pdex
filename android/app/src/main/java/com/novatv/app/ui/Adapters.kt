package com.novatv.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.novatv.app.R
import com.novatv.app.data.Category
import com.novatv.app.data.Episode
import com.novatv.app.data.Kind
import com.novatv.app.data.Media
import com.novatv.app.data.Prefs

// ======================================================================== categorias

class CategoryAdapter(
    private val chip: Boolean,
    private val onClick: (Int, Category) -> Unit,
    private val onRight: (() -> Boolean)? = null,
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    var items: List<Category> = emptyList()
        private set
    var selected = -1
        private set
    /** Mostra ícone do tipo (aba Kids mistura canais/filmes/séries). */
    var showKind = false

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Category>, sel: Int) {
        items = list
        selected = sel
        notifyDataSetChanged()
    }

    fun select(pos: Int) {
        val old = selected
        selected = pos
        if (old >= 0) notifyItemChanged(old)
        if (pos >= 0) notifyItemChanged(pos)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val icon: ImageView = v.findViewById(R.id.icon)
        val lock: ImageView = v.findViewById(R.id.lock)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(if (chip) R.layout.item_chip else R.layout.item_category, parent, false)
        val ctx = parent.context
        v.background = Ui.itemBg(ctx)
        val h = VH(v)
        h.name.setTextColor(Ui.textColors())
        h.icon.imageTintList = Ui.textColors()
        h.lock.imageTintList = Ui.textColors()
        if (chip) {
            v.background = android.graphics.drawable.StateListDrawable().apply {
                val r = Ui.dp(ctx, 40).toFloat()
                addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(Ui.accent, r))
                addState(intArrayOf(android.R.attr.state_selected), Ui.rounded(Ui.primary, r))
                addState(intArrayOf(), Ui.rounded(Ui.surface, r, Ui.withAlpha(Color.WHITE, 40), Ui.dp(ctx, 1)))
            }
            Ui.focusZoom(v, 1.06f)
        }
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p, items[p])
        }
        v.setOnKeyListener { _, code, ev ->
            ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT && !chip && onRight?.invoke() == true
        }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        h.name.text = c.name
        h.itemView.isSelected = position == selected
        h.lock.visibility = if (c.locked) View.VISIBLE else View.GONE
        val icon = when {
            c.special == "fav" -> R.drawable.ic_heart
            c.special == "home" -> R.drawable.ic_home
            c.special == "recent" -> R.drawable.ic_refresh
            showKind -> when (c.kind) {
                Kind.LIVE -> R.drawable.ic_tv
                Kind.MOVIE -> R.drawable.ic_movie
                Kind.SERIES -> R.drawable.ic_list
            }
            else -> 0
        }
        if (icon != 0) {
            h.icon.setImageResource(icon)
            h.icon.visibility = View.VISIBLE
        } else {
            h.icon.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size
}

// ======================================================================== canais

class ChannelAdapter(
    private val onClick: (Int, Media) -> Unit,
    private val onLong: (Int, Media) -> Unit,
    private val onLeft: (() -> Boolean)? = null,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Media> = emptyList()
        private set
    var playingKey: String? = null
    private var favKeys: Set<String> = emptySet()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Media>) {
        items = list
        refreshFavs()
        notifyDataSetChanged()
    }

    fun refreshFavs() {
        favKeys = Prefs.favorites(Kind.LIVE).map { it.key }.toSet()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setPlaying(key: String?) {
        playingKey = key
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val playing: ImageView = v.findViewById(R.id.playing)
        val fav: ImageView = v.findViewById(R.id.fav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        v.background = Ui.itemBg(parent.context)
        val h = VH(v)
        val tc = Ui.textColors()
        h.num.setTextColor(tc)
        h.name.setTextColor(tc)
        h.playing.imageTintList = tc
        h.fav.imageTintList = android.content.res.ColorStateList.valueOf(Ui.fav)
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p, items[p])
        }
        v.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLong(p, items[p])
            true
        }
        v.setOnKeyListener { _, code, ev ->
            ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT && onLeft?.invoke() == true
        }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val m = items[position]
        h.num.text = (if (m.num > 0) m.num else position + 1).toString()
        h.name.text = m.name
        h.logo.load(m.icon.ifEmpty { null }) {
            placeholder(R.drawable.ic_tv)
            error(R.drawable.ic_tv)
            size(160, 100)
        }
        val isPlaying = m.key == playingKey
        h.playing.visibility = if (isPlaying) View.VISIBLE else View.GONE
        h.itemView.isSelected = isPlaying
        h.fav.visibility = if (m.key in favKeys) View.VISIBLE else View.GONE
    }

    override fun getItemCount() = items.size
}

// ======================================================================== cartazes

data class PosterItem(
    val title: String,
    val image: String,
    val kind: Kind,
    val badge: String = "",
    val rating: String = "",
    val progress: Int = -1,
    val media: Media? = null,
    val payload: Any? = null,
)

class PosterAdapter(
    private val onClick: (Int, PosterItem) -> Unit,
    private val fixedWidthDp: Int = 0,
    private val accentColor: Int = Ui.accent,
    private val onLong: ((Int, PosterItem) -> Unit)? = null,
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    var items: List<PosterItem> = emptyList()
        private set
    private var favKeys: Set<String> = Prefs.favorites().map { it.key }.toSet()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<PosterItem>) {
        items = list
        refreshFavs()
        notifyDataSetChanged()
    }

    fun refreshFavs() {
        favKeys = Prefs.favorites().map { it.key }.toSet()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val frame: AspectFrame = v.findViewById(R.id.frame)
        val image: ImageView = v.findViewById(R.id.image)
        val title: TextView = v.findViewById(R.id.title)
        val badge: TextView = v.findViewById(R.id.badge)
        val rating: TextView = v.findViewById(R.id.rating)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val focusFrame: View = v.findViewById(R.id.focusFrame)
        val fav: ImageView = v.findViewById(R.id.fav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_poster, parent, false)
        if (fixedWidthDp > 0) v.layoutParams = v.layoutParams.apply { width = Ui.dp(ctx, fixedWidthDp) }
        val h = VH(v)
        val r = Ui.radius(ctx)
        h.frame.background = Ui.rounded(Ui.withAlpha(Ui.surfaceSolid, 200), r)
        Ui.clipRounded(h.frame, r)
        h.focusFrame.background = Ui.posterFrame(ctx, accentColor)
        h.badge.background = Ui.rounded(Ui.primary, Ui.dp(ctx, 4).toFloat())
        h.rating.background = Ui.rounded(Ui.withAlpha(Color.BLACK, 180), Ui.dp(ctx, 4).toFloat())
        h.progress.progressTintList = android.content.res.ColorStateList.valueOf(Ui.primary)
        h.title.setTextColor(Color.WHITE)
        h.badge.setTextColor(Color.WHITE)
        h.fav.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Ui.withAlpha(Color.BLACK, 170))
        }
        h.fav.imageTintList = android.content.res.ColorStateList.valueOf(Ui.fav)
        Ui.focusZoom(v, 1.08f)
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p, items[p])
        }
        if (onLong != null) v.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLong.invoke(p, items[p])
            true
        }
        return h
    }

    override fun getItemViewType(position: Int): Int = if (items[position].kind == Kind.LIVE) 1 else 0

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        val live = it.kind == Kind.LIVE
        h.frame.ratio = if (live) 0.75f else 1.5f
        h.image.scaleType = if (live) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        val pad = if (live) Ui.dp(h.itemView.context, 18) else 0
        h.image.setPadding(pad, pad, pad, pad + if (live) Ui.dp(h.itemView.context, 14) else 0)
        h.title.text = it.title
        h.image.load(it.image.ifEmpty { null }) {
            placeholder(if (live) R.drawable.ic_tv else R.drawable.ic_movie)
            error(if (live) R.drawable.ic_tv else R.drawable.ic_movie)
            size(300, 450)
        }
        h.badge.visibility = if (it.badge.isNotEmpty()) View.VISIBLE else View.GONE
        h.badge.text = it.badge
        h.rating.visibility = if (it.rating.isNotEmpty()) View.VISIBLE else View.GONE
        h.rating.text = "★ ${it.rating}"
        h.progress.visibility = if (it.progress >= 0) View.VISIBLE else View.GONE
        if (it.progress >= 0) h.progress.progress = it.progress
        h.fav.visibility = if (it.media != null && it.media.key in favKeys) View.VISIBLE else View.GONE
    }

    override fun getItemCount() = items.size

    companion object {
        fun from(m: Media, showKind: Boolean = false): PosterItem = PosterItem(
            title = m.name,
            image = m.icon,
            kind = m.kind,
            badge = if (!showKind) "" else when (m.kind) {
                Kind.LIVE -> "AO VIVO"
                Kind.MOVIE -> "FILME"
                Kind.SERIES -> "SÉRIE"
            },
            rating = m.rating,
            media = m,
        )
    }
}

// ======================================================================== banners

class BannerAdapter(private val onClick: (Int) -> Unit) : RecyclerView.Adapter<BannerAdapter.VH>() {
    data class Item(val title: String, val subtitle: String, val image: String)

    var items: List<Item> = emptyList()
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Item>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val frame: AspectFrame = v.findViewById(R.id.frame)
        val image: ImageView = v.findViewById(R.id.image)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val focusFrame: View = v.findViewById(R.id.focusFrame)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_banner, parent, false)
        val w = parent.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        v.layoutParams = v.layoutParams.apply { width = (w * if (Ui.isTv || w > Ui.dp(ctx, 700)) 0.62f else 0.88f).toInt() }
        val h = VH(v)
        val r = Ui.radius(ctx) * 1.3f
        h.frame.background = Ui.rounded(Ui.surface, r)
        Ui.clipRounded(h.frame, r)
        h.focusFrame.background = Ui.posterFrame(ctx)
        h.title.setTextColor(Color.WHITE)
        h.subtitle.setTextColor(Color.WHITE)
        Ui.focusZoom(v, 1.03f)
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.title.text = it.title
        h.subtitle.text = it.subtitle
        h.subtitle.visibility = if (it.subtitle.isEmpty()) View.GONE else View.VISIBLE
        h.image.load(it.image.ifEmpty { null }) { error(R.drawable.ic_movie) }
    }

    override fun getItemCount() = items.size
}

// ======================================================================== episódios

class EpisodeAdapter(private val onClick: (Int, Episode) -> Unit) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
    var items: List<Episode> = emptyList()
        private set
    var fallbackImage: String = ""
    var progressOf: (Episode) -> Int = { -1 }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Episode>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val frame: AspectFrame = v.findViewById(R.id.frame)
        val image: ImageView = v.findViewById(R.id.image)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val focusFrame: View = v.findViewById(R.id.focusFrame)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_episode, parent, false)
        val h = VH(v)
        val r = Ui.radius(ctx)
        v.background = Ui.rounded(Ui.surface, r)
        h.frame.background = Ui.rounded(Ui.withAlpha(Color.BLACK, 160), r)
        Ui.clipRounded(h.frame, r)
        h.focusFrame.background = Ui.posterFrame(ctx)
        h.title.setTextColor(Ui.text)
        h.meta.setTextColor(Ui.text2)
        h.progress.progressTintList = android.content.res.ColorStateList.valueOf(Ui.primary)
        Ui.focusZoom(v, 1.06f)
        v.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p, items[p])
        }
        return h
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.title.text = "${e.number}. ${e.title}"
        h.meta.text = listOf("T${e.season} E${e.number}", e.duration.removePrefix("00:")).filter { it.isNotBlank() }.joinToString("  •  ")
        h.image.load(e.image.ifEmpty { fallbackImage }.ifEmpty { null }) {
            error(R.drawable.ic_movie)
            size(400, 225)
        }
        val p = progressOf(e)
        h.progress.visibility = if (p >= 0) View.VISIBLE else View.GONE
        if (p >= 0) h.progress.progress = p
    }

    override fun getItemCount() = items.size
}
