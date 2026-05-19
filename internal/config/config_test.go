package config

import (
	"testing"

	"github.com/stretchr/testify/require"
)

func TestDefaultsApplied(t *testing.T) {
	cfg := &Config{}
	cfg.ApplyDefaults()
	require.Equal(t, ":9090", cfg.Server.Listen)
	require.Equal(t, "data", cfg.Storage.RootDir)
	require.Equal(t, "30s", cfg.Storage.SegmentDuration)
	require.Equal(t, 30, cfg.Cleanup.RetentionDays)
	require.Equal(t, "1h", cfg.Cleanup.CheckInterval)
	require.Equal(t, 95, cfg.Cleanup.DiskThresholdPercent)
	require.Equal(t, 2121, cfg.FTP.Port)
	require.Equal(t, true, *cfg.FTP.Enabled)
	require.Equal(t, true, *cfg.WebDAV.Enabled)
	require.Equal(t, "/dav", cfg.WebDAV.PathPrefix)
}

func TestFTPExplicitlyDisabled(t *testing.T) {
	cfg := &Config{FTP: FTPConfig{Enabled: new(bool)}}
	*cfg.FTP.Enabled = false // explicitly set to false
	cfg.ApplyDefaults()
	require.NotNil(t, cfg.FTP.Enabled)
	require.Equal(t, false, *cfg.FTP.Enabled) // should remain false
}

func TestWebDAVExplicitlyDisabled(t *testing.T) {
	cfg := &Config{WebDAV: WebDAVConfig{Enabled: new(bool)}}
	*cfg.WebDAV.Enabled = false // explicitly set to false
	cfg.ApplyDefaults()
	require.NotNil(t, cfg.WebDAV.Enabled)
	require.Equal(t, false, *cfg.WebDAV.Enabled) // should remain false
}

func TestFTPNotConfigured(t *testing.T) {
	cfg := &Config{}
	cfg.ApplyDefaults()
	require.NotNil(t, cfg.FTP.Enabled)
	require.Equal(t, true, *cfg.FTP.Enabled) // should default to true
}

func TestWebDAVNotConfigured(t *testing.T) {
	cfg := &Config{}
	cfg.ApplyDefaults()
	require.NotNil(t, cfg.WebDAV.Enabled)
	require.Equal(t, true, *cfg.WebDAV.Enabled) // should default to true
}

func TestResolveMergeConfig_NilReturnsGlobal(t *testing.T) {
	global := MergeConfig{
		Enabled:            true,
		CheckInterval:      "1h",
		WindowSize:         "1h",
		BatchLimit:         200,
		MinSegmentAge:      "10m",
		MinSegmentsToMerge: 3,
	}
	result := ResolveMergeConfig(global, nil)
	require.Equal(t, global, result)
}

func TestResolveMergeConfig_OverridesNonZeroFields(t *testing.T) {
	global := MergeConfig{
		Enabled:            true,
		CheckInterval:      "1h",
		WindowSize:         "1h",
		BatchLimit:         200,
		MinSegmentAge:      "10m",
		MinSegmentsToMerge: 3,
	}
	perCamera := &MergeConfig{
		CheckInterval:      "30m",
		BatchLimit:         50,
	}
	result := ResolveMergeConfig(global, perCamera)
	require.True(t, result.Enabled)
	require.Equal(t, "30m", result.CheckInterval)
	require.Equal(t, 50, result.BatchLimit)
	require.Equal(t, "1h", result.WindowSize)
	require.Equal(t, "10m", result.MinSegmentAge)
	require.Equal(t, 3, result.MinSegmentsToMerge)
}

func TestResolveMergeConfig_AllFieldsOverridden(t *testing.T) {
	global := MergeConfig{
		Enabled:            true,
		CheckInterval:      "1h",
		WindowSize:         "1h",
		BatchLimit:         200,
		MinSegmentAge:      "10m",
		MinSegmentsToMerge: 3,
	}
	perCamera := &MergeConfig{
		Enabled:            false,
		CheckInterval:      "5m",
		WindowSize:         "30m",
		BatchLimit:         10,
		MinSegmentAge:      "2m",
		MinSegmentsToMerge: 2,
	}
	result := ResolveMergeConfig(global, perCamera)
	require.True(t, result.Enabled) // perCamera.Enabled=false is not >0/!="", so global stays
	require.Equal(t, "5m", result.CheckInterval)
	require.Equal(t, "30m", result.WindowSize)
	require.Equal(t, 10, result.BatchLimit)
	require.Equal(t, "2m", result.MinSegmentAge)
	require.Equal(t, 2, result.MinSegmentsToMerge)
}
