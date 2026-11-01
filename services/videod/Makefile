# Builds the on-device service that owns cookies, yt-dlp and the download queue.
#
#   make            arm64 binary for the phone
#   make run        run it on this host against a scratch data directory
#
BINARY  ?= videod-arm64
LDFLAGS ?= -s -w

.PHONY: build run tidy clean

build:
	GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags "$(LDFLAGS)" -o $(BINARY) ./cmd/videod

run:
	go run ./cmd/videod

tidy:
	go mod tidy

clean:
	rm -f $(BINARY)
