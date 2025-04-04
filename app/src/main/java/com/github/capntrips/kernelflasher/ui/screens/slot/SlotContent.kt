package com.github.capntrips.kernelflasher.ui.screens.slot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.ExperimentalUnitApi
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.capntrips.kernelflasher.R
import com.github.capntrips.kernelflasher.ui.components.MyOutlinedButton
import com.github.capntrips.kernelflasher.ui.components.SlotCard

@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@ExperimentalUnitApi
@Composable
fun ColumnScope.SlotContent(
  viewModel: SlotViewModel,
  slotSuffix: String,
  navController: NavController
) {
  val context = LocalContext.current
  SlotCard(
    title = stringResource(if (slotSuffix == "_a") R.string.slot_a else if (slotSuffix == "_b") R.string.slot_b else R.string.slot),
    viewModel = viewModel,
    navController = navController,
    isSlotScreen = true
  )
  AnimatedVisibility(!viewModel.isRefreshing) {
    Column {
      Spacer(Modifier.height(5.dp))
      if (viewModel.isActive) {
        MyOutlinedButton(
          {
            navController.navigate("slot$slotSuffix/flash")
          }
        ) {
          Text(stringResource(R.string.flash))
        }
      }
      MyOutlinedButton(
        {
          viewModel.clearFlash(context)
          navController.navigate("slot$slotSuffix/backup")
        }
      ) {
        Text(stringResource(R.string.backup))
      }
      if (viewModel.isActive) {
        MyOutlinedButton(
          {
            navController.navigate("slot$slotSuffix/backups")
          }
        ) {
          Text(stringResource(R.string.restore))
        }
      }
      MyOutlinedButton(
        { if (!viewModel.isRefreshing) viewModel.getKernel(context) }
      ) {
        Text(stringResource(R.string.check_kernel_version))
      }
      if (viewModel.hasVendorDlkm) {
        AnimatedVisibility(!viewModel.isRefreshing) {
          AnimatedVisibility(viewModel.isVendorDlkmMounted) {
            MyOutlinedButton(
              { viewModel.unmountVendorDlkm(context) }
            ) {
              Text(stringResource(R.string.unmount_vendor_dlkm))
            }
          }
          AnimatedVisibility(!viewModel.isVendorDlkmMounted && viewModel.isVendorDlkmMapped) {
            Column {
              MyOutlinedButton(
                { viewModel.mountVendorDlkm(context) }
              ) {
                Text(stringResource(R.string.mount_vendor_dlkm))
              }
              MyOutlinedButton(
                { viewModel.unmapVendorDlkm(context) }
              ) {
                Text(stringResource(R.string.unmap_vendor_dlkm))
              }
            }
          }
          AnimatedVisibility(!viewModel.isVendorDlkmMounted && !viewModel.isVendorDlkmMapped) {
            MyOutlinedButton(
              { viewModel.mapVendorDlkm(context) }
            ) {
              Text(stringResource(R.string.map_vendor_dlkm))
            }
          }
        }
      }
    }
  }
}
