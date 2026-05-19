package api

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/beyondChang/go-nvr/internal/camera"
	"github.com/beyondChang/go-nvr/internal/config"
	"github.com/beyondChang/go-nvr/internal/middleware"
	"github.com/beyondChang/go-nvr/internal/model"
	"github.com/beyondChang/go-nvr/internal/hls"
	"github.com/beyondChang/go-nvr/internal/merge"
	"github.com/beyondChang/go-nvr/internal/onvif"
	"github.com/beyondChang/go-nvr/internal/recorder"
	"github.com/beyondChang/go-nvr/internal/storage"
)

var logger = slog.Default().With("component", "api")

var appStartTime = time.Now()

// HealthCheck represents the result of a single health check.
type HealthCheck struct {
	Status  string `json:"status"`  // "ok" | "warning" | "error"
	Message string `json:"message,omitempty"`
}

// HealthResponse is the response from /api/health.
type HealthResponse struct {
	Status string            `json:"status"` // "ok" | "degraded" | "unhealthy"
	Checks map[string]HealthCheck `json:"checks"`
	Uptime string            `json:"uptime"`
}

// SystemStats is the response from /api/stats/system.
type SystemStats struct {
	CPU     CPUStats     `json:"cpu"`
	Memory  MemoryStats  `json:"memory"`
	Network NetworkStats `json:"network"`
	Uptime  string       `json:"uptime"`
	Timestamp int64       `json:"timestamp"`
}

type CPUStats struct {
	Total uint64 `json:"total"` // cumulative total jiffies
	Idle  uint64 `json:"idle"`  // cumulative idle jiffies
}

type MemoryStats struct {
	Total      uint64 `json:"total"`       // MemTotal bytes
	Available  uint64 `json:"available"`   // MemAvailable bytes
	ProcessRSS uint64 `json:"process_rss"` // NVR process RSS bytes
}

type NetworkStats struct {
	BytesSent uint64 `json:"bytes_sent"`
	BytesRecv uint64 `json:"bytes_recv"`
}

type snapshotCache struct {
	data      []byte
	timestamp time.Time
}

// Handler holds dependencies for the REST API handlers.

type Handler struct {
	db      *storage.DB
	store   *storage.Manager
	authMW  func(http.Handler) http.Handler
	config  *config.Config
	camMgr  *camera.CameraManager
	hlsMgr  *hls.Manager
	configPath string
	snapshotMu    sync.RWMutex
	snapshots     map[string]*snapshotCache // cameraID -> cached snapshot
	mergeMgr      *merge.MergeManager
}
// NewHandler creates a new API handler.
func NewHandler(db *storage.DB, store *storage.Manager, authMW func(http.Handler) http.Handler, cfg *config.Config, camMgr *camera.CameraManager, hlsMgr *hls.Manager, configPath string, mergeMgr *merge.MergeManager) *Handler {
	return &Handler{db: db, store: store, authMW: authMW, config: cfg, camMgr: camMgr, hlsMgr: hlsMgr, configPath: configPath, snapshots: make(map[string]*snapshotCache), mergeMgr: mergeMgr}
}

// Routes returns a chi.Router with all routes registered.
func (h *Handler) Routes() http.Handler {
	r := chi.NewRouter()

	// Public routes
	r.Get("/api/health", h.handleHealth)
	r.Get("/api/readyz", h.handleReadyz)
	r.Post("/api/auth/login", h.handleLogin)

	// Protected routes
	r.Group(func(r chi.Router) {
		r.Use(h.authMW)
		r.Route("/api/recordings", func(r chi.Router) {
			r.Get("/", h.handleListRecordings)
			r.With(h.requireAdmin).Post("/batch-delete", h.handleBatchDeleteRecordings)
			r.Route("/{id}", func(r chi.Router) {
				r.Get("/", h.handleGetRecording)
				r.With(h.requireAdmin).Delete("/", h.handleDeleteRecording)
				r.Get("/download", h.handleDownloadRecording)
				r.Get("/frames", h.handleListFrames)
			})
		})
		r.Route("/api/cameras", func(r chi.Router) {
			r.Get("/", h.handleListCameras)
			r.Group(func(r chi.Router) {
				r.Use(h.requireAdmin)
				r.Post("/", h.handleCreateCamera)
			})
			r.Route("/{id}", func(r chi.Router) {
				r.Get("/", h.handleGetCamera)
				r.Group(func(r chi.Router) {
					r.Use(h.requireAdmin)
					r.Put("/", h.handleUpdateCamera)
					r.Delete("/", h.handleDeleteCamera)
				})
			r.Get("/stream/*", h.handleHLSStream)
			r.Delete("/stream", h.handleStopHLSStream)
				r.Get("/onvif/profiles", h.handleONVIFCameraProfiles)
				r.Post("/ptz/move", h.handlePTZMove)
				r.Post("/ptz/stop", h.handlePTZStop)
				r.Get("/ptz/status", h.handlePTZStatus)
				r.Get("/snapshot", h.handleSnapshot)
				r.Put("/merge-config", h.handleUpdateCameraMergeConfig)
				r.Delete("/merge-config", h.handleDeleteCameraMergeConfig)
			})
		})
		r.Get("/api/stats", h.handleStats)
		r.Get("/api/stats/system", h.handleSystemStats)
		r.Get("/api/stats/trends", h.handleStatsTrends)
		r.Get("/api/settings", h.handleGetSettings)
		r.With(h.requireAdmin).Put("/api/settings", h.handleUpdateSettings)
		r.With(h.requireAdmin).Post("/api/auth/change-password", h.handleChangePassword)
		r.Get("/api/settings/merge", h.handleGetMergeSettings)
		r.With(h.requireAdmin).Put("/api/settings/merge", h.handleUpdateMergeSettings)
		r.With(h.requireAdmin).Post("/api/backup", h.handleBackup)
		r.Get("/api/backups", h.handleListBackups)
		r.With(h.requireAdmin).Post("/api/onvif/discover", h.handleONVIFDiscover)
		r.Get("/api/onvif/discover/{ip}", h.handleONVIFDeviceDetail)
		r.Get("/api/merge/status", h.handleMergeStatus)
		r.Get("/api/merge/pending", h.handleMergePending)
		// User management (admin only)
		r.Route("/api/users", func(r chi.Router) {
			r.Use(h.requireAdmin)
			r.Get("/", h.handleListUsers)
			r.Post("/", h.handleCreateUser)
			r.Put("/{id}", h.handleUpdateUser)
			r.Delete("/{id}", h.handleDeleteUser)
		})
	})

	return r
}

// --- Public endpoints ---

func (h *Handler) handleHealth(w http.ResponseWriter, r *http.Request) {
	resp := HealthResponse{Checks: make(map[string]HealthCheck)}
	hasWarning, hasError := false, false

	// Database check
	if h.db != nil {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		err := h.db.DB().PingContext(ctx)
		if err != nil {
			resp.Checks["database"] = HealthCheck{Status: "error", Message: err.Error()}
			hasError = true
		} else {
			resp.Checks["database"] = HealthCheck{Status: "ok"}
		}
	} else {
		resp.Checks["database"] = HealthCheck{Status: "error", Message: "数据库未配置"}
		hasError = true
	}

	// Storage check
	if h.store != nil {
		total, used, err := h.store.GetDiskUsage()
		if err != nil {
			resp.Checks["storage"] = HealthCheck{Status: "error", Message: err.Error()}
			hasError = true
		} else {
			pct := 0
			if total > 0 {
				pct = int(float64(used) / float64(total) * 100)
			}
			msg := fmt.Sprintf("%d%% used (%d / %d bytes)", pct, used, total)
			if pct > 95 {
				resp.Checks["storage"] = HealthCheck{Status: "error", Message: msg}
				hasError = true
			} else if pct > 90 {
				resp.Checks["storage"] = HealthCheck{Status: "warning", Message: msg}
				hasWarning = true
			} else {
				resp.Checks["storage"] = HealthCheck{Status: "ok", Message: msg}
			}
		}
	} else {
		resp.Checks["storage"] = HealthCheck{Status: "error", Message: "存储未配置"}
		hasError = true
	}

	// Goroutine check
	numGoroutines := runtime.NumGoroutine()
	if numGoroutines > 1000 {
		resp.Checks["goroutines"] = HealthCheck{Status: "error", Message: fmt.Sprintf("%d goroutines (threshold: 1000)", numGoroutines)}
		hasError = true
	} else {
		resp.Checks["goroutines"] = HealthCheck{Status: "ok", Message: fmt.Sprintf("%d goroutines", numGoroutines)}
	}

	// Overall status
	switch {
	case hasError:
		resp.Status = "unhealthy"
	case hasWarning:
		resp.Status = "degraded"
	default:
		resp.Status = "ok"
	}

	// Uptime
	resp.Uptime = formatUptime(time.Since(appStartTime))

	writeJSON(w, http.StatusOK, resp)
		}

		func (h *Handler) handleReadyz(w http.ResponseWriter, r *http.Request) {
	checks := make(map[string]HealthCheck)

	// Database must be ok
	allOK := true
	if h.db == nil {
		checks["database"] = HealthCheck{Status: "error", Message: "数据库未配置"}
		allOK = false
	} else {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		if err := h.db.DB().PingContext(ctx); err != nil {
			checks["database"] = HealthCheck{Status: "error", Message: err.Error()}
			allOK = false
		} else {
			checks["database"] = HealthCheck{Status: "ok"}
		}
	}

	// Storage must be < 95%
	if h.store == nil {
		checks["storage"] = HealthCheck{Status: "error", Message: "存储未配置"}
		allOK = false
	} else {
		total, used, err := h.store.GetDiskUsage()
		if err != nil {
			checks["storage"] = HealthCheck{Status: "error", Message: err.Error()}
			allOK = false
		} else {
			pct := 0
			if total > 0 {
				pct = int(float64(used) / float64(total) * 100)
			}
			if pct >= 95 {
				checks["storage"] = HealthCheck{Status: "error", Message: fmt.Sprintf("%d%% used (threshold: 95%%)", pct)}
				allOK = false
			} else {
				checks["storage"] = HealthCheck{Status: "ok"}
			}
		}
	}

	// Goroutines must be < 5000
	numGoroutines := runtime.NumGoroutine()
	if numGoroutines >= 5000 {
		checks["goroutines"] = HealthCheck{Status: "error", Message: fmt.Sprintf("%d goroutines (threshold: 5000)", numGoroutines)}
		allOK = false
	} else {
		checks["goroutines"] = HealthCheck{Status: "ok"}
	}

	if allOK {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	} else {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"status": "not ready", "checks": checks})
	}
}

func (h *Handler) handleLogin(w http.ResponseWriter, r *http.Request) {
	// Parse JSON body from SPA login form (username/password in POST body)
	var body struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if r.Body != nil {
		json.NewDecoder(r.Body).Decode(&body)
	}
	if body.Username != "" {
		// Convert JSON credentials to Basic Auth for the middleware to validate
		r.SetBasicAuth(body.Username, body.Password)
	}

	// Validate credentials by running through the auth middleware.
	// If auth is disabled, any request succeeds; otherwise BasicAuth is checked.
	done := make(chan *http.Request, 1)

	inner := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		done <- r
	})
	rec := &middleware.StatusRecorder{ResponseWriter: w, Status: http.StatusOK}
	h.authMW(inner).ServeHTTP(rec, r)

	select {
	case req := <-done:
		if rec.Status == http.StatusOK {
			forcePasswordChange := false
			// Role is set in request context by the auth middleware (from DB user's role)
			role := middleware.GetRole(req)
			if role == "" {
				role = "admin"
			}
			writeJSON(w, http.StatusOK, map[string]any{
				"status":               "ok",
				"force_password_change": forcePasswordChange,
				"role":                 role,
			})
		}
	default:
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]string{"error": "用户名或密码错误"})
	}
}


// --- Authentication (requires auth) ---

func (h *Handler) handleChangePassword(w http.ResponseWriter, r *http.Request) {
	var body struct {
		OldPassword string `json:"old_password"`
		NewPassword string `json:"new_password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "无效的请求体")
		return
	}

	if body.OldPassword == "" || body.NewPassword == "" {
		writeError(w, http.StatusBadRequest, "旧密码和新密码不能为空")
		return
	}

	if len(body.NewPassword) < 6 {
		writeError(w, http.StatusBadRequest, "新密码至少需要 6 个字符")
		return
	}

	if h.db == nil {
		writeError(w, http.StatusInternalServerError, "数据库不可用")
		return
	}

	// Get current user from request context (set by auth middleware)
	username := middleware.GetUsername(r)
	if username == "" {
		writeError(w, http.StatusUnauthorized, "未登录")
		return
	}

	// Look up the user in the database
	dbUser, err := h.db.GetUserByUsername(r.Context(), username)
	if err != nil || dbUser == nil {
		writeError(w, http.StatusForbidden, "用户不存在")
		return
	}

	if !middleware.CheckPassword(body.OldPassword, dbUser.PasswordHash) {
		writeError(w, http.StatusForbidden, "旧密码错误")
		return
	}

	hash, err := middleware.HashPassword(body.NewPassword)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "密码加密失败")
		return
	}

	// Update password in the database
	if err := h.db.UpdateUser(r.Context(), dbUser.ID, dbUser.Username, hash, dbUser.Role); err != nil {
		logger.Warn("failed to update password in db", "error", err)
		writeError(w, http.StatusInternalServerError, "密码更新失败")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) handleListRecordings(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	filter := model.RecordingFilter{
		CameraID: r.URL.Query().Get("camera_id"),
		Format:   model.Format(r.URL.Query().Get("format")),
	}

	if v := r.URL.Query().Get("merged"); v != "" {
		merged := v == "true" || v == "1"
		filter.Merged = &merged
	}

	if v := r.URL.Query().Get("start"); v != "" {
		if t, err := time.Parse(time.RFC3339, v); err == nil {
			filter.StartTime = t
		}
	}

	if v := r.URL.Query().Get("end"); v != "" {
		if t, err := time.Parse(time.RFC3339, v); err == nil {
			filter.EndTime = t
		}
	}

	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			filter.Limit = n
		}
	}

	if v := r.URL.Query().Get("offset"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 0 {
			filter.Offset = n
		}
	}

	// Sorting
	filter.SortBy = r.URL.Query().Get("sort_by")
	filter.SortOrder = r.URL.Query().Get("order")

	filter.Search = r.URL.Query().Get("search")

	recordings, err := h.db.ListRecordings(ctx, filter)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "获取录像列表失败")
		return
	}

	if recordings == nil {
		recordings = []model.Recording{}
	}

	total, err := h.db.CountRecordingsWithFilter(ctx, filter)
	if err != nil {
		total = 0 // non-fatal
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"recordings": recordings,
		"total":     total,
	})
}

func (h *Handler) handleGetRecording(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	rec, err := h.db.GetRecording(r.Context(), id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "获取录像失败")
		return
	}
	if rec == nil {
		writeError(w, http.StatusNotFound, "录像未找到")
		return
	}
	writeJSON(w, http.StatusOK, rec)
}

func (h *Handler) handleDeleteRecording(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	ctx := r.Context()

	rec, err := h.db.GetRecording(ctx, id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "获取录像失败")
		return
	}
	if rec == nil {
		writeError(w, http.StatusNotFound, "录像未找到")
		return
	}

	// Delete from DB first (authoritative source)
	if err := h.db.DeleteRecording(ctx, id); err != nil {
		writeError(w, http.StatusInternalServerError, "删除录像失败")
		return
	}

	// Then delete file (non-fatal if fails)
	if rec.FilePath != "" {
		if err := h.store.DeleteFile(rec.FilePath); err != nil {
			logger.Warn("failed to delete file", "file_path", rec.FilePath, "error", err)
		}
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
}

func (h *Handler) handleBatchDeleteRecordings(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	var body struct {
		IDs []string `json:"ids"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "无效的请求体")
		return
	}
	if len(body.IDs) == 0 {
		writeError(w, http.StatusBadRequest, "ID列表不能为空")
		return
	}
	if len(body.IDs) > 100 {
		writeError(w, http.StatusBadRequest, "ID列表不能超过100个")
		return
	}
	// Fetch file paths before batch delete
	filePaths := map[string]string{}
	for _, id := range body.IDs {
		rec, err := h.db.GetRecording(ctx, id)
		if err == nil && rec != nil && rec.FilePath != "" {
			filePaths[id] = rec.FilePath
		}
	}

	// Delete DB records (transaction)
	deleted, err := h.db.DeleteRecordingsBatch(ctx, body.IDs)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "批量删除录像失败")
		return
	}

	// Attempt file deletion for successfully deleted records (non-fatal)
	failed := []string{}
	deletedSet := make(map[string]bool, len(deleted))
	for _, id := range deleted {
		deletedSet[id] = true
		if fp, ok := filePaths[id]; ok {
			if err := h.store.DeleteFile(fp); err != nil {
				logger.Warn("batch delete: failed to delete file", "file_path", fp, "error", err)
			}
		}
	}
	for _, id := range body.IDs {
		if !deletedSet[id] {
			failed = append(failed, id)
		}
	}

	result := map[string]any{"deleted": deleted}
	if len(failed) > 0 {
		result["failed"] = failed
	} else {
		result["failed"] = []string{}
	}
	writeJSON(w, http.StatusOK, result)
}


func (h *Handler) handleDownloadRecording(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	rec, err := h.db.GetRecording(r.Context(), id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "获取录像失败")
		return
	}
	if rec == nil {
		writeError(w, http.StatusNotFound, "录像未找到")
		return
	}

	if rec.FilePath == "" {
		writeError(w, http.StatusNotFound, "文件不可用")
		return
	}

	// Check for frame parameter (MJPEG frame download)
	frameStr := r.URL.Query().Get("frame")
	if frameStr != "" && rec.Format == model.FormatMJPEG {
		frameIndex, err := strconv.Atoi(frameStr)
		if err == nil {
			entries, err := os.ReadDir(rec.FilePath)
			if err == nil {
				jpgFiles := []os.DirEntry{}
				for _, e := range entries {
					if !e.IsDir() && isImageFile(e.Name()) {
						jpgFiles = append(jpgFiles, e)
					}
				}
				sort.Slice(jpgFiles, func(i, j int) bool { return jpgFiles[i].Name() < jpgFiles[j].Name() })
				if frameIndex >= 0 && frameIndex < len(jpgFiles) {
					framePath := filepath.Join(rec.FilePath, jpgFiles[frameIndex].Name())
					http.ServeFile(w, r, framePath)
					return
				}
			}
		}
		http.Error(w, "帧未找到", http.StatusNotFound)
		return
	}

	filePath := rec.FilePath
	info, err := os.Stat(filePath)
	if err != nil {
		writeError(w, http.StatusNotFound, "文件未找到")
		return
	}
	if info.IsDir() {
		entries, err := os.ReadDir(filePath)
		if err != nil || len(entries) == 0 {
			writeError(w, http.StatusNotFound, "录像目录中没有文件")
			return
		}
		for _, e := range entries {
			if e.IsDir() {
				continue
			}
			name := e.Name()
			if strings.HasSuffix(name, ".jpg") || strings.HasSuffix(name, ".jpeg") || strings.HasSuffix(name, ".mp4") {
				filePath = filepath.Join(filePath, name)
				break
			}
		}
	}

	w.Header().Set("Content-Disposition", fmt.Sprintf("inline; filename=\"%s\"", filepath.Base(filePath)))
	http.ServeFile(w, r, filePath)
}

func (h *Handler) handleListFrames(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	rec, err := h.db.GetRecording(r.Context(), id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "获取录像失败")
		return
	}
	if rec == nil {
		writeError(w, http.StatusNotFound, "录像未找到")
		return
	}

	if rec.Format != "mjpeg" {
		writeError(w, http.StatusBadRequest, "不是JPEG录像")
		return
	}

	filePath := rec.FilePath
	info, err := os.Stat(filePath)
	if err != nil {
		writeError(w, http.StatusNotFound, "录像文件未找到")
		return
	}
	if !info.IsDir() {
		writeError(w, http.StatusNotFound, "录像不是目录格式")
		return
	}

	entries, err := os.ReadDir(filePath)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "读取录像目录失败")
		return
	}

	type FrameInfo struct {
		Index    int    `json:"index"`
		Filename string `json:"filename"`
		Size     int64  `json:"size"`
	}

	var frames []FrameInfo
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		if !strings.HasSuffix(strings.ToLower(name), ".jpg") && !strings.HasSuffix(strings.ToLower(name), ".jpeg") {
			continue
		}
		fi, err := e.Info()
		if err != nil {
			continue
		}
		frames = append(frames, FrameInfo{
			Filename: name,
			Size:     fi.Size(),
		})
	}

	// Sort by filename (natural order - timestamp-based names sort correctly)
	sort.Slice(frames, func(i, j int) bool {
		return frames[i].Filename < frames[j].Filename
	})

	// Assign sequential indices
	for i := range frames {
		frames[i].Index = i
	}

	if frames == nil {
		frames = []FrameInfo{}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"frames": frames,
	})
}

// --- Camera and stats endpoints ---

// cameraRowForAPI normalizes camera rows for API responses.
// For ONVIF cameras, it exposes onvif_endpoint as url so the frontend
// can use a single url field for all protocols.
func cameraRowForAPI(row *storage.CameraRow) {
	if row.Protocol == "onvif" && row.URL == "" && row.ONVIFEndpoint != "" {
		row.URL = row.ONVIFEndpoint
	}
}

func (h *Handler) handleListCameras(w http.ResponseWriter, r *http.Request) {
	cameras, err := h.db.ListCameras(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "获取设备列表失败")
		return
	}
	if cameras == nil {
		cameras = []storage.CameraRow{}
	}
	// Inject recorder status from CameraManager
	if h.camMgr != nil {
		statusMap := h.camMgr.Status()
		for i := range cameras {
			if s, ok := statusMap[cameras[i].ID]; ok {
				cameras[i].Status = s
			} else {
				cameras[i].Status = model.StatusStopped
			}
		}
	// Inject last_seen from DB
	lastSeenMap, err := h.db.GetAllLastRecordingTimes(r.Context())
	if err == nil {
		for i := range cameras {
			if t, ok := lastSeenMap[cameras[i].ID]; ok {
				cameras[i].LastSeen = t
			}
		}
	}
	}
	// For ONVIF cameras, show onvif_endpoint as url for unified frontend handling
	for i := range cameras {
		cameraRowForAPI(&cameras[i])
	}
	writeJSON(w, http.StatusOK, cameras)
}


func (h *Handler) handleStats(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	total, used, err := h.store.GetDiskUsage()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "获取磁盘使用量失败")
		return
	}

	count, err := h.db.CountRecordings(ctx)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "统计录像数量失败")
		return
	}

	cameras, err := h.db.ListCameras(ctx)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "统计设备数量失败")
		return
	}

	stats := model.StorageStats{
		TotalBytes:     total,
		UsedBytes:      used,
		RecordingCount: count,
		CameraCount:    len(cameras),
	}
	writeJSON(w, http.StatusOK, stats)
}

func (h *Handler) handleStatsTrends(w http.ResponseWriter, r *http.Request) {
	days := 7
	if v := r.URL.Query().Get("days"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 1 && n <= 30 {
			days = n
		}
	}
	trends, err := h.db.GetRecordingTrends(r.Context(), days)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "获取录像趋势失败")
		return
	}
	writeJSON(w, http.StatusOK, trends)
}

// --- Camera CRUD endpoints ---

var validProtocols = map[string]bool{
	// New transport-only protocols
	"rtsp":  true,
	"http":  true,
	"onvif": true,
	// Legacy combined protocols (accepted, will be normalized)
	"rtsp_h264":  true,
	"rtsp_h265":  true,
	"rtsp_mjpeg": true,
	"http_jpeg":  true,
}

func (h *Handler) handleCreateCamera(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name         string  `json:"name"`
		Protocol     string  `json:"protocol"`
		URL          string  `json:"url"`
		Username     string  `json:"username"`
		Password     string  `json:"password"`
		Enabled      *bool   `json:"enabled"`
		Description  string  `json:"description"`
		Location     string  `json:"location"`
		Brand        string  `json:"brand"`
		Model        string  `json:"model"`
		SerialNumber string  `json:"serial_number"`
		ONVIFEndpoint  string  `json:"onvif_endpoint"`
		ProfileToken   string  `json:"profile_token"`
		StreamEncoding string  `json:"stream_encoding"`
		Encoding        string  `json:"encoding"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "无效的请求体")
		return
	}
	if body.Name == "" {
		writeError(w, http.StatusBadRequest, "名称必填")
		return
	}
	if body.Protocol == "" {
		writeError(w, http.StatusBadRequest, "协议必填")
		return
	}
	if !validProtocols[body.Protocol] {
		writeError(w, http.StatusBadRequest, fmt.Sprintf("无效的协议 %q，必须是 rtsp、http 或 onvif 之一", body.Protocol))
		return
	}
	// ONVIF cameras: accept url OR onvif_endpoint
	if body.Protocol == "onvif" {
		endpoint := body.ONVIFEndpoint
		if endpoint == "" {
			endpoint = body.URL
		}
		if endpoint == "" {
			writeError(w, http.StatusBadRequest, "ONVIF设备需要提供url或onvif_endpoint")
			return
		}
		body.ONVIFEndpoint = endpoint
		body.URL = "" // Don't store in url field for ONVIF
		// Check for duplicate ONVIF endpoint
		if h.db != nil {
			existingCams, _ := h.db.ListCameras(r.Context())
			for _, ec := range existingCams {
				if ec.Protocol == "onvif" && ec.ONVIFEndpoint == body.ONVIFEndpoint {
					writeError(w, http.StatusConflict, "该ONVIF端点已被其他设备使用")
					return
				}
			}
		}
	} else if body.URL == "" {
		writeError(w, http.StatusBadRequest, "URL必填")
		return
	}
	// Normalize protocol — handle legacy combined formats
	proto := body.Protocol
	enc := body.Encoding
	if strings.Contains(proto, "_") {
		parsedProto, parsedEnc, err := model.ParseLegacyProtocol(proto)
		if err != nil {
			writeError(w, http.StatusBadRequest, fmt.Sprintf("无效的协议 %q", proto))
			return
		}
		proto = parsedProto
		if enc == "" {
			enc = parsedEnc
		}
	}
	// Set default encoding if still empty
	if enc == "" {
		switch proto {
		case "rtsp":
			enc = "h264"
		case "http":
			enc = "jpeg"
		}
	}

	cam := config.CameraConfig{
    Name:          body.Name,
    Protocol:      proto,
    Encoding:      enc,
    URL:           body.URL,
    Username:      body.Username,
    Password:      body.Password,
    ONVIFEndpoint:  body.ONVIFEndpoint,
    ProfileToken:   body.ProfileToken,
    StreamEncoding: body.StreamEncoding,
  }
	if body.Enabled != nil {
		cam.Enabled = *body.Enabled
	} else {
		cam.Enabled = true
	}

	if h.camMgr == nil {
		writeError(w, http.StatusInternalServerError, "设备管理器不可用")
		return
	}
	id, err := h.camMgr.AddCamera(r.Context(), cam)
	if err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Sprintf("添加设备失败: %v", err))
		return
	}
	// Persist DB-only metadata fields
	if body.Description != "" || body.Location != "" || body.Brand != "" || body.Model != "" || body.SerialNumber != "" {
		if err := h.db.UpdateCameraMetadata(r.Context(), id, body.Description, body.Location, body.Brand, body.Model, body.SerialNumber, 0); err != nil {
			logger.Warn("failed to set camera metadata", "camera_id", id, "error", err)
		}
	}
	// Return CameraRow with status
	row, err := h.db.GetCamera(r.Context(), id)
	if row != nil {
		if h.camMgr != nil {
			row.Status = h.camMgr.CameraStatus(id)
		}
		// Inject last_seen from DB
		lastSeen, err := h.db.GetLastRecordingTime(r.Context(), id)
		if err == nil {
			row.LastSeen = lastSeen
		}
		cameraRowForAPI(row)
		writeJSON(w, http.StatusCreated, row)
	} else {
		cam.ID = id
		writeJSON(w, http.StatusCreated, cam)
	}
}

func (h *Handler) handleGetCamera(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	row, err := h.db.GetCamera(r.Context(), id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "获取设备失败")
		return
	}
	if row == nil {
		writeError(w, http.StatusNotFound, "设备未找到")
		return
	}
	// Inject recorder status
	if h.camMgr != nil {
		row.Status = h.camMgr.CameraStatus(id)
	}
	// Inject last_seen from DB
	lastSeen, err := h.db.GetLastRecordingTime(r.Context(), id)
	if err == nil {
		row.LastSeen = lastSeen
	}
	cameraRowForAPI(row)
	writeJSON(w, http.StatusOK, row)
}

func (h *Handler) handleUpdateCamera(w http.ResponseWriter, r *http.Request) {
	if h.camMgr == nil {
		writeError(w, http.StatusInternalServerError, "设备管理器不可用")
		return
	}
	id := chi.URLParam(r, "id")

	var body struct {
		Name          *string `json:"name"`
		URL           *string `json:"url"`
		Protocol      *string `json:"protocol"`
		Encoding      *string `json:"encoding"`
		Username      *string `json:"username"`
		Password      *string `json:"password"`
		Enabled       *bool   `json:"enabled"`
		Description   *string `json:"description"`
		Location      *string `json:"location"`
		Brand         *string `json:"brand"`
		Model         *string `json:"model"`
		SerialNumber  *string `json:"serial_number"`
		RetentionDays *int    `json:"retention_days"`
		ONVIFEndpoint  *string `json:"onvif_endpoint"`
		ProfileToken   *string `json:"profile_token"`
		StreamEncoding *string `json:"stream_encoding"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "无效的请求体")
		return
	}

	// Harden credential updates: empty string from frontend means "don't update"
	username := body.Username
	if username != nil && *username == "" {
		username = nil
	}
	password := body.Password
	if password != nil && *password == "" {
		password = nil
	}

	updates := camera.CameraUpdate{
		Name:          body.Name,
		URL:           body.URL,
		Protocol:      body.Protocol,
		Encoding:      body.Encoding,
		Username:      username,
		Password:      password,
		Enabled:       body.Enabled,
		Description:   body.Description,
		Location:      body.Location,
		Brand:         body.Brand,
		Model:         body.Model,
		SerialNumber:  body.SerialNumber,
		RetentionDays: body.RetentionDays,
		ONVIFEndpoint:  body.ONVIFEndpoint,
		ProfileToken:   body.ProfileToken,
		StreamEncoding: body.StreamEncoding,
	}

	// For ONVIF cameras, sync url and onvif_endpoint
	if body.Protocol != nil && *body.Protocol == "onvif" {
		if updates.URL != nil && *updates.URL != "" {
			updates.ONVIFEndpoint = updates.URL
			updates.URL = nil
		}
		if updates.ONVIFEndpoint != nil && *updates.ONVIFEndpoint != "" {
			updates.URL = updates.ONVIFEndpoint
		}
	}

	_, err := h.camMgr.UpdateCamera(r.Context(), id, updates)
	if err != nil {
		if strings.Contains(err.Error(), "not found") {
			writeError(w, http.StatusNotFound, "设备未找到")
			return
		}
		writeError(w, http.StatusInternalServerError, fmt.Sprintf("更新设备失败: %v", err))
		return
	}
	// Return updated CameraRow with status
	row, err := h.db.GetCamera(r.Context(), id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "获取设备失败")
		return
	}
	if row != nil {
		if h.camMgr != nil {
			row.Status = h.camMgr.CameraStatus(id)
		}
		// Inject last_seen from DB
		lastSeen, err := h.db.GetLastRecordingTime(r.Context(), id)
		if err == nil {
			row.LastSeen = lastSeen
		}
		cameraRowForAPI(row)
		writeJSON(w, http.StatusOK, row)
	} else {
		writeJSON(w, http.StatusOK, map[string]string{"status": "updated"})
	}
}

func (h *Handler) handleDeleteCamera(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	ctx := r.Context()

	// Try removing from camera manager (handles config + recorder)
	// This may fail for orphaned DB-only cameras, which is expected.
	removedFromConfig := true
	if h.camMgr != nil {
		if err := h.camMgr.RemoveCamera(ctx, id); err != nil {
			removedFromConfig = false
		}
	}

	// Always delete from DB to handle both "camera in config" and "camera only in DB" cases.
	dbErr := h.db.DeleteCamera(ctx, id)
	if !removedFromConfig && dbErr != nil {
		writeError(w, http.StatusNotFound, "设备未找到")
		return
	}
	if dbErr != nil {
		logger.Warn("failed to delete camera from DB", "camera_id", id, "error", dbErr)
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
}


// --- ONVIF camera management endpoints ---

func (h *Handler) handleONVIFCameraProfiles(w http.ResponseWriter, r *http.Request) {
	cameraID := chi.URLParam(r, "id")
	if cameraID == "" {
		writeError(w, http.StatusBadRequest, "设备ID必填")
		return
	}

	// For now, return empty profiles (actual implementation needs ONVIF client)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"profiles":     []interface{}{},
		"capabilities": map[string]bool{"ptz": false, "streaming": false},
	})
}

// --- ONVIF discovery endpoints ---

func (h *Handler) handleONVIFDiscover(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Timeout int `json:"timeout"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		req.Timeout = 5
	}
	if req.Timeout <= 0 {
		req.Timeout = 5
	}
	if req.Timeout > 30 {
		writeError(w, http.StatusBadRequest, "超时时间必须在1到30秒之间")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), time.Duration(req.Timeout)*time.Second)
	defer cancel()

	devices, err := onvif.Discover(ctx, time.Duration(req.Timeout)*time.Second)
	if err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Sprintf("设备发现失败: %v", err))
		return
	}
	if devices == nil {
		devices = []onvif.DiscoveredDevice{}
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"devices": devices})
}

func (h *Handler) handleONVIFDeviceDetail(w http.ResponseWriter, r *http.Request) {
	ip := chi.URLParam(r, "ip")
	if ip == "" {
		writeError(w, http.StatusBadRequest, "IP地址必填")
		return
	}
	ctx := r.Context()
	client := onvif.NewClient(fmt.Sprintf("http://%s/onvif/device_service", ip), "", "")
	if err := client.Connect(ctx); err != nil {
		writeError(w, http.StatusBadGateway, fmt.Sprintf("连接设备失败: %v", err))
		return
	}
	info, err := client.GetDeviceInformation(ctx)
	if err != nil {
		writeError(w, http.StatusBadGateway, fmt.Sprintf("获取设备信息失败: %v", err))
		return
	}
	profiles, err := client.GetProfiles(ctx)
	if err != nil {
		profiles = nil
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"device_info": info,
		"profiles":    profiles,
	})
}

func (h *Handler) requireONVIF(w http.ResponseWriter, r *http.Request) bool {
	if h.db == nil {
		writeError(w, http.StatusNotFound, "设备未找到")
		return false
	}
	cameraID := chi.URLParam(r, "id")
	camera, err := h.db.GetCamera(r.Context(), cameraID)
	if err != nil || camera == nil {
		writeError(w, http.StatusNotFound, "设备未找到")
		return false
	}
	if camera.Protocol != "onvif" {
		writeError(w, http.StatusBadRequest, "PTZ控制仅适用于ONVIF设备")
		return false
	}
	return true
}

// --- PTZ control endpoints ---

func (h *Handler) handlePTZMove(w http.ResponseWriter, r *http.Request) {
	cameraID := chi.URLParam(r, "id")
	var req struct {
		Mode  string  `json:"mode"`
		Pan   float64 `json:"pan"`
		Tilt  float64 `json:"tilt"`
		Zoom  float64 `json:"zoom"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "无效的请求体")
		return
	}
	if req.Mode != "continuous" && req.Mode != "absolute" && req.Mode != "relative" {
		writeError(w, http.StatusBadRequest, "模式必须为 continuous、absolute 或 relative")
		return
	}
	if !h.requireONVIF(w, r) {
		return
	}
	if h.camMgr == nil {
		writeError(w, http.StatusInternalServerError, "设备管理器不可用")
		return
	}
	ptz, err := h.camMgr.GetONVIFPTZController(r.Context(), cameraID)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	vec := onvif.PTZVector{Pan: req.Pan, Tilt: req.Tilt, Zoom: req.Zoom}
	switch req.Mode {
	case "continuous":
		err = ptz.ContinuousMove(r.Context(), vec)
	case "absolute":
		err = ptz.AbsoluteMove(r.Context(), vec)
	case "relative":
		err = ptz.RelativeMove(r.Context(), vec)
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Sprintf("PTZ命令失败: %v", err))
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) handlePTZStop(w http.ResponseWriter, r *http.Request) {
	cameraID := chi.URLParam(r, "id")
	if !h.requireONVIF(w, r) {
		return
	}
	if h.camMgr == nil {
		writeError(w, http.StatusInternalServerError, "设备管理器不可用")
		return
	}
	ptz, err := h.camMgr.GetONVIFPTZController(r.Context(), cameraID)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if err := ptz.Stop(r.Context(), true, true); err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Sprintf("PTZ停止失败: %v", err))
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "stopped"})
}

func (h *Handler) handlePTZStatus(w http.ResponseWriter, r *http.Request) {
	cameraID := chi.URLParam(r, "id")
	if !h.requireONVIF(w, r) {
		return
	}
	if h.camMgr == nil {
		writeError(w, http.StatusInternalServerError, "设备管理器不可用")
		return
	}
	ptz, err := h.camMgr.GetONVIFPTZController(r.Context(), cameraID)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	pos, moving, err := ptz.GetStatus(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Sprintf("获取PTZ状态失败: %v", err))
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"pan":    pos.Pan,
		"tilt":   pos.Tilt,
		"zoom":   pos.Zoom,
		"moving": moving,
	})
}

// --- Snapshot endpoint ---

func (h *Handler) handleSnapshot(w http.ResponseWriter, r *http.Request) {
	cameraID := chi.URLParam(r, "id")

	// Find camera config to get SnapshotURL
	var snapshotURL string
	if h.config != nil {
		for _, cam := range h.config.Cameras {
			if cam.ID == cameraID {
				snapshotURL = cam.SnapshotURL
				break
			}
		}
	}
	if snapshotURL == "" {
		http.Error(w, "未配置快照URL", http.StatusNotFound)
		return
	}

	// Check cache (10 second TTL)
	const cacheTTL = 10 * time.Second
	h.snapshotMu.RLock()
	cached, ok := h.snapshots[cameraID]
	h.snapshotMu.RUnlock()

	if ok && time.Since(cached.timestamp) < cacheTTL {
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", "max-age=5")
		w.Write(cached.data)
		return
	}

	// Fetch from camera
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Get(snapshotURL)
	if err != nil {
		// Return stale cache if available
		if ok {
			w.Header().Set("Content-Type", "image/jpeg")
			w.Header().Set("X-Cache", "stale")
			w.Write(cached.data)
			return
		}
		logger.Warn("failed to fetch snapshot", "camera_id", cameraID, "error", err)
		http.Error(w, "获取快照失败", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		http.Error(w, "设备返回了错误", http.StatusBadGateway)
		return
	}

	data, err := io.ReadAll(io.LimitReader(resp.Body, 10*1024*1024)) // 10MB max
	if err != nil || len(data) == 0 {
		http.Error(w, "读取快照失败", http.StatusBadGateway)
		return
	}

	// Update cache
	h.snapshotMu.Lock()
	h.snapshots[cameraID] = &snapshotCache{data: data, timestamp: time.Now()}
	h.snapshotMu.Unlock()

	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "max-age=5")
	w.Write(data)
}

// --- Helpers ---

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

// isImageFile checks if a filename has an image extension (jpg/jpeg/png).
func isImageFile(name string) bool {
	lower := strings.ToLower(name)
	return strings.HasSuffix(lower, ".jpg") || strings.HasSuffix(lower, ".jpeg") || strings.HasSuffix(lower, ".png")
}

// noopAuthMW is a middleware that passes all requests through (no auth).
func noopAuthMW() func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return next
	}
}

// noopHandler is a helper for creating a Handler without real auth.
func noopHandler(db *storage.DB, store *storage.Manager) *Handler {
	return NewHandler(db, store, noopAuthMW(), nil, nil, nil, "", nil)
}
// --- Test helper exported for handler_test.go ---

// TestHandler creates a Handler with a no-op auth middleware for testing.
func TestHandler(db *storage.DB, store *storage.Manager) *Handler {
	return noopHandler(db, store)
}

// TestHandlerWithAuth creates a Handler with real auth middleware for testing.
func TestHandlerWithAuth(db *storage.DB, store *storage.Manager, username, passwordHash string) *Handler {
	authMW, _ := middleware.NewAuthMiddleware(username, passwordHash, "")
	return NewHandler(db, store, authMW, nil, nil, nil, "", nil)
}

// requireAdmin is a middleware that rejects non-admin users.
func (h *Handler) requireAdmin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Check role from request context (set by NewAuthMiddlewareWithDB)
		role := middleware.GetRole(r)
		// When role is empty (backward compat for tests), allow through
		if role == "" || role == "admin" {
			next.ServeHTTP(w, r)
			return
		}
		writeError(w, http.StatusForbidden, "无权限，仅管理员可执行此操作")
	})
}

// --- HLS streaming endpoints ---

func (h *Handler) handleHLSStream(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	if h.hlsMgr == nil || h.camMgr == nil {
		writeError(w, http.StatusInternalServerError, "HLS功能不可用")
		return
	}

	// Get camera to check protocol
	cam, err := h.db.GetCamera(r.Context(), id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "获取设备失败")
		return
	}
	if cam == nil {
		writeError(w, http.StatusNotFound, "设备未找到")
		return
	}

	// Only H.264/H.265/ONVIF cameras support HLS
	if cam.Protocol != string(model.ProtoRTSP) && cam.Protocol != string(model.ProtoONVIF) {
		writeError(w, http.StatusBadRequest, "该设备协议不支持HLS直播")
		return
	}

	// If stream not active, start it
	if !h.hlsMgr.IsActive(id) {
		rec := h.camMgr.GetRecorder(id)
		if rec == nil {
			writeError(w, http.StatusBadRequest, "设备录像器未运行")
			return
		}

		// Get camera config for HLS options
		camCfg := h.camMgr.GetCameraConfig(id)
		hlsMaxFPS := 0
		if camCfg != nil {
			hlsMaxFPS = camCfg.HLSMaxFPS
		}

		// Try H264 recorder first
		if h264Rec, ok := rec.(*recorder.H264Recorder); ok {
			sps := h264Rec.SPS()
			pps := h264Rec.PPS()
			if sps == nil || pps == nil {
				writeError(w, http.StatusServiceUnavailable, "SPS/PPS尚未就绪，等待视频流中")
				return
			}

			err := h.hlsMgr.StartStream(id, sps, pps, hlsMaxFPS)
			if err != nil {
				if err == hls.ErrMaxStreamsReached {
					writeError(w, http.StatusServiceUnavailable, "达到HLS最大流数量限制")
				} else {
					logger.Error("启动HLS流失败", "camera_id", id, "error", err)
					writeError(w, http.StatusInternalServerError, "启动HLS流失败")
				}
				return
			}

			// Check if sub-stream URL is configured
			if camCfg != nil && camCfg.SubStreamURL != "" {
				if subErr := h.hlsMgr.StartSubStreamReader(id, camCfg.SubStreamURL, false); subErr != nil {
					logger.Warn("failed to start HLS sub-stream reader, falling back to main stream", "camera_id", id, "error", subErr)
					// Fall back to main stream OnHLSFrame
					h264Rec.OnHLSFrame = func(pts int64, au [][]byte) {
						_ = h.hlsMgr.WriteH264(id, pts, au)
					}
				}
				// Sub-stream reader is running — do NOT set OnHLSFrame on recorder
			} else {
				h264Rec.OnHLSFrame = func(pts int64, au [][]byte) {
					_ = h.hlsMgr.WriteH264(id, pts, au)
				}
			}
		} else if h265Rec, ok := rec.(*recorder.H265Recorder); ok {
			vps := h265Rec.VPS()
			sps := h265Rec.SPS()
			pps := h265Rec.PPS()
			if vps == nil || sps == nil || pps == nil {
				writeError(w, http.StatusServiceUnavailable, "VPS/SPS/PPS尚未就绪，等待视频流中")
				return
			}

			err := h.hlsMgr.StartStreamH265(id, vps, sps, pps, hlsMaxFPS)
			if err != nil {
				if err == hls.ErrMaxStreamsReached {
					writeError(w, http.StatusServiceUnavailable, "达到HLS最大流数量限制")
				} else {
					logger.Error("failed to start HLS H265 stream", "camera_id", id, "error", err)
					writeError(w, http.StatusInternalServerError, "启动HLS流失败")
				}
				return
			}

			// Check if sub-stream URL is configured
			if camCfg != nil && camCfg.SubStreamURL != "" {
				if subErr := h.hlsMgr.StartSubStreamReader(id, camCfg.SubStreamURL, true); subErr != nil {
					logger.Warn("failed to start HLS sub-stream reader, falling back to main stream", "camera_id", id, "error", subErr)
					// Fall back to main stream OnHLSFrame
					h265Rec.OnHLSFrame = func(pts int64, au [][]byte) {
						_ = h.hlsMgr.WriteH265(id, pts, au)
					}
				}
			} else {
				h265Rec.OnHLSFrame = func(pts int64, au [][]byte) {
					_ = h.hlsMgr.WriteH265(id, pts, au)
				}
			}
		} else if onvifRec, ok := rec.(*recorder.ONVIFRecorder); ok {
			// ONVIF recorder delegates to H264/H265 internally
			delegate := onvifRec.Delegate()
			if delegate == nil {
				writeError(w, http.StatusServiceUnavailable, "ONVIF录像器委托尚未就绪")
				return
			}
			// Unwrap the delegate and handle as H264/H265
			if h264Rec, ok := delegate.(*recorder.H264Recorder); ok {
				sps := h264Rec.SPS()
				pps := h264Rec.PPS()
				if sps == nil || pps == nil {
					writeError(w, http.StatusServiceUnavailable, "SPS/PPS尚未就绪，等待视频流中")
					return
				}
				err := h.hlsMgr.StartStream(id, sps, pps, hlsMaxFPS)
				if err != nil {
					if err == hls.ErrMaxStreamsReached {
						writeError(w, http.StatusServiceUnavailable, "达到HLS最大流数量限制")
					} else {
						writeError(w, http.StatusInternalServerError, "启动HLS流失败")
					}
					return
				}
				h264Rec.OnHLSFrame = func(pts int64, au [][]byte) {
					_ = h.hlsMgr.WriteH264(id, pts, au)
				}
			} else if h265Rec, ok := delegate.(*recorder.H265Recorder); ok {
				vps := h265Rec.VPS()
				sps := h265Rec.SPS()
				pps := h265Rec.PPS()
				if vps == nil || sps == nil || pps == nil {
					writeError(w, http.StatusServiceUnavailable, "VPS/SPS/PPS尚未就绪，等待视频流中")
					return
				}
				err := h.hlsMgr.StartStreamH265(id, vps, sps, pps, hlsMaxFPS)
				if err != nil {
					if err == hls.ErrMaxStreamsReached {
						writeError(w, http.StatusServiceUnavailable, "达到HLS最大流数量限制")
					} else {
						writeError(w, http.StatusInternalServerError, "启动HLS流失败")
					}
					return
				}
				h265Rec.OnHLSFrame = func(pts int64, au [][]byte) {
					_ = h.hlsMgr.WriteH265(id, pts, au)
				}
			} else {
				writeError(w, http.StatusBadRequest, "ONVIF录像器委托类型不支持HLS")
				return
			}
		} else {
			writeError(w, http.StatusBadRequest, "设备录像器不支持HLS")
			return
		}
	}
	// Proxy to muxer handler
	if !h.hlsMgr.Handle(id, w, r) {
		writeError(w, http.StatusServiceUnavailable, "HLS流不可用")
		return
	}
}

func (h *Handler) handleStopHLSStream(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	if h.hlsMgr == nil {
		writeError(w, http.StatusInternalServerError, "HLS功能不可用")
		return
	}

	if !h.hlsMgr.IsActive(id) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "not active"})
		return
	}

	h.hlsMgr.StopStream(id)
	writeJSON(w, http.StatusOK, map[string]string{"status": "stopped"})
}

// --- Settings endpoints ---

func (h *Handler) handleGetSettings(w http.ResponseWriter, r *http.Request) {
	if h.config == nil {
		writeError(w, http.StatusInternalServerError, "配置不可用")
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"cleanup": map[string]any{
			"retention_days":         h.config.Cleanup.RetentionDays,
			"check_interval":         h.config.Cleanup.CheckInterval,
			"disk_threshold_percent": h.config.Cleanup.DiskThresholdPercent,
		},
		"webdav": map[string]any{
			"enabled":     h.config.WebDAV.Enabled != nil && *h.config.WebDAV.Enabled,
			"path_prefix": h.config.WebDAV.PathPrefix,
			"read_write":  h.config.WebDAV.ReadWrite,
		},
	})
}

func (h *Handler) handleUpdateSettings(w http.ResponseWriter, r *http.Request) {
	if h.config == nil {
		writeError(w, http.StatusInternalServerError, "配置不可用")
		return
	}

	var body struct {
		Cleanup *struct {
			RetentionDays        *int    `json:"retention_days"`
			DiskThresholdPercent *int    `json:"disk_threshold_percent"`
			CheckInterval        *string `json:"check_interval"`
		} `json:"cleanup"`
		WebDAV *struct {
			Enabled    *bool   `json:"enabled"`
			PathPrefix *string `json:"path_prefix"`
			ReadWrite  *bool   `json:"read_write"`
		} `json:"webdav"`
	}

	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "无效的请求体")
		return
	}

	// Update cleanup settings
	if body.Cleanup != nil {
		if body.Cleanup.RetentionDays != nil {
			if *body.Cleanup.RetentionDays < 1 {
				writeError(w, http.StatusBadRequest, "保留天数必须 >= 1")
				return
			}
			h.config.Cleanup.RetentionDays = *body.Cleanup.RetentionDays
		}
		if body.Cleanup.DiskThresholdPercent != nil {
			if *body.Cleanup.DiskThresholdPercent < 1 || *body.Cleanup.DiskThresholdPercent > 100 {
				writeError(w, http.StatusBadRequest, "磁盘阈值百分比必须在1到100之间")
				return
			}
			h.config.Cleanup.DiskThresholdPercent = *body.Cleanup.DiskThresholdPercent
		}
		if body.Cleanup.CheckInterval != nil {
			if _, err := time.ParseDuration(*body.Cleanup.CheckInterval); err != nil {
				writeError(w, http.StatusBadRequest, "检查间隔必须为有效的时间段（如 \"30m\"、\"1h\"）")
				return
			}
			h.config.Cleanup.CheckInterval = *body.Cleanup.CheckInterval
		}
	}

	// Update webdav settings
	if body.WebDAV != nil {
		if body.WebDAV.Enabled != nil {
			if h.config.WebDAV.Enabled == nil {
				h.config.WebDAV.Enabled = new(bool)
			}
			*h.config.WebDAV.Enabled = *body.WebDAV.Enabled
		}
		if body.WebDAV.PathPrefix != nil {
			h.config.WebDAV.PathPrefix = *body.WebDAV.PathPrefix
		}
		if body.WebDAV.ReadWrite != nil {
			h.config.WebDAV.ReadWrite = *body.WebDAV.ReadWrite
		}
	}

	// Persist config to disk
	if err := config.Save(h.configPath, h.config); err != nil {
		logger.Warn("failed to save config", "error", err)
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "updated"})
}

// --- Merge settings endpoints ---

func (h *Handler) handleGetMergeSettings(w http.ResponseWriter, r *http.Request) {
	if h.config == nil {
		writeError(w, http.StatusInternalServerError, "配置不可用")
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"enabled":              h.config.Merge.Enabled,
		"check_interval":        h.config.Merge.CheckInterval,
		"window_size":           h.config.Merge.WindowSize,
		"batch_limit":           h.config.Merge.BatchLimit,
		"min_segment_age":       h.config.Merge.MinSegmentAge,
		"min_segments_to_merge": h.config.Merge.MinSegmentsToMerge,
	})
}

func (h *Handler) handleUpdateMergeSettings(w http.ResponseWriter, r *http.Request) {
	if h.config == nil {
		writeError(w, http.StatusInternalServerError, "配置不可用")
		return
	}

	var body struct {
		Enabled            *bool   `json:"enabled"`
		CheckInterval      *string `json:"check_interval"`
		WindowSize         *string `json:"window_size"`
		BatchLimit         *int    `json:"batch_limit"`
		MinSegmentAge      *string `json:"min_segment_age"`
		MinSegmentsToMerge *int    `json:"min_segments_to_merge"`
	}

	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "无效的请求体")
		return
	}

	if body.Enabled != nil {
		h.config.Merge.Enabled = *body.Enabled
	}
	if body.CheckInterval != nil {
		if _, err := time.ParseDuration(*body.CheckInterval); err != nil {
			writeError(w, http.StatusBadRequest, "检查间隔必须为有效的时间段（如 \"30m\"、\"1h\"）")
			return
		}
		h.config.Merge.CheckInterval = *body.CheckInterval
	}
	if body.WindowSize != nil {
		if _, err := time.ParseDuration(*body.WindowSize); err != nil {
			writeError(w, http.StatusBadRequest, "时间窗口必须为有效的时间段（如 \"24h\"、\"48h\"）")
			return
		}
		h.config.Merge.WindowSize = *body.WindowSize
	}
	if body.BatchLimit != nil {
		if *body.BatchLimit < 1 {
			writeError(w, http.StatusBadRequest, "批处理限制必须 >= 1")
			return
		}
		h.config.Merge.BatchLimit = *body.BatchLimit
	}
	if body.MinSegmentAge != nil {
		if _, err := time.ParseDuration(*body.MinSegmentAge); err != nil {
			writeError(w, http.StatusBadRequest, "分段最小时长必须为有效的时间段（如 \"1h\"、\"6h\"）")
			return
		}
		h.config.Merge.MinSegmentAge = *body.MinSegmentAge
	}
	if body.MinSegmentsToMerge != nil {
		if *body.MinSegmentsToMerge < 1 {
			writeError(w, http.StatusBadRequest, "最小合并分段数必须 >= 1")
			return
		}
		h.config.Merge.MinSegmentsToMerge = *body.MinSegmentsToMerge
	}

	// Persist config to disk
	if err := config.Save(h.configPath, h.config); err != nil {
		logger.Warn("failed to save config", "error", err)
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "updated"})
}

func (h *Handler) handleUpdateCameraMergeConfig(w http.ResponseWriter, r *http.Request) {
	if h.db == nil {
		writeError(w, http.StatusInternalServerError, "数据库不可用")
		return
	}

	cameraID := chi.URLParam(r, "id")
	if cameraID == "" {
		writeError(w, http.StatusBadRequest, "设备ID必填")
		return
	}

	var body struct {
		Enabled            *bool   `json:"enabled"`
		CheckInterval      *string `json:"check_interval"`
		WindowSize         *string `json:"window_size"`
		BatchLimit         *int    `json:"batch_limit"`
		MinSegmentAge      *string `json:"min_segment_age"`
		MinSegmentsToMerge *int    `json:"min_segments_to_merge"`
	}

	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "无效的请求体")
		return
	}

	// Validate duration fields
	for _, d := range []*string{body.CheckInterval, body.WindowSize, body.MinSegmentAge} {
		if d != nil {
			if _, err := time.ParseDuration(*d); err != nil {
				writeError(w, http.StatusBadRequest, "持续时间字段必须为有效的时间段（如 \"30m\"、\"1h\"）")
				return
			}
		}
	}
	if body.BatchLimit != nil && *body.BatchLimit < 1 {
		writeError(w, http.StatusBadRequest, "批处理限制必须 >= 1")
		return
	}
	if body.MinSegmentsToMerge != nil && *body.MinSegmentsToMerge < 1 {
		writeError(w, http.StatusBadRequest, "最小合并分段数必须 >= 1")
		return
	}

	if err := h.db.UpsertCameraMerge(r.Context(), cameraID,
			body.Enabled, body.CheckInterval, body.WindowSize, body.MinSegmentAge,
			body.BatchLimit, body.MinSegmentsToMerge); err != nil {
		logger.Warn("failed to update camera merge config", "error", err, "camera_id", cameraID)
		writeError(w, http.StatusInternalServerError, "更新合并配置失败")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "updated"})
}

func (h *Handler) handleDeleteCameraMergeConfig(w http.ResponseWriter, r *http.Request) {
	if h.db == nil {
		writeError(w, http.StatusInternalServerError, "数据库不可用")
		return
	}

	cameraID := chi.URLParam(r, "id")
	if cameraID == "" {
		writeError(w, http.StatusBadRequest, "设备ID必填")
		return
	}

	// Pass all nil to clear (revert to global defaults)
	if err := h.db.UpsertCameraMerge(r.Context(), cameraID,
			nil, nil, nil, nil, nil, nil); err != nil {
		logger.Warn("failed to clear camera merge config", "error", err, "camera_id", cameraID)
		writeError(w, http.StatusInternalServerError, "清除合并配置失败")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "cleared"})
}

// --- Merge status endpoints ---

func (h *Handler) handleMergeStatus(w http.ResponseWriter, r *http.Request) {
	if h.mergeMgr == nil {
		writeJSON(w, http.StatusOK, map[string]any{
			"enabled": false,
		})
		return
	}
	status := h.mergeMgr.Status()
	writeJSON(w, http.StatusOK, map[string]any{
		"enabled":         true,
		"last_run_time":   status.LastRunTime,
		"segments_merged": status.SegmentsMerged,
		"files_created":   status.FilesCreated,
		"error_count":     status.ErrorCount,
	})
}

func (h *Handler) handleMergePending(w http.ResponseWriter, r *http.Request) {
	if h.mergeMgr == nil {
		writeJSON(w, http.StatusOK, map[string]any{
			"enabled": false,
			"pending": map[string]int{},
		})
		return
	}
	counts := h.mergeMgr.PendingCounts(r.Context())
	writeJSON(w, http.StatusOK, map[string]any{
		"enabled": true,
		"pending": counts,
	})
}


func (h *Handler) handleBackup(w http.ResponseWriter, r *http.Request) {
	if h.db == nil {
		writeError(w, http.StatusInternalServerError, "数据库不可用")
		return
	}
	backupDir := filepath.Join(filepath.Dir(h.configPath), "backups")
	if err := os.MkdirAll(backupDir, 0755); err != nil {
		writeError(w, http.StatusInternalServerError, "创建备份目录失败")
		return
	}
	filename := fmt.Sprintf("nvr-backup-%s.db", time.Now().Format("20060102-150405"))
	destPath := filepath.Join(backupDir, filename)
	if err := h.db.Backup(r.Context(), destPath); err != nil {
		writeError(w, http.StatusInternalServerError, "创建备份失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "created", "file": filename})
}
func (h *Handler) handleListBackups(w http.ResponseWriter, r *http.Request) {
	backupDir := filepath.Join(filepath.Dir(h.configPath), "backups")
	entries, err := os.ReadDir(backupDir)
	if err != nil {
		writeJSON(w, http.StatusOK, []string{})
		return
	}
	var backups []string
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".db") {
			backups = append(backups, e.Name())
		}
	}
	if backups == nil {
		backups = []string{}
	}
	writeJSON(w, http.StatusOK, backups)
}

// --- User Management ---

func (h *Handler) handleListUsers(w http.ResponseWriter, r *http.Request) {
	if h.db == nil {
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "数据库不可用"})
		return
	}
	users, err := h.db.ListUsers(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "获取用户列表失败")
		return
	}
	writeJSON(w, http.StatusOK, users)
}

func (h *Handler) handleCreateUser(w http.ResponseWriter, r *http.Request) {
	if h.db == nil {
		writeError(w, http.StatusInternalServerError, "数据库不可用")
		return
	}
	var body struct {
		Username string `json:"username"`
		Password string `json:"password"`
		Role     string `json:"role"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "无效的请求体")
		return
	}
	if body.Username == "" {
		writeError(w, http.StatusBadRequest, "用户名不能为空")
		return
	}
	if body.Password == "" || len(body.Password) < 6 {
		writeError(w, http.StatusBadRequest, "密码至少需要 6 个字符")
		return
	}
	if body.Role != "admin" && body.Role != "viewer" {
		body.Role = "viewer"
	}

	// Check if username already exists
	existing, err := h.db.GetUserByUsername(r.Context(), body.Username)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "检查用户失败")
		return
	}
	if existing != nil {
		writeError(w, http.StatusConflict, "用户名已存在")
		return
	}

	hash, err := middleware.HashPassword(body.Password)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "密码加密失败")
		return
	}

	id := fmt.Sprintf("user_%d", time.Now().UnixNano())
	if err := h.db.CreateUser(r.Context(), id, body.Username, hash, body.Role); err != nil {
		writeError(w, http.StatusInternalServerError, "创建用户失败")
		return
	}

	writeJSON(w, http.StatusCreated, map[string]string{"status": "ok", "id": id})
}

func (h *Handler) handleUpdateUser(w http.ResponseWriter, r *http.Request) {
	if h.db == nil {
		writeError(w, http.StatusInternalServerError, "数据库不可用")
		return
	}
	id := chi.URLParam(r, "id")
	if id == "" {
		writeError(w, http.StatusBadRequest, "缺少用户 ID")
		return
	}

	var body struct {
		Username string `json:"username"`
		Password string `json:"password,omitempty"`
		Role     string `json:"role"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "无效的请求体")
		return
	}
	if body.Username == "" {
		writeError(w, http.StatusBadRequest, "用户名不能为空")
		return
	}
	if body.Role != "admin" && body.Role != "viewer" {
		writeError(w, http.StatusBadRequest, "无效的角色")
		return
	}

	passwordHash := ""
	if body.Password != "" {
		if len(body.Password) < 6 {
			writeError(w, http.StatusBadRequest, "密码至少需要 6 个字符")
			return
		}
		var err error
		passwordHash, err = middleware.HashPassword(body.Password)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "密码加密失败")
			return
		}
	}

	if err := h.db.UpdateUser(r.Context(), id, body.Username, passwordHash, body.Role); err != nil {
		writeError(w, http.StatusInternalServerError, "更新用户失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *Handler) handleDeleteUser(w http.ResponseWriter, r *http.Request) {
	if h.db == nil {
		writeError(w, http.StatusInternalServerError, "数据库不可用")
		return
	}
	id := chi.URLParam(r, "id")
	if id == "" {
		writeError(w, http.StatusBadRequest, "缺少用户 ID")
		return
	}

	// Prevent deleting admin users
	target, err := h.db.GetUserByID(r.Context(), id)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "查询用户失败")
		return
	}
	if target != nil && target.Role == "admin" {
		writeError(w, http.StatusForbidden, "不能删除管理员用户")
		return
	}

	// Prevent deleting yourself
	currentUser := middleware.GetUsername(r)
	if currentUser != "" {
		// Get user's ID by username to compare
		user, err := h.db.GetUserByUsername(r.Context(), currentUser)
		if err == nil && user != nil && user.ID == id {
			writeError(w, http.StatusForbidden, "不能删除当前用户")
			return
		}
	}

	if err := h.db.DeleteUser(r.Context(), id); err != nil {
		writeError(w, http.StatusInternalServerError, "删除用户失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// formatUptime converts a duration to a human-readable string like "2h 15m 30s".
func formatUptime(d time.Duration) string {
	rounded := d.Round(time.Second)
	h := rounded / time.Hour
	rounded -= h * time.Hour
	m := rounded / time.Minute
	rounded -= m * time.Minute
	s := rounded / time.Second
	if h > 0 {
		return fmt.Sprintf("%dh %dm %ds", h, m, s)
	}
	if m > 0 {
		return fmt.Sprintf("%dm %ds", m, s)
	}
	return fmt.Sprintf("%ds", s)
}

// --- System stats helpers (Linux /proc) ---

func readCPURaw() (total, idle uint64, err error) {
	data, err := os.ReadFile("/proc/stat")
	if err != nil {
		return 0, 0, err
	}
	lines := strings.Split(string(data), "\n")
	if len(lines) == 0 {
		return 0, 0, fmt.Errorf("empty /proc/stat")
	}
	fields := strings.Fields(lines[0])
	if len(fields) < 5 {
		return 0, 0, fmt.Errorf("unexpected /proc/stat format")
	}
	for i := 1; i < len(fields); i++ {
		v, _ := strconv.ParseUint(fields[i], 10, 64)
		total += v
	}
	idle, _ = strconv.ParseUint(fields[4], 10, 64)
	return
}

func readMemoryInfo() (total, available uint64, err error) {
	data, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return 0, 0, err
	}
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		v, _ := strconv.ParseUint(fields[1], 10, 64)
		switch fields[0] {
		case "MemTotal:":
			total = v * 1024
		case "MemAvailable:":
			available = v * 1024
		}
	}
	return
}

func readNetworkInfo() (bytesSent, bytesRecv uint64, err error) {
	data, err := os.ReadFile("/proc/net/dev")
	if err != nil {
		return 0, 0, err
	}
	// Try eth0 or wlan0 first
	for _, line := range strings.Split(string(data), "\n") {
		trimmed := strings.TrimSpace(line)
		if !strings.HasPrefix(trimmed, "eth0:") && !strings.HasPrefix(trimmed, "wlan0:") {
			continue
		}
		parts := strings.SplitN(trimmed, ":", 2)
		if len(parts) < 2 {
			continue
		}
		fields := strings.Fields(parts[1])
		if len(fields) < 10 {
			continue
		}
		bytesRecv, _ = strconv.ParseUint(fields[0], 10, 64)
		bytesSent, _ = strconv.ParseUint(fields[8], 10, 64)
		return
	}
	// Fallback: sum all interfaces
	for _, line := range strings.Split(string(data), "\n") {
		trimmed := strings.TrimSpace(line)
		if !strings.Contains(trimmed, ":") {
			continue
		}
		parts := strings.SplitN(trimmed, ":", 2)
		if len(parts) < 2 {
			continue
		}
		fields := strings.Fields(parts[1])
		if len(fields) < 10 {
			continue
		}
		r, _ := strconv.ParseUint(fields[0], 10, 64)
		s, _ := strconv.ParseUint(fields[8], 10, 64)
		bytesRecv += r
		bytesSent += s
	}
	return
}

func readProcessRSS() uint64 {
	data, err := os.ReadFile("/proc/self/statm")
	if err != nil {
		return 0
	}
	fields := strings.Fields(string(data))
	if len(fields) < 2 {
		return 0
	}
	rssPages, _ := strconv.ParseUint(fields[1], 10, 64)
	return rssPages * uint64(os.Getpagesize())
}

func (h *Handler) handleSystemStats(w http.ResponseWriter, r *http.Request) {
	cpuTotal, cpuIdle, _ := readCPURaw()
	memTotal, memAvailable, _ := readMemoryInfo()
	netSent, netRecv, _ := readNetworkInfo()
	processRSS := readProcessRSS()

	writeJSON(w, http.StatusOK, SystemStats{
		CPU:     CPUStats{Total: cpuTotal, Idle: cpuIdle},
		Memory:  MemoryStats{Total: memTotal, Available: memAvailable, ProcessRSS: processRSS},
		Network: NetworkStats{BytesSent: netSent, BytesRecv: netRecv},
		Uptime:  formatUptime(time.Since(appStartTime)),
		Timestamp: time.Now().Unix(),
	})
}
