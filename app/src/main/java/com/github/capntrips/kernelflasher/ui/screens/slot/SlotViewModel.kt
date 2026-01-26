package com.github.capntrips.kernelflasher.ui.screens.slot

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.github.capntrips.kernelflasher.common.PartitionUtil
import com.github.capntrips.kernelflasher.common.extensions.ByteArray.toHex
import com.github.capntrips.kernelflasher.common.extensions.ExtendedFile.inputStream
import com.github.capntrips.kernelflasher.common.extensions.ExtendedFile.outputStream
import com.github.capntrips.kernelflasher.common.types.backups.Backup
import com.github.capntrips.kernelflasher.common.types.partitions.Partitions
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

class SlotViewModel(
  context: Context,
  private val fileSystemManager: FileSystemManager,
  private val navController: NavController,
  private val _isRefreshing: MutableState<Boolean>,
  val isActive: Boolean,
  val slotSuffix: String,
  val boot: File,
  val initBoot: File?,
  private val _backups: MutableMap<String, Backup>
) : ViewModel() {
  companion object {
    const val TAG: String = "KernelFlasher/SlotState"
    const val HEADER_VER = "HEADER_VER"
    const val KERNEL_FMT = "KERNEL_FMT"
    const val RAMDISK_FMT = "RAMDISK_FMT"
  }

  data class BootInfo(
    var kernelVersion: String? = null,
    var bootFmt: String? = null,
    var headerVersion: String? = null,
    var initBootFmt: String? = null,
    var ramdiskLocation: String? = null
  )

  private var _sha1: String? = null
  private val _bootInfo: MutableState<BootInfo> = mutableStateOf(BootInfo())
  var hasVendorDlkm: Boolean = false
  var isVendorDlkmMapped: Boolean = false
  var isVendorDlkmMounted: Boolean = false
  private val _flashOutput: SnapshotStateList<String> = mutableStateListOf()
  private val _wasFlashSuccess: MutableState<Boolean?> = mutableStateOf(null)
  private val _backupPartitions: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
  private var wasSlotReset: Boolean = false
  private var flashUri: Uri? = null
  private var flashFilename: String? = null
  private val hashAlgorithm: String = "SHA-256"
  private var inInit = true
  private var _error: String? = null
  private val _showCautionDialog: MutableState<Boolean> = mutableStateOf(false)
  private var cachedKernelVersion: String? = null
  val magiskboot by lazy { File(context.filesDir, "magiskboot") }

  val sha1: String?
    get() = _sha1
  val flashOutput: List<String>
    get() = _flashOutput
  val uiPrintedOutput: List<String>
    get() = _flashOutput.filter { it.startsWith("ui_print") }
      .map { it.substringAfter("ui_print").trim() }.filter { it.isNotEmpty() || it == "" }
  val wasFlashSuccess: MutableState<Boolean?>
    get() = _wasFlashSuccess
  val backupPartitions: MutableMap<String, Boolean>
    get() = _backupPartitions
  val isRefreshing: MutableState<Boolean>
    get() = _isRefreshing
  val hasError: Boolean
    get() = _error != null
  val error: String?
    get() = _error
  val showCautionDialog: Boolean
    get() = _showCautionDialog.value
  val bootInfo: BootInfo
    get() = _bootInfo.value

  init {
    runBlocking {
      refresh(context)
    }
  }

  private fun extractKernelValues(input: String, key: String): String? {
    val regex = Regex("$key\\s*\\[([^]]+)]")
    return regex.find(input)?.groupValues?.get(1)
  }

  private fun resetInfo() {
    _error = null
    _sha1 = null
    _bootInfo.value = _bootInfo.value.copy(
      kernelVersion = null,
      bootFmt = null,
      headerVersion = null,
      initBootFmt = null,
      ramdiskLocation = null
    )
  }

  suspend fun refresh(context: Context) {
    resetInfo()

    if (!isActive) {
      inInit = true
    }

    withContext(Dispatchers.IO) {
      Shell.cmd("$magiskboot cleanup").exec()
    }

    val unpackBootOutput = mutableListOf<String>()
    withContext(Dispatchers.IO) {
      Shell.cmd("$magiskboot unpack $boot").to(unpackBootOutput, unpackBootOutput).exec()
    }
    val bootUnpackOp = unpackBootOutput.joinToString("\n")

    _bootInfo.value.headerVersion = extractKernelValues(bootUnpackOp.trimIndent(), HEADER_VER)
    _bootInfo.value.bootFmt = extractKernelValues(bootUnpackOp.trimIndent(), KERNEL_FMT)
    _bootInfo.value.initBootFmt = extractKernelValues(bootUnpackOp.trimIndent(), RAMDISK_FMT)
    if (_bootInfo.value.initBootFmt != null)
      _bootInfo.value.ramdiskLocation = "boot.img"

    Log.d(TAG, _bootInfo.value.toString())
    if (initBoot != null && _bootInfo.value.initBootFmt == null) {
      val unpackInitBootOutput = mutableListOf<String>()

      Shell.cmd("$magiskboot unpack $initBoot").to(unpackInitBootOutput, unpackInitBootOutput)
        .submit {
          if (it.isSuccess) {
            val initBootUnpackOp = unpackInitBootOutput.joinToString("\n")
            _bootInfo.value.initBootFmt =
              extractKernelValues(initBootUnpackOp.trimIndent(), RAMDISK_FMT)
            _bootInfo.value.ramdiskLocation = "init_boot.img"
          }
        }
    }

    val ramdisk = File(context.filesDir, "ramdisk.cpio")
    val kernel = File(context.filesDir, "kernel")

    var vendorDlkm = PartitionUtil.findPartitionBlockDevice(context, "vendor_dlkm", slotSuffix)
    hasVendorDlkm = vendorDlkm != null
    if (hasVendorDlkm) {
      isVendorDlkmMapped = vendorDlkm?.exists() == true
      if (isVendorDlkmMapped) {
        isVendorDlkmMounted = isPartitionMounted(vendorDlkm!!)
        if (!isVendorDlkmMounted) {
          vendorDlkm = fileSystemManager.getFile("/dev/block/mapper/vendor_dlkm-verity")
          isVendorDlkmMounted = isPartitionMounted(vendorDlkm)
        }
      } else {
        isVendorDlkmMounted = false
      }
    }

    if (ramdisk.exists()) {
      when (Shell.cmd("$magiskboot cpio ramdisk.cpio test").exec().code) {
        0 -> _sha1 = Shell.cmd("$magiskboot sha1 $boot").exec().out.firstOrNull()
        1 -> _sha1 = Shell.cmd("$magiskboot cpio ramdisk.cpio sha1").exec().out.firstOrNull()
        else -> _error = "Invalid ramdisk in boot.img"
      }
    } else if (kernel.exists()) {
      _sha1 = getBootSha1()
      if (_bootInfo.value.headerVersion.equals("4") && _bootInfo.value.ramdiskLocation.equals(null)) {
        _bootInfo.value.ramdiskLocation = "boot.img"
        _bootInfo.value.initBootFmt = "lz4_legacy"
      }
    } else {
      if (_bootInfo.value.headerVersion.equals("4") && _bootInfo.value.ramdiskLocation.equals(null)) {
        _bootInfo.value.ramdiskLocation = "boot.img"
        _bootInfo.value.initBootFmt = "lz4_legacy"
      }
      _error = "Unable to generate SHA1 hash. Invalid boot.img or magiskboot unpack failed!"
    }
    Shell.cmd("$magiskboot cleanup").submit()

    PartitionUtil.AvailablePartitions.forEach { partitionName ->
      _backupPartitions[partitionName] = true
    }

    _bootInfo.value.kernelVersion = null
    inInit = false
  }

  private suspend fun getBootSha1(): String? {
    return withContext(Dispatchers.IO) {
      Shell.cmd("$magiskboot sha1 $boot").exec().out.firstOrNull()
    }
  }

  // TODO: use base class for common functions
  private fun launch(block: suspend () -> Unit) {
    viewModelScope.launch {
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
      }
      _isRefreshing.value = false
    }
  }

  private fun showCautionDialog() {
    _showCautionDialog.value = true
  }

  fun hideCautionDialog() {
    _showCautionDialog.value = false
  }

  // TODO: use base class for common functions
  @Suppress("SameParameterValue")
  private fun log(context: Context, message: String, shouldThrow: Boolean = false) {
    Log.d(TAG, message)
    if (!shouldThrow) {
      viewModelScope.launch(Dispatchers.Main) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      }
    } else {
      if (inInit) {
        _error = message
      } else {
        throw Exception(message)
      }
    }
  }

  @Suppress("SameParameterValue")
  private fun uiPrint(message: String) {
    viewModelScope.launch(Dispatchers.Main) {
      _flashOutput.add("ui_print $message")
    }
  }

  // TODO: use base class for common functions
  private fun addMessage(message: String) {
    viewModelScope.launch(Dispatchers.Main) {
      _flashOutput.add(message)
    }
  }

  private fun clearTmp(context: Context) {
    if (flashFilename != null) {
      val zip = File(context.filesDir, flashFilename!!)
      if (zip.exists()) {
        zip.delete()
      }
    }
  }

  @Suppress("FunctionName")
  private fun _clearFlash() {
    _flashOutput.clear()
    _wasFlashSuccess.value = null
  }

  fun clearFlash(context: Context) {
    _clearFlash()
    PartitionUtil.AvailablePartitions.forEach { partitionName ->
      _backupPartitions[partitionName] = true
    }
    launch {
      clearTmp(context)
    }
  }

  // TODO: use base class for common functions
  @SuppressLint("SdCardPath")
  fun saveLog(context: Context) {
    launch {
      val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm"))
      val logName = if (navController.currentDestination!!.route!!.contains("ak3")) {
        "ak3"
      } else if (navController.currentDestination!!.route!!.endsWith("/backup")) {
        "backup"
      } else {
        "flash"
      }
      val log = File("/sdcard/Download/$logName-log--$now.log")
      if (navController.currentDestination!!.route!!.contains("ak3")) {
        log.writeText(flashOutput.filter { !it.matches("""progress [\d.]* [\d.]*""".toRegex()) }
          .joinToString("\n").replace("""ui_print (.*)\n {6}ui_print""".toRegex(), "$1"))
      } else {
        log.writeText(flashOutput.joinToString("\n"))
      }
      if (log.exists()) {
        log(context, "Saved $logName log to $log")
      } else {
        log(context, "Failed to save $log", shouldThrow = true)
      }
    }
  }

  @Suppress("FunctionName", "SameParameterValue")
  private fun _getKernel(context: Context) {
    val magiskboot = File(context.filesDir, "magiskboot")
    Shell.cmd("$magiskboot unpack $boot").exec()
    val kernel = File(context.filesDir, "kernel")
    if (kernel.exists()) {
      val result =
        Shell.cmd("strings kernel | grep -E -m1 'Linux version.*#' | cut -d\\  -f3-").exec().out
      if (result.isNotEmpty()) {
        _bootInfo.value.kernelVersion =
          result[0].replace("""\(.+\)""".toRegex(), "").replace("""\s+""".toRegex(), " ")
      }
    }
    Shell.cmd("$magiskboot cleanup").exec()
  }

  fun getKernel(context: Context) {
    if (cachedKernelVersion != null) return

    launch {
      _getKernel(context)
      cachedKernelVersion = _bootInfo.value.kernelVersion
    }
  }

  private fun isPartitionMounted(partition: File): Boolean {
    @Suppress("LiftReturnOrAssignment")
    if (partition.exists()) {
      val dmPath = Shell.cmd("readlink -f $partition").exec().out[0]
      val mounts = Shell.cmd("mount | grep -w $dmPath").exec().out
      return mounts.isNotEmpty()
    } else {
      return false
    }
  }

  fun unmountVendorDlkm(context: Context) {
    launch {
      val httools = File(context.filesDir, "httools_static")
      Shell.cmd("$httools umount vendor_dlkm").exec()
      refresh(context)
    }
  }

  fun mountVendorDlkm(context: Context) {
    launch {
      val httools = File(context.filesDir, "httools_static")
      Shell.cmd("$httools mount vendor_dlkm").exec()
      refresh(context)
    }
  }

  fun unmapVendorDlkm(context: Context) {
    launch {
      val lptools = File(context.filesDir, "lptools_static")
      val mapperDir = "/dev/block/mapper"
      val vendorDlkm = fileSystemManager.getFile(mapperDir, "vendor_dlkm$slotSuffix")
      if (vendorDlkm.exists()) {
        val vendorDlkmVerity = fileSystemManager.getFile(mapperDir, "vendor_dlkm-verity")
        if (vendorDlkmVerity.exists()) {
          Shell.cmd("$lptools unmap vendor_dlkm-verity").exec()
        } else {
          Shell.cmd("$lptools unmap vendor_dlkm$slotSuffix").exec()
        }
      }
      refresh(context)
    }
  }

  fun mapVendorDlkm(context: Context) {
    launch {
      val lptools = File(context.filesDir, "lptools_static")
      Shell.cmd("$lptools map vendor_dlkm$slotSuffix").exec()
      refresh(context)
    }
  }

  private fun backupPartition(partition: ExtendedFile, destination: ExtendedFile): String? {
    if (partition.exists()) {
      val messageDigest = MessageDigest.getInstance(hashAlgorithm)
      partition.inputStream().use { inputStream ->
        destination.outputStream().use { outputStream ->
          DigestOutputStream(outputStream, messageDigest).use { digestOutputStream ->
            inputStream.copyTo(digestOutputStream)
          }
        }
      }
      return messageDigest.digest().toHex()
    }
    return null
  }

  private fun backupPartitions(context: Context, destination: ExtendedFile): Partitions? {
    val partitions = HashMap<String, String>()
    for (partitionName in PartitionUtil.PartitionNames) {
      if (_backupPartitions[partitionName] == true) {
        val blockDevice = PartitionUtil.findPartitionBlockDevice(context, partitionName, slotSuffix)
        if (blockDevice != null) {
          addMessage("Saving $partitionName")
          val hash = backupPartition(blockDevice, destination.getChildFile("$partitionName.img"))
          if (hash != null) {
            partitions[partitionName] = hash
          }
        }
      }
    }
    if (partitions.isNotEmpty()) {
      return Partitions.from(partitions)
    }
    return null
  }

  private fun createBackupDir(context: Context, now: String): ExtendedFile {
    @SuppressLint("SdCardPath")
    val externalDir = fileSystemManager.getFile("/sdcard/KernelFlasher")
    if (!externalDir.exists()) {
      if (!externalDir.mkdir()) {
        log(context, "Failed to create KernelFlasher dir on /sdcard", shouldThrow = true)
      }
    }
    val backupsDir = externalDir.getChildFile("backups")
    if (!backupsDir.exists()) {
      if (!backupsDir.mkdir()) {
        log(context, "Failed to create backups dir", shouldThrow = true)
      }
    }
    val backupDir = backupsDir.getChildFile(now)
    if (backupDir.exists()) {
      log(context, "Backup $now already exists", shouldThrow = true)
    } else {
      if (!backupDir.mkdir()) {
        log(context, "Failed to create backup dir", shouldThrow = true)
      }
    }
    return backupDir
  }

  fun backup(context: Context) {
    launch {
      _clearFlash()
      val currentKernelVersion = if (_bootInfo.value.kernelVersion != null) {
        _bootInfo.value.kernelVersion
      } else if (isActive) {
        System.getProperty("os.version")!!
      } else {
        _getKernel(context)
        _bootInfo.value.kernelVersion
      }
      val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm"))
      val backupDir = createBackupDir(context, now)
      addMessage("Saving backup $now")
      val hashes = backupPartitions(context, backupDir)
      if (hashes == null) {
        log(context, "No partitions saved", shouldThrow = true)
      }
      val jsonFile = backupDir.getChildFile("backup.json")
      val backup = Backup(now, "raw", currentKernelVersion!!, sha1, null, hashes, hashAlgorithm)
      val indentedJson = Json { prettyPrint = true }
      jsonFile.outputStream()
        .use { it.write(indentedJson.encodeToString(backup).toByteArray(Charsets.UTF_8)) }
      _backups[now] = backup
      addMessage("Backup $now saved")
      _wasFlashSuccess.value = true
    }
  }

  fun backupZip(context: Context, callback: () -> Unit) {
    launch {
      val source = context.contentResolver.openInputStream(flashUri!!)
      if (source != null) {
        _getKernel(context)
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd--HH-mm"))
        val backupDir = createBackupDir(context, now)
        val jsonFile = backupDir.getChildFile("backup.json")
        val backup = Backup(now, "ak3", _bootInfo.value.kernelVersion!!, null, flashFilename)
        val indentedJson = Json { prettyPrint = true }
        jsonFile.outputStream()
          .use { it.write(indentedJson.encodeToString(backup).toByteArray(Charsets.UTF_8)) }
        val destination = backupDir.getChildFile(flashFilename!!)
        source.use { inputStream ->
          destination.outputStream().use { outputStream ->
            inputStream.copyTo(outputStream)
          }
        }
        _backups[now] = backup
        withContext(Dispatchers.Main) {
          callback.invoke()
        }
      } else {
        log(context, "AK3 zip is missing", shouldThrow = true)
      }
    }
  }

  private fun resetSlot() {
    val activeSlotSuffix = Shell.cmd("getprop ro.boot.slot_suffix").exec().out[0]
    val newSlot = if (activeSlotSuffix == "_a") "_b" else "_a"
    Shell.cmd("resetprop -n ro.boot.slot_suffix $newSlot").exec()
    wasSlotReset = !wasSlotReset
  }

  @Suppress("FunctionName")
  private suspend fun _checkZip(context: Context, zip: File, callback: (() -> Unit)? = null) {
    if (zip.exists()) {
      try {
        @Suppress("BlockingMethodInNonBlockingContext")
        val zipFile = ZipFile(zip)
        zipFile.use { z ->
          if (z.getEntry("anykernel.sh") == null) {
            log(context, "Invalid AK3 zip", shouldThrow = true)
          }
          withContext(Dispatchers.Main) {
            callback?.invoke()
          }
        }
      } catch (e: Exception) {
        zip.delete()
        throw e
      }
    } else {
      log(context, "Failed to save zip", shouldThrow = true)
    }
  }

  @Suppress("FunctionName")
  private fun _copyFile(context: Context, currentBackup: String, filename: String) {
    flashUri = null
    flashFilename = filename
    @SuppressLint("SdCardPath")
    val externalDir = File("/sdcard/KernelFlasher")
    val backupsDir = fileSystemManager.getFile("$externalDir/backups")
    val backupDir = backupsDir.getChildFile(currentBackup)
    if (!backupDir.exists()) {
      log(context, "Backup $currentBackup does not exists", shouldThrow = true)
    }
    val source = backupDir.getChildFile(flashFilename!!)
    val zip = File(context.filesDir, flashFilename!!)
    source.newInputStream().use { inputStream ->
      zip.outputStream().use { outputStream ->
        inputStream.copyTo(outputStream)
      }
    }
  }

  @Suppress("FunctionName")
  private fun _copyFile(context: Context, uri: Uri) {
    flashUri = uri
    flashFilename = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (!cursor.moveToFirst()) return@use null
      val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      return@use cursor.getString(name)
    } ?: "ak3.zip"
    val source = context.contentResolver.openInputStream(uri)
    val file = File(context.filesDir, flashFilename!!)
    source.use { inputStream ->
      file.outputStream().use { outputStream ->
        inputStream?.copyTo(outputStream)
      }
    }
  }

  @Suppress("FunctionName")
  private fun _copyDriver(context: Context, uri: Uri) {
    flashUri = uri
    flashFilename = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      if (!cursor.moveToFirst()) return@use null
      val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      return@use cursor.getString(name)
    } ?: "kernelsu.ko"
    val source = context.contentResolver.openInputStream(uri)
    val file = File(context.filesDir, "kernelsu.ko")
    source.use { inputStream ->
      file.outputStream().use { outputStream ->
        inputStream?.copyTo(outputStream)
      }
    }
    Shell.cmd("chmod +rwx $file").exec()
  }

  @Suppress("FunctionName")
  private suspend fun _flashAk3(context: Context, type: String) {
    if (!isActive) {
      resetSlot()
    }
    val zip = File(context.filesDir.canonicalPath, flashFilename!!)
    _checkZip(context, zip)
    try {
      if (zip.exists()) {
        _wasFlashSuccess.value = false
        val files = File(context.filesDir.canonicalPath)
        val flashScript = File(files, "flash_ak3$type.sh")
        val result = Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build().newJob()
          .add("F=$files Z=\"$zip\" /system/bin/sh $flashScript").to(flashOutput, flashOutput)
          .exec()
        if (result.isSuccess) {
          log(context, "Kernel flashed successfully")
          _wasFlashSuccess.value = true
        } else {
          log(context, "Failed to flash zip", shouldThrow = false)
//                    Log.e(TAG, "Error: ${result.stderr.joinToString("\n")}")
        }
        clearTmp(context)
      } else {
        log(context, "AK3 zip is missing", shouldThrow = true)
      }
    } catch (e: Exception) {
      clearFlash(context)
      throw e
    } finally {
      uiPrint("")
      if (wasSlotReset) {
        resetSlot()
        viewModelScope.launch(Dispatchers.Main) {
          showCautionDialog() // Show dialog instead of uiPrint
        }
      }
    }
  }

  fun switchSlot(context: Context) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        // Get current slot
        val currentSlot = Shell.cmd("getprop ro.boot.slot_suffix").exec().out.firstOrNull() ?: "_a"
        val targetSlot = if (currentSlot == "_a") "b" else "a"

        // Execute bootctl command
        val result = Shell.cmd("bootctl set-active-boot-slot $targetSlot").exec()

        if (result.isSuccess) {
          log(context, "Slot was successfully switched to $targetSlot", shouldThrow = false)
        } else {
          log(context, "Failed to switch slot", shouldThrow = false)
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        throw e
      }
    }
  }

  fun flashAk3(context: Context, currentBackup: String, filename: String) {
    launch {
      _clearFlash()
      _copyFile(context, currentBackup, filename)
      _flashAk3(context, "")
    }
  }

  fun flashAk3(context: Context, uri: Uri) {
    launch {
      _clearFlash()
      _copyFile(context, uri)
      _flashAk3(context, "")
    }
  }

  fun flashAk3_mkbootfs(context: Context, currentBackup: String, filename: String) {
    launch {
      _clearFlash()
      _copyFile(context, currentBackup, filename)
      _flashAk3(context, "_mkbootfs")
    }
  }

  fun flashAk3_mkbootfs(context: Context, uri: Uri) {
    launch {
      _clearFlash()
      _copyFile(context, uri)
      _flashAk3(context, "_mkbootfs")
    }
  }

  fun flashKsuDriver(context: Context, uri: Uri) {
    launch {
      _clearFlash()
      addMessage("Copying KernelSU Driver ...")
      _copyDriver(context, uri)
      if (!isActive) {
        resetSlot()
      }
      val driver = fileSystemManager.getFile(context.filesDir, "kernelsu.ko")
      val newBootImg = File(context.filesDir, "new-boot.img")
      var image: ExtendedFile? = null
      try {
        if (driver.exists()) {
          addMessage("Copied $flashFilename")
          _wasFlashSuccess.value = false
          val partitionName = bootInfo.ramdiskLocation?.removeSuffix(".img") ?: "boot"
          val magiskboot = File(context.filesDir, "magiskboot")
          val ksuinit = File(context.filesDir, "ksuinit")
          if (partitionName == "boot") {
            addMessage("Unpacking boot.img")
            Shell.cmd("$magiskboot unpack $boot").exec()
          } else {
            addMessage("Unpacking init_boot")
            Shell.cmd("$magiskboot unpack $initBoot").exec()
          }

          val ramdisk = File(context.filesDir, "ramdisk.cpio")

          if (ramdisk.exists()) {
            addMessage("Patching Ramdisk")

            if (Shell.cmd("$magiskboot cpio ramdisk.cpio 'exists kernelsu.ko'")
                .to(flashOutput, flashOutput).exec().isSuccess
            ) {
              Shell.cmd("$magiskboot cpio ramdisk.cpio 'rm init'")
                .to(flashOutput, flashOutput).exec()
              Shell.cmd("$magiskboot cpio ramdisk.cpio 'add 0755 init $ksuinit'")
                .to(flashOutput, flashOutput).exec()
              Shell.cmd("$magiskboot cpio ramdisk.cpio 'rm kernelsu.ko'")
                .to(flashOutput, flashOutput).exec()
              Shell.cmd("$magiskboot cpio ramdisk.cpio 'add 0755 kernelsu.ko $driver'")
                .to(flashOutput, flashOutput).exec()
            } else {
              Shell.cmd("$magiskboot cpio ramdisk.cpio 'mv init init.real'")
                .to(flashOutput, flashOutput).exec()
              Shell.cmd("$magiskboot cpio ramdisk.cpio 'add 0755 init $ksuinit'")
                .to(flashOutput, flashOutput).exec()
              Shell.cmd("$magiskboot cpio ramdisk.cpio 'add 0755 kernelsu.ko $driver'")
                .to(flashOutput, flashOutput).exec()
            }
            if (partitionName == "boot") {
              addMessage("Repacking boot.img")
              Shell.cmd("$magiskboot repack $boot").exec()
            } else {
              addMessage("Repacking init_boot.img")
              Shell.cmd("$magiskboot repack $initBoot").exec()
            }

            if (newBootImg.exists()) {
              image = fileSystemManager.getFile(context.filesDir, "new-boot.img")
            } else {
              Shell.cmd("$magiskboot cleanup").exec()
              log(context, "Image Repack Failed!", shouldThrow = true)
            }
          } else {
            Shell.cmd("$magiskboot cleanup").exec()
            log(context, "Ramdisk not found", shouldThrow = true)
          }
          Shell.cmd("$magiskboot cleanup").exec()

          addMessage("Flashing $image to $partitionName$slotSuffix ...")
          val blockDevice = partitionName.let {
            PartitionUtil.findPartitionBlockDevice(
              context,
              it, slotSuffix
            )
          }
          if (blockDevice != null && blockDevice.exists()) {
            if (PartitionUtil.isPartitionLogical(context, partitionName)) {
              if (image != null) {
                PartitionUtil.flashLogicalPartition(
                  context,
                  image,
                  blockDevice,
                  partitionName,
                  slotSuffix,
                  hashAlgorithm
                ) { message ->
                  addMessage(message)
                }
              }
            } else {
              if (image != null) {
                PartitionUtil.flashBlockDevice(image, blockDevice, hashAlgorithm)
              }
            }
          } else {
            log(context, "Partition $partitionName$slotSuffix was not found", shouldThrow = true)
          }
          addMessage("Flashed ${image?.name} to $partitionName$slotSuffix")
          addMessage("Cleaning up ...")
          clearTmp(context)
          addMessage("Done.")
          _wasFlashSuccess.value = true
        } else {
          log(context, "KernelSU Driver is missing", shouldThrow = true)
        }
      } catch (e: Exception) {
        clearFlash(context)
        throw e
      } finally {
        addMessage("")
        if (driver.exists())
          driver.delete()
        if (newBootImg.exists())
          newBootImg.delete()
        if (wasSlotReset) {
          resetSlot()
          viewModelScope.launch(Dispatchers.Main) {
            showCautionDialog() // Show dialog instead of uiPrint
          }
        }
      }
    }
  }

  fun flashImage(context: Context, uri: Uri, partitionName: String) {
    launch {
      _clearFlash()
      addMessage("Copying image ...")
      _copyFile(context, uri)
      if (!isActive) {
        resetSlot()
      }
      val image = fileSystemManager.getFile(context.filesDir, flashFilename!!)
      try {
        if (image.exists()) {
          addMessage("Copied $flashFilename")
          _wasFlashSuccess.value = false
          addMessage("Flashing $flashFilename to $partitionName ...")
          val blockDevice =
            PartitionUtil.findPartitionBlockDevice(context, partitionName, slotSuffix)
          if (blockDevice != null && blockDevice.exists()) {
            if (PartitionUtil.isPartitionLogical(context, partitionName)) {
              PartitionUtil.flashLogicalPartition(
                context,
                image,
                blockDevice,
                partitionName,
                slotSuffix,
                hashAlgorithm
              ) { message ->
                addMessage(message)
              }
            } else {
              PartitionUtil.flashBlockDevice(image, blockDevice, hashAlgorithm)
            }
          } else {
            log(context, "Partition $partitionName was not found", shouldThrow = true)
          }
          addMessage("Flashed $flashFilename to $partitionName")
          addMessage("Cleaning up ...")
          clearTmp(context)
          addMessage("Done.")
          _wasFlashSuccess.value = true
        } else {
          log(context, "Partition image is missing", shouldThrow = true)
        }
      } catch (e: Exception) {
        clearFlash(context)
        throw e
      } finally {
        addMessage("")
        if (wasSlotReset) {
          resetSlot()
          viewModelScope.launch(Dispatchers.Main) {
            showCautionDialog() // Show dialog instead of uiPrint
          }
        }
      }
    }
  }
}