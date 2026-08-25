package com.tjcelaya.calwrite.ui.main

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.widget.ImageView
import com.tjcelaya.calwrite.R
import com.tjcelaya.calwrite.util.PhotoUtils

/**
 * Dialog for viewing full-size photos
 */
object PhotoViewerDialog {

    fun show(context: Context, photoPath: String) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_photo_viewer, null)
        val imageView = view.findViewById<ImageView>(R.id.fullSizePhoto)

        // Load full-size photo
        val photo = PhotoUtils.loadPhoto(photoPath)
        if (photo != null) {
            imageView.setImageBitmap(photo)
        } else {
            // Show error or placeholder
            imageView.setImageResource(android.R.drawable.ic_dialog_alert)
        }

        AlertDialog.Builder(context)
            .setTitle("Event Photo")
            .setView(view)
            .setPositiveButton("Close", null)
            .create()
            .show()
    }
}