package com.titan.titanLibs.monitor

import android.net.TrafficStats
import android.os.Build
import com.titan.titanLibs.logger.Logger
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轻量级进程资源监控器
 *
 * 特性：
 * - 内存占用 < 500KB (复用主进程，避免GC压力)
 * - 支持多进程同时监控
 * - 动态采样频率 (空闲时降低频率)
 * - 无需Root权限
 *
 * 监控指标：
 * - CPU使用率 (通过 /proc/[pid]/stat)
 * - 物理内存 (通过 /proc/[pid]/status 的 VmRSS)
 * - 网络流量 (通过 TrafficStats.getUidRxBytes/getUidTxBytes)
 *
 * @author TitanEngine Team
 * @version 1.1.0
 */
object ProcessMonitor {
    private const val TAG = "ProcessMonitor"
    
    // 监控配置
    private const val DEFAULT_SAMPLE_INTERVAL_MS = 5000L // 默认5秒采样
    private const val IDLE_SAMPLE_INTERVAL_MS = 30000L   // 空闲时30秒采样
    private const val IDLE_NETWORK_THRESHOLD_BPS = 1024  // 网络速率低于1KB/s视为空闲
    
    // 进程监控任务管理
    private val monitoredProcesses = ConcurrentHashMap<Int, MonitorTask>()
    private val executor = Executors.newScheduledThreadPool(
        1,
        ThreadFactory { r ->
            Thread(r, "TitanProcessMonitor").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY + 1 // 低优先级，避免影响主业务
            }
        }
    )
    
    private val isMonitoring = AtomicBoolean(false)
    
    /**
     * 开始监控指定进程
     * @param pid 进程ID
     * @param uid 进程所属的UID，用于流量统计。如果为null，则不统计流量。
     * @param callback 监控数据回调
     * @param intervalMs 采样间隔(毫秒)，默认5000ms
     */
    @JvmStatic
    @JvmOverloads
    fun startMonitoring(
        pid: Int,
        uid: Int?,
        callback: ProcessMonitorCallback,
        intervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS
    ) {
        if (pid <= 0) {
            Logger.log(TAG, "Invalid PID: $pid")
            return
        }
        
        if (monitoredProcesses.containsKey(pid)) {
            Logger.log(TAG, "PID $pid is already being monitored")
            return
        }
        
        isMonitoring.set(true)
        
        val task = MonitorTask(pid, uid, callback, intervalMs)
        monitoredProcesses[pid] = task
        
        task.scheduledFuture = executor.scheduleAtFixedRate(
            { task.collect() },
            0,
            intervalMs,
            TimeUnit.MILLISECONDS
        )
        
        Logger.log(TAG, "Started monitoring PID $pid (UID: $uid) with interval ${intervalMs}ms")
    }
    
    /**
     * 停止监控指定进程
     */
    @JvmStatic
    fun stopMonitoring(pid: Int) {
        monitoredProcesses.remove(pid)?.let {
            it.scheduledFuture?.cancel(false)
            Logger.log(TAG, "Stopped monitoring PID $pid")
        }
        
        if (monitoredProcesses.isEmpty()) {
            isMonitoring.set(false)
        }
    }
    
    /**
     * 停止所有监控任务
     */
    @JvmStatic
    fun stopAll() {
        monitoredProcesses.keys.toList().forEach { stopMonitoring(it) }
        isMonitoring.set(false)
    }
    
    /**
     * 单个进程的监控任务
     */
    private class MonitorTask(
        val pid: Int,
        val uid: Int?,
        val callback: ProcessMonitorCallback,
        var intervalMs: Long
    ) {
        var scheduledFuture: ScheduledFuture<*>? = null
        private var lastMetrics: ProcessMetrics? = null
        private var lastPidCpuTime = 0L
        private var lastSystemTime = 0L
        private var failureCount = 0
        private val maxFailures = 3
        
        fun collect() {
            try {
                val metrics = collectMetrics() ?: run {
                    handleCollectionFailure()
                    return
                }
                
                failureCount = 0
                
                // 计算增量
                val ioDelta = metrics.calculateIoDelta(lastMetrics)
                val networkDelta = metrics.calculateNetworkDelta(lastMetrics)
                
                // 回调通知
                callback.onMetricsCollected(metrics, ioDelta, networkDelta)
                
                // 动态调整采样频率
                adjustSamplingRate(networkDelta)
                
                lastMetrics = metrics
                
            } catch (e: Exception) {
                Logger.log(TAG, "Error collecting metrics for PID $pid: ${e.message}")
                callback.onMonitorError(pid, e.message ?: "Unknown error")
                handleCollectionFailure()
            }
        }
        
        private fun collectMetrics(): ProcessMetrics? {
            val procDir = File("/proc/$pid")
            if (!procDir.exists()) {
                // 进程已死亡，返回最后一次有效数据（如果存在）的标记
                lastMetrics?.let {
                    callback.onProcessDied(pid, it)
                    ProcessMonitor.stopMonitoring(pid)
                }
                return null
            }
            
            val cpuUsage = collectCpuUsage()
            val memoryRss = collectMemoryRss()
            val (ioRead, ioWrite) = collectIoStats()
            val (netRx, netTx) = uid?.let { collectTrafficStats(it) } ?: Pair(0L, 0L)
            
            return ProcessMetrics(
                pid = pid,
                timestamp = System.currentTimeMillis(),
                cpuUsagePercent = cpuUsage,
                memoryRssKb = memoryRss,
                ioReadBytes = ioRead,
                ioWriteBytes = ioWrite,
                networkRxBytes = netRx,
                networkTxBytes = netTx,
                isProcessAlive = true
            )
        }

        private fun collectCpuUsage(): Float {
            try {
                val statContent = File("/proc/$pid/stat").readText()
                val parts = statContent.split(" ")
                if (parts.size < 17) return 0f
                
                val utime = parts[13].toLongOrNull() ?: 0L
                val stime = parts[14].toLongOrNull() ?: 0L
                val currentPidCpuTime = utime + stime
                
                val systemCpuTime = getSystemCpuTime()
                
                if (lastPidCpuTime == 0L || lastSystemTime == 0L) {
                    lastPidCpuTime = currentPidCpuTime
                    lastSystemTime = systemCpuTime
                    return 0f
                }
                
                val pidTimeDelta = currentPidCpuTime - lastPidCpuTime
                val systemTimeDelta = systemCpuTime - lastSystemTime
                
                lastPidCpuTime = currentPidCpuTime
                lastSystemTime = systemCpuTime
                
                return if (systemTimeDelta > 0) {
                    (pidTimeDelta.toFloat() / systemTimeDelta.toFloat() * 100f)
                } else {
                    0f
                }
            } catch (e: Exception) {
                return 0f
            }
        }

        private fun getSystemCpuTime(): Long {
            try {
                File("/proc/stat").bufferedReader().use {
                    val parts = it.readLine().split("\\s+".toRegex())
                    var totalTime = 0L
                    for (i in 1 until parts.size.coerceAtMost(8)) {
                        totalTime += parts[i].toLongOrNull() ?: 0L
                    }
                    return totalTime
                }
            } catch (e: Exception) {
                return 0L
            }
        }

        private fun collectMemoryRss(): Long {
            try {
                File("/proc/$pid/status").forEachLine { line ->
                    if (line.startsWith("VmRSS:")) {
                        return line.split("\\s+".toRegex())[1].toLongOrNull() ?: 0L
                    }
                }
                return 0L
            } catch (e: Exception) {
                return 0L
            }
        }

        private fun collectIoStats(): Pair<Long, Long> {
             return Pair(0L, 0L) // 磁盘IO暂不实现，优先网络
        }
        
        private fun collectTrafficStats(uid: Int): Pair<Long, Long> {
            return try {
                val rx = TrafficStats.getUidRxBytes(uid)
                val tx = TrafficStats.getUidTxBytes(uid)
                // TrafficStats.UNSUPPORTED means the device doesn't support it.
                if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
                    Pair(0L, 0L)
                } else {
                    Pair(rx, tx)
                }
            } catch (e: Exception) {
                Logger.log(TAG, "Error reading traffic stats for UID $uid: ${e.message}")
                Pair(0L, 0L)
            }
        }
        
        private fun adjustSamplingRate(delta: NetworkDelta) {
            val networkBps = delta.getRxBytesPerSecond() + delta.getTxBytesPerSecond()
            
            val newInterval = if (networkBps < IDLE_NETWORK_THRESHOLD_BPS) {
                IDLE_SAMPLE_INTERVAL_MS
            } else {
                DEFAULT_SAMPLE_INTERVAL_MS
            }
            
            if (newInterval != intervalMs) {
                intervalMs = newInterval
                scheduledFuture?.cancel(false)
                scheduledFuture = executor.scheduleAtFixedRate(
                    { this.collect() },
                    newInterval,
                    newInterval,
                    TimeUnit.MILLISECONDS
                )
                Logger.log(TAG, "Adjusted sampling rate for PID $pid to ${newInterval}ms")
            }
        }

        private fun handleCollectionFailure() {
            failureCount++
            if (failureCount >= maxFailures) {
                Logger.log(TAG, "PID $pid failed $failureCount times, assuming process died")
                callback.onProcessDied(pid, lastMetrics)
                ProcessMonitor.stopMonitoring(pid)
            }
        }
    }
    
    @JvmStatic
    fun getPidFromProcess(process: Process): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.pid().toInt()
            } else {
                val field = process.javaClass.getDeclaredField("pid")
                field.isAccessible = true
                field.getInt(process)
            }
        } catch (e: Exception) {
            -1
        }
    }
}
