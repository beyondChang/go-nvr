package cmd

import (
	"context"
	"flag"
	"fmt"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	_ "net/http/pprof"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/beyondChang/go-nvr/internal/api"
	"github.com/beyondChang/go-nvr/internal/camera"
	"github.com/beyondChang/go-nvr/internal/cleanup"
	"github.com/beyondChang/go-nvr/internal/config"
	"github.com/beyondChang/go-nvr/internal/ftp"
	"github.com/beyondChang/go-nvr/internal/hls"
	"github.com/beyondChang/go-nvr/internal/merge"
	"github.com/beyondChang/go-nvr/internal/metrics"
	authmw "github.com/beyondChang/go-nvr/internal/middleware"
	"github.com/beyondChang/go-nvr/internal/mqtt"
	"github.com/beyondChang/go-nvr/internal/storage"
	ui "github.com/beyondChang/go-nvr/internal/ui"
	"github.com/beyondChang/go-nvr/internal/upload"
	"github.com/beyondChang/go-nvr/internal/webdav"
)

var (
	listenAddr = flag.String("port", ":9090", "listen address (e.g. :9090 or 0.0.0.0:9090)")
	dataDir    = flag.String("data", "data", "data directory path")
	version    = flag.Bool("version", false, "print version and exit")
)

var appVersion = "0.0.1" // overridden via -ldflags at build time

// parseListenAddr normalises a port-or-address flag:
//
//	"--port 9090"       → ":9090"
//	"--port :9090"      → ":9090"
//	"--port 0.0.0.0:9090" → "0.0.0.0:9090"
func parseListenAddr(v string) string {
	v = strings.TrimSpace(v)
	if v == "" {
		return ":9090"
	}
	if !strings.Contains(v, ":") {
		return ":" + v
	}
	if v[0] != ':' {
		// already "host:port"
		return v
	}
	return v
}

func Run() {
	// Handle health subcommand: go-nvr health [--addr :9090]
	if len(os.Args) > 1 && os.Args[1] == "health" {
		addr := ":9090"
		for i := 2; i < len(os.Args); i++ {
			if os.Args[i] == "--addr" && i+1 < len(os.Args) {
				i++
				addr = parseListenAddr(os.Args[i])
			}
		}
		resp, err := http.Get("http://localhost" + addr + "/api/health")
		if err != nil {
			fmt.Fprintf(os.Stderr, "Health check failed: %v\n", err)
			os.Exit(1)
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			fmt.Fprintf(os.Stderr, "Health check failed: HTTP %d\n", resp.StatusCode)
			os.Exit(1)
		}
		os.Exit(0)
	}

	// Handle init subcommand: go-nvr init [--data-dir data] [--port :9090]
	if len(os.Args) > 1 && os.Args[1] == "init" {
		var initDataDir, initListen string
		for i := 2; i < len(os.Args); i++ {
			switch os.Args[i] {
			case "--data-dir":
				i++
				if i < len(os.Args) {
					initDataDir = os.Args[i]
				}
			case "--port":
				i++
				if i < len(os.Args) {
					initListen = parseListenAddr(os.Args[i])
				}
			}
		}
		if initDataDir == "" {
			initDataDir = "data"
		}
		if initListen == "" {
			initListen = ":9090"
		}
		if err := os.MkdirAll(initDataDir, 0755); err != nil {
			fmt.Fprintf(os.Stderr, "Error creating data directory: %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("Data directory: %s\n", initDataDir)
		fmt.Printf("Listen address: %s\n", initListen)
		fmt.Println("\nNext steps:")
		fmt.Printf("  1. Start: %s --port %s --data %s\n", os.Args[0], initListen, initDataDir)
		fmt.Printf("  2. Open http://localhost%s in your browser\n", initListen)
		fmt.Printf("  3. Log in with username: admin, password: 123456\n")
		os.Exit(0)
	}

	// Handle hash-password subcommand: go-nvr hash-password <password>
	if len(os.Args) > 1 && os.Args[1] == "hash-password" {
		if len(os.Args) < 3 {
			fmt.Fprintln(os.Stderr, "Usage: go-nvr hash-password <password>")
			os.Exit(1)
		}
		hash, err := authmw.HashPassword(os.Args[2])
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
		fmt.Println(hash)
		os.Exit(0)
	}

	// Setup initial logger before any config (no data dir yet → stdout only)
	logger := authmw.SetupLogger("info", "text", "")
	slog.SetDefault(logger)

	flag.Parse()

	if *version {
		fmt.Printf("Go NVR version %s\n", appVersion)
		os.Exit(0)
	}

	// Resolve listen address from flag
	listen := parseListenAddr(*listenAddr)

	// Resolve data directory — if relative, make it relative to working dir
	rootDir := *dataDir

	// Reconfigure logger with daily log file now that rootDir is known.
	// This ensures all startup logs (DB init, config loading, etc.) are
	// written to the log file, not just stdout.
	logDir := filepath.Join(rootDir, "logs")
	logger = authmw.SetupLogger("info", "text", logDir)
	slog.SetDefault(logger)

	// Build the config object from flags + defaults
	cfg := &config.Config{
		Server:  config.ServerConfig{Listen: listen},
		Storage: config.StorageConfig{RootDir: rootDir, SegmentDuration: "30s"},
		Cameras: []config.CameraConfig{},
		Cleanup: config.CleanupConfig{RetentionDays: 30, CheckInterval: "1h", DiskThresholdPercent: 95},
		FTP:     config.FTPConfig{Port: 2121, PassivePortRange: "2122-2140"},
		WebDAV:  config.WebDAVConfig{PathPrefix: "/dav"},
		Observability: config.ObservabilityConfig{LogLevel: "info", LogFormat: "text"},
		Version: appVersion,
	}

	// Create data directory if needed
	if err := os.MkdirAll(rootDir, 0755); err != nil {
		slog.Warn("failed to create data directory", "dir", rootDir, "error", err)
	}

	// Init database
	dbPath := filepath.Join(rootDir, "go-nvr.db")
	db, err := storage.New(dbPath)
	if err != nil {
		slog.Error("db", "error", err)
		os.Exit(1)
	}
	defer db.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if err := db.Init(ctx); err != nil {
		slog.Error("db init", "error", err)
		os.Exit(1)
	}

	// Load settings from DB and merge into config
	settings, err := db.GetAllSettings(ctx)
	if err != nil {
		slog.Warn("load settings from db", "error", err)
	}
	if settings != nil {
		// Cleanup settings
		if v, ok := settings["cleanup.retention_days"]; ok {
			if n, e := strconv.Atoi(v); e == nil {
				cfg.Cleanup.RetentionDays = n
			}
		}
		if v, ok := settings["cleanup.disk_threshold_percent"]; ok {
			if n, e := strconv.Atoi(v); e == nil {
				cfg.Cleanup.DiskThresholdPercent = n
			}
		}
		if v, ok := settings["cleanup.check_interval"]; ok {
			cfg.Cleanup.CheckInterval = v
		}
		// WebDAV settings
		if v, ok := settings["webdav.enabled"]; ok {
			b, e := strconv.ParseBool(v)
			if e == nil {
				cfg.WebDAV.Enabled = &b
			}
		}
		if v, ok := settings["webdav.path_prefix"]; ok {
			cfg.WebDAV.PathPrefix = v
		}
		if v, ok := settings["webdav.read_write"]; ok {
			b, e := strconv.ParseBool(v)
			if e == nil {
				cfg.WebDAV.ReadWrite = b
			}
		}
		// Merge settings
		if v, ok := settings["merge.enabled"]; ok {
			b, e := strconv.ParseBool(v)
			if e == nil {
				cfg.Merge.Enabled = b
			}
		}
		if v, ok := settings["merge.check_interval"]; ok {
			cfg.Merge.CheckInterval = v
		}
		if v, ok := settings["merge.window_size"]; ok {
			cfg.Merge.WindowSize = v
		}
		if v, ok := settings["merge.batch_limit"]; ok {
			if n, e := strconv.Atoi(v); e == nil {
				cfg.Merge.BatchLimit = n
			}
		}
		if v, ok := settings["merge.min_segment_age"]; ok {
			cfg.Merge.MinSegmentAge = v
		}
		if v, ok := settings["merge.min_segments_to_merge"]; ok {
			if n, e := strconv.Atoi(v); e == nil {
				cfg.Merge.MinSegmentsToMerge = n
			}
		}
	}

	cfg.ApplyDefaults()

	// Reconfigure logger with user settings and daily log file
	logDir = filepath.Join(rootDir, "logs")
	logger = authmw.SetupLogger(cfg.Observability.LogLevel, cfg.Observability.LogFormat, logDir)
	slog.SetDefault(logger)

	// Ensure admin user exists in the database (default: admin/123456)
	existingAdmin, err := db.GetUserByUsername(ctx, "admin")
	if err != nil {
		slog.Warn("check admin user in db", "error", err)
	}
	if existingAdmin == nil {
		hash, hashErr := authmw.HashPassword("123456")
		if hashErr == nil {
			if createErr := db.CreateUser(ctx, "admin_admin", "admin", hash, "admin"); createErr != nil {
				slog.Warn("create admin user in db", "error", createErr)
			} else {
				slog.Info("created admin user in database", "username", "admin")
			}
		}
	}

	// Init storage manager
	m := metrics.NewMetrics()
	store, err := storage.NewManager(cfg.Storage.RootDir, m)
	if err != nil {
		slog.Error("storage", "error", err)
		os.Exit(1)
	}

	// Cleanup temp files from previous crash
	if err := store.CleanupTempFiles(); err != nil {
		slog.Warn("temp cleanup", "error", err)
	}
	if err := db.CleanupIncomplete(ctx); err != nil {
		slog.Warn("incomplete cleanup", "error", err)
	}

	// Auth middleware — authenticates against the database users table
	authMW := authmw.NewAuthMiddlewareWithDB(db)

	// Camera manager
	camMgr := camera.NewCameraManager(cfg, store, db, m)

	// HLS manager
	hlsDataDir := filepath.Join(cfg.Storage.RootDir, "hls")
	hlsMgr := hls.NewManagerWithOpts(hlsDataDir, cfg.HLS.WriteBufferSize, cfg.HLS.SegmentMaxSizeMB*1024*1024)

	// Merge manager
	mergeMgr := merge.NewMergeManager(
		db, store,
		func() config.MergeConfig { return cfg.Merge },
		func(cameraID string) *config.MergeConfig {
			for _, c := range cfg.Cameras {
				if c.ID == cameraID {
					return c.Merge
				}
			}
			return nil
		},
		func() []config.CameraConfig { return cfg.Cameras },
	)

	// API handler
	handler := api.NewHandler(db, store, authMW, cfg, camMgr, hlsMgr, mergeMgr)

	// WebDAV
	var davHandler http.Handler
	if cfg.WebDAV.Enabled != nil && *cfg.WebDAV.Enabled {
		davSrv := webdav.NewServer(store, cfg.WebDAV.PathPrefix, authMW, db, cfg.WebDAV.ReadWrite)
		davHandler = davSrv.Handler()
	}

	// Upload handler
	uploadHandler := upload.NewHandler(store, db, 100<<20) // 100MB max

	// Register WebDAV methods with chi so it doesn't reject them as 405.
	chi.RegisterMethod("PROPFIND")
	chi.RegisterMethod("MKCOL")
	chi.RegisterMethod("LOCK")
	chi.RegisterMethod("UNLOCK")
	chi.RegisterMethod("COPY")
	chi.RegisterMethod("MOVE")

	// Router
	r := chi.NewRouter()
	r.Use(authmw.RequestLogger(slog.Default(), "/api/health", "/api/readyz", "/metrics"))
	r.Use(middleware.Recoverer)
	r.Use(authmw.SecurityHeaders)

	// Prometheus metrics (public, no auth)
	r.Handle("/metrics", promhttp.HandlerFor(m.Registry, promhttp.HandlerOpts{ErrorHandling: promhttp.ContinueOnError}))

	// pprof (same auth level as other routes)
	if cfg.Observability.EnablePprof {
		r.Mount("/debug/pprof", http.DefaultServeMux)
	}
	r.Mount("/", handler.Routes())

	// WebDAV
	if davHandler != nil {
		r.Mount(cfg.WebDAV.PathPrefix, davHandler)
	}

	// Upload routes (authenticated)
	r.Group(func(r chi.Router) {
		r.Use(authMW)
		uploadHandler.RegisterRoutes(r)
	})

	// Static UI — serve from embedded filesystem with authentication
	staticContent, err := fs.Sub(ui.StaticFS, "static")
	if err != nil {
		slog.Error("static fs", "error", err)
		os.Exit(1)
	}
	fileServer := http.FileServer(http.FS(staticContent))
	r.NotFound(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fileServer.ServeHTTP(w, r)
	}))

	// Start camera manager
	go func() {
		if err := camMgr.Start(ctx); err != nil {
			slog.Error("camera manager", "error", err)
		}
	}()

	// Cleanup manager
	cleanupMgr, err := cleanup.NewCleanupManager(db, store, cfg.Cleanup, m)
	if err != nil {
		slog.Error("cleanup", "error", err)
		os.Exit(1)
	}

	go func() {
		if cfg.Merge.Enabled {
			mergeMgr.Run(ctx)
			slog.Info("merge-manager stopped")
		}
	}()
	go cleanupMgr.Run(ctx)

	// MQTT
	if cfg.MQTT.Enabled {
		mqClient := mqtt.NewClient(cfg.MQTT.Broker, cfg.MQTT.ClientID, cfg.MQTT.Topic, nil)
		go func() {
			if err := mqClient.Start(ctx); err != nil {
				slog.Error("mqtt", "error", err)
			}
		}()
	}

	// FTP
	if cfg.FTP.Enabled != nil && *cfg.FTP.Enabled {
		ftpAddr := fmt.Sprintf(":%d", cfg.FTP.Port)
		ftpSrv := ftp.NewServer(ftpAddr, cfg.FTP.PassivePortRange, "admin", "123456", store, db)
		go func() {
			if err := ftpSrv.Start(ctx); err != nil {
				slog.Error("ftp", "error", err)
			}
		}()
	}

	// HTTP server
	srv := &http.Server{Addr: cfg.Server.Listen, Handler: r}
	go func() {
		slog.Info("Go NVR listening", "version", appVersion, "addr", cfg.Server.Listen)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("http", "error", err)
			os.Exit(1)
		}
	}()

	// Wait for shutdown signal
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	sig := <-sigCh
	slog.Info("received signal, shutting down", "signal", sig.String())

	// Graceful shutdown with timeout
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer shutdownCancel()

	done := make(chan struct{})
	go func() {
		_ = camMgr.Stop()
		hlsMgr.StopAll()
		srv.Shutdown(shutdownCtx)
		close(done)
	}()

	select {
	case <-done:
		authmw.ComponentLogger("server").Info("graceful shutdown completed")
	case <-shutdownCtx.Done():
		authmw.ComponentLogger("server").Warn("shutdown timed out, forcing exit")
	}
	slog.Info("Go NVR stopped")
}
