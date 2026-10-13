// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.app

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference

/** Colour setting: a few swatches plus a hex field. Stored as an ARGB int. */
class ColorPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    private var color: Int = Color.WHITE

    init { isIconSpaceReserved = true }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any =
        try { Color.parseColor(a.getString(index)) } catch (e: Exception) { Color.WHITE }

    override fun onSetInitialValue(defaultValue: Any?) {
        color = getPersistedInt((defaultValue as? Int) ?: Color.WHITE)
        refresh()
    }

    private fun refresh() {
        summary = hex(color)
        val dp = context.resources.displayMetrics.density
        icon = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((1 * dp).toInt().coerceAtLeast(1), Color.GRAY)
            setSize((24 * dp).toInt(), (24 * dp).toInt())
        }
    }

    override fun onClick() {
        val dp = context.resources.displayMetrics.density
        val field = EditText(context).apply {
            setText(hex(color))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = context.getString(R.string.color_dialog_hint)
        }
        val swatches = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        PALETTE.forEach { c ->
            val v = View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(1, Color.GRAY) }
                layoutParams = LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt()).apply { setMargins(0, (8 * dp).toInt(), (6 * dp).toInt(), 0) }
                setOnClickListener { field.setText(hex(c)) }
            }
            swatches.addView(v)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
            addView(swatches)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val parsed = try { Color.parseColor(field.text.toString().trim()) } catch (e: IllegalArgumentException) { null }
                if (parsed == null) Toast.makeText(context, R.string.color_invalid, Toast.LENGTH_SHORT).show()
                else {
                    val opaque = parsed or (0xFF shl 24)
                    if (callChangeListener(opaque)) { color = opaque; persistInt(opaque); refresh() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    private companion object {
        val PALETTE = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF1B2733.toInt(), 0xFF2E7D32.toInt(), 0xFF1565C0.toInt(),
            0xFFC62828.toInt(), 0xFFF9A825.toInt(), 0xFF6A1B9A.toInt(),
        )
    }
}
