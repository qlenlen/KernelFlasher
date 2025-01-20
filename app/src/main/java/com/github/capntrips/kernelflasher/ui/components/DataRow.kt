package com.github.capntrips.kernelflasher.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DataRow(
  label: String,
  value: String,
  labelColor: Color = Color.Unspecified,
  labelStyle: TextStyle = MaterialTheme.typography.labelMedium,
  valueColor: Color = Color.Unspecified,
  valueStyle: TextStyle = MaterialTheme.typography.titleSmall,
  mutableMaxWidth: MutableState<Int>? = null,
  clickable: Boolean = false,
) {
  Row {
    val labelModifier = if (mutableMaxWidth != null) {
      var maxWidth by mutableMaxWidth
      Modifier
        .padding(bottom = 6.dp)
        .layout { measurable, constraints ->
          val placeable = measurable.measure(constraints)
          maxWidth = maxOf(maxWidth, placeable.width)
          layout(width = maxWidth, height = placeable.height) {
            placeable.placeRelative(0, 0)
          }
        }
        .alignByBaseline()
    } else {
      Modifier.alignByBaseline()
    }
    Text(
      modifier = labelModifier.then(Modifier.padding(top = 2.dp)),
      text = label,
      color = labelColor,
      style = labelStyle,
      fontSize = 12.sp
    )
    Spacer(Modifier.width(8.dp))
    DataValue(
      value = value,
      color = valueColor,
      style = valueStyle,
      clickable = clickable,
    )
  }
}
