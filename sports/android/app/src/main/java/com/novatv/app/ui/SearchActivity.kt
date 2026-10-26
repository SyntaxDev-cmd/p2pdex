package com.novatv.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novatv.app.R
import com.novatv.app.data.Kind
import com.novatv.app.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchActivity : BaseActivity() {

    override val immersiveMode: Boolean get() = Ui.isTv

    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var loading: ProgressBar
    private lateinit var results: RecyclerView
    private val adapter = PosterAdapter({ _, item -> open(item) })
    private var job: Job? = null
    private var kinds = listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES)
    private val filterViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Repo.provider == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_search)
        window.setBackgroundDrawable(Ui.backgroundGradient())

        val back = findViewById<ImageView>(R.id.back)
        back.background = Ui.iconButton(this)
        back.imageTintList = Ui.textColors(Ui.text, Ui.focusText)
        back.setOnClickListener { finish() }

        query = findViewById(R.id.query)
        status = findViewById(R.id.status)
        loading = findViewById(R.id.loading)
        results = findViewById(R.id.results)
        query.background = Ui.inputBg(this)
        query.setTextColor(Ui.text)
        query.setHintTextColor(Ui.withAlpha(Ui.text, 130))
        query.compoundDrawablesRelative[0]?.setTint(Ui.text2)
        status.setTextColor(Ui.text2)

        val span = (resources.displayMetrics.widthPixels / Ui.dp(this, 120)).coerceIn(2, 10)
        results.layoutManager = GridLayoutManager(this, span)
        results.adapter = adapter
        results.itemAnimator = null

        buildFilters()

        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = schedule(350)
        })
        query.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE) {
                schedule(0)
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(query.windowToken, 0)
                results.post { results.getChildAt(0)?.requestFocus() }
                true
            } else false
        }

        intent.getStringExtra("q")?.takeIf { it.isNotBlank() }?.let { query.setText(it) }
        query.requestFocus()

        // índice de busca em segundo plano enquanto o usuário digita
        status.text = "Preparando catálogo..."
        launch {
            Repo.warmSearch(kinds)
            if (query.text.isNullOrBlank()) status.text = "Digite pelo menos 2 letras"
        }
    }

    private fun buildFilters() {
        val box = findViewById<LinearLayout>(R.id.filters)
        val opts = listOf(
            "Tudo" to listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES),
            "Canais" to listOf(Kind.LIVE),
            "Filmes" to listOf(Kind.MOVIE),
            "Séries" to listOf(Kind.SERIES),
        )
        opts.forEachIndexed { i, (label, k) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(Ui.dp(this@SearchActivity, 16), Ui.dp(this@SearchActivity, 7), Ui.dp(this@SearchActivity, 16), Ui.dp(this@SearchActivity, 7))
                background = android.graphics.drawable.StateListDrawable().apply {
                    val r = Ui.dp(this@SearchActivity, 40).toFloat()
                    addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(Ui.accent, r))
                    addState(intArrayOf(android.R.attr.state_selected), Ui.rounded(Ui.primary, r))
                    addState(intArrayOf(), Ui.rounded(Ui.surface, r))
                }
                setTextColor(Ui.textColors())
                isFocusable = true
                isClickable = true
                isSelected = i == 0
                setOnClickListener {
                    filterViews.forEach { it.isSelected = false }
                    isSelected = true
                    kinds = k
                    schedule(0)
                }
            }
            filterViews.add(chip)
            box.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = Ui.dp(this@SearchActivity, 8)
            })
        }
    }

    private fun schedule(wait: Long) {
        job?.cancel()
        val q = query.text.toString()
        job = launch {
            if (wait > 0) delay(wait)
            if (q.trim().length < 2) {
                adapter.submit(emptyList())
                status.text = "Digite pelo menos 2 letras"
                return@launch
            }
            loading.visibility = View.VISIBLE
            val found = runCatching { Repo.search(q, kinds) }.getOrDefault(emptyList())
            loading.visibility = View.GONE
            adapter.submit(found.map { PosterAdapter.from(it, showKind = true) })
            status.text = if (found.isEmpty()) "Nada encontrado para \"$q\"" else "${found.size} resultado(s)"
        }
    }

    private fun open(item: PosterItem) {
        val m = item.media ?: return
        if (m.kind == Kind.LIVE) {
            val lives = adapter.items.mapNotNull { it.media }.filter { it.kind == Kind.LIVE }
            PlayQueue.live(lives, lives.indexOfFirst { it.key == m.key }.coerceAtLeast(0))
            startActivity(Intent(this, PlayerActivity::class.java))
        } else {
            DetailActivity.pending = m
            startActivity(Intent(this, DetailActivity::class.java))
        }
    }
}
