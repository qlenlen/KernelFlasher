package com.github.capntrips.kernelflasher.ui.screens.main

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.github.capntrips.kernelflasher.common.PartitionUtil
import com.github.capntrips.kernelflasher.common.types.backups.Backup
import com.github.capntrips.kernelflasher.ui.screens.backups.BackupsViewModel
import com.github.capntrips.kernelflasher.ui.screens.reboot.RebootViewModel
import com.github.capntrips.kernelflasher.ui.screens.slot.SlotViewModel
import com.github.capntrips.kernelflasher.ui.screens.updates.UpdatesViewModel
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val SUSBIN = "/data/adb/ksu/bin/ksu_susfs"

data class SusStatus(val version: String, val mode: String) {
  fun isSupported(): Boolean {
    return version.isNotEmpty() && mode.isNotEmpty()
  }
}

@ExperimentalSerializationApi
class MainViewModel(
  context: Context,
  fileSystemManager: FileSystemManager,
  private val navController: NavController
) : ViewModel() {
  companion object {
    const val TAG: String = "KernelFlasher/MainViewModel"
  }

  val slotSuffix: String

  val susfsVersion: SusStatus
  val kernelVersion: String
  val isAb: Boolean
  val slotA: SlotViewModel
  val slotB: SlotViewModel?
  val backups: BackupsViewModel
  val updates: UpdatesViewModel
  val reboot: RebootViewModel
  val hasRamoops: Boolean

  private val _isRefreshing: MutableState<Boolean> = mutableStateOf(true)
  private var _error: String? = null
  private var _backups: MutableMap<String, Backup> = mutableMapOf()

  val isRefreshing: Boolean
    get() = _isRefreshing.value
  val hasError: Boolean
    get() = _error != null
  val error: String
    get() = _error!!


  private val isSusSupport: Boolean
    get() = Shell.cmd("[ -f \"/data/adb/ksu/bin/ksu_susfs\" ] && echo 1 || echo 0")
      .exec().out[0] == "1"

  fun getSusStatus(): SusStatus {
    if (isSusSupport) {
      var ver = Shell.cmd("$SUSBIN show version").exec().out[0]
      if (ver.contains("not supported")) ver = ""
      var mode = Shell.cmd("$SUSBIN sus_su show_working_mode").exec().out[0]
      if (mode.contains("not supported")) mode = ""
      return SusStatus(ver, mode)
    } else {
      return SusStatus("", "")
    }
  }

  init {
    PartitionUtil.init(context, fileSystemManager)
    kernelVersion = Shell.cmd("echo $(uname -r) $(uname -v)").exec().out[0]
    susfsVersion = getSusStatus()
    slotSuffix = Shell.cmd("getprop ro.boot.slot_suffix").exec().out[0]
    backups = BackupsViewModel(fileSystemManager, navController, _isRefreshing, _backups)
    updates = UpdatesViewModel(context, fileSystemManager, navController, _isRefreshing)
    reboot = RebootViewModel(context, fileSystemManager, navController, _isRefreshing)
    // https://cs.android.com/android/platform/superproject/+/android-14.0.0_r18:bootable/recovery/recovery.cpp;l=320
    isAb = slotSuffix.isNotEmpty()
    if (isAb) {
      val bootA = PartitionUtil.findPartitionBlockDevice(context, "boot", "_a")!!
      val bootB = PartitionUtil.findPartitionBlockDevice(context, "boot", "_b")!!
      val initBootA = PartitionUtil.findPartitionBlockDevice(context, "init_boot", "_a")
      val initBootB = PartitionUtil.findPartitionBlockDevice(context, "init_boot", "_b")
      slotA = SlotViewModel(
        context,
        fileSystemManager,
        navController,
        _isRefreshing,
        slotSuffix == "_a",
        "_a",
        bootA,
        initBootA,
        _backups
      )
      if (slotA.hasError && slotSuffix == "_a") {
        _error = slotA.error
      }
      slotB = SlotViewModel(
        context,
        fileSystemManager,
        navController,
        _isRefreshing,
        slotSuffix == "_b",
        "_b",
        bootB,
        initBootB,
        _backups
      )
      if (slotB.hasError && slotSuffix == "_b") {
        _error = slotB.error
      }
    } else {
      val boot = PartitionUtil.findPartitionBlockDevice(context, "boot", "")!!
      val initBoot = PartitionUtil.findPartitionBlockDevice(context, "init_boot", "")
      slotA = SlotViewModel(
        context,
        fileSystemManager,
        navController,
        _isRefreshing,
        true,
        "",
        boot,
        initBoot,
        _backups
      )
      if (slotA.hasError) {
        _error = slotA.error
      }
      slotB = null
    }

    hasRamoops = fileSystemManager.getFile("/sys/fs/pstore/console-ramoops-0").exists()
    _isRefreshing.value = false
  }

  fun refresh(context: Context) {
    launch {
      slotA.refresh(context)
      if (isAb) {
        slotB!!.refresh(context)
      }
      backups.refresh()
    }
  }

  private fun launch(block: suspend () -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
      _isRefreshing.value = true
      try {
        withContext(Dispatchers.IO) {
          block()
        }
      } catch (e: Exception) {
        Log.e(TAG, e.message, e)
        navController.navigate("error/${e.message}") {
          popUpTo("main")
        }
      } finally {
        _isRefreshing.value = false
      }
    }
  }

  @Suppress("SameParameterValue")
  private fun log(context: Context, message: String, shouldThrow: Boolean = false) {
    Log.d(TAG, message)
    if (!shouldThrow) {
      viewModelScope.launch(Dispatchers.Main) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      }
    } else {
      throw Exception(message)
    }
  }

  fun saveRamoops(context: Context) {
    launch {
      val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm"))

      @SuppressLint("SdCardPath")
      val ramoops = File("/sdcard/Download/console-ramoops--$now.log")
      Shell.cmd("cp /sys/fs/pstore/console-ramoops-0 $ramoops").exec()
      if (ramoops.exists()) {
        log(context, "Saved ramoops to $ramoops")
      } else {
        log(context, "Failed to save $ramoops", shouldThrow = true)
      }
    }
  }

  fun saveDmesg(context: Context) {
    launch {
      val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm"))

      @SuppressLint("SdCardPath")
      val dmesg = File("/sdcard/Download/dmesg--$now.log")
      Shell.cmd("dmesg > $dmesg").exec()
      if (dmesg.exists()) {
        log(context, "Saved dmesg to $dmesg")
      } else {
        log(context, "Failed to save $dmesg", shouldThrow = true)
      }
    }
  }

  fun saveLogcat(context: Context) {
    launch {
      val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm"))

      @SuppressLint("SdCardPath")
      val logcat = File("/sdcard/Download/logcat--$now.log")
      Shell.cmd("logcat -d > $logcat").exec()
      if (logcat.exists()) {
        log(context, "Saved logcat to $logcat")
      } else {
        log(context, "Failed to save $logcat", shouldThrow = true)
      }
    }
  }
}
