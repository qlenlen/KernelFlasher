package com.github.capntrips.kernelflasher.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun MyOutlinedButton(
  onclick: () -> Unit,
  enabled: Boolean = true,
  content: @Composable () -> Unit
) {
  OutlinedButton(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 6.dp, vertical = 1.dp),
    shape = RoundedCornerShape(10.dp),
    colors = ButtonDefaults.outlinedButtonColors(
      containerColor = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ),
    border = BorderStroke(
      width = 1.2.dp,
      color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
    ),
    enabled = enabled,
    onClick = onclick
  ) { content() }
}