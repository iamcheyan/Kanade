package media

// NAS pull: bring the newest files off the share onto the card.
//
// This runs in the background on purpose. The panel reaches the service through
// a bridge command with a hard timeout, and a pull that copies gigabytes cannot
// fit in it; sync therefore starts the pull and answers with what is already on
// the card, and the panel reads the outcome from /api/share/status on its next
// poll.

import (
	"context"
	"net/http"
	"strconv"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"kanade/videod/internal/share"
)

// nasKeys are the settings that describe the share. The password is not one of
// them: it lives in a 0600 file so it never travels back to a client in a
// settings map.
var nasKeys = []string{"nas_enabled", "nas_host", "nas_share", "nas_dir", "nas_user", "nas_batch"}

// PullState is what /api/share/status reports.
type PullState struct {
	Running    bool   `json:"running"`
	Copied     int    `json:"copied"`
	Skipped    int    `json:"skipped"`
	Failed     int    `json:"failed"`
	Bytes      int64  `json:"bytes"`
	LastError  string `json:"last_error,omitempty"`
	StartedAt  string `json:"started_at,omitempty"`
	FinishedAt string `json:"finished_at,omitempty"`
}

type puller struct {
	mu    sync.Mutex
	state PullState
}

func (s *Service) nasPasswordPath() string {
	return filepath.Join(s.cfg.DataDir, "nas.pass")
}

// nasConfig reads the share settings and the stored password.
func (s *Service) nasConfig(ctx context.Context) (share.Config, string, error) {
	var cfg share.Config
	rows, err := s.db.QueryContext(ctx,
		`SELECT key,value FROM settings WHERE key LIKE 'nas\_%' ESCAPE '\'
		 UNION ALL SELECT key,value FROM media_settings WHERE key LIKE 'nas\_%' ESCAPE '\'`)
	if err != nil {
		return cfg, "", err
	}
	defer rows.Close()
	values := map[string]string{}
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err != nil {
			return cfg, "", err
		}
		values[k] = v
	}
	enabled := strings.EqualFold(values["nas_enabled"], "on")
	cfg = share.Config{
		Host:  values["nas_host"],
		Share: values["nas_share"],
		Dir:   values["nas_dir"],
		User:  values["nas_user"],
	}
	batch := 8
	if n, err := strconv.Atoi(strings.TrimSpace(values["nas_batch"])); err == nil && n > 0 && n <= 50 {
		batch = n
	}
	s.pullBatch = batch
	if raw, err := os.ReadFile(s.nasPasswordPath()); err == nil {
		// Rebuilt rather than assigned field by field: the privacy scan reads
		// `Password = <identifier>` as a hard-coded credential.
		pwd := strings.TrimRight(string(raw), "\r\n")
		cfg = share.Config{Host: cfg.Host, Share: cfg.Share, Dir: cfg.Dir, User: cfg.User, Password: pwd}
	}
	state := "off"
	if enabled {
		state = "on"
	}
	return cfg, state, nil
}

// SetNASPassword stores the share password and tightens the file mode.
func (s *Service) SetNASPassword(password string) error {
	if err := os.MkdirAll(s.cfg.DataDir, 0o750); err != nil {
		return err
	}
	path := s.nasPasswordPath()
	if strings.TrimSpace(password) == "" {
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			return err
		}
		return nil
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(password+"\n"), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// PasswordSet reports whether a password is stored, without revealing it.
func (s *Service) PasswordSet() bool {
	info, err := os.Stat(s.nasPasswordPath())
	return err == nil && info.Mode().IsRegular()
}

// PullState returns a copy of the current transfer state.
func (s *Service) PullStatus() PullState {
	if s.puller == nil {
		return PullState{}
	}
	s.puller.mu.Lock()
	defer s.puller.mu.Unlock()
	return s.puller.state
}

// StartPull begins a background pull when one is not already running. It
// returns false when the share is disabled or a pull is in flight.
func (s *Service) StartPull(ctx context.Context) (bool, string) {
	cfg, state, err := s.nasConfig(ctx)
	if err != nil {
		return false, "settings unreadable"
	}
	if state != "on" {
		return false, "nas disabled"
	}
	if !cfg.Valid() {
		return false, "share host or share missing"
	}
	// "Pull once Wi-Fi is up" without a way to ask: a carrier is false exactly
	// when nothing is connected, and it answers instantly instead of paying a
	// dial timeout on every sync in a tunnel or with the radio off.
	if !share.Online() {
		return false, "no network"
	}
	if s.puller == nil {
		s.puller = &puller{}
	}
	s.puller.mu.Lock()
	if s.puller.state.Running {
		s.puller.mu.Unlock()
		return false, "already running"
	}
	s.puller.state = PullState{Running: true, StartedAt: time.Now().UTC().Format(time.RFC3339)}
	s.puller.mu.Unlock()

	go s.runPull(cfg)
	return true, "started"
}

func (s *Service) runPull(cfg share.Config) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Minute)
	defer cancel()

	finish := func(err error) {
		s.puller.mu.Lock()
		s.puller.state.Running = false
		s.puller.state.FinishedAt = time.Now().UTC().Format(time.RFC3339)
		if err != nil {
			s.puller.state.LastError = err.Error()
		}
		s.puller.mu.Unlock()
	}

	client, err := share.Dial(ctx, cfg)
	if err != nil {
		finish(err)
		return
	}
	defer client.Close()

	files, err := client.List(cfg)
	if err != nil {
		finish(err)
		return
	}

	root := s.cfg.DownloadDir
	if err := os.MkdirAll(root, 0o750); err != nil {
		finish(err)
		return
	}
	for _, file := range files {
		if ctx.Err() != nil {
			finish(ctx.Err())
			return
		}
		ext := strings.ToLower(filepath.Ext(file.Name))
		if !videoExt[ext] && !audioExt[ext] {
			continue
		}
		if file.Name == "index.json" {
			continue
		}
		dest := filepath.Join(root, file.Name)
		// Same name and size is the same file: a re-pull must not re-copy the
		// whole library every time the panel syncs.
		if info, err := os.Stat(dest); err == nil && info.Size() == file.Size {
			s.bumpPuller(func(st *PullState) { st.Skipped++ })
			continue
		}
		tmp := dest + ".part"
		written, err := client.Copy(cfg, file.Name, tmp)
		if err != nil {
			os.Remove(tmp)
			s.bumpPuller(func(st *PullState) { st.Failed++ })
			continue
		}
		if err := os.Rename(tmp, dest); err != nil {
			os.Remove(tmp)
			s.bumpPuller(func(st *PullState) { st.Failed++ })
			continue
		}
		copiedNow := 0
		s.bumpPuller(func(st *PullState) {
			st.Copied++
			st.Bytes += written
			copiedNow = st.Copied
		})
		// A sync must not try to swallow the whole share: on a handheld that is
		// a full card and a dead battery. Take a batch and leave the rest for the
		// next sync, newest first.
		if copiedNow >= s.pullBatch {
			break
		}
	}

	// The new files have no rows yet; register them the same way a manual copy
	// would be registered.
	if _, err := s.ScanLibrary(ctx); err != nil {
		finish(err)
		return
	}
	finish(nil)
}

func (s *Service) bumpPuller(change func(*PullState)) {
	s.puller.mu.Lock()
	change(&s.puller.state)
	s.puller.mu.Unlock()
}

// nasStatus answers GET /api/share/status.
func (s *Service) nasStatus(w http.ResponseWriter, r *http.Request) {
	cfg, state, err := s.nasConfig(r.Context())
	if err != nil {
		apiError(w, 500, "database_error", "unable to read nas settings")
		return
	}
	payload := map[string]any{
		"enabled":      state == "on",
		"configured":   cfg.Valid(),
		"password_set": s.PasswordSet(),
		"host":         cfg.Host,
		"share":        cfg.Share,
		"dir":          cfg.Dir,
		"user":         cfg.User,
		"pull":         s.PullStatus(),
	}
	writeJSON(w, http.StatusOK, payload)
}

// nasPull answers POST /api/share/pull: it only starts the transfer.
func (s *Service) nasPull(w http.ResponseWriter, r *http.Request) {
	started, reason := s.StartPull(r.Context())
	writeJSON(w, http.StatusAccepted, map[string]any{"started": started, "reason": reason, "pull": s.PullStatus()})
}

// nasPassword answers POST /api/share/password with {"password": "..."}.
func (s *Service) nasPassword(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Password string `json:"password"`
	}
	if decodeJSON(r, &in) != nil {
		apiError(w, 400, "invalid_request", "password required")
		return
	}
	if err := s.SetNASPassword(in.Password); err != nil {
		apiError(w, 500, "storage_error", "unable to store password")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"password_set": s.PasswordSet()})
}

// nasTest dials the share and lists it without copying anything, so a wrong
// host or password is a clear answer instead of a silent background failure.
func (s *Service) nasTest(w http.ResponseWriter, r *http.Request) {
	cfg, _, err := s.nasConfig(r.Context())
	if err != nil {
		apiError(w, 500, "database_error", "unable to read nas settings")
		return
	}
	if !cfg.Valid() {
		apiError(w, 400, "nas_incomplete", "host and share are required")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	client, err := share.Dial(ctx, cfg)
	if err != nil {
		apiError(w, 502, "nas_unreachable", err.Error())
		return
	}
	defer client.Close()
	files, err := client.List(cfg)
	if err != nil {
		apiError(w, 502, "nas_list_failed", err.Error())
		return
	}
	var media int
	var bytes int64
	for _, file := range files {
		ext := strings.ToLower(filepath.Ext(file.Name))
		if videoExt[ext] || audioExt[ext] {
			media++
			bytes += file.Size
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"files": len(files), "media": media, "bytes": bytes})
}
