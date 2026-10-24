package io.github.monkut.wallscanner.routes

import android.app.Activity
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import io.github.monkut.wallscanner.R
import io.github.monkut.wallscanner.io.SendResult

/** The log-a-climb form (dialog_send): result, tries, note. Used by the route record and after filming a climb. */
object SendDialog {
    fun show(activity: Activity, title: Int, tries: Int?, onSave: (SendResult, Int, String) -> Unit, onCancel: () -> Unit = {}) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_send, null)
        val results = view.findViewById<RadioGroup>(R.id.result)
        val attempts = view.findViewById<EditText>(R.id.attempts)
        val note = view.findViewById<EditText>(R.id.note)
        if (tries != null) attempts.setText(tries.toString())
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val result = when (results.checkedRadioButtonId) {
                    R.id.resultFlash -> SendResult.FLASH
                    R.id.resultAttempt -> SendResult.ATTEMPT
                    else -> SendResult.SEND
                }
                onSave(result, attempts.text.toString().toIntOrNull() ?: 1, note.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
