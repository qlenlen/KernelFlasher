package com.github.capntrips.kernelflasher.ui.screens.reboot

import android.os.PowerManager
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.github.capntrips.kernelflasher.R
import com.github.capntrips.kernelflasher.ui.components.MyOutlinedButton

@Suppress("UnusedReceiverParameter")
@Composable
fun ColumnScope.RebootContent(
  viewModel: RebootViewModel
) {
  val context = LocalContext.current
  MyOutlinedButton(
    { viewModel.rebootSystem() }
  ) {
    Text(stringResource(R.string.reboot))
  }
  if (context.getSystemService(PowerManager::class.java)?.isRebootingUserspaceSupported == true) {
    MyOutlinedButton(
      { viewModel.rebootUserspace() }
    ) {
      Text(stringResource(R.string.reboot_userspace))
    }
  }
  MyOutlinedButton(
    { viewModel.rebootRecovery() }
  ) {
    Text(stringResource(R.string.reboot_recovery))
  }
  MyOutlinedButton(
    { viewModel.rebootBootloader() }
  ) {
    Text(stringResource(R.string.reboot_bootloader))
  }
  MyOutlinedButton(
    { viewModel.rebootDownload() }
  ) {
    Text(stringResource(R.string.reboot_download))
  }
  MyOutlinedButton(
    { viewModel.rebootEdl() }
  ) {
    Text(stringResource(R.string.reboot_edl))
  }
}