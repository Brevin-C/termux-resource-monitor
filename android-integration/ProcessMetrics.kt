package com.titan.titanLibs.monitor

/**
 * 进程监控指标数据模型
 * @property pid 进程ID
 * @property timestamp 采样时间戳 (毫秒)
 * @property cpuUsagePercent CPU使用率 (0-100)
 * @property memoryRssKb 物理内存占用 (KB)
 * @property ioReadBytes 累计磁盘读取字节数
 * @property ioWriteBytes 累计磁盘写入字节数
 * @property networkRxBytes 累计网络接收字节数
 * @property networkTxBytes 累计网络发送字节数
 * @property isProcessAlive 进程是否仍存活
 */
data class ProcessMetrics(
    val pid: Int,
    val timestamp: Long,
    val cpuUsagePercent: Float,
    val memoryRssKb: Long,
    val ioReadBytes: Long,
    val ioWriteBytes: Long,
    val networkRxBytes: Long,
    val networkTxBytes: Long,
    val isProcessAlive: Boolean
) {
    /**
     * 计算与上一次采样的IO增量
     */
    fun calculateIoDelta(previous: ProcessMetrics?): IoDelta {
        if (previous == null || previous.pid != this.pid) {
            return IoDelta(0, 0, 0)
        }
        
        val timeDeltaMs = this.timestamp - previous.timestamp
        val readDelta = maxOf(0, this.ioReadBytes - previous.ioReadBytes)
        val writeDelta = maxOf(0, this.ioWriteBytes - previous.ioWriteBytes)
        
        return IoDelta(readDelta, writeDelta, timeDeltaMs)
    }

    /**
     * 计算与上一次采样的网络流量增量
     */
    fun calculateNetworkDelta(previous: ProcessMetrics?): NetworkDelta {
        if (previous == null || previous.pid != this.pid) {
            return NetworkDelta(0, 0, 0)
        }
        
        val timeDeltaMs = this.timestamp - previous.timestamp
        val rxDelta = maxOf(0, this.networkRxBytes - previous.networkRxBytes)
        val txDelta = maxOf(0, this.networkTxBytes - previous.networkTxBytes)
        
        return NetworkDelta(rxDelta, txDelta, timeDeltaMs)
    }
    
    /**
     * 将监控数据格式化为CSV字符串
     */
    fun toCsv(): String {
        return "$pid,$timestamp,${"%.2f".format(cpuUsagePercent)},$memoryRssKb,$ioReadBytes,$ioWriteBytes,$networkRxBytes,$networkTxBytes,$isProcessAlive"
    }
    
    override fun toString(): String {
        return "ProcessMetrics(pid=$pid, cpu=${"%.2f".format(cpuUsagePercent)}%, " +
                "mem=${memoryRssKb}KB, ioRead=${ioReadBytes}, ioWrite=${ioWriteBytes}, " +
                "netRx=${networkRxBytes}, netTx=${networkTxBytes}, alive=$isProcessAlive)"
    }
}

/**
 * IO增量数据
 * @property readBytes 读取增量 (字节)
 * @property writeBytes 写入增量 (字节)
 * @property timeDeltaMs 时间间隔 (毫秒)
 */
data class IoDelta(
    val readBytes: Long,
    val writeBytes: Long,
    val timeDeltaMs: Long
)

/**
 * 网络流量增量数据
 * @property rxBytes 接收增量 (字节)
 * @property txBytes 发送增量 (字节)
 * @property timeDeltaMs 时间间隔 (毫秒)
 */
data class NetworkDelta(
    val rxBytes: Long,
    val txBytes: Long,
    val timeDeltaMs: Long
) {
    /**
     * 计算平均接收速率 (字节/秒)
     */
    fun getRxBytesPerSecond(): Long {
        return if (timeDeltaMs > 0) (rxBytes * 1000 / timeDeltaMs) else 0
    }
    
    /**
     * 计算平均发送速率 (字节/秒)
     */
    fun getTxBytesPerSecond(): Long {
        return if (timeDeltaMs > 0) (txBytes * 1000 / timeDeltaMs) else 0
    }
}
