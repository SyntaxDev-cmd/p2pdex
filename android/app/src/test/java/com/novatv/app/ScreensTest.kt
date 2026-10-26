package com.novatv.app

import android.os.Looper
import com.novatv.app.data.Account
import com.novatv.app.data.Category
import com.novatv.app.data.Details
import com.novatv.app.data.EpgItem
import com.novatv.app.data.Episode
import com.novatv.app.data.Kind
import com.novatv.app.data.Media
import com.novatv.app.data.Provider
import com.novatv.app.data.Repo
import com.novatv.app.data.ServerDef
import com.novatv.app.ui.DetailActivity
import com.novatv.app.ui.MainActivity
import com.novatv.app.ui.StreamService
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Provedor falso: catálogo pequeno, sem rede. */
class FakeProvider : Provider {
    override val server = ServerDef(1, "Teste", "xtream", listOf("http://x"), "", "", "", "ts", "", "", "")
    override val username = "u"
    override val activeBase = "http://x"
    private val cats = mapOf(
        Kind.MOVIE to listOf(Category("10", "Filmes | Globoplay", Kind.MOVIE), Category("11", "Lançamentos", Kind.MOVIE)),
        Kind.SERIES to listOf(Category("20", "Séries | Globoplay", Kind.SERIES)),
        Kind.LIVE to listOf(Category("30", "Esportes", Kind.LIVE), Category("31", "⚽ DISNEY+ • MAX", Kind.LIVE), Category("32", "Infantil", Kind.LIVE)),
    )
    override suspend fun login() = Account("Active", null, "1", "0", false)
    override suspend fun categories(kind: Kind) = cats[kind].orEmpty()
    override suspend fun items(kind: Kind, categoryId: String): List<Media> = (1..12).map {
        Media("$categoryId$it", "Título $it (2025)", kind, "", categoryId, ext = "mp4", rating = "7.5", year = "2025", added = it.toLong())
    }
    override suspend fun all(kind: Kind) = cats[kind].orEmpty().flatMap { items(kind, it.id) }
    override suspend fun details(m: Media) = Details(
        name = m.name, cover = "", backdrop = "", plot = "Sinopse de teste", genre = "Drama", cast = "Fulano", director = "Ciclano",
        rating = "7.5", year = "2025", duration = "1h 50min", ext = "mp4", tmdbId = "",
        seasons = if (m.kind == Kind.SERIES) sortedMapOf(1 to listOf(Episode("e1", "Ep 1", 1, 1, "mp4", "", "", ""))) else sortedMapOf(),
    )
    override suspend fun shortEpg(m: Media) = emptyList<EpgItem>()
    override suspend fun fullEpg(m: Media) = emptyList<EpgItem>()
    override fun streamUrl(m: Media) = "http://x/${m.id}"
    override fun episodeUrl(e: Episode) = "http://x/${e.id}"
    override fun failover() = false
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 25, 28, 30, 34], application = App::class, qualifiers = "w960dp-h540dp-land")
class ScreensTest {

    @Before
    fun setUp() {
        Repo.setProviderForTest(FakeProvider())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun openDetail(m: Media) {
        DetailActivity.pending = m
        val c = Robolectric.buildActivity(DetailActivity::class.java).setup()
        idle()
        assertFalse("A tela de detalhes fechou sozinha", c.get().isFinishing)
        c.pause().stop().destroy()
    }

    @Test
    fun detalheDeFilme() = openDetail(Media("10_1", "Invencível (2014)", Kind.MOVIE, "", "10", ext = "mp4"))

    @Test
    fun detalheDeSerie() = openDetail(Media("20_1", "Nesse Canto Eu Conto (2025)", Kind.SERIES, "", "20"))

    @Test
    fun telaDoStreamingEAbrirFilme() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val act = c.get()
        act.openStreaming(StreamService("Globoplay", "", 0xFFFF3D2E.toInt(), -1, listOf("globoplay")))
        idle()
        Thread.sleep(300)
        idle()
        // toca no primeiro filme da grade
        act.openDetail(Media("10_1", "Invencível (2014)", Kind.MOVIE, "", "10", ext = "mp4"))
        idle()
        c.pause().stop()
        idle()
        openDetail(Media("10_2", "Vítimas do Dia (2025)", Kind.MOVIE, "", "10", ext = "mp4"))
        c.destroy()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television")
    fun detalheDeFilmeNaTv() = openDetail(Media("10_3", "Terremoto (1974)", Kind.MOVIE, "", "10", ext = "mp4"))

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun detalheDeFilmeNoCelular() = openDetail(Media("10_4", "Lutador de Rua (2009)", Kind.MOVIE, "", "10", ext = "mp4"))

    /** Espera o carregamento em segundo plano (rede falsa) e processa a tela. */
    private fun settle() = repeat(40) { Thread.sleep(50); idle() }

    private fun findAll(v: android.view.View, out: MutableList<android.view.View> = mutableListOf()): MutableList<android.view.View> {
        out.add(v)
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findAll(v.getChildAt(i), out)
        return out
    }

    @Test
    fun streamingTocarFiltroFilmesEFilme() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        val act = c.get()
        act.openStreaming(StreamService("Globoplay", "", 0xFFFF3D2E.toInt(), -1, listOf("globoplay")))
        settle()
        val root = act.pagesContainer
        // filtro "Filmes (n)"
        val chip = findAll(root).filterIsInstance<android.widget.TextView>().firstOrNull { it.text.startsWith("Filmes") }
        org.junit.Assert.assertNotNull("filtro Filmes não apareceu", chip)
        chip!!.performClick()
        settle()
        // primeiro cartaz da grade
        val grid = findAll(root).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.adapter?.itemCount ?: 0 > 0 }
        grid.measure(android.view.View.MeasureSpec.makeMeasureSpec(1280, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY))
        grid.layout(0, 0, 1280, 600)
        idle()
        val first = grid.getChildAt(0)
        org.junit.Assert.assertNotNull("grade vazia", first)
        first.performClick()
        settle()
        val next = shadowOf(act).nextStartedActivity
        org.junit.Assert.assertNotNull("tocar no filme não abriu os detalhes", next)
        // abre a tela de detalhes iniciada
        val d = Robolectric.buildActivity(DetailActivity::class.java, next).setup()
        settle()
        assertFalse("A tela de detalhes fechou sozinha", d.get().isFinishing)
        d.pause().stop().destroy()
        c.pause().stop().destroy()
    }

    @Test
    fun playerCanalAoVivo() {
        com.novatv.app.ui.PlayQueue.live(listOf(Media("30_1", "Canal Teste HD", Kind.LIVE, "", "30"), Media("30_2", "Canal 2", Kind.LIVE, "", "30")), 0)
        val c = Robolectric.buildActivity(com.novatv.app.ui.PlayerActivity::class.java).setup()
        settle()
        assertFalse("o player fechou sozinho", c.get().isFinishing)
        // troca de canal pelo controle
        c.get().dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP))
        settle()
        c.get().dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        settle()
        assertFalse("o player fechou ao trocar de canal", c.get().isFinishing)
        c.pause().stop().destroy()
    }

    @Test
    fun playerFilme() {
        com.novatv.app.ui.PlayQueue.set(listOf(com.novatv.app.ui.PlayQueue.Entry("Filme", "2025", Kind.MOVIE, "",
            Media("10_1", "Filme", Kind.MOVIE, "", "10", ext = "mp4"))), 0)
        val c = Robolectric.buildActivity(com.novatv.app.ui.PlayerActivity::class.java).setup()
        settle()
        assertFalse("o player de filme fechou sozinho", c.get().isFinishing)
        c.pause().stop().destroy()
    }

    @Test
    fun abaTvTocarCanal() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        val act = c.get()
        act.switchTab("tv")
        settle()
        val lists = findAll(act.pagesContainer).filterIsInstance<androidx.recyclerview.widget.RecyclerView>()
        lists.forEach { rv ->
            rv.measure(android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY))
            rv.layout(0, 0, 600, 800)
        }
        idle()
        // categoria e depois o canal (1º toque = prévia, 2º = tela cheia)
        lists.forEach { rv -> rv.getChildAt(0)?.performClick(); settle() }
        lists.forEach { rv -> rv.getChildAt(0)?.performClick(); settle() }
        assertFalse(act.isFinishing)
        c.pause().stop().destroy()
    }

    @Test
    fun abaKidsMostraCanais() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        val act = c.get()
        act.switchTab("kids")
        settle()
        val names = findAll(act.pagesContainer).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        org.junit.Assert.assertTrue("Kids puxou categoria de esporte: $names", names.none { it.contains("DISNEY+ • MAX") })
        assertFalse(act.isFinishing)
        c.pause().stop().destroy()
    }

    @Test
    fun abasPrincipais() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val act = c.get()
        listOf("tv", "movies", "series", "kids", "anime", "favorites", "sports", "highlights").forEach {
            act.switchTab(it)
            idle()
        }
        c.pause().stop().destroy()
    }
}
