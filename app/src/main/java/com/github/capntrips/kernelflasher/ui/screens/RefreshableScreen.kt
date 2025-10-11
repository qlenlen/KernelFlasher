package com.github.capntrips.kernelflasher.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.github.capntrips.kernelflasher.R
import com.github.capntrips.kernelflasher.ui.screens.main.MainViewModel
import kotlinx.serialization.ExperimentalSerializationApi

val colorList: List<Color> = listOf(
  Color(0xE5E57373),
  Color(0xE564B5F6),
  Color(0xE54DB6AC),
  Color(0xE581C784),
  Color(0xE5FFD54F),
  Color(0xE5FF8A65),
  Color(0xE5A1887F),
  Color(0xE590A4AE)
).shuffled()


@ExperimentalMaterial3Api
@ExperimentalSerializationApi
@Composable
fun RefreshableScreen(
  viewModel: MainViewModel,
  navController: NavController,
  swipeEnabled: Boolean = false,
  content: @Composable ColumnScope.() -> Unit
) {
  val statusBar = WindowInsets.statusBars.only(WindowInsetsSides.Top).asPaddingValues()
  val navigationBars = WindowInsets.navigationBars.asPaddingValues()
  val context = LocalContext.current

  Scaffold(
    topBar = {
      Box(
        Modifier
          .fillMaxWidth()
          .padding(statusBar)
      ) {
        if (navController.previousBackStackEntry != null) {
          AnimatedVisibility(
            !viewModel.isRefreshing,
            enter = fadeIn(),
            exit = fadeOut()
          ) {
            IconButton(
              onClick = { navController.popBackStack() },
              modifier = Modifier.padding(16.dp, 8.dp, 0.dp, 8.dp)
            ) {
              Icon(
                Icons.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface
              )
            }
          }
        }
        Box(
          Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp, horizontal = 2.dp)
        ) {
          if (isSystemInDarkTheme()) {
            Text(
              modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 8.dp),
              text = buildAnnotatedString {
                withStyle(
                  style = SpanStyle(brush = Brush.linearGradient(colors = colorList))
                ) {
                  append("Qkernel Flasher")
                }
              },
              style = MaterialTheme.typography.headlineMedium,
              fontWeight = FontWeight.Medium,
              fontSize = 23.5.sp
            )
          } else {
            Text(
              modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 8.dp),
              text = "Qkernel Flasher",
              style = MaterialTheme.typography.headlineMedium,
              fontWeight = FontWeight.Medium,
              color = MaterialTheme.colorScheme.onSurface,
              fontSize = 23.5.sp
            )
          }
        }
      }
    }
  ) { paddingValues ->
    PullToRefreshBox(
      isRefreshing = viewModel.isRefreshing,
      onRefresh = { viewModel.refresh(context) },
      modifier = Modifier
        .padding(paddingValues)
        .fillMaxSize()
    ) {
      Column(
        modifier = Modifier
          .padding(16.dp, 0.dp, 16.dp, 16.dp + navigationBars.calculateBottomPadding())
          .fillMaxSize()
          .verticalScroll(rememberScrollState()),
        content = content
      )
    }
  }
}
