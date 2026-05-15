//go:build windows

package storage

import (
	"fmt"
	"syscall"
	"unsafe"

	"github.com/beyondChang/go-nvr/internal/metrics"
)

var (
	kernel32           = syscall.NewLazyDLL("kernel32.dll")
	getDiskFreeSpaceEx = kernel32.NewProc("GetDiskFreeSpaceExW")
)

func getDiskUsage(rootDir string, m *metrics.Metrics) (total int64, used int64, err error) {
	var freeBytesAvailable, totalBytes, totalFreeBytes int64

	r, _, err := getDiskFreeSpaceEx.Call(
		uintptr(unsafe.Pointer(syscall.StringToUTF16Ptr(rootDir))),
		uintptr(unsafe.Pointer(&freeBytesAvailable)),
		uintptr(unsafe.Pointer(&totalBytes)),
		uintptr(unsafe.Pointer(&totalFreeBytes)),
	)
	if r == 0 {
		return 0, 0, fmt.Errorf("storage: failed to get disk free space: %w", err)
	}

	used = totalBytes - totalFreeBytes
	total = totalBytes

	if m != nil {
		m.StorageUsedBytes.Set(float64(used))
		m.StorageTotalBytes.Set(float64(total))
	}
	return total, used, nil
}
