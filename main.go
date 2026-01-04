package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ResourceStats 资源统计数据
type ResourceStats struct {
	Timestamp   time.Time `json:"timestamp"`
	PID         int       `json:"pid"`
	ProcessName string    `json:"process_name"`
	CPUPercent  float64   `json:"cpu_percent"`
	MemoryMB    float64   `json:"memory_mb"`
	NetworkRx   uint64    `json:"network_rx_bytes"`
	NetworkTx   uint64    `json:"network_tx_bytes"`
}

var (
	statsHistory []ResourceStats
	statsMutex   sync.RWMutex
	maxHistory   = 1000
)

func getCPUUsage(pid int) (float64, error) {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return 0, err
	}
	fields := strings.Fields(string(data))
	if len(fields) < 17 {
		return 0, fmt.Errorf("invalid stat format")
	}
	utime, _ := strconv.ParseUint(fields[13], 10, 64)
	stime, _ := strconv.ParseUint(fields[14], 10, 64)
	totalTime := utime + stime
	uptimeData, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return 0, err
	}
	uptimeFields := strings.Fields(string(uptimeData))
	uptime, _ := strconv.ParseFloat(uptimeFields[0], 64)
	hertz := 100.0
	seconds := uptime - (float64(totalTime) / hertz)
	if seconds > 0 {
		return (float64(totalTime) / hertz / seconds) * 100, nil
	}
	return 0, nil
}

func getMemoryUsage(pid int) (float64, error) {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/status", pid))
	if err != nil {
		return 0, err
	}
	lines := strings.Split(string(data), "\n")
	for _, line := range lines {
		if strings.HasPrefix(line, "VmRSS:") {
			fields := strings.Fields(line)
			if len(fields) >= 2 {
				kb, _ := strconv.ParseFloat(fields[1], 64)
				return kb / 1024, nil
			}
		}
	}
	return 0, nil
}

func getNetworkStats(pid int) (rx, tx uint64, err error) {
	data, err := os.ReadFile("/proc/net/dev")
	if err != nil {
		return 0, 0, err
	}
	lines := strings.Split(string(data), "\n")
	for _, line := range lines {
		if strings.Contains(line, ":") {
			fields := strings.Fields(line)
			if len(fields) >= 10 {
				rxBytes, _ := strconv.ParseUint(fields[1], 10, 64)
				txBytes, _ := strconv.ParseUint(fields[9], 10, 64)
				rx += rxBytes
				tx += txBytes
			}
		}
	}
	return rx, tx, nil
}

func getProcessName(pid int) string {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/comm", pid))
	if err != nil {
		return "unknown"
	}
	return strings.TrimSpace(string(data))
}

func monitorProcess(pid int) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		cpu, err := getCPUUsage(pid)
		if err != nil {
			log.Printf("Failed to get CPU for PID %d: %v", pid, err)
			continue
		}
		mem, err := getMemoryUsage(pid)
		if err != nil {
			log.Printf("Failed to get memory for PID %d: %v", pid, err)
			continue
		}
		rx, tx, err := getNetworkStats(pid)
		if err != nil {
			log.Printf("Failed to get network stats: %v", err)
		}
		stats := ResourceStats{
			Timestamp:   time.Now(),
			PID:         pid,
			ProcessName: getProcessName(pid),
			CPUPercent:  cpu,
			MemoryMB:    mem,
			NetworkRx:   rx,
			NetworkTx:   tx,
		}
		statsMutex.Lock()
		statsHistory = append(statsHistory, stats)
		if len(statsHistory) > maxHistory {
			statsHistory = statsHistory[1:]
		}
		statsMutex.Unlock()
		log.Printf("PID %d [%s]: CPU=%.2f%%, MEM=%.2f MB", pid, stats.ProcessName, cpu, mem)
	}
}

func statsHandler(w http.ResponseWriter, r *http.Request) {
	statsMutex.RLock()
	defer statsMutex.RUnlock()
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(statsHistory)
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, "OK")
}

func findTermuxProcesses() []int {
	cmd := exec.Command("pgrep", "-x", "termux")
	output, err := cmd.Output()
	if err != nil {
		return nil
	}
	var pids []int
	lines := strings.Split(strings.TrimSpace(string(output)), "\n")
	for _, line := range lines {
		if pid, err := strconv.Atoi(line); err == nil {
			pids = append(pids, pid)
		}
	}
	return pids
}

func main() {
	port := os.Getenv("MONITOR_PORT")
	if port == "" {
		port = "8080"
	}
	pidStr := os.Getenv("MONITOR_PID")
	if pidStr == "" {
		log.Println("No MONITOR_PID specified, attempting to find Termux processes...")
		pids := findTermuxProcesses()
		if len(pids) > 0 {
			log.Printf("Found %d Termux processes: %v", len(pids), pids)
			go monitorProcess(pids[0])
		} else {
			log.Fatal("No processes found. Please set MONITOR_PID environment variable.")
		}
	} else {
		pid, err := strconv.Atoi(pidStr)
		if err != nil {
			log.Fatalf("Invalid PID: %s", pidStr)
		}
		log.Printf("Starting monitor for PID: %d", pid)
		go monitorProcess(pid)
	}
	http.HandleFunc("/stats", statsHandler)
	http.HandleFunc("/health", healthHandler)
	log.Printf("Monitor API running on :%s", port)
	if err := http.ListenAndServe(":"+port, nil); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}
