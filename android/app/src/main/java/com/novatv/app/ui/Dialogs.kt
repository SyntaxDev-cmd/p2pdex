package com.novatv.app.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.novatv.app.data.PanelConfig

/** Diálogos no estilo do app (cantos arredondados, foco grande para controle remoto). */
object Dialogs {

    class Btn(val label: String, val primary: Boolean = false, val action: (() -> Unit)? = null)

    fun show(
        act: Activity,
        title: String,
        message: String? = null,
        buttons: List<Btn> = listOf(Btn("OK", true)),
        content: View? = null,
        cancelable: Boolean = true,
        onShow: ((Dialog) -> Unit)? = null,
    ): Dialog? {
        if (act.isFinishing || act.isDestroyed) return null
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setCancelable(cancelable)
        val pad = Ui.dp(act, 24)
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, Ui.dp(act, 18))
            background = Ui.rounded(Ui.withAlpha(Ui.surfaceSolid.blendDark(), 245), Ui.dp(act, 20).toFloat(), Ui.withAlpha(Ui.primary, 160), Ui.dp(act, 1))
        }
        box.addView(View(act).apply {
            background = Ui.rounded(Ui.primary, Ui.dp(act, 3).toFloat())
        }, LinearLayout.LayoutParams(Ui.dp(act, 42), Ui.dp(act, 4)).apply { bottomMargin = Ui.dp(act, 14) })
        box.addView(TextView(act).apply {
            text = title
            setTextColor(Ui.text)
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
        })
        if (!message.isNullOrBlank()) {
            val sv = ScrollView(act)
            sv.addView(TextView(act).apply {
                text = message
                setTextColor(Ui.withAlpha(Ui.text, 215))
                textSize = 15f
                setLineSpacing(0f, 1.2f)
            })
            box.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(act, 10)
            })
            (sv.layoutParams as LinearLayout.LayoutParams).height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        if (content != null) {
            box.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(act, 14)
            })
        }
        if (buttons.isNotEmpty()) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            buttons.forEachIndexed { i, b ->
                row.addView(button(act, b.label, b.primary) {
                    d.dismiss()
                    b.action?.invoke()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) leftMargin = Ui.dp(act, 10)
                })
            }
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(act, 20)
            })
        }
        d.setContentView(box)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = minOf(act.resources.displayMetrics.widthPixels - Ui.dp(act, 32), Ui.dp(act, 460))
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.7f)
        }
        d.setOnShowListener {
            // foco no botão principal
            box.findViewWithTag<View>("primary")?.requestFocus()
            onShow?.invoke(d)
        }
        // tela fechando no meio da ação (TV Box lenta): ignora em vez de derrubar o app
        try {
            d.show()
        } catch (e: android.view.WindowManager.BadTokenException) {
            return null
        } catch (e: IllegalStateException) {
            return null
        }
        return d
    }

    private fun Int.blendDark(): Int {
        val r = (Color.red(this) * 0.35 + 18).toInt().coerceAtMost(255)
        val g = (Color.green(this) * 0.35 + 18).toInt().coerceAtMost(255)
        val b = (Color.blue(this) * 0.35 + 22).toInt().coerceAtMost(255)
        return Color.rgb(r, g, b)
    }

    fun button(act: Activity, label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(act).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = Ui.dp(act, 96)
            setPadding(Ui.dp(act, 20), Ui.dp(act, 11), Ui.dp(act, 20), Ui.dp(act, 11))
            background = Ui.button(act, primary)
            setTextColor(Ui.textColors(Color.WHITE, Ui.focusText))
            isFocusable = true
            isClickable = true
            if (primary) tag = "primary"
            setOnClickListener { onClick() }
            Ui.focusZoom(this, 1.05f)
        }

    fun toast(act: Activity, msg: String) {
        if (!act.isFinishing && !act.isDestroyed) Toast.makeText(act.applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    fun input(act: Activity, hint: String, numeric: Boolean = false, password: Boolean = false, max: Int = 64): EditText =
        EditText(act).apply {
            this.hint = hint
            setHintTextColor(Ui.withAlpha(Ui.text, 120))
            setTextColor(Ui.text)
            textSize = 18f
            isSingleLine = true
            background = Ui.inputBg(act)
            setPadding(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 16), Ui.dp(act, 12))
            filters = arrayOf(InputFilter.LengthFilter(max))
            inputType = when {
                numeric && password -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
        }

    /** Pede o PIN parental. */
    fun pin(act: Activity, title: String = "PIN de controle parental", expected: String? = null, message: String = "Conteúdo protegido.", onOk: () -> Unit) {
        val field = input(act, "Digite o PIN", numeric = true, password = true, max = 8).apply { gravity = Gravity.CENTER }
        var dialog: Dialog? = null
        val check = {
            if (field.text.toString() == (expected ?: PanelConfig.current.pin)) {
                dialog?.dismiss()
                onOk()
            } else {
                field.setText("")
                field.error = "PIN incorreto"
            }
        }
        field.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { check(); true } else false
        }
        dialog = show(act, title, message, content = field, buttons = listOf(
            Btn("Cancelar"),
        ), onShow = { field.requestFocus() })
        // botão confirmar não fecha antes de validar
        (field.parent as? LinearLayout)?.let { box ->
            val row = box.getChildAt(box.childCount - 1) as? LinearLayout
            row?.addView(button(act, "Confirmar", true) { check() }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.dp(act, 10) })
        }
    }

    /** Lista de opções. */
    fun choose(act: Activity, title: String, options: List<String>, selected: Int = -1, onPick: (Int) -> Unit) {
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        var dialog: Dialog? = null
        options.forEachIndexed { i, label ->
            list.addView(TextView(act).apply {
                text = if (i == selected) "●  $label" else label
                textSize = 17f
                setPadding(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 16), Ui.dp(act, 12))
                background = Ui.itemBg(act)
                setTextColor(Ui.textColors())
                isFocusable = true
                isClickable = true
                setOnClickListener { dialog?.dismiss(); onPick(i) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(act, 4)
            })
        }
        val sv = ScrollView(act).apply { addView(list) }
        sv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog = show(act, title, content = sv, buttons = listOf(Btn("Fechar")), onShow = {
            list.getChildAt(selected.coerceAtLeast(0))?.requestFocus()
        })
        // limita altura da lista
        sv.post {
            val max = (act.resources.displayMetrics.heightPixels * 0.55f).toInt()
            if (sv.height > max) sv.layoutParams = sv.layoutParams.apply { height = max }
        }
    }
}
