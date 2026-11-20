package com.soprso.dhurandharai.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

/** Snapshot of the hardware facts used for model compatibility decisions. */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val lowMemory: Boolean,
    val lowRamDevice: Boolean,
    val memoryThresholdBytes: Long,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val appMaxMemoryBytes: Long,
    val nativeLibraryBytes: Long,
    val cpuCount: Int,
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val internalStorageBytes: Long,
    val availableStorageBytes: Long,
    val availableModelStorageBytes: Long,
    val summary: String,
) {
    companion object {
        /** Neutral profile for pure estimation without Android APIs (used by tests). */
        fun estimateFallback(): DeviceProfile = DeviceProfile(
            manufacturer = "",
            model = "",
            androidVersion = "",
            sdkVersion = 0,
            totalMemoryBytes = 0,
            availableMemoryBytes = 0,
            lowMemory = false,
            lowRamDevice = false,
            memoryThresholdBytes = 0,
            memoryClassMb = 0,
            largeMemoryClassMb = 0,
            appMaxMemoryBytes = 0,
            nativeLibraryBytes = 0,
            cpuCount = 4,
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a"),
            internalStorageBytes = 0,
            availableStorageBytes = 0,
            availableModelStorageBytes = 0,
            summary = "estimate",
        )
    }
}

object DeviceInfoProvider {

    fun read(context: Context): DeviceProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)

        val dataStat = StatFs(context.filesDir.path)
        val modelDir = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
        val modelStat = StatFs(modelDir.path)

        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.toList()
        } else {
            listOf(Build.CPU_ABI)
        }

        val nativeSize = File(context.applicationInfo.nativeLibraryDir)
            .listFiles { file -> file.name.endsWith(".so") }
            ?.sumOf { it.length() } ?: 0L

        return DeviceProfile(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkVersion = Build.VERSION.SDK_INT,
            totalMemoryBytes = memory.totalMem,
            availableMemoryBytes = memory.availMem,
            lowMemory = memory.lowMemory,
            lowRamDevice = activityManager.isLowRamDevice,
            memoryThresholdBytes = memory.threshold,
            memoryClassMb = activityManager.memoryClass,
            largeMemoryClassMb = activityManager.largeMemoryClass,
            appMaxMemoryBytes = Runtime.getRuntime().maxMemory(),
            nativeLibraryBytes = nativeSize,
            cpuCount = Runtime.getRuntime().availableProcessors(),
            primaryAbi = abis.firstOrNull() ?: "unknown",
            supportedAbis = abis,
            internalStorageBytes = dataStat.blockCountLong * dataStat.blockSizeLong,
            availableStorageBytes = dataStat.availableBlocksLong * dataStat.blockSizeLong,
            availableModelStorageBytes = modelStat.availableBlocksLong * modelStat.blockSizeLong,
            summary = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${abis.firstOrNull() ?: "unknown"}",
        )
    }
}
