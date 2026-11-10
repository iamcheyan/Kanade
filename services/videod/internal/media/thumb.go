package media

// Thumbnails for the panel list.
//
// The panel cannot fetch a URL: the guest has no network and the runtime has no
// JPEG decoder, so a remote cover URL is unrenderable there. The one thing that
// does work is `uploadTexture` with raw pixels, so the service decodes the
// sidecar cover next to the download and hands back exactly those bytes,
// already cropped to the row's aspect and small enough to send over the bridge.

import (
	"database/sql"
	"image"
	"image/jpeg"
	"image/png"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

const (
	thumbMaxW = 160
	thumbMaxH = 96
	thumbDefW = 64
	thumbDefH = 36
)

func (s *Service) thumb(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimSpace(r.PathValue("video_id"))
	if id == "" || strings.ContainsAny(id, "/\\..") {
		apiError(w, 400, "invalid_video_id", "video id required")
		return
	}
	tw := clampInt(queryInt(r, "w", thumbDefW), 8, thumbMaxW)
	th := clampInt(queryInt(r, "h", thumbDefH), 8, thumbMaxH)

	path := s.thumbPath(r, id)
	if path == "" {
		apiError(w, 404, "thumb_missing", "no local cover for this video")
		return
	}
	file, err := os.Open(path)
	if err != nil {
		apiError(w, 404, "thumb_missing", "cover unreadable")
		return
	}
	defer file.Close()

	var src image.Image
	switch strings.ToLower(filepath.Ext(path)) {
	case ".png":
		src, err = png.Decode(file)
	default:
		src, err = jpeg.Decode(file)
	}
	if err != nil {
		apiError(w, 415, "thumb_undecodable", "cover is not decodable")
		return
	}

	rgba := coverPixels(src, tw, th)
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("X-Thumb-Width", strconv.Itoa(tw))
	w.Header().Set("X-Thumb-Height", strconv.Itoa(th))
	w.Header().Set("Cache-Control", "private, max-age=3600")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(rgba)
}

// thumbPath looks for the sidecar cover next to the downloaded media. Both
// yt-dlp conventions appear in the library: `<id>.jpg` beside `<id>.mp4`, and
// `<output base>.jpg` for playlists that keep a suffix.
func (s *Service) thumbPath(r *http.Request, id string) string {
	var output string
	err := s.db.QueryRowContext(r.Context(),
		`SELECT output_path FROM downloads WHERE video_id=? AND state='completed' AND output_path<>'' LIMIT 1`,
		id).Scan(&output)
	if err != nil && err != sql.ErrNoRows {
		return ""
	}
	var candidates []string
	if output != "" {
		base := strings.TrimSuffix(output, filepath.Ext(output))
		candidates = append(candidates, base+".jpg", base+".png", base+".webp")
	}
	if dir := s.cfg.DownloadDir; dir != "" {
		candidates = append(candidates,
			filepath.Join(dir, id+".jpg"),
			filepath.Join(dir, id+".png"))
	}
	for _, candidate := range candidates {
		if info, err := os.Stat(candidate); err == nil && info.Mode().IsRegular() {
			return candidate
		}
	}
	return ""
}

// coverPixels centre-crops to the requested aspect (so rows keep one shape
// without distorting the frame) and box-filters down to target size.
func coverPixels(src image.Image, tw, th int) []byte {
	b := src.Bounds()
	sw, sh := b.Dx(), b.Dy()
	if sw <= 0 || sh <= 0 {
		return make([]byte, tw*th*4)
	}
	// Aspect-preserving source window.
	cropW, cropH := sw, sh
	if float64(sw)/float64(sh) > float64(tw)/float64(th) {
		cropW = sh * tw / th
	} else {
		cropH = sw * th / tw
	}
	if cropW < 1 {
		cropW = 1
	}
	if cropH < 1 {
		cropH = 1
	}
	x0 := b.Min.X + (sw-cropW)/2
	y0 := b.Min.Y + (sh-cropH)/2

	out := make([]byte, tw*th*4)
	// Area average: each destination pixel covers a box of source pixels, which
	// is what keeps a 160x90 cover from moiréing at 64x36.
	for ty := 0; ty < th; ty++ {
		sy0 := y0 + ty*cropH/th
		sy1 := y0 + (ty+1)*cropH/th
		if sy1 <= sy0 {
			sy1 = sy0 + 1
		}
		for tx := 0; tx < tw; tx++ {
			sx0 := x0 + tx*cropW/tw
			sx1 := x0 + (tx+1)*cropW/tw
			if sx1 <= sx0 {
				sx1 = sx0 + 1
			}
			var r, g, bl, a, n uint32
			for y := sy0; y < sy1; y++ {
				for x := sx0; x < sx1; x++ {
					cr, cg, cb, ca := src.At(x, y).RGBA()
					r += cr >> 8
					g += cg >> 8
					bl += cb >> 8
					a += ca >> 8
					n++
				}
			}
			if n == 0 {
				n = 1
			}
			i := (ty*tw + tx) * 4
			// PSM_8888 uploads the bytes untouched into an RGBA texture.
			out[i] = byte(r / n)
			out[i+1] = byte(g / n)
			out[i+2] = byte(bl / n)
			out[i+3] = byte(a / n)
		}
	}
	return out
}

func queryInt(r *http.Request, key string, fallback int) int {
	raw := strings.TrimSpace(r.URL.Query().Get(key))
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}

func clampInt(value, low, high int) int {
	if value < low {
		return low
	}
	if value > high {
		return high
	}
	return value
}
