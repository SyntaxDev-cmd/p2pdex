package com.novatv.app

import com.novatv.app.data.Kind
import com.novatv.app.data.UrlVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlVariantsTest {
    @Test fun aoVivoTsTentaHlsSemExtensaoEUrlAntiga() {
        val v = UrlVariants.of("http://s.com:80/live/u/p/123.ts", Kind.LIVE)
        assertEquals("http://s.com:80/live/u/p/123.m3u8", v[0])
        assertTrue("http://s.com:80/live/u/p/123" in v)
        assertTrue("http://s.com:80/u/p/123.ts" in v)
        assertTrue("http://s.com:80/live/u/p/123.ts" !in v)
    }

    @Test fun filmeSemExtensaoCertaTentaMkv() {
        val v = UrlVariants.of("http://s.com/movie/u/p/55.mp4", Kind.MOVIE)
        assertEquals("http://s.com/movie/u/p/55.mkv", v[0])
        assertTrue(v.none { it.endsWith("55.mp4") })
    }

    @Test fun linkComumNaoInventaEnderecos() {
        assertTrue(UrlVariants.of("https://cdn.com/video/filme-legal.mp4", Kind.MOVIE).isEmpty())
        assertEquals(listOf("https://cdn.com/canal/index.ts?t=1"), UrlVariants.of("https://cdn.com/canal/index.m3u8?t=1", Kind.LIVE))
    }

    @Test fun mantemParametros() {
        val v = UrlVariants.of("http://s.com/live/u/p/9.m3u8?token=x", Kind.LIVE)
        assertEquals("http://s.com/live/u/p/9.ts?token=x", v[0])
    }
}
