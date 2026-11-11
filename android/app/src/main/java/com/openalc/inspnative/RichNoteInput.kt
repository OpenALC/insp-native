package com.openalc.inspnative

import android.content.Context
import android.widget.EditText

class RichNoteInput(context: Context) : EditText(context) {
    var selectionChanged: (() -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        selectionChanged?.invoke()
    }
}
