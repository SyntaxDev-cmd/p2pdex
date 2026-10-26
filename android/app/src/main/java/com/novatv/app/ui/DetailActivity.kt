package com.novatv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.novatv.app.R
import com.novatv.app.data.Category
import com.novatv.app.data.Details
import com.novatv.app.data.Episode
import com.novatv.app.data.Kind
import com.novatv.app.data.Media
import com.novatv.app.data.Prefs
import com.novatv.app.data.Repo
import kotlinx.coroutines.launch

class DetailActivity : BaseActivity() {

    companion object {
        /** Item a abrir (passado em memória para não estourar o Intent). */
        var pending: Media? = null
    }

    private lateinit var media: Media
    private var details: Details? = null
    private lateinit var buttons: LinearLayout
    private lateinit var seasonsList: RecyclerView
    private lateinit var episodesList: RecyclerView
    private val seasonAdapter = CategoryAdapter(true, { pos, c -> selectSeason(pos, c.id.toInt()) })
    private val episodeAdapter = EpisodeAdapter { pos, _ -> playEpisode(pos) }
    private var season = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val m = pending ?: savedInstanceState?.getString("media")?.let { Media.fromJson(org.json.JSONObject(it)) }
        if (m == null || Repo.provider == null) {
            finish()
            return
        }
        media = m
        setContentView(R.layout.activity_detail)
        window.setBackgroundDrawable(Ui.backgroundGradient())

        val back = findViewById<ImageView>(R.id.back)
        back.background = Ui.iconButton(this)
        back.imageTintList = Ui.textColors(Ui.text, Ui.focusText)
        back.setOnClickListener { finish() }
        Ui.focusZoom(back, 1.1f)

        val posterBox = findViewById<View>(R.id.posterBox)
        Ui.clipRounded(posterBox, Ui.radius(this))
        posterBox.background = Ui.rounded(Ui.surface, Ui.radius(this))
        findViewById<TextView>(R.id.title).apply { text = m.name; setTextColor(Ui.text) }
        findViewById<TextView>(R.id.meta).setTextColor(Ui.text2)
        findViewById<TextView>(R.id.plot).setTextColor(Ui.withAlpha(Ui.text, 230))
        findViewById<TextView>(R.id.cast).setTextColor(Ui.text2)
        findViewById<ImageView>(R.id.poster).load(m.icon.ifEmpty { null }) { error(R.drawable.ic_movie) }
        findViewById<ImageView>(R.id.backdrop).load(m.icon.ifEmpty { null })
        buttons = findViewById(R.id.buttons)
        seasonsList = findViewById(R.id.seasons)
        episodesList = findViewById(R.id.episodes)
        seasonsList.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        seasonsList.adapter = seasonAdapter
        seasonsList.itemAnimator = null
        episodesList.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        episodesList.adapter = episodeAdapter
        episodesList.itemAnimator = null
        episodeAdapter.progressOf = { e -> progressOf("EP:${e.id}") }

        renderButtons()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::media.isInitialized) outState.putString("media", media.toJson().toString())
    }

    override fun onResume() {
        super.onResume()
        if (::media.isInitialized && details != null) {
            renderButtons()
            episodeAdapter.notifyDataSetChanged()
        }
    }

    private fun progressOf(key: String): Int {
        val r = Prefs.resumes().firstOrNull { it.key == key } ?: return -1
        return if (r.duration > 0) (r.position * 100 / r.duration).toInt() else -1
    }

    private fun load() {
        val loading = findViewById<ProgressBar>(R.id.loading)
        launch {
            try {
                val d = Repo.details(media)
                details = d
                findViewById<TextView>(R.id.title).text = d.name
                val meta = listOf(
                    d.year,
                    d.duration,
                    if (d.rating.isNotEmpty()) "★ ${d.rating}" else "",
                    d.genre,
                    if (d.seasons.isNotEmpty()) "${d.seasons.size} temporada" + (if (d.seasons.size > 1) "s" else "") else "",
                ).filter { it.isNotBlank() }.joinToString("   •   ")
                findViewById<TextView>(R.id.meta).text = meta
                findViewById<TextView>(R.id.plot).text = d.plot.ifBlank { "Sem sinopse disponível." }
                val cast = listOf(
                    if (d.director.isNotBlank()) "Direção: ${d.director}" else "",
                    if (d.cast.isNotBlank()) "Elenco: ${d.cast}" else "",
                ).filter { it.isNotEmpty() }.joinToString("\n")
                findViewById<TextView>(R.id.cast).text = cast
                if (d.cover.isNotEmpty() && d.cover != media.icon) findViewById<ImageView>(R.id.poster).load(d.cover)
                findViewById<ImageView>(R.id.backdrop).load(d.backdrop.ifEmpty { d.cover }.ifEmpty { media.icon }.ifEmpty { null })
                if (media.kind == Kind.SERIES) showSeasons(d)
                renderButtons()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                findViewById<TextView>(R.id.plot).text = "Não foi possível carregar os detalhes."
            }
            loading.visibility = View.GONE
        }
    }

    private fun showSeasons(d: Details) {
        if (d.seasons.isEmpty()) return
        val cats = d.seasons.keys.map { Category(it.toString(), "Temporada $it", Kind.SERIES) }
        val resumeEp = lastEpisode()
        val startSeason = resumeEp?.season ?: d.seasons.keys.first()
        val idx = cats.indexOfFirst { it.id == startSeason.toString() }.coerceAtLeast(0)
        seasonAdapter.submit(cats, idx)
        seasonsList.visibility = View.VISIBLE
        episodesList.visibility = View.VISIBLE
        episodeAdapter.fallbackImage = d.backdrop.ifEmpty { d.cover }
        selectSeason(idx, cats[idx].id.toInt())
    }

    private fun selectSeason(pos: Int, s: Int) {
        val d = details ?: return
        season = s
        seasonAdapter.select(pos)
        episodeAdapter.submit(d.seasons[s].orEmpty())
        episodesList.scrollToPosition(0)
    }

    private fun allEpisodes(): List<Episode> = details?.seasons?.values?.flatten().orEmpty()

    private fun lastEpisode(): Episode? {
        val r = Prefs.resumes().firstOrNull { it.media?.key == media.key && it.key.startsWith("EP:") } ?: return null
        val id = r.key.removePrefix("EP:")
        return allEpisodes().firstOrNull { it.id == id }
    }

    private fun renderButtons() {
        buttons.removeAllViews()
        val isSeries = media.kind == Kind.SERIES
        val resumePos = if (isSeries) 0L else Prefs.resumePosition(media.key)
        val last = if (isSeries) lastEpisode() else null
        val playLabel = when {
            resumePos > 0 -> "▶  Continuar ${fmt(resumePos)}"
            last != null -> "▶  Continuar T${last.season} E${last.number}"
            else -> "▶  Assistir"
        }
        val play = Dialogs.button(this, playLabel, true) {
            if (isSeries) {
                val eps = allEpisodes()
                if (eps.isEmpty()) {
                    Dialogs.toast(this, "Nenhum episódio disponível")
                } else {
                    val target = last ?: eps.first()
                    playEpisodeById(target.id)
                }
            } else playMovie(restart = false)
        }
        addButton(play)
        if (resumePos > 0) addButton(Dialogs.button(this, "Do início", false) { playMovie(restart = true) })
        val fav = Prefs.isFavorite(media)
        addButton(Dialogs.button(this, if (fav) "♥  Na minha lista" else "♡  Minha lista", false) {
            val added = Prefs.toggleFavorite(media)
            Dialogs.toast(this, if (added) "Adicionado à Minha lista" else "Removido da Minha lista")
            renderButtons()
        })
        play.requestFocus()
    }

    private fun addButton(v: View) {
        buttons.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = Ui.dp(this@DetailActivity, 12)
        })
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun playMovie(restart: Boolean) {
        val d = details
        val m = if (d != null && d.ext.isNotEmpty() && media.ext.isEmpty()) media.copy(ext = d.ext) else media
        PlayQueue.set(listOf(PlayQueue.Entry(d?.name ?: m.name, d?.year.orEmpty(), Kind.MOVIE, d?.cover ?: m.icon, m)), 0)
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("restart", restart))
    }

    private fun playEpisode(pos: Int) {
        val e = episodeAdapter.items.getOrNull(pos) ?: return
        playEpisodeById(e.id)
    }

    private fun playEpisodeById(id: String) {
        val d = details ?: return
        val eps = allEpisodes()
        val entries = eps.map { e ->
            PlayQueue.Entry(d.name, "T${e.season} E${e.number} • ${e.title}", Kind.SERIES, e.image.ifEmpty { d.cover }, media, e, media)
        }
        PlayQueue.set(entries, eps.indexOfFirst { it.id == id }.coerceAtLeast(0))
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}
