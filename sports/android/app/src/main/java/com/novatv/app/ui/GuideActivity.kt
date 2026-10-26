package com.novatv.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.novatv.app.R
import com.novatv.app.data.EpgItem
import com.novatv.app.data.Media
import com.novatv.app.data.Prefs
import com.novatv.app.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Guia de programação: canais à esquerda, grade do dia do canal à direita. */
class GuideActivity : BaseActivity() {

    companion object {
        var channels: List<Media> = emptyList()
        var startIndex: Int = 0

        fun open(act: android.app.Activity, list: List<Media>, start: Int) {
            channels = list
            startIndex = start
            act.startActivity(Intent(act, GuideActivity::class.java))
        }
    }

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))
    private lateinit var nowTitle: TextView
    private lateinit var nowTime: TextView
    private lateinit var nowDesc: TextView
    private lateinit var nowProgress: ProgressBar
    private lateinit var nowLogo: ImageView
    private lateinit var progList: RecyclerView
    private lateinit var loading: ProgressBar
    private lateinit var empty: TextView
    private lateinit var chAdapter: ChannelAdapter
    private val progAdapter = ProgAdapter { item -> onProgram(item) }
    private var selected: Media? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Repo.provider == null || channels.isEmpty()) {
            finish()
            return
        }
        window.setBackgroundDrawable(Ui.backgroundGradient())
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val pad = Ui.dp(this, if (portrait) 12 else 22)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(this@GuideActivity, 16), pad, Ui.dp(this@GuideActivity, 12))
        }
        // topo
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            background = Ui.iconButton(this@GuideActivity)
            imageTintList = Ui.textColors(Ui.text, Ui.focusText)
            val p = Ui.dp(this@GuideActivity, 9)
            setPadding(p, p, p, p)
            isFocusable = true
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)))
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@GuideActivity, 14), 0, 0, 0)
            addView(TextView(this@GuideActivity).apply {
                text = "Guia de programação"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.text)
            })
            addView(TextView(this@GuideActivity).apply {
                text = dayFmt.format(Date()).replaceFirstChar { it.uppercase() }
                textSize = 14f
                setTextColor(Ui.text2)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)

        val body = LinearLayout(this).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@GuideActivity, 12), 0, 0)
            isBaselineAligned = false
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        // canais
        val chList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@GuideActivity)
            itemAnimator = null
            background = Ui.panel(this@GuideActivity)
            val p = Ui.dp(this@GuideActivity, 4)
            setPadding(p, p, p, p)
            clipToPadding = false
        }
        chAdapter = ChannelAdapter(
            onClick = { pos, _ -> play(pos) },
            onLong = { pos, m ->
                val added = Prefs.toggleFavorite(m)
                Dialogs.toast(this, if (added) "♥ Adicionado aos favoritos" else "Removido dos favoritos")
                chAdapter.refreshFavs()
                chAdapter.notifyItemChanged(pos)
            },
        )
        chList.adapter = chAdapter
        chAdapter.submit(channels)
        body.addView(chList, if (portrait) LinearLayout.LayoutParams(-1, 0, 0.38f) else LinearLayout.LayoutParams(0, -1, 0.34f))

        // programação
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(right, if (portrait) LinearLayout.LayoutParams(-1, 0, 0.62f).apply { topMargin = Ui.dp(this@GuideActivity, 10) }
        else LinearLayout.LayoutParams(0, -1, 0.66f).apply { leftMargin = Ui.dp(this@GuideActivity, 12) })

        val nowCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.panel(this@GuideActivity)
            val p = Ui.dp(this@GuideActivity, 14)
            setPadding(p, p, p, p)
            gravity = Gravity.CENTER_VERTICAL
        }
        nowLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        nowCard.addView(nowLogo, LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 48)).apply { rightMargin = Ui.dp(this@GuideActivity, 14) })
        val nowTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nowTime = TextView(this).apply { textSize = 13f; setTextColor(Ui.text2); typeface = Typeface.DEFAULT_BOLD }
        nowTitle = TextView(this).apply { textSize = 19f; setTextColor(Ui.text); typeface = Typeface.DEFAULT_BOLD; maxLines = 2 }
        nowDesc = TextView(this).apply { textSize = 13f; setTextColor(Ui.withAlpha(Ui.text, 210)); maxLines = 3 }
        nowProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Ui.primary)
        }
        nowTexts.addView(nowTime)
        nowTexts.addView(nowTitle)
        nowTexts.addView(nowProgress, LinearLayout.LayoutParams(-1, Ui.dp(this, 4)).apply { topMargin = Ui.dp(this@GuideActivity, 6); bottomMargin = Ui.dp(this@GuideActivity, 6) })
        nowTexts.addView(nowDesc)
        nowCard.addView(nowTexts, LinearLayout.LayoutParams(0, -2, 1f))
        right.addView(nowCard)

        val listBox = FrameLayout(this)
        progList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@GuideActivity)
            adapter = progAdapter
            itemAnimator = null
            clipToPadding = false
            setPadding(0, Ui.dp(this@GuideActivity, 8), 0, Ui.dp(this@GuideActivity, 20))
        }
        listBox.addView(progList, FrameLayout.LayoutParams(-1, -1))
        loading = ProgressBar(this)
        listBox.addView(loading, FrameLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36), Gravity.CENTER))
        empty = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Ui.text2)
            visibility = View.GONE
            setPadding(Ui.dp(this@GuideActivity, 20), 0, Ui.dp(this@GuideActivity, 20), 0)
        }
        listBox.addView(empty, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        right.addView(listBox, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)

        // ao focar um canal (controle remoto) a grade dele aparece
        chList.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnFocusChangeListener { v, has ->
                    if (has) {
                        val pos = chList.getChildAdapterPosition(v)
                        if (pos != RecyclerView.NO_POSITION) select(pos, delayMs = 250)
                    }
                }
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })

        val start = startIndex.coerceIn(0, channels.size - 1)
        (chList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start, Ui.dp(this, 80))
        chList.post { chList.layoutManager?.findViewByPosition(start)?.requestFocus() }
        select(start, 0)

        // atualiza o "agora" a cada minuto
        launch {
            while (isActive) {
                delay(60_000)
                selected?.let { render(it, progAdapter.items) }
            }
        }
    }

    private fun select(pos: Int, delayMs: Long) {
        val m = channels.getOrNull(pos) ?: return
        if (selected?.key == m.key && progAdapter.itemCount > 0) return
        selected = m
        chAdapter.setPlaying(m.key)
        job?.cancel()
        job = launch {
            if (delayMs > 0) delay(delayMs)
            nowLogo.load(m.icon.ifEmpty { null }) { error(R.drawable.ic_tv) }
            nowTitle.text = m.name
            nowTime.text = "Carregando programação..."
            nowDesc.text = ""
            nowProgress.visibility = View.GONE
            loading.visibility = View.VISIBLE
            empty.visibility = View.GONE
            progAdapter.submit(emptyList())
            val list = runCatching { Repo.guide(m) }.getOrDefault(emptyList())
            loading.visibility = View.GONE
            if (selected?.key != m.key) return@launch
            render(m, list)
        }
    }

    private fun render(m: Media, list: List<EpgItem>) {
        val now = System.currentTimeMillis()
        val current = list.firstOrNull { it.start <= now && it.end > now }
        if (current != null) {
            nowTime.text = "AGORA  •  ${timeFmt.format(Date(current.start))} – ${timeFmt.format(Date(current.end))}  •  ${m.name}"
            nowTitle.text = current.title.ifBlank { m.name }
            nowDesc.text = current.description
            nowProgress.progress = current.progress(now)
            nowProgress.visibility = View.VISIBLE
        } else {
            nowTime.text = m.name
            nowTitle.text = if (list.isEmpty()) "Sem programação disponível" else "Fora do ar / sem informação agora"
            nowDesc.text = ""
            nowProgress.visibility = View.GONE
        }
        progAdapter.submit(list)
        empty.text = "Este canal não informa a programação."
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val idx = list.indexOf(current).takeIf { it >= 0 } ?: list.indexOfFirst { it.start > now }
        if (idx >= 0) (progList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, Ui.dp(this, 10))
    }

    private fun play(pos: Int) {
        PlayQueue.live(channels, pos)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun onProgram(item: EpgItem) {
        val m = selected ?: return
        val now = System.currentTimeMillis()
        when {
            item.start <= now && item.end > now -> play(channels.indexOfFirst { it.key == m.key }.coerceAtLeast(0))
            else -> {
                val day = Calendar.getInstance().apply { timeInMillis = item.start }
                val today = Calendar.getInstance()
                val whenTxt = (if (day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) "Hoje" else "Amanhã") +
                    ", ${timeFmt.format(Date(item.start))} – ${timeFmt.format(Date(item.end))}"
                val status = if (item.end <= now) "Já exibido" else "Começa ${timeFmt.format(Date(item.start))}"
                Dialogs.show(this, item.title.ifBlank { m.name }, "$whenTxt  •  $status\n\n${item.description.ifBlank { "Sem descrição." }}", listOf(
                    Dialogs.Btn("Fechar"),
                    Dialogs.Btn("Assistir canal", true) { play(channels.indexOfFirst { it.key == m.key }.coerceAtLeast(0)) },
                ))
            }
        }
    }

    /** Linhas da programação: horário, título, "AO VIVO" no programa atual. */
    private inner class ProgAdapter(private val onClick: (EpgItem) -> Unit) : RecyclerView.Adapter<ProgAdapter.VH>() {
        var items: List<EpgItem> = emptyList()
            private set

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<EpgItem>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(v: View, val time: TextView, val title: TextView, val badge: TextView, val desc: TextView) : RecyclerView.ViewHolder(v)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                isFocusable = true
                isClickable = true
                background = Ui.itemBg(ctx, Ui.withAlpha(Ui.primary, 90))
                val p = Ui.dp(ctx, 12)
                setPadding(p, Ui.dp(ctx, 10), p, Ui.dp(ctx, 10))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(ctx, 3) }
            }
            val time = TextView(ctx).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.textColors(Ui.text2, Ui.focusText))
                isDuplicateParentStateEnabled = true
            }
            row.addView(time, LinearLayout.LayoutParams(Ui.dp(ctx, 62), -2))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true }
            val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isDuplicateParentStateEnabled = true }
            val title = TextView(ctx).apply {
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Ui.textColors(Ui.text, Ui.focusText))
                isDuplicateParentStateEnabled = true
            }
            titleRow.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            val badge = TextView(ctx).apply {
                text = "AO VIVO"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = Ui.rounded(Ui.primary, Ui.dp(ctx, 4).toFloat())
                setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 2), Ui.dp(ctx, 6), Ui.dp(ctx, 2))
            }
            titleRow.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.dp(ctx, 8) })
            texts.addView(titleRow)
            val desc = TextView(ctx).apply {
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Ui.textColors(Ui.withAlpha(Ui.text2, 200), Ui.withAlpha(Ui.focusText, 200)))
                isDuplicateParentStateEnabled = true
            }
            texts.addView(desc)
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val h = VH(row, time, title, badge, desc)
            row.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p])
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = items[position]
            val now = System.currentTimeMillis()
            val live = e.start <= now && e.end > now
            h.time.text = timeFmt.format(Date(e.start))
            h.title.text = e.title.ifBlank { "Programa" }
            h.title.typeface = if (live) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            h.desc.text = e.description
            h.desc.visibility = if (e.description.isBlank()) View.GONE else View.VISIBLE
            h.badge.visibility = if (live) View.VISIBLE else View.GONE
            h.itemView.isSelected = live
            h.itemView.alpha = if (e.end <= now) 0.5f else 1f
        }

        override fun getItemCount() = items.size
    }
}
