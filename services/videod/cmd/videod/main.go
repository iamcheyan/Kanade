package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"

	"kanade/videod/internal/api"
	"kanade/videod/internal/media"
	"kanade/videod/internal/store"
)

func main() {
	addr := flag.String("addr", "127.0.0.1:8732", "listen address")
	dataDir := flag.String("data", "/storage/.config/kanade", "data directory")
	flag.Parse()

	if err := os.MkdirAll(*dataDir, 0755); err != nil {
		log.Fatal(err)
	}
	dbPath := filepath.Join(*dataDir, "kanade.db")
	db, err := store.Open(dbPath)
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()

	cfg := media.Config{
		YtDlpPath:   envOr("KANADE_YTDLP", "yt-dlp"),
		MpvPath:     envOr("KANADE_MPV", "mpv"),
		DownloadDir: envOr("KANADE_DOWNLOADS", "/storage/content/downloads"),
		IPCDir:      envOr("KANADE_IPC", filepath.Join(*dataDir, "ipc")),
		Concurrency: 2,
		Headless:    true,
	}
	svc, err := media.New(db, cfg)
	if err != nil {
		log.Fatal(err)
	}
	handler := api.New(db)
	handler.Register(http.NewServeMux())
	mux := http.NewServeMux()
	handler.Register(mux)
	_ = svc

	log.Printf("kanade videod listening on %s (data=%s)", *addr, *dataDir)
	if err := http.ListenAndServe(*addr, mux); err != nil {
		log.Fatal(err)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

var _ = fmt.Sprintf
