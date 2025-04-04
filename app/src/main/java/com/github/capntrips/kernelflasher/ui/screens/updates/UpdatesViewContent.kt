package com.github.capntrips.kernelflasher.ui.screens.updates

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.capntrips.kernelflasher.R
import com.github.capntrips.kernelflasher.common.types.room.updates.DateSerializer
import com.github.capntrips.kernelflasher.ui.components.DataCard
import com.github.capntrips.kernelflasher.ui.components.DataRow
import com.github.capntrips.kernelflasher.ui.components.MyOutlinedButton
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalMaterial3Api
@ExperimentalSerializationApi
@Composable
fun ColumnScope.UpdatesViewContent(
  viewModel: UpdatesViewModel,
  navController: NavController
) {
  val context = LocalContext.current
  viewModel.currentUpdate?.let { currentUpdate ->
    DataCard(currentUpdate.kernelName) {
      val cardWidth = remember { mutableIntStateOf(0) }
      DataRow(
        stringResource(R.string.version),
        currentUpdate.kernelVersion,
        mutableMaxWidth = cardWidth
      )
      DataRow(
        stringResource(R.string.date_released),
        DateSerializer.formatter.format(currentUpdate.kernelDate),
        mutableMaxWidth = cardWidth
      )
      DataRow(
        label = stringResource(R.string.last_updated),
        value = UpdatesViewModel.lastUpdatedFormatter.format(currentUpdate.lastUpdated!!),
        labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.33f),
        labelStyle = MaterialTheme.typography.labelMedium.copy(
          fontStyle = FontStyle.Italic
        ),
        valueColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.33f),
        valueStyle = MaterialTheme.typography.titleSmall.copy(
          fontStyle = FontStyle.Italic,
        ),
        mutableMaxWidth = cardWidth
      )
    }
    AnimatedVisibility(!viewModel.isRefreshing) {
      Column {
        Spacer(Modifier.height(5.dp))
        MyOutlinedButton(
          { viewModel.downloadChangelog { navController.navigate("updates/view/${currentUpdate.id}/changelog") } }
        ) {
          Text(stringResource(R.string.changelog))
        }
        // TODO: add download progress indicator
        MyOutlinedButton(
          { viewModel.downloadKernel(context) }
        ) {
          Text(stringResource(R.string.download))
        }
        MyOutlinedButton(
          { viewModel.update() }
        ) {
          Text(stringResource(R.string.check_for_updates))
        }
        MyOutlinedButton(
          { viewModel.delete { navController.popBackStack() } }
        ) {
          Text(stringResource(R.string.delete))
        }
      }
    }
  }
}
