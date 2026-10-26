package com.novatv.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import coil.load
import com.novatv.app.R
import com.novatv.app.data.PanelConfig
import com.novatv.app.data.Profile
import com.novatv.app.data.Profiles
import com.novatv.app.data.Repo

/** Avatares de perfil (degradê + emoji), reaproveitados no topo da tela principal. */
object Avatar {
    fun drawable(ctx: Context, p: Profile, radiusDp: Int = 22, focusRing: Boolean = false): Drawable {
        val (c1, c2) = Profiles.palette[p.color.mod(Profiles.palette.size)]
        val r = Ui.dp(ctx, radiusDp).toFloat()
        fun base(stroke: Boolean) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
            cornerRadius = r
            if (stroke) setStroke(Ui.dp(ctx, 3), Color.WHITE)
        }
        if (!focusRing) return base(false)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), base(true))
            addState(intArrayOf(android.R.attr.state_pressed), base(true))
            addState(intArrayOf(), base(false))
        }
    }
}

/** "Quem está assistindo?" — escolher, criar, editar e excluir perfis. */
class ProfilesActivity : BaseActivity() {

    companion object {
        /** Depois do login: escolhe perfil se houver mais de um, senão vai direto. */
        fun route(act: android.app.Activity) {
            Profiles.ensureDefault()
            Repo.onProfileChanged()
            val target = if (Profiles.all().size > 1) ProfilesActivity::class.java else MainActivity::class.java
            act.startActivity(Intent(act, target))
            act.finish()
        }
    }

    private lateinit var grid: LinearLayout
    private lateinit var manageBtn: TextView
    private var editMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Repo.provider == null) {
            startActivity(Intent(this, SplashActivity::class.java))
            finish()
            return
        }
        Profiles.ensureDefault()
        editMode = intent.getBooleanExtra("manage", false)

        val root = FrameLayout(this)
        val bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply { setBackgroundResource(R.drawable.scrim) }, FrameLayout.LayoutParams(-1, -1))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(this@ProfilesActivity, 20), Ui.dp(this@ProfilesActivity, 36), Ui.dp(this@ProfilesActivity, 20), Ui.dp(this@ProfilesActivity, 30))
            clipChildren = false
            clipToPadding = false
        }
        val logo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; maxWidth = Ui.dp(this@ProfilesActivity, 180) }
        val cfg = PanelConfig.current
        if (cfg.logo.isNotEmpty()) logo.load(cfg.logo) else logo.setImageResource(R.mipmap.ic_launcher)
        column.addView(logo, LinearLayout.LayoutParams(-2, Ui.dp(this, 44)).apply { bottomMargin = Ui.dp(this@ProfilesActivity, 22) })
        column.addView(TextView(this).apply {
            text = "Quem está assistindo?"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.text)
            gravity = Gravity.CENTER
        })
        column.addView(TextView(this).apply {
            id = View.generateViewId()
            tag = "subtitle"
            textSize = 15f
            setTextColor(Ui.text2)
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@ProfilesActivity, 6), 0, Ui.dp(this@ProfilesActivity, 26))
        })
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        column.addView(grid, LinearLayout.LayoutParams(-1, -2))
        manageBtn = Dialogs.button(this, "", false) { toggleManage() }
        column.addView(manageBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(this@ProfilesActivity, 28) })

        val scroll = SafeScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(FrameLayout(this@ProfilesActivity).apply {
                addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            }, ViewGroup.LayoutParams(-1, -1))
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        applyBackground(bg)
        render()
        Ui.fadeIn(column, 350)
    }

    private fun toggleManage() {
        editMode = !editMode
        render()
    }

    private fun render() {
        grid.removeAllViews()
        val profiles = Profiles.all()
        val cards = profiles.map { card(it) }.toMutableList<View>()
        if (profiles.size < Profiles.MAX) cards.add(addCard())

        val cardW = Ui.dp(this, if (Ui.isTv) 150 else 128)
        val available = resources.displayMetrics.widthPixels - Ui.dp(this, 40)
        val perRow = (available / cardW).coerceIn(2, 5)
        cards.chunked(perRow).forEach { rowCards ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
            }
            rowCards.forEach { row.addView(it, LinearLayout.LayoutParams(cardW, -2)) }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@ProfilesActivity, 18) })
        }
        manageBtn.text = if (editMode) "✓  Concluído" else "✎  Gerenciar perfis"
        findViewTagged<TextView>("subtitle")?.text =
            if (editMode) "Escolha um perfil para editar" else "Cada perfil tem seus favoritos e o seu continuar assistindo"
        // foco inicial no perfil atual
        val idx = profiles.indexOfFirst { it.id == Profiles.currentId }.coerceAtLeast(0)
        cards.getOrNull(idx)?.post { cards[idx].requestFocus() }
    }

    private fun <T : View> findViewTagged(tag: String): T? = window.decorView.findViewWithTag(tag)

    private fun card(p: Profile): View {
        val size = Ui.dp(this, if (Ui.isTv) 112 else 96)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            clipChildren = false
            setPadding(0, Ui.dp(this@ProfilesActivity, 6), 0, Ui.dp(this@ProfilesActivity, 6))
        }
        val avatar = FrameLayout(this).apply {
            background = Avatar.drawable(this@ProfilesActivity, p, focusRing = true)
            isDuplicateParentStateEnabled = true
        }
        avatar.addView(TextView(this).apply {
            text = p.emoji
            textSize = if (Ui.isTv) 50f else 44f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        if (p.kids) avatar.addView(badge("KIDS", Ui.kids), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(this@ProfilesActivity, 6)
        })
        if (p.pin.isNotEmpty()) avatar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(Ui.dp(this@ProfilesActivity, 5), Ui.dp(this@ProfilesActivity, 5), Ui.dp(this@ProfilesActivity, 5), Ui.dp(this@ProfilesActivity, 5))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.withAlpha(Color.BLACK, 140)) }
        }, FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26), Gravity.TOP or Gravity.END).apply {
            setMargins(0, Ui.dp(this@ProfilesActivity, 6), Ui.dp(this@ProfilesActivity, 6), 0)
        })
        if (editMode) avatar.addView(TextView(this).apply {
            text = "✎"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ui.rounded(Ui.withAlpha(Color.BLACK, 130), Ui.dp(this@ProfilesActivity, 22).toFloat())
        }, FrameLayout.LayoutParams(-1, -1))
        box.addView(avatar, LinearLayout.LayoutParams(size, size))
        box.addView(TextView(this).apply {
            text = p.name
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Ui.textColors(Ui.withAlpha(Ui.text, 200), Ui.text))
            isDuplicateParentStateEnabled = true
            setPadding(Ui.dp(this@ProfilesActivity, 4), Ui.dp(this@ProfilesActivity, 10), Ui.dp(this@ProfilesActivity, 4), 0)
        })
        Ui.focusZoom(box, 1.1f)
        box.setOnClickListener { if (editMode) edit(p) else select(p) }
        box.setOnLongClickListener { edit(p); true }
        return box
    }

    private fun badge(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.BLACK)
        setPadding(Ui.dp(this@ProfilesActivity, 7), Ui.dp(this@ProfilesActivity, 2), Ui.dp(this@ProfilesActivity, 7), Ui.dp(this@ProfilesActivity, 2))
        background = Ui.rounded(color, Ui.dp(this@ProfilesActivity, 20).toFloat())
    }

    private fun addCard(): View {
        val size = Ui.dp(this, if (Ui.isTv) 112 else 96)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            setPadding(0, Ui.dp(this@ProfilesActivity, 6), 0, Ui.dp(this@ProfilesActivity, 6))
        }
        val plus = TextView(this).apply {
            text = "+"
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(Ui.textColors(Ui.withAlpha(Ui.text, 190), Ui.focusText))
            isDuplicateParentStateEnabled = true
            val r = Ui.dp(this@ProfilesActivity, 22).toFloat()
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(Ui.accent, r))
                addState(intArrayOf(), GradientDrawable().apply {
                    cornerRadius = r
                    setColor(Ui.withAlpha(Color.WHITE, 18))
                    setStroke(Ui.dp(this@ProfilesActivity, 2), Ui.withAlpha(Color.WHITE, 110), Ui.dp(this@ProfilesActivity, 8).toFloat(), Ui.dp(this@ProfilesActivity, 6).toFloat())
                })
            }
        }
        box.addView(plus, LinearLayout.LayoutParams(size, size))
        box.addView(TextView(this).apply {
            text = "Novo perfil"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Ui.withAlpha(Ui.text, 200))
            setPadding(0, Ui.dp(this@ProfilesActivity, 10), 0, 0)
        })
        Ui.focusZoom(box, 1.1f)
        box.setOnClickListener { edit(null) }
        return box
    }

    // ------------------------------------------------------------------ escolher

    private fun select(p: Profile) {
        val current = Profiles.current
        val leavingKids = current != null && current.kids && !p.kids && current.id != p.id
        val enter = {
            if (p.pin.isNotEmpty() && p.id != current?.id) {
                Dialogs.pin(this, "Perfil ${p.name}", expected = p.pin, message = "Digite o PIN deste perfil.") { go(p) }
            } else go(p)
        }
        if (leavingKids) {
            Dialogs.pin(this, "Sair do perfil infantil", message = "Peça a um adulto para digitar o PIN.") { enter() }
        } else enter()
    }

    private fun go(p: Profile) {
        Profiles.currentId = p.id
        Repo.onProfileChanged()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    // ------------------------------------------------------------------ criar / editar

    private fun edit(existing: Profile?) {
        val isNew = existing == null
        var draft = existing ?: Profile(Profiles.newId(), "", Profiles.all().size % Profiles.palette.size, Profiles.emojis.random())
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val preview = FrameLayout(this)
        val previewEmoji = TextView(this).apply { textSize = 40f; gravity = Gravity.CENTER }
        preview.addView(previewEmoji, FrameLayout.LayoutParams(-1, -1))
        fun refreshPreview() {
            preview.background = Avatar.drawable(this, draft)
            previewEmoji.text = draft.emoji
        }
        refreshPreview()
        content.addView(preview, LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 84)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = Ui.dp(this@ProfilesActivity, 14)
        })

        val name = Dialogs.input(this, "Nome do perfil", max = 14).apply { setText(draft.name) }
        content.addView(name)

        content.addView(label("Avatar"))
        val emojiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val emojiViews = mutableListOf<TextView>()
        Profiles.emojis.forEach { e ->
            val tv = TextView(this).apply {
                text = e
                textSize = 24f
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                background = Ui.itemBg(this@ProfilesActivity, Ui.withAlpha(Color.WHITE, 60), Ui.accent, Ui.dp(this@ProfilesActivity, 12).toFloat())
                isSelected = e == draft.emoji
                setOnClickListener {
                    draft = draft.copy(emoji = e)
                    emojiViews.forEach { v -> v.isSelected = v.text == e }
                    refreshPreview()
                }
            }
            emojiViews.add(tv)
            emojiRow.addView(tv, LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)).apply { marginEnd = Ui.dp(this@ProfilesActivity, 4) })
        }
        content.addView(SafeHorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(emojiRow) })

        content.addView(label("Cor"))
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val colorViews = mutableListOf<View>()
        Profiles.palette.forEachIndexed { i, (c1, c2) ->
            val v = View(this).apply {
                isFocusable = true
                isClickable = true
                tag = i
                fun bg(sel: Boolean) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
                    shape = GradientDrawable.OVAL
                    if (sel) setStroke(Ui.dp(this@ProfilesActivity, 3), Color.WHITE)
                }
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), bg(true))
                    addState(intArrayOf(android.R.attr.state_selected), bg(true))
                    addState(intArrayOf(), bg(false))
                }
                isSelected = i == draft.color
                setOnClickListener {
                    draft = draft.copy(color = i)
                    colorViews.forEach { cv -> cv.isSelected = cv.tag == i }
                    refreshPreview()
                }
            }
            Ui.focusZoom(v, 1.15f)
            colorViews.add(v)
            colorRow.addView(v, LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)).apply { marginEnd = Ui.dp(this@ProfilesActivity, 10) })
        }
        content.addView(SafeHorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(colorRow) })

        val kidsToggle = toggleRow("Perfil infantil", "Mostra só conteúdo infantil", draft.kids) { on -> draft = draft.copy(kids = on) }
        content.addView(kidsToggle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@ProfilesActivity, 14) })

        val pinRow = toggleRow("Bloquear com PIN", "Pede um PIN para entrar neste perfil", draft.pin.isNotEmpty()) { on ->
            if (on) {
                val f = Dialogs.input(this, "PIN de 4 números", numeric = true, password = true, max = 4)
                Dialogs.show(this, "PIN do perfil", content = f, buttons = listOf(
                    Dialogs.Btn("Cancelar"),
                    Dialogs.Btn("Salvar", true) {
                        val pin = f.text.toString()
                        if (pin.length == 4) draft = draft.copy(pin = pin) else Dialogs.toast(this, "O PIN precisa ter 4 números")
                    },
                ), onShow = { f.requestFocus() })
            } else draft = draft.copy(pin = "")
        }
        content.addView(pinRow)

        val sv = SafeScrollView(this).apply { addView(content) }
        val buttons = mutableListOf<Dialogs.Btn>()
        if (!isNew && Profiles.all().size > 1) buttons.add(Dialogs.Btn("Excluir") { confirmDelete(existing!!) })
        buttons.add(Dialogs.Btn("Cancelar"))
        buttons.add(Dialogs.Btn("Salvar", true) {
            val n = name.text.toString().trim().ifEmpty { "Perfil ${Profiles.all().size + if (isNew) 1 else 0}" }
            Profiles.upsert(draft.copy(name = n))
            if (draft.id == Profiles.currentId) Repo.onProfileChanged()
            render()
        })
        Dialogs.show(this, if (isNew) "Novo perfil" else "Editar perfil", content = sv, buttons = buttons, onShow = { name.requestFocus() })
        sv.post {
            val max = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            if (sv.height > max) sv.layoutParams = sv.layoutParams.apply { height = max }
        }
    }

    private fun confirmDelete(p: Profile) {
        Dialogs.show(this, "Excluir ${p.name}?", "Favoritos e histórico deste perfil serão apagados.", listOf(
            Dialogs.Btn("Cancelar"),
            Dialogs.Btn("Excluir", true) {
                Profiles.delete(p.id)
                render()
            },
        ))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Ui.text2)
        setPadding(0, Ui.dp(this@ProfilesActivity, 14), 0, Ui.dp(this@ProfilesActivity, 6))
    }

    /** Linha com título, descrição e chave liga/desliga (navegável por controle remoto). */
    private fun toggleRow(title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        var on = initial
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = Ui.itemBg(this@ProfilesActivity, Ui.withAlpha(Color.WHITE, 20), Ui.withAlpha(Ui.accent, 60))
            setPadding(Ui.dp(this@ProfilesActivity, 12), Ui.dp(this@ProfilesActivity, 10), Ui.dp(this@ProfilesActivity, 12), Ui.dp(this@ProfilesActivity, 10))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(Ui.text) })
        texts.addView(TextView(this).apply { text = desc; textSize = 12f; setTextColor(Ui.text2) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switches.view(this, on)
        row.addView(sw, LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 26)))
        row.setOnClickListener {
            on = !on
            Switches.set(sw, on)
            onChange(on)
        }
        return row
    }
}

/** Chave liga/desliga desenhada (o Switch do sistema é ruim no controle remoto). */
object Switches {
    fun view(ctx: Context, on: Boolean): FrameLayout {
        val track = FrameLayout(ctx)
        val knob = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } }
        track.addView(knob, FrameLayout.LayoutParams(Ui.dp(ctx, 20), Ui.dp(ctx, 20), Gravity.CENTER_VERTICAL).apply {
            leftMargin = Ui.dp(ctx, 3)
        })
        set(track, on, animate = false)
        return track
    }

    fun set(track: FrameLayout, on: Boolean, animate: Boolean = true) {
        val ctx = track.context
        track.background = Ui.rounded(if (on) Ui.primary else Ui.withAlpha(Color.WHITE, 70), Ui.dp(ctx, 20).toFloat())
        val knob = track.getChildAt(0)
        val x = if (on) Ui.dp(ctx, 20).toFloat() else 0f
        if (animate) knob.animate().translationX(x).setDuration(150).start() else knob.translationX = x
    }
}
