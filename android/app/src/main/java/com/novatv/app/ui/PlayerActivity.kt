package com.novatv.app.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.novatv.app.R
import com.novatv.app.data.Kind
import com.novatv.app.data.Prefs
import com.novatv.app.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity() {

    override val keepScreenOn: Boolean = true

    private lateinit var playerView: PlayerView
    private lateinit var infoBar: View
    private lateinit var infoLogo: ImageView
    private lateinit var infoTitle: TextView
    private lateinit var infoSub: TextView
    private lateinit var infoProgress: ProgressBar
    private lateinit var infoClock: TextView
    private lateinit var overlay: View
    private lateinit var overlayList: RecyclerView
    private lateinit var errorMsg: TextView
    private lateinit var topRight: View
    private lateinit var nextBanner: TextView

    private var player: ExoPlayer? = null
    private var retries = 0
    private var infoJob: Job? = null
    private var saveJob: Job? = null
    private var nextJob: Job? = null
    private var restartFirst = false
    private var playingEntry: PlayQueue.Entry? = null
    private val live: Boolean get() = PlayQueue.current?.kind == Kind.LIVE
    private val modes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
    private val modeNames = arrayOf("Original", "Esticar", "Zoom")
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val overlayAdapter = ChannelAdapter(
        onClick = { pos, _ ->
            hideOverlay()
            if (pos != PlayQueue.index) {
                PlayQueue.index = pos
                play()
            }
        },
        onLong = { pos, m ->
            val added = Prefs.toggleFavorite(m)
            Dialogs.toast(this, if (added) "★ Favoritado" else "Removido dos favoritos")
            refreshOverlayFavs(pos)
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PlayQueue.current == null || Repo.provider == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player)
        infoBar = findViewById(R.id.infoBar)
        infoLogo = findViewById(R.id.infoLogo)
        infoTitle = findViewById(R.id.infoTitle)
        infoSub = findViewById(R.id.infoSub)
        infoProgress = findViewById(R.id.infoProgress)
        infoClock = findViewById(R.id.infoClock)
        overlay = findViewById(R.id.chOverlay)
        overlayList = findViewById(R.id.chOverlayList)
        errorMsg = findViewById(R.id.errorMsg)
        topRight = findViewById(R.id.topRight)
        nextBanner = findViewById(R.id.nextBanner)
        restartFirst = intent.getBooleanExtra("restart", false)

        infoProgress.progressTintList = ColorStateList.valueOf(Ui.primary)
        overlay.background = Ui.rounded(Ui.withAlpha(Ui.surfaceSolid, 225), 0f)
        findViewById<TextView>(R.id.chOverlayTitle).apply {
            background = Ui.rounded(Ui.primary, Ui.radius(this@PlayerActivity) * 0.8f)
            setTextColor(Color.WHITE)
        }
        nextBanner.background = Ui.button(this, true)
        nextBanner.setTextColor(Ui.textColors(Color.WHITE, Ui.focusText))
        nextBanner.setOnClickListener { playNext() }
        errorMsg.background = Ui.rounded(Ui.withAlpha(Color.BLACK, 210), Ui.radius(this))

        listOf(R.id.btnList, R.id.btnAspect).forEach { id ->
            findViewById<ImageView>(id).apply {
                background = Ui.iconButton(this@PlayerActivity)
                imageTintList = Ui.textColors(Color.WHITE, Ui.focusText)
            }
        }
        findViewById<View>(R.id.btnAspect).setOnClickListener { cycleAspect() }
        findViewById<View>(R.id.btnList).setOnClickListener { showOverlay() }

        overlayList.layoutManager = LinearLayoutManager(this)
        overlayList.adapter = overlayAdapter
        overlayList.itemAnimator = null

        playerView.resizeMode = modes[Prefs.resizeMode.coerceIn(0, modes.size - 1)]
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
            if (!live) topRight.visibility = vis
        })
        findViewById<View>(R.id.root).setOnClickListener {
            if (live) {
                if (overlay.visibility == View.VISIBLE) hideOverlay() else showOverlay()
            }
        }
        setupMode()
    }

    private fun setupMode() {
        playerView.useController = !live
        findViewById<View>(R.id.btnList).visibility = if (live) View.VISIBLE else View.GONE
        if (live) {
            overlayAdapter.submit(PlayQueue.entries.mapNotNull { it.media })
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null && PlayQueue.current != null) {
            val p = Players.create(this)
            p.addListener(listener)
            player = p
            playerView.player = p
            play()
        }
    }

    override fun onStop() {
        saveProgress()
        releasePlayer()
        super.onStop()
    }

    private fun releasePlayer() {
        saveJob?.cancel()
        nextJob?.cancel()
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        playerView.player = null
    }

    // ---------------------------------------------------------------- reprodução

    private fun play() {
        val e = PlayQueue.current ?: return
        val p = player ?: return
        saveProgress()
        playingEntry = e
        retries = 0
        errorMsg.visibility = View.GONE
        nextBanner.visibility = View.GONE
        nextJob?.cancel()
        val url = e.url()
        if (url.isEmpty()) {
            showError("Endereço do conteúdo indisponível.")
            return
        }
        val key = e.resumeKey
        val start = if (key == null || restartFirst) 0L else Prefs.resumePosition(key)
        restartFirst = false
        p.setMediaItem(Players.item(url), start)
        p.prepare()
        p.playWhenReady = true
        if (start > 0) Dialogs.toast(this, "Continuando de onde você parou")
        if (e.kind == Kind.LIVE) e.media?.let { Prefs.addRecent(it) }
        overlayAdapter.setPlaying(e.media?.key)
        showInfo()
        startSaving()
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (retries < 3) {
                retries++
                if (retries >= 2) Repo.provider?.failover()
                val pos = if (live) 0L else p.currentPosition
                launch {
                    delay(1000L * retries)
                    val cur = player ?: return@launch
                    val url = PlayQueue.current?.url().orEmpty()
                    if (url.isEmpty()) return@launch
                    cur.setMediaItem(Players.item(url), pos)
                    cur.prepare()
                }
                return
            }
            showError(if (live) "Canal indisponível no momento.\nPressione OK para tentar de novo." else "Não foi possível reproduzir este conteúdo.\nPressione OK para tentar de novo.")
        }

        override fun onRenderedFirstFrame() {
            errorMsg.visibility = View.GONE
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                errorMsg.visibility = View.GONE
                launch {
                    delay(15_000)
                    if (player?.isPlaying == true) retries = 0
                }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state != Player.STATE_ENDED) return
            if (live) {
                // transmissão fechou: reconecta
                play()
                return
            }
            PlayQueue.current?.resumeKey?.let { key ->
                val e = PlayQueue.current!!
                Prefs.saveResume(key, e.title, e.logo, Long.MAX_VALUE, Long.MAX_VALUE, e.series ?: e.media)
            }
            if (PlayQueue.index + 1 < PlayQueue.entries.size) countdownNext() else finish()
        }
    }

    private fun showError(msg: String) {
        errorMsg.text = msg
        errorMsg.visibility = View.VISIBLE
    }

    private fun retryNow() {
        errorMsg.visibility = View.GONE
        play()
    }

    private fun countdownNext() {
        val next = PlayQueue.entries.getOrNull(PlayQueue.index + 1) ?: return
        nextJob?.cancel()
        nextBanner.visibility = View.VISIBLE
        nextBanner.requestFocus()
        nextJob = launch {
            for (s in 8 downTo 1) {
                nextBanner.text = "Próximo: ${next.subtitle.ifEmpty { next.title }}  ($s)"
                delay(1000)
            }
            playNext()
        }
    }

    private fun playNext() {
        nextJob?.cancel()
        nextBanner.visibility = View.GONE
        if (PlayQueue.index + 1 < PlayQueue.entries.size) {
            PlayQueue.index++
            play()
        }
    }

    private fun zap(delta: Int) {
        val size = PlayQueue.entries.size
        if (size < 2) return
        PlayQueue.index = (PlayQueue.index + delta).mod(size)
        play()
    }

    private fun startSaving() {
        saveJob?.cancel()
        if (live) return
        saveJob = launch {
            while (isActive) {
                delay(10_000)
                saveProgress()
            }
        }
    }

    private fun saveProgress() {
        val p = player ?: return
        val e = playingEntry ?: return
        val key = e.resumeKey ?: return
        val dur = p.duration
        if (dur <= 0) return
        Prefs.saveResume(key, if (e.episode != null) "${e.title} • ${e.subtitle}" else e.title, e.logo, p.currentPosition, dur, e.series ?: e.media)
    }

    // ---------------------------------------------------------------- interface

    private fun showInfo() {
        val e = PlayQueue.current ?: return
        infoTitle.text = if (e.kind == Kind.LIVE && e.media != null && e.media.num > 0) "${e.media.num}  ${e.title}" else e.title
        infoSub.text = e.subtitle
        infoClock.text = timeFmt.format(Date())
        infoProgress.visibility = View.GONE
        infoLogo.load(e.logo.ifEmpty { null }) { error(R.drawable.ic_tv) }
        infoLogo.visibility = if (e.kind == Kind.LIVE) View.VISIBLE else View.GONE
        if (e.kind != Kind.LIVE && !live) return
        infoBar.visibility = View.VISIBLE
        infoBar.alpha = 1f
        infoJob?.cancel()
        infoJob = launch {
            val m = e.media
            if (m != null) {
                val epg = runCatching { Repo.epg(m) }.getOrDefault(emptyList())
                val now = epg.firstOrNull { it.start <= System.currentTimeMillis() }
                if (now != null && PlayQueue.current == e) {
                    val next = epg.getOrNull(epg.indexOf(now) + 1)
                    infoSub.text = buildString {
                        append(timeFmt.format(Date(now.start))).append(" – ").append(timeFmt.format(Date(now.end))).append("  ").append(now.title)
                        if (next != null) append("\nA seguir: ").append(timeFmt.format(Date(next.start))).append("  ").append(next.title)
                    }
                    infoProgress.progress = now.progress()
                    infoProgress.visibility = View.VISIBLE
                }
            }
            delay(5000)
            infoBar.animate().alpha(0f).setDuration(300).withEndAction { infoBar.visibility = View.GONE }.start()
        }
    }

    private fun showOverlay() {
        if (!live) return
        overlay.visibility = View.VISIBLE
        overlay.translationX = -overlay.width.toFloat().coerceAtLeast(200f)
        overlay.animate().translationX(0f).setDuration(200).start()
        val idx = PlayQueue.index
        (overlayList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, Ui.dp(this, 120))
        overlayList.post {
            overlayList.layoutManager?.findViewByPosition(idx)?.requestFocus() ?: overlayList.requestFocus()
        }
    }

    private fun hideOverlay() {
        overlay.animate().translationX(-overlay.width.toFloat()).setDuration(180).withEndAction {
            overlay.visibility = View.GONE
            overlay.translationX = 0f
        }.start()
        playerView.requestFocus()
    }

    private fun refreshOverlayFavs(pos: Int) {
        overlayAdapter.refreshFavs()
        overlayAdapter.notifyItemChanged(pos)
    }

    private fun cycleAspect() {
        val next = (Prefs.resizeMode + 1) % modes.size
        Prefs.resizeMode = next
        playerView.resizeMode = modes[next]
        Dialogs.toast(this, "Tela: ${modeNames[next]}")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO && !live) {
            cycleAspect()
            return true
        }
        if (errorMsg.visibility == View.VISIBLE && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            retryNow()
            return true
        }
        if (live && overlay.visibility != View.VISIBLE) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { showOverlay(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> { showInfo(); return true }
            }
        }
        if (live && overlay.visibility == View.VISIBLE && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            hideOverlay()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay.visibility == View.VISIBLE) {
            hideOverlay()
            return
        }
        if (!live && playerView.isControllerFullyVisible) {
            playerView.hideController()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
