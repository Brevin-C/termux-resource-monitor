package com.titan.titanLibs.monitor

/**
 * 进程监控回调接口
 */
interface ProcessMonitorCallback {
    /**
     * 监控指标采集完成回调
     * @param metrics 当前采样的指标数据
     * @param ioDelta IO增量数据
     * @param networkDelta 网络流量增量数据
     */
    fun onMetricsCollected(metrics: ProcessMetrics, ioDelta: IoDelta, networkDelta: NetworkDelta)
    
    /**
     * 进程已退出回调
     * @param pid 已退出的进程ID
     * @param lastMetrics 最后一次采样的指标数据（可能为null）
     */
    fun onProcessDied(pid: Int, lastMetrics: ProcessMetrics?)
    
    /**
     * 监控异常回调
     * @param pid 进程ID
     * @param error 异常信息
     */
    fun onMonitorError(pid: Int, error: String)
}
