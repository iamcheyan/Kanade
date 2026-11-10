package media

// Library ingest: register media that is already on the card.
//
// The downloader and this service are separate processes, and a card can also
// be filled by hand. Nothing in the library query knows about a file that has
// no `downloads` row, so a freshly copied video stayed invisible until the
// whole library was re-imported. This walks the download directory and adds the
// completed rows the query reads, which is what "sync" means for an offline
// player: the newest files on the card, not the newest items on a website.

import (
	"context"
	"database/sql"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"

	"kanade/videod/internal/store"
)

var (
	videoExt = map[string]bool{".mp4": true, ".webm": true, ".mkv": true, ".mov": true, ".m4v": true}
	audioExt = map[string]bool{".mp3": true, ".m4a": true, ".opus": true, ".flac": true}
)

// ScanResult is what the panel shows after a sync.
type ScanResult struct {
	Added   int `json:"added"`
	Updated int `json:"updated"`
	Skipped int `json:"skipped"`
	Total   int `json:"total"`
}

// ScanLibrary adds a completed download row for every media file in the
// download directory that does not have one yet. Files already known are
// counted and left alone, so a repeated scan is cheap and idempotent.
func (s *Service) ScanLibrary(ctx context.Context) (ScanResult, error) {
	var out ScanResult
	root := strings.TrimSpace(s.cfg.DownloadDir)
	if root == "" {
		return out, errors.New("no download directory configured")
	}
	info, err := os.Stat(root)
	if err != nil || !info.IsDir() {
		return out, errors.New("download directory is not readable")
	}

	account, err := store.ActiveAccount(ctx, s.db)
	if err != nil || account == "" {
		account = "local"
		if err := store.EnsureAccount(ctx, s.db, account); err != nil {
			return out, err
		}
	}
	// videos.channel_id is a foreign key; local files need one channel to sit
	// under, and it must not collide with a provider channel id.
	channel := account + "-local"
	if _, err := s.db.ExecContext(ctx,
		`INSERT INTO channels(id,account_id,title,url) VALUES(?,?,?,?)
		 ON CONFLICT(id) DO NOTHING`,
		channel, account, "本地文件", "file://"+root); err != nil {
		return out, err
	}

	walk := func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil // an unreadable entry is skipped, not fatal
		}
		if d.IsDir() {
			// One level of playlists/ is normal; deeper trees are not.
			if rel, relErr := filepath.Rel(root, path); relErr == nil && strings.Count(rel, string(filepath.Separator)) >= 1 {
				return fs.SkipDir
			}
			return nil
		}
		ext := strings.ToLower(filepath.Ext(path))
		mode := ""
		switch {
		case videoExt[ext]:
			mode = "video"
		case audioExt[ext]:
			mode = "audio"
		default:
			return nil
		}
		out.Total++

		id := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
		id = strings.TrimSuffix(id, ".audio")
		// A playlist entry keeps its _pN suffix in the file name; the video id is
		// what the rest of the app keys on.
		if cut := strings.Index(id, "_p"); cut > 0 {
			id = id[:cut]
		}

		// Three cases, and the middle one matters: the database may already know
		// this video at a path that no longer exists (a card was re-copied, a
		// directory moved). Skipping would leave a library row that cannot play,
		// so point it at the file that is actually here. A second copy of a video
		// whose registered path still resolves is a real duplicate and stays out.
		var existingPath, existingID string
		lookupErr := s.db.QueryRowContext(ctx,
			`SELECT id, output_path FROM downloads
			  WHERE state='completed' AND (output_path=? OR video_id=?) LIMIT 1`,
			path, id).Scan(&existingID, &existingPath)
		switch {
		case lookupErr == nil && existingPath == path:
			out.Skipped++
			return nil
		case lookupErr == nil:
			if _, statErr := os.Stat(existingPath); statErr == nil {
				out.Skipped++
				return nil
			}
			if _, err := s.db.ExecContext(ctx,
				`UPDATE downloads SET output_path=?, mode=? WHERE id=?`, path, mode, existingID); err != nil {
				return nil
			}
			out.Updated++
			return nil
		case lookupErr != sql.ErrNoRows && lookupErr != nil:
			return nil
		}
		title := id
		added := time.Time{}
		if stat, statErr := d.Info(); statErr == nil {
			added = stat.ModTime().UTC()
		}
		if _, err := s.db.ExecContext(ctx,
			`INSERT INTO videos(id,account_id,channel_id,title,url,published_at) VALUES(?,?,?,?,?,?)
			 ON CONFLICT(id) DO NOTHING`,
			id, account, channel, title, "file://"+path, added.Format(time.RFC3339)); err != nil {
			return nil
		}
		if _, err := s.db.ExecContext(ctx,
			`INSERT INTO downloads(account_id,video_id,url,mode,quality,priority,state,progress,output_path)
			 VALUES(?,?,?,?,?,0,'completed',100,?)`,
			account, id, "file://"+path, mode, "local", path); err != nil {
			return nil
		}
		out.Added++
		return nil
	}

	if err := filepath.WalkDir(root, walk); err != nil && !errors.Is(err, sql.ErrNoRows) {
		return out, err
	}
	return out, nil
}
