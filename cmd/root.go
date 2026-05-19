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
	configPath = flag.String("config", "go-nvr.yaml", "path to configuration file")
	version    = flag.Bool("version", false, "print version and exit")
)

var appVersion = "0.1.0-dev" // overridden via -ldflags at build time

func autoInitConfig(configPath string) *config.Config {
	dataDir := "data"

	cfg := &config.Config{
		Server:        config.ServerConfig{Listen: ":9090"},
		Storage:       config.StorageConfig{RootDir: dataDir, SegmentDuration: "30s"},
		Cameras:       []config.CameraConfig{},
		Cleanup:       config.CleanupConfig{RetentionDays: 30, CheckInterval: "1h", DiskThresholdPercent: 95},
		FTP:           config.FTPConfig{Port: 2121, PassivePortRange: "2122-2140"},
		WebDAV:        config.WebDAVConfig{PathPrefix: "/dav"},
		Observability: config.ObservabilityConfig{LogLevel: "info", LogFormat: "text"},
		Version:       "1.0",
	}

	// Create data directory if needed
	if err := os.MkdirAll(dataDir, 0755); err != nil {
		slog.Warn("failed to create data directory", "dir", dataDir, "error", err)
	}

	// Create config directory if needed
	configDir := filepath.Dir(configPath)
	if configDir != "." && configDir != "/" {
		if err := os.MkdirAll(configDir, 0755); err != nil {
			slog.Warn("failed to create config directory", "dir", configDir, "error", err)
		}
	}

	if err := config.Save(configPath, cfg); err != nil {
		slog.Warn("failed to save auto-generated config", "path", configPath, "error", err)
	} else {
		slog.Info("auto-generated default config", "path", configPath, "data_dir", dataDir)
	}

	return cfg
}

func Run() {
	// Handle health subcommand
	if len(os.Args) > 1 && os.Args[1] == "health" {
		addr := ":9090"
		for i := 2; i < len(os.Args); i++ {
			switch os.Args[i] {
			case "--addr":
				i++
				if i < len(os.Args) {
					addr = os.Args[i]
				}
			case "--config":
				i++
				if i < len(os.Args) {
					cfg, err := config.Load(os.Args[i])
					if err != nil {
						fmt.Fprintf(os.Stderr, "Error loading config: %v\n", err)
						os.Exit(1)
					}
					if cfg.Server.Listen != "" {
						addr = cfg.Server.Listen
					}
				}
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

	// Handle init subcommand
	if len(os.Args) > 1 && os.Args[1] == "init" {
		var dataDir, listenAddr, configPath string
		var force bool
		for i := 2; i < len(os.Args); i++ {
			switch os.Args[i] {
			case "--data-dir":
				i++
				if i < len(os.Args) {
					dataDir = os.Args[i]
				}
			case "--listen":
				i++
				if i < len(os.Args) {
					listenAddr = os.Args[i]
				}
			case "--config":
				i++
				if i < len(os.Args) {
					configPath = os.Args[i]
				}
			case "--force":
				force = true
			}
		}
		if dataDir == "" {
			dataDir = "data"
		}
		if listenAddr == "" {
			listenAddr = ":9090"
		}
		if configPath == "" {
			configPath = "go-nvr.yaml"
		}
		if _, err := os.Stat(configPath); err == nil && !force {
			fmt.Fprintf(os.Stderr, "Error: config file %s already exists (use --force to overwrite)\n", configPath)
			os.Exit(2)
		}
		if err := os.MkdirAll(dataDir, 0755); err != nil {
			fmt.Fprintf(os.Stderr, "Error creating data directory: %v\n", err)
			os.Exit(1)
		}
		cfg := config.Config{
			Server:        config.ServerConfig{Listen: listenAddr},
			Storage:       config.StorageConfig{RootDir: dataDir, SegmentDuration: "30s"},
			Cameras:       []config.CameraConfig{},
			Cleanup:       config.CleanupConfig{RetentionDays: 30, CheckInterval: "1h", DiskThresholdPercent: 95},
			FTP:           config.FTPConfig{Port: 2121, PassivePortRange: "2122-2140"},
			WebDAV:        config.WebDAVConfig{PathPrefix: "/dav"},
			Observability: config.ObservabilityConfig{LogLevel: "info", LogFormat: "text"},
			Version:       "1.0",
		}
		if err := config.Save(configPath, &cfg); err != nil {
			fmt.Fprintf(os.Stderr, "Error saving config: %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("Configuration saved to %s\n", configPath)
		fmt.Printf("Data directory: %s\n", dataDir)
		fmt.Println("\nNext steps:")
		fmt.Printf("  1. Edit %s to add your cameras\n", configPath)
		fmt.Printf("  2. Run: ./go-nvr -config %s\n", configPath)
		fmt.Printf("  3. Open http://localhost%s in your browser\n", listenAddr)
		os.Exit(0)
	}

	// Handle hash-password subcommand
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

	// Setup initial logger before config load
	logger := authmw.SetupLogger("info", "text")
	slog.SetDefault(logger)

	flag.Parse()

	if *version {
		fmt.Printf("Go NVR version %s\n", appVersion)
		os.Exit(0)
	}

	// Load and validate config
	cfg, err := config.Load(*configPath)
	if err != nil {
		if !os.IsNotExist(err) {
			slog.Error("config", "error", err)
			os.Exit(1)
		}
		// Auto-initialize: config file not found, generate defaults
		slog.Info("config file not found, auto-initializing with defaults", "path", *configPath)
		cfg = autoInitConfig(*configPath)
	}
	if err := config.Validate(cfg); err != nil {
		slog.Error("config validation", "error", err)
		os.Exit(1)
	}

	// Reconfigure logger with user settings after config load
	logger = authmw.SetupLogger(cfg.Observability.LogLevel, cfg.Observability.LogFormat)
	slog.SetDefault(logger)

	// Init database
	dbPath := filepath.Join(cfg.Storage.RootDir, "go-nvr.db")
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
	camMgr := camera.NewCameraManager(cfg, store, db, *configPath, m)

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

	// API handler — Routes() already includes /api prefix
	// API handler — Routes() already includes /api prefix
	handler := api.NewHandler(db, store, authMW, cfg, camMgr, hlsMgr, *configPath, mergeMgr)

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

	// API routes (handler.Routes() already includes /api prefix)

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
	// Wrap static file server with authentication middleware
	// Static files served without auth — SPA handles login flow client-side.
	// All sensitive data is protected via API endpoints in handler.Routes().
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
