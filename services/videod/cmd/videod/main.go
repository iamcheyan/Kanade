// Command videod is the on-device media service behind Kanade.
//
// Upstream never committed this entry point: the Makefile referenced
// ./cmd/videod while no such file existed in any commit, because services/videod/.gitignore
// carries a bare "videod" pattern that also ignores this directory. The PJOS
// port supplies the wiring and narrows that ignore rule so the file can be
// versioned.
//
// Layout under the data directory (default /storage/.config/kanade):
//
//	kanade.db        SQLite (accounts, channels, videos, playlists, downloads)
//	downloads/       finished media, what the launcher library reads
//	ipc/             mpv IPC sockets
//	cookies/         Netscape jars per provider (youtube.txt, bilibili.txt)
//
// Build for PJOS (aarch64, static, no cgo):
//
//	CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o videod-arm64 ./cmd/videod
package main

import (
	"context"
	"database/sql"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"kanade/videod/internal/api"
	"kanade/videod/internal/media"
	"kanade/videod/internal/provider"
	"kanade/videod/internal/store"
)

func main() {
	addr := flag.String("addr", envOr("KANADE_ADDR", "127.0.0.1:8732"), "listen address")
	dataDir := flag.String("data", envOr("KANADE_DATA", "/storage/.config/kanade"), "data directory")
	seed := flag.Bool("seed", false, "insert the demo account and sample rows")
	seedLibrary := flag.Bool("seed-library", false, "also create sample media files with completed library rows")
	flag.Parse()

	if err := run(*addr, *dataDir, *seed, *seedLibrary); err != nil {
		log.Fatalf("videod: %v", err)
	}
}

func run(addr, dataDir string, seed, seedLibrary bool) error {
	downloads := envOr("KANADE_DOWNLOADS", "/storage/content/downloads")
	ipc := envOr("KANADE_IPC", filepath.Join(dataDir, "ipc"))
	cookies := filepath.Join(dataDir, "cookies")
	for _, dir := range []string{dataDir, ipc, cookies} {
		if err := os.MkdirAll(dir, 0o750); err != nil {
			return err
		}
	}

	db, err := store.Open(filepath.Join(dataDir, "kanade.db"))
	if err != nil {
		return err
	}
	defer db.Close()

	ctx := context.Background()
	if seed {
		if err := store.Seed(ctx, db); err != nil {
			return err
		}
	}
	if seedLibrary {
		if err := seedDemoLibrary(ctx, db, downloads); err != nil {
			return err
		}
	}

	cfg := media.Config{
		YtDlpPath:   envOr("KANADE_YTDLP", "yt-dlp"),
		MpvPath:     envOr("KANADE_MPV", "mpv"),
		DownloadDir: downloads,
		IPCDir:      ipc,
		DataDir:     dataDir,
		Concurrency: 2,
		Headless:    true,
	}
	svc, err := media.New(db, cfg)
	if err != nil {
		return err
	}
	defer svc.Close()

	mux := http.NewServeMux()

	handler := api.New(db)
	handler.DataDir = cookies
	handler.HealthProbe = func(ctx context.Context) (map[string]any, error) {
		return map[string]any{"tools": media.Probe(ctx, cfg)}, nil
	}
	handler.EnqueueDownload = func(account, videoID, url, mode, quality, bitrate string, priority int) (int64, error) {
		return svc.Enqueue(account, videoID, url, mode, quality, bitrate, priority)
	}
	handler.DeleteDownload = func(account, videoID string) (bool, error) {
		return svc.DeleteDownload(ctx, account, videoID)
	}

	// Providers read the jar the cookie-import endpoint writes, so a pasted
	// Cookie header takes effect without a restart.
	handler.SetSyncSources(map[string]api.SyncProvider{
		"youtube": provider.NewYouTubeProvider(provider.YouTubeConfig{
			Binary:     cfg.YtDlpPath,
			CookiePath: filepath.Join(cookies, "youtube.txt"),
		}),
		"bilibili": provider.NewBilibiliProvider(provider.BilibiliConfig{
			Binary:     cfg.YtDlpPath,
			CookiePath: filepath.Join(cookies, "bilibili.txt"),
		}),
	})
	handler.Register(mux)

	// The media service owns the download directory, so it owns the scan that
	// makes files already on the card visible to /api/sync.
	handler.NASPull = svc.StartPull
	handler.LibraryScan = func(ctx context.Context) (int, int, int, error) {
		result, err := svc.ScanLibrary(ctx)
		return result.Added, result.Updated, result.Skipped, err
	}

	// Downloads, player, library, settings, storage.
	media.RegisterRoutes(mux, svc)

	srv := &http.Server{
		Addr:              addr,
		Handler:           logRequests(mux),
		ReadHeaderTimeout: 10 * time.Second,
	}

	errs := make(chan error, 1)
	go func() {
		log.Printf("videod listening on %s (data=%s, downloads=%s)", addr, dataDir, downloads)
		errs <- srv.ListenAndServe()
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)

	select {
	case err := <-errs:
		if err != nil && err != http.ErrServerClosed {
			return err
		}
	case <-stop:
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
		defer cancel()
		if err := srv.Shutdown(shutdownCtx); err != nil {
			log.Printf("videod: shutdown: %v", err)
		}
	}
	log.Printf("videod stopped")
	return nil
}

// logRequests keeps one line per request so a launcher bug is visible in
// /tmp/videod.log without full body logging.
func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s %s", r.Method, r.URL.Path, time.Since(start).Round(time.Millisecond))
	})
}

// seedDemoLibrary makes the emulator run show a real library: small files on
// disk plus completed download rows that point at them. The panel reads the
// same two things it reads on a device — the completed rows and the directory
// walk — so a screenshot taken here is the layout, not a fixture.
func seedDemoLibrary(ctx context.Context, db *sql.DB, downloadDir string) error {
	if err := os.MkdirAll(downloadDir, 0o750); err != nil {
		return err
	}
	// videos.channel_id is a foreign key, so borrow whatever store.Seed made.
	var channelID string
	if err := db.QueryRowContext(ctx, `SELECT id FROM channels LIMIT 1`).Scan(&channelID); err != nil {
		return fmt.Errorf("seed-library needs seeded channels: %w", err)
	}
	titles := []string{
		"【実機テスト】GBA 音声が出ない件を追う",
		"用 PocketJS 重写的新面板 v0.1",
		"NAS へ落とした動画を一覧する",
		"字幕とチャプターの扱い",
		"キーボード操作だけで完結させる",
		"バッテリー残量の表示",
		"存储占用与清理策略",
		"离线库的同步时机",
		"小屏 360x240 的排版",
		"下载队列与重试",
		"缩略图缓存",
		"最后的缓存目录",
	}
	for i := 1; i <= 12; i++ {
		id := fmt.Sprintf("lib-%04d", i)
		path := filepath.Join(downloadDir, id+".mp4")
		if _, err := os.Stat(path); err != nil {
			// 4 KiB of filler: enough for the storage walk to report bytes.
			if err := os.WriteFile(path, make([]byte, 4096+i*128), 0o640); err != nil {
				return err
			}
		}
		if _, err := db.ExecContext(ctx,
			`INSERT INTO videos(id,account_id,channel_id,title,url,duration,published_at)
			 VALUES(?, 'demo', ?, ?, 'https://example.test/watch/'||?, ?, '2026-11-01T00:00:00Z')
			 ON CONFLICT DO NOTHING`,
			id, channelID, titles[i-1], id, 90+i*7); err != nil {
			return err
		}
		if _, err := db.ExecContext(ctx,
			`INSERT INTO downloads(account_id,video_id,url,mode,quality,audio_bitrate,priority,state,progress,output_path)
			 VALUES('demo',?,'https://example.test/watch/'||?,'video','720p','192',?, 'completed',100,?)
			 ON CONFLICT DO NOTHING`,
			id, id, 100-i, path); err != nil {
			return err
		}
	}
	return nil
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
