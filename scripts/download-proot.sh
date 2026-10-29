#!/usr/bin/env bash
# Fetches proot, its loader and libtalloc from Termux's package repository and places them in
# jniLibs as libproot.so, libproot-loader.so and libtalloc.so, rewritten so they load from
# nativeLibraryDir instead of Termux's prefix. Needs: curl, ar, tar, xz, patchelf.
#
# Nothing here is installed at runtime: SELinux only lets this app execve from nativeLibraryDir,
# so proot has to ship inside the APK. It then runs the downloaded Ubuntu image by mapping the
# guest's binaries itself instead of execve'ing them.
set -euo pipefail

REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
PFX="data/data/com.termux/files/usr"

for t in curl ar tar xz patchelf; do
  command -v "$t" >/dev/null || { echo "missing tool: $t" >&2; exit 1; }
done

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir -p "$OUT"

curl -fsSL "$REPO/dists/stable/main/binary-aarch64/Packages" -o "$tmp/Packages"

filename_of() {   # newest stanza for a package name -> pool path
  awk -v p="$1" 'BEGIN{RS="";FS="\n"}
    { hit=0; fn="";
      for(i=1;i<=NF;i++){ if($i=="Package: " p) hit=1; if($i ~ /^Filename: /) fn=substr($i,11) }
      if(hit) last=fn }
    END{ print last }' "$tmp/Packages"
}

fetch_pkg() {     # name -> extracted tree in $tmp/<name>
  local name="$1" fn
  fn="$(filename_of "$name")"
  [ -n "$fn" ] || { echo "package $name not found in $REPO" >&2; exit 1; }
  echo "==> $name ($fn)"
  mkdir -p "$tmp/$name"
  curl -fsSL "$REPO/$fn" -o "$tmp/$name.deb"
  ( cd "$tmp/$name" && ar x "$tmp/$name.deb" && tar -xf data.tar.xz )
}

fetch_pkg proot
fetch_pkg libtalloc

cp "$tmp/proot/$PFX/bin/proot" "$OUT/libproot.so"
cp "$tmp/proot/$PFX/libexec/proot/loader" "$OUT/libproot-loader.so"
talloc="$(find "$tmp/libtalloc/$PFX/lib" -name 'libtalloc.so*' -type f | head -1)"
[ -n "$talloc" ] || { echo "libtalloc.so not found in its package" >&2; exit 1; }
cp "$talloc" "$OUT/libtalloc.so"
chmod 755 "$OUT/libproot.so" "$OUT/libproot-loader.so" "$OUT/libtalloc.so"

# Android only extracts files named lib*.so, so the versioned name proot asks for has to change,
# and the Termux library path it was built with has to go.
needed_talloc="$(patchelf --print-needed "$OUT/libproot.so" | grep -E '^libtalloc' || true)"
patchelf --set-soname libtalloc.so "$OUT/libtalloc.so"
[ -z "$needed_talloc" ] || patchelf --replace-needed "$needed_talloc" libtalloc.so "$OUT/libproot.so"
patchelf --set-rpath '$ORIGIN' "$OUT/libproot.so" "$OUT/libtalloc.so"

# Anything else proot wants has to be a system library, or the app cannot load it.
extra="$(patchelf --print-needed "$OUT/libproot.so" | grep -Ev '^(libc|libdl|libm|libtalloc)\.so$' || true)"
if [ -n "$extra" ]; then
  echo "WARNING: libproot.so also needs: $extra" >&2
  echo "         Bundle each of those the same way, or the loader fails on a device." >&2
fi

# The ELF and shell-path gates the Gradle build runs, when the repo has them.
if [ -f "$ROOT/scripts/verify-android-elf.py" ]; then
  python3 "$ROOT/scripts/verify-android-elf.py" --dir "$OUT" --lib-dir "$OUT" || true
fi
if [ -f "$ROOT/scripts/patch-default-shell.py" ]; then
  python3 "$ROOT/scripts/patch-default-shell.py" "$OUT/libproot.so" "$OUT/libproot-loader.so" || true
fi

echo "Done: libproot.so, libproot-loader.so, libtalloc.so are in $OUT"
