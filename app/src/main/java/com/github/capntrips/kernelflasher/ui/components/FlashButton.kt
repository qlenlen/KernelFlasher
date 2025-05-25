package com.github.capntrips.kernelflasher.ui.components

import android.net.Uri
import androidx.activity.compose.LocalActivity
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.ExperimentalUnitApi
import com.github.capntrips.kernelflasher.MainActivity
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalAnimationApi
@ExperimentalMaterialApi
@ExperimentalMaterial3Api
@ExperimentalSerializationApi
@ExperimentalUnitApi
@Composable
fun FlashButton(
    buttonText: String,
    validExtension: String,
    callback: (uri: Uri) -> Unit
) {
  val mainActivity = LocalActivity.current as MainActivity
  val result = remember { mutableStateOf<Uri?>(null) }
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
    result.value = it
    if (it == null) {
      mainActivity.isAwaitingResult = false
    }
  }
  MyOutlinedButton(
    {
      mainActivity.isAwaitingResult = true
      launcher.launch("*/*")
    }
  ) {
    Text(buttonText)
  }
    result.value?.let {uri ->
        if (mainActivity.isAwaitingResult) {
            val contentResolver = mainActivity.contentResolver
            val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
            }

            if (fileName != null && fileName.endsWith(validExtension, ignoreCase = true)) {
                callback.invoke(uri)
            }
            else {
                // Invalid file extension, show an error message or handle it
                Toast.makeText(mainActivity.applicationContext, "Invalid file selected!", Toast.LENGTH_LONG).show()
            }
        }
        mainActivity.isAwaitingResult = false
    }
    mainActivity.isAwaitingResult = false
  }
}
