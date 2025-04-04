package com.github.capntrips.kernelflasher.ui.components

import android.net.Uri
import androidx.activity.compose.LocalActivity
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
  result.value?.let { uri ->
    if (mainActivity.isAwaitingResult) {
      callback.invoke(uri)
    }
    mainActivity.isAwaitingResult = false
  }
}
