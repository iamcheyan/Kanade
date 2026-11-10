# Builds the Kanade PocketJS package for PJOS (RG34XX / H700).
#
# The PocketJS CLI only compiles an app that lives inside the SDK tree, so this
# stages app/ into $POCKETJS_SDK/apps/kanade and config/ into
# $POCKETJS_SDK/programs/kanade first, exactly like the boot launcher does.
#
#   make build    -> dist/kanade.pocket
#   make check    -> admission check against the device runtime
#   make deploy   -> push the package to the card and launch it once
#   make clean
POCKETJS_SDK ?= $(HOME)/development/.pocketjs-star-drift-sdk
BUN ?= $(HOME)/.bun/bin/bun
TARGET ?= rocknix-h700-kms
PKG := dist/kanade.pocket
SERIAL ?= 5c0010894414c802512

# Fonts are 23 MB of OFL faces already vendored by the launcher checkout; they
# are staged here rather than committed twice.
FONT_SRC ?= $(HOME)/development/pocketshell/app/fonts

.PHONY: build check deploy clean distclean fonts

build: fonts
	POCKETJS_SDK="$(POCKETJS_SDK)" BUN="$(BUN)" TARGET="$(TARGET)" ./scripts/build.sh

fonts:
	@mkdir -p app/fonts
	@for f in FusionPixel-12px-monospaced-zh_hans.ttf fusion-pixel-12px-monospaced-ja.ttf SymbolsNerdFontMono-Regular.ttf; do \
	  [ -f "app/fonts/$$f" ] || cp "$(FONT_SRC)/$$f" "app/fonts/$$f" || { echo "missing font $$f (set FONT_SRC=)"; exit 1; }; \
	done

check: build
	./scripts/check.sh $(PKG)

deploy: check
	./scripts/deploy.sh $(PKG)

clean:
	rm -f $(PKG)

distclean: clean
	rm -rf dist app/fonts
