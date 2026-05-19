package middleware

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// DailyRotateWriter is an io.Writer that writes log messages to daily rotating
// files named YYYY-MM-DD.log under the specified directory. It is safe for
// concurrent use by multiple slog goroutines.
type DailyRotateWriter struct {
	dir  string
	mu   sync.Mutex
	file *os.File
	date string // current file date in "2006-01-02" format
}

// NewDailyRotateWriter creates a DailyRotateWriter that writes logs into dir.
// The directory is created if it does not exist.
func NewDailyRotateWriter(dir string) (*DailyRotateWriter, error) {
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, fmt.Errorf("create log dir %s: %w", dir, err)
	}
	w := &DailyRotateWriter{dir: dir}
	if err := w.rotateIfNeeded(time.Now()); err != nil {
		return nil, err
	}
	return w, nil
}

// Write implements io.Writer. It writes p to the current daily log file,
// rotating to a new file if the date has changed.
func (w *DailyRotateWriter) Write(p []byte) (int, error) {
	now := time.Now()
	w.mu.Lock()
	defer w.mu.Unlock()

	if err := w.rotateIfNeeded(now); err != nil {
		// If rotation fails, fall back to stderr so logs are not silently lost.
		_, _ = os.Stderr.Write([]byte("log rotation error: " + err.Error() + "\n"))
		return os.Stderr.Write(p)
	}
	return w.file.Write(p)
}

// Close closes the current log file.
func (w *DailyRotateWriter) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.file != nil {
		return w.file.Close()
	}
	return nil
}

// rotateIfNeeded opens a new file if the date has changed or no file is open.
// Must be called with w.mu held.
func (w *DailyRotateWriter) rotateIfNeeded(now time.Time) error {
	today := now.Format("2006-01-02")
	if w.file != nil && w.date == today {
		return nil
	}

	// Close previous file
	if w.file != nil {
		w.file.Close()
	}

	// Open new file
	path := filepath.Join(w.dir, today+".log")
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return fmt.Errorf("open log file %s: %w", path, err)
	}

	w.file = f
	w.date = today
	return nil
}

// multiWriter creates an io.Writer that duplicates writes to stdout and the
// daily log file. If logDir is empty, only stdout is used.
func multiWriter(logDir string) (io.Writer, error) {
	if logDir == "" {
		return os.Stdout, nil
	}
	rotateWriter, err := NewDailyRotateWriter(logDir)
	if err != nil {
		return nil, err
	}
	return io.MultiWriter(os.Stdout, rotateWriter), nil
}
