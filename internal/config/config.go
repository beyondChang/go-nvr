package config

import (
	"strings"

	"github.com/beyondChang/go-nvr/internal/model"
)

type Config struct {
	Server        ServerConfig
	Storage       StorageConfig
	Cameras       []CameraConfig
	Cleanup       CleanupConfig
	Merge         MergeConfig
	FTP           FTPConfig
	MQTT          MQTTConfig
	WebDAV        WebDAVConfig
	HLS           HLSConfig
	Observability ObservabilityConfig
	Version       string
}

type ServerConfig struct {
	Listen string // default ":9090"
}

type StorageConfig struct {
	RootDir         string // default "/mnt/data/nvr"
	SegmentDuration string // default "30s"
}

type CameraConfig struct {
	ID       string
	Name     string
	Protocol string // rtsp, http, onvif
	Encoding       string // h264, h265, mjpeg, jpeg
	URL      string
	Username string
	Password string
	ONVIFEndpoint  string
	ProfileToken   string
	StreamEncoding string // H264 or H265, for ONVIF cameras. Empty = auto-detect.
	Enabled  bool
	SubStreamURL   string
	SnapshotURL    string
	SampleInterval int
	HLSMaxFPS      int
	Merge         *MergeConfig
}

type CleanupConfig struct {
	RetentionDays       int    // default 30
	CheckInterval       string // default "1h"
	DiskThresholdPercent int   // default 95
}

type MergeConfig struct {
	Enabled            bool   // default false
	CheckInterval      string // default "1h"
	WindowSize         string // default "1h"
	BatchLimit         int    // default 200
	MinSegmentAge      string // default "10m"
	MinSegmentsToMerge int    // default 3
}

type FTPConfig struct {
	Enabled          *bool  // default true
	Port             int    // default 2121
	PassivePortRange string // default "2122-2140"
}

type MQTTConfig struct {
	Enabled  bool   // default false
	Broker   string
	Topic    string
	ClientID string
}

type WebDAVConfig struct {
	Enabled    *bool  // default true
	PathPrefix string // default "/dav"
	ReadWrite  bool   // default false
}

// ObservabilityConfig defines observability settings
type ObservabilityConfig struct {
	LogLevel     string // default "info"
	LogFormat    string // default "text"
	EnablePprof  bool   // default false
}

type HLSConfig struct {
	WriteBufferSize  int // async frame buffer per stream (default 40)
	SegmentMaxSizeMB int // HLS segment max size in MB (default 10)
}

func (cfg *Config) ApplyDefaults() {
	// Server
	if strings.TrimSpace(cfg.Server.Listen) == "" {
		cfg.Server.Listen = ":9090"
	}
	// Storage
	if strings.TrimSpace(cfg.Storage.RootDir) == "" {
		cfg.Storage.RootDir = "data"
	}
	if strings.TrimSpace(cfg.Storage.SegmentDuration) == "" {
		cfg.Storage.SegmentDuration = "30s"
	}
	// Cameras: nothing heavy, but ensure at least enable false
	// Cleanup
	if cfg.Cleanup.RetentionDays == 0 {
		cfg.Cleanup.RetentionDays = 30
	}
	if strings.TrimSpace(cfg.Cleanup.CheckInterval) == "" {
		cfg.Cleanup.CheckInterval = "1h"
	}
	if cfg.Cleanup.DiskThresholdPercent == 0 {
		cfg.Cleanup.DiskThresholdPercent = 95
	}
	// FTP
	if cfg.FTP.Enabled == nil {
		// set default to true only if not configured by user
		cfg.FTP.Enabled = new(bool)
		*cfg.FTP.Enabled = true
	}
	if cfg.FTP.Port == 0 {
		cfg.FTP.Port = 2121
	}
	if strings.TrimSpace(cfg.FTP.PassivePortRange) == "" {
		cfg.FTP.PassivePortRange = "2122-2140"
	}
	// MQTT
	// default false already
	// WebDAV
	if cfg.WebDAV.Enabled == nil {
		// set default to true only if not configured by user
		cfg.WebDAV.Enabled = new(bool)
		*cfg.WebDAV.Enabled = true
	}
	if strings.TrimSpace(cfg.WebDAV.PathPrefix) == "" {
		cfg.WebDAV.PathPrefix = "/dav"
	}
	// Observability
	if strings.TrimSpace(cfg.Observability.LogLevel) == "" {
		cfg.Observability.LogLevel = "info"
	}
	if strings.TrimSpace(cfg.Observability.LogFormat) == "" {
		cfg.Observability.LogFormat = "text"
	}
	// EnablePprof defaults to false (zero value)
	// Version
	// HLS defaults
	if cfg.HLS.WriteBufferSize <= 0 {
		cfg.HLS.WriteBufferSize = 40
	}
	if cfg.HLS.SegmentMaxSizeMB <= 0 {
		cfg.HLS.SegmentMaxSizeMB = 10
	}
	if strings.TrimSpace(cfg.Version) == "" {
		cfg.Version = "1.0"
	}
	// Merge defaults
	if cfg.Merge.BatchLimit <= 0 {
		cfg.Merge.BatchLimit = 200
	}
	if cfg.Merge.CheckInterval == "" {
		cfg.Merge.CheckInterval = "1h"
	}
	if cfg.Merge.WindowSize == "" {
		cfg.Merge.WindowSize = "1h"
	}
	if cfg.Merge.MinSegmentAge == "" {
		cfg.Merge.MinSegmentAge = "10m"
	}
	if cfg.Merge.MinSegmentsToMerge <= 0 {
		cfg.Merge.MinSegmentsToMerge = 3
	}
	// Camera protocol/encoding normalization (backward compat with old combined protocol strings)
	for i := range cfg.Cameras {
		cam := &cfg.Cameras[i]
		// If encoding is empty but protocol looks like old combined format (e.g. "rtsp_h264")
		if cam.Encoding == "" && strings.Contains(cam.Protocol, "_") {
			proto, enc, err := model.ParseLegacyProtocol(cam.Protocol)
			if err == nil {
				cam.Protocol = proto
				cam.Encoding = enc
			}
		}
		// If encoding is still empty for known transport-only protocols, set sensible defaults
		if cam.Encoding == "" {
			switch cam.Protocol {
			case "rtsp":
				cam.Encoding = "h264"
			case "http":
				cam.Encoding = "jpeg"
			case "onvif":
				cam.Encoding = "" // ONVIF auto-detects
			}
		}
	}
}

// ResolveMergeConfig returns the effective MergeConfig for a camera.
// If perCamera is nil, the global config is returned unchanged.
// If perCamera is non-nil, only non-zero fields override the global config.
func ResolveMergeConfig(global MergeConfig, perCamera *MergeConfig) MergeConfig {
	if perCamera == nil {
		return global
	}
	result := global
	if perCamera.Enabled {
		result.Enabled = true
	}
	if perCamera.CheckInterval != "" {
		result.CheckInterval = perCamera.CheckInterval
	}
	if perCamera.WindowSize != "" {
		result.WindowSize = perCamera.WindowSize
	}
	if perCamera.BatchLimit > 0 {
		result.BatchLimit = perCamera.BatchLimit
	}
	if perCamera.MinSegmentAge != "" {
		result.MinSegmentAge = perCamera.MinSegmentAge
	}
	if perCamera.MinSegmentsToMerge > 0 {
		result.MinSegmentsToMerge = perCamera.MinSegmentsToMerge
	}
	return result
}