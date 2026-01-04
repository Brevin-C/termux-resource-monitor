# Termux Resource Monitor

轻量级 Termux 进程资源监控工具，专为 Android 电视盒子环境设计。

## 功能特性

- 🚀 **轻量化**：单个二进制文件，内存占用 < 10MB
- 📊 **实时监控**：CPU、内存、网络流量
- 🔌 **HTTP API**：方便远程数据采集
- 🎯 **零依赖**：纯 Go 实现，基于 /proc 文件系统

## 快速开始

### 编译

```bash
# ARM64 (适用于大部分现代 Android 盒子)
GOOS=linux GOARCH=arm64 go build -ldflags="-s -w" -o monitor-arm64

# ARMv7 (适用于老旧 Android 盒子)
GOOS=linux GOARCH=arm GOARM=7 go build -ldflags="-s -w" -o monitor-armv7
```

### 运行

```bash
# 监控指定 PID
MONITOR_PID=1234 ./monitor-arm64

# 自动发现 Termux 进程
./monitor-arm64

# 自定义端口
MONITOR_PORT=9090 MONITOR_PID=1234 ./monitor-arm64
```

### API 使用

```bash
# 获取统计数据
curl http://localhost:8080/stats

# 健康检查
curl http://localhost:8080/health
```

## 响应示例

```json
[
  {
    "timestamp": "2026-01-04T15:25:00Z",
    "pid": 1234,
    "process_name": "proxy-client",
    "cpu_percent": 12.5,
    "memory_mb": 45.3,
    "network_rx_bytes": 1024000,
    "network_tx_bytes": 512000
  }
]
```

## 部署建议

### 集成到 APK/SDK

1. 将编译好的二进制文件打包到 assets
2. 在运行时解压到 Termux 环境
3. 通过 `Runtime.exec()` 启动监控进程

### 资源限制

- 推荐采样间隔：5 秒（已hardcode）
- 历史记录上限：1000 条（约占用 < 500KB 内存）
- HTTP 端口：默认 8080（可通过环境变量修改）

## 技术实现

- **CPU 使用率**：读取 `/proc/[pid]/stat` + `/proc/uptime`  
- **内存占用**：读取 `/proc/[pid]/status` (VmRSS)  
- **网络流量**：读取 `/proc/net/dev`（系统级统计）

## 许可证

MIT License
