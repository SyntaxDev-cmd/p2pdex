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
    @Config(qualifiers = "television-w960dp-h540dp-land")
    fun detalheDeFilmeNaTv() = openDetail(Media("10_3", "Terremoto (1974)", Kind.MOVIE, "", "10", ext = "mp4"))

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun detalheDeFilmeNoCelular() = openDetail(Media("10_4", "Lutador de Rua (2009)", Kind.MOVIE, "", "10", ext = "mp4"))

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
