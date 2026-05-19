package middleware

import (
	"context"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/bcrypt"

	"github.com/beyondChang/go-nvr/internal/storage"
)

var logger = slog.Default().With("component", "auth")

const (
	authMaxFailures   = 20
	authWindowMinutes = 1
	authCacheTTL      = 5 * time.Minute
)

type rateLimitEntry struct {
	count       int
	windowStart time.Time
}

var authFailures sync.Map

// NewAuthMiddleware returns a middleware that protects endpoints with HTTP Basic auth.
// If passwordHash is empty but plaintextPassword is non-empty, it is auto-hashed via bcrypt.
// Returns the middleware and the effective hash used (for config persistence).
// If both are empty, defaults to admin/123456.
func NewAuthMiddleware(username, passwordHash, plaintextPassword string) (func(http.Handler) http.Handler, string) {
	// Default username
	if strings.TrimSpace(username) == "" {
		username = "admin"
	}

	effectiveHash := passwordHash
	if strings.TrimSpace(passwordHash) == "" && strings.TrimSpace(plaintextPassword) != "" {
		hash, err := HashPassword(plaintextPassword)
		if err != nil {
			logger.Error("failed to hash plaintext password", "error", err)
		} else {
			logger.Info("auto-hashed plaintext password from config")
			effectiveHash = hash
		}
	}

	// If still no hash, use default password "123456"
	if strings.TrimSpace(effectiveHash) == "" {
		hash, err := HashPassword("123456")
		if err != nil {
			logger.Error("failed to hash default password", "error", err)
		} else {
			logger.Info("no password configured, using default admin/123456")
			effectiveHash = hash
		}
	}

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ip := extractIP(r.RemoteAddr)

			if v, ok := authFailures.Load(ip); ok {
				entry := v.(rateLimitEntry)
				if time.Since(entry.windowStart) > time.Duration(authWindowMinutes)*time.Minute {
					authFailures.Delete(ip)
				} else if entry.count >= authMaxFailures {
					logger.Info("rate limited request", "ip", ip, "failures", entry.count)
					w.WriteHeader(http.StatusTooManyRequests)
					return
				}
			}

			user, pass, ok := r.BasicAuth()
			if !ok || user != username || !CheckPassword(pass, effectiveHash) {
				if v, ok := authFailures.Load(ip); ok {
					entry := v.(rateLimitEntry)
					if time.Since(entry.windowStart) > time.Duration(authWindowMinutes)*time.Minute {
						authFailures.Store(ip, rateLimitEntry{count: 1, windowStart: time.Now()})
					} else {
						entry.count++
						authFailures.Store(ip, entry)
					}
				} else {
					authFailures.Store(ip, rateLimitEntry{count: 1, windowStart: time.Now()})
				}

				w.WriteHeader(http.StatusUnauthorized)
				return
			}

			authFailures.Delete(ip)
			next.ServeHTTP(w, r)
		})
	}, effectiveHash
}

// HashPassword generates a bcrypt hash from a plaintext password.
func HashPassword(password string) (string, error) {
	b, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

type authCacheEntry struct {
	hash       string
	verifiedAt time.Time
}

var authCache sync.Map

// CheckPassword compares a plaintext password against a bcrypt hash.
// Results are cached for authCacheTTL to avoid repeated bcrypt overhead.
func CheckPassword(password, hash string) bool {
	if strings.TrimSpace(hash) == "" {
		return false
	}

	cacheKey := password + "\x00" + hash

	if v, ok := authCache.Load(cacheKey); ok {
		entry := v.(authCacheEntry)
		if entry.hash == hash && time.Since(entry.verifiedAt) < authCacheTTL {
			return true
		}
		authCache.Delete(cacheKey)
	}

	err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
	if err == nil {
		authCache.Store(cacheKey, authCacheEntry{hash: hash, verifiedAt: time.Now()})
	}
	return err == nil
}

func extractIP(remoteAddr string) string {
	if idx := strings.LastIndex(remoteAddr, "]"); idx != -1 {
		return remoteAddr[:idx+1]
	}
	if idx := strings.LastIndex(remoteAddr, ":"); idx != -1 {
		return remoteAddr[:idx]
	}
	return remoteAddr
}

func ResetAuthFailures() {
	authFailures.Range(func(key, _ interface{}) bool {
		authFailures.Delete(key)
		return true
	})
}

// Context keys for storing auth information in request context.
type contextKey string

const (
	ContextKeyUsername contextKey = "auth_username"
	ContextKeyRole     contextKey = "auth_role"
)

// GetUsername extracts the authenticated username from the request context.
func GetUsername(r *http.Request) string {
	if v := r.Context().Value(ContextKeyUsername); v != nil {
		return v.(string)
	}
	return ""
}

// GetRole extracts the authenticated user role from the request context.
func GetRole(r *http.Request) string {
	if v := r.Context().Value(ContextKeyRole); v != nil {
		return v.(string)
	}
	return ""
}

// NewAuthMiddlewareWithDB returns an auth middleware that authenticates
// against the database users table. The authenticated username and role
// are stored in the request context.
func NewAuthMiddlewareWithDB(db *storage.DB) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ip := extractIP(r.RemoteAddr)

			if v, ok := authFailures.Load(ip); ok {
				entry := v.(rateLimitEntry)
				if time.Since(entry.windowStart) > time.Duration(authWindowMinutes)*time.Minute {
					authFailures.Delete(ip)
				} else if entry.count >= authMaxFailures {
					logger.Info("rate limited request", "ip", ip, "failures", entry.count)
					w.WriteHeader(http.StatusTooManyRequests)
					return
				}
			}

			user, pass, ok := r.BasicAuth()
			if !ok {
				recordFailure(ip)
				w.WriteHeader(http.StatusUnauthorized)
				return
			}

			// Authenticate against database
			if db != nil {
				dbUser, err := db.GetUserByUsername(r.Context(), user)
				if err == nil && dbUser != nil && CheckPassword(pass, dbUser.PasswordHash) {
					authFailures.Delete(ip)
					ctx := context.WithValue(r.Context(), ContextKeyUsername, dbUser.Username)
					ctx = context.WithValue(ctx, ContextKeyRole, dbUser.Role)
					next.ServeHTTP(w, r.WithContext(ctx))
					return
				}
			}

			recordFailure(ip)
			w.WriteHeader(http.StatusUnauthorized)
		})
	}
}

func recordFailure(ip string) {
	if v, ok := authFailures.Load(ip); ok {
		entry := v.(rateLimitEntry)
		entry.count++
		authFailures.Store(ip, entry)
	} else {
		authFailures.Store(ip, rateLimitEntry{count: 1, windowStart: time.Now()})
	}
}
