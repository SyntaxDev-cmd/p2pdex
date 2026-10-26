package com.novatv.app.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.novatv.app.data.Episode
import com.novatv.app.data.Kind
import com.novatv.app.data.Media
import com.novatv.app.data.Net
import com.novatv.app.data.PanelConfig
import com.novatv.app.data.Repo

object Players {

    @OptIn(UnstableApi::class)
    fun create(ctx: Context, preview: Boolean = false): ExoPlayer {
        val ua = Repo.provider?.server?.userAgent.orEmpty().ifBlank { Net.DEFAULT_UA }
        val (minB, maxB, play, rebuf) = when (com.novatv.app.data.Prefs.bufferMode.ifEmpty { PanelConfig.current.buffer }) {
            "low" -> listOf(6_000, 25_000, 800, 1_500)
            "high" -> listOf(30_000, 90_000, 2_500, 5_000)
            else -> listOf(15_000, 50_000, 1_200, 2_500)
        }
        // a prévia usa buffer menor; o mínimo nunca pode passar do máximo (senão o ExoPlayer fecha o app)
        val min = if (preview) minOf(minB, 15_000) else minB
        val max = (if (preview) minOf(maxB, 20_000) else maxB).coerceAtLeast(min)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(min, max, minOf(play, min), minOf(rebuf, min))
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val http = OkHttpDataSource.Factory(Net.media).setUserAgent(ua)
        val data = DefaultDataSource.Factory(ctx, http)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
            .setConstantBitrateSeekingEnabled(true)
        val sources = DefaultMediaSourceFactory(data, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        return ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(sources)
            .setLoadControl(load)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                !preview,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    fun item(url: String): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        val l = url.lowercase().substringBefore('?')
        when {
            l.endsWith(".m3u8") || l.endsWith(".m3u") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            l.endsWith(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return b.build()
    }
}

/** Fila de reprodução compartilhada com o player em tela cheia (evita Intent gigante). */
object PlayQueue {
    data class Entry(
        val title: String,
        val subtitle: String,
        val kind: Kind,
        val logo: String,
        val media: Media?,
        val episode: Episode? = null,
        /** Série dona do episódio (para "continuar assistindo"). */
        val series: Media? = null,
    ) {
        val resumeKey: String? get() = when {
            episode != null -> "EP:${episode.id}"
            media != null && media.kind != Kind.LIVE -> media.key
            else -> null
        }

        fun url(): String {
            val p = Repo.provider ?: return ""
            return if (episode != null) p.episodeUrl(episode) else media?.let { p.streamUrl(it) }.orEmpty()
        }
    }

    var entries: List<Entry> = emptyList()
        private set
    var index: Int = 0

    fun set(list: List<Entry>, start: Int) {
        entries = list
        index = start.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    fun live(list: List<Media>, start: Int) = set(list.map { Entry(it.name, "", Kind.LIVE, it.icon, it) }, start)

    val current: Entry? get() = entries.getOrNull(index)
}
