//go:build !windows

package storage

import (
	"fmt"
	"syscall"

	"github.com/beyondChang/go-nvr/internal/metrics"
)

func getDiskUsage(rootDir string, m *metrics.Metrics) (total int64, used int64, err error) {
	var stat syscall.Statfs_t
	if err := syscall.Statfs(rootDir, &stat); err != nil {
		return 0, 0, fmt.Errorf("storage: failed to stat filesystem: %w", err)
	}
	total = int64(stat.Blocks * uint64(stat.Bsize))
	free := int64(stat.Bfree * uint64(stat.Bsize))
	used = total - free
	if m != nil {
		m.StorageUsedBytes.Set(float64(used))
		m.StorageTotalBytes.Set(float64(total))
	}
	return total, used, nil
}
