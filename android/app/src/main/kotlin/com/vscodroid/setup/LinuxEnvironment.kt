package com.vscodroid.setup

import android.content.Context
import com.vscodroid.util.Logger
import java.io.File

/**
 * A real Ubuntu userland under proot, so the terminal has `pkg` and `apt` the way Termux does.
 *
 * Why proot and not unpacking packages under filesDir: SELinux refuses execve of anything whose
 * inode lives under filesDir for targetSdk >= 29, and this app targets 36. proot never execve's a
 * guest binary. It rewrites the call to run its own loader, which maps the ELF itself, so a
 * rootfs under filesDir can run. proot itself (and its loader) are the only things exec'd, and
 * both ship in nativeLibraryDir, the one directory this app may execve from.
 *
 * What this class writes, into `~/.vscodroid`:
 *  - `linux.sh`: shell functions (`ubuntu`, `ubuntu-setup`, `ubuntu-remove`, `pkg`, `apt`,
 *    `apt-get`, `apt-cache`, `dpkg`) plus a `command_not_found_handle` that forwards a command the
 *    Android side does not have to the Ubuntu side when it exists there.
 *  - `ubuntu-setup.js`: downloads and unpacks the Ubuntu base image. Run with the bundled node,
 *    because there is no curl and no xz on the device.
 *
 * Both are generated whole at every launch, like the BASH_ENV file, so a moved nativeLibraryDir
 * never leaves a stale path behind. `linux.sh` is sourced from `.bashrc` (interactive shells) and
 * from the BASH_ENV file (everything else). It must print nothing when sourced and must be safe
 * to source twice, for the reasons given at [FirstRunSetup.createBashEnvFile].
 *
 * Nothing is downloaded until the user asks (`ubuntu-setup`, or says yes when `pkg`/`apt` offers).
 */
class LinuxEnvironment(private val context: Context) {

    private val tag = "LinuxEnvironment"

    fun ensure() {
        val dir = File(context.filesDir, "home/.vscodroid")
        dir.mkdirs()
        val files = context.filesDir.absolutePath
        val native = context.applicationInfo.nativeLibraryDir

        writeIfChanged(File(dir, "linux.sh"), shellScript(files, native))
        writeIfChanged(File(dir, "ubuntu-setup.js"), SETUP_JS)
        ensureBashrcSourcing()
    }

    private fun writeIfChanged(dest: File, content: String) {
        if (dest.isFile && runCatching { dest.readText() }.getOrNull() == content) return
        if (!writeAtomically(dest) { it.write(content.toByteArray()) }) {
            Logger.w(tag, "Could not write ${dest.name}; it keeps whatever it held before")
        }
    }

    /** Appends the sourcing line to an existing `.bashrc` once, atomically, bytes unchanged. */
    private fun ensureBashrcSourcing() {
        val bashrc = File(context.filesDir, "home/.bashrc")
        if (!bashrc.exists()) return
        val existing = bashrc.readBytes()
        if (String(existing, Charsets.ISO_8859_1).contains(SOURCE_MARKER)) return
        val block = "\n\n# Ubuntu under proot: pkg, apt, ubuntu\n$SOURCE_LINE\n"
        if (!writeAtomically(bashrc) { it.write(existing); it.write(block.toByteArray()) }) {
            Logger.w(tag, "Could not append the linux.sh sourcing to .bashrc; it is unchanged")
        }
    }

    companion object {
        const val SOURCE_MARKER = ".vscodroid/linux.sh"

        /** The same line goes into `.bashrc` and into the BASH_ENV file. */
        const val SOURCE_LINE =
            "[ -f \"\$HOME/.vscodroid/linux.sh\" ] && . \"\$HOME/.vscodroid/linux.sh\""

        // The scripts below are written with ¤ standing for a dollar sign, because a raw Kotlin
        // string would read every bash expansion as a template.
        private fun d(s: String) = s.replace('¤', '$')

        internal fun shellScript(files: String, native: String): String = d(
            """# VSCodroid: Ubuntu under proot. Generated at every launch; do not edit.
# Prints nothing when sourced, and is safe to source twice.
__vsc_files='$files'
__vsc_native='$native'
__vsc_root="¤__vsc_files/linux/ubuntu"

__vsc_proot() {
    mkdir -p "¤__vsc_files/tmp/proot" 2>/dev/null
    PROOT_LOADER="¤__vsc_native/libproot-loader.so" \
    PROOT_TMP_DIR="¤__vsc_files/tmp/proot" \
        "¤__vsc_native/libproot.so" "¤@"
}

__vsc_ubuntu_ready() { [ -e "¤__vsc_root/usr/bin/bash" ]; }

# Runs a command inside Ubuntu as (fake) root. The app's whole files directory is bound at the
# same path inside, so a project under it keeps its path and the working directory carries over.
__vsc_ubuntu_run() {
    local cwd="¤PWD"
    case "¤cwd" in "¤__vsc_files"|"¤__vsc_files"/*) ;; *) cwd=/root ;; esac
    local binds=(-b /dev -b /proc -b /sys -b "¤__vsc_files")
    [ -d /storage ] && binds+=(-b /storage)
    [ -d /sdcard ] && binds+=(-b /sdcard)
    __vsc_proot -0 --link2symlink --kill-on-exit -r "¤__vsc_root" -w "¤cwd" "¤{binds[@]}" \
        /usr/bin/env -i HOME=/root USER=root LOGNAME=root TERM="¤{TERM:-xterm-256color}" \
        LANG=C.UTF-8 TMPDIR=/tmp \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "¤@"
}

__vsc_ubuntu_ensure() {
    __vsc_ubuntu_ready && return 0
    if [ ! -x "¤__vsc_native/libproot.so" ]; then
        echo "Ubuntu needs proot, which this build does not include." >&2
        return 127
    fi
    if [ -t 0 ] && [ -t 1 ]; then
        printf 'Ubuntu is not installed yet (about 30 MB to download). Install it now? [Y/n] ' >&2
        local a
        read -r a
        case "¤a" in n|N|no|NO) return 1 ;; esac
        ubuntu-setup
        return ¤?
    fi
    echo "Ubuntu is not installed. Run: ubuntu-setup" >&2
    return 1
}

ubuntu-setup() {
    if [ ! -x "¤__vsc_native/libproot.so" ]; then
        echo "Ubuntu needs proot, which this build does not include." >&2
        return 127
    fi
    if __vsc_ubuntu_ready; then
        echo "Ubuntu is already installed. ubuntu-remove deletes it." >&2
        return 0
    fi
    mkdir -p "¤__vsc_files/tmp/proot" "¤__vsc_root" || return 1
    VSC_ROOT="¤__vsc_root" VSC_FILES="¤__vsc_files" VSC_PROOT="¤__vsc_native/libproot.so" \
    VSC_LOADER="¤__vsc_native/libproot-loader.so" VSC_TMP="¤__vsc_files/tmp/proot" \
        "¤__vsc_native/libnode.so" "¤HOME/.vscodroid/ubuntu-setup.js"
}

ubuntu-remove() {
    if [ ! -d "¤__vsc_root" ]; then
        echo "Ubuntu is not installed." >&2
        return 0
    fi
    printf 'Delete Ubuntu and everything installed in it? [y/N] ' >&2
    local a
    read -r a
    case "¤a" in y|Y|yes|YES) ;; *) return 1 ;; esac
    chmod -R u+rwX "¤__vsc_root" 2>/dev/null
    rm -rf "¤__vsc_root" && echo "Removed." >&2
}

# `ubuntu` opens a shell inside; `ubuntu cmd args` runs one command inside.
ubuntu() {
    __vsc_ubuntu_ensure || return ¤?
    if [ "¤#" -eq 0 ]; then
        __vsc_ubuntu_run /bin/bash -l
    else
        __vsc_ubuntu_run "¤@"
    fi
}

apt()       { __vsc_ubuntu_ensure || return ¤?; __vsc_ubuntu_run apt "¤@"; }
apt-get()   { __vsc_ubuntu_ensure || return ¤?; __vsc_ubuntu_run apt-get "¤@"; }
apt-cache() { __vsc_ubuntu_ensure || return ¤?; __vsc_ubuntu_run apt-cache "¤@"; }
dpkg()      { __vsc_ubuntu_ensure || return ¤?; __vsc_ubuntu_run dpkg "¤@"; }

# Termux-style front end to apt.
pkg() {
    local c="¤{1:-help}"
    [ "¤#" -gt 0 ] && shift
    case "¤c" in
        install|i|add)
            __vsc_ubuntu_ensure || return ¤?
            # A fresh image has no package lists, so `install` would find nothing.
            ls "¤__vsc_root"/var/lib/apt/lists/*Packages* >/dev/null 2>&1 ||
                __vsc_ubuntu_run apt-get update || return ¤?
            __vsc_ubuntu_run apt-get install -y "¤@" ;;
        uninstall|remove|rm|purge)
            __vsc_ubuntu_ensure || return ¤?
            __vsc_ubuntu_run apt-get remove -y "¤@" ;;
        update|up)
            __vsc_ubuntu_ensure || return ¤?
            __vsc_ubuntu_run apt-get update ;;
        upgrade|full-upgrade)
            __vsc_ubuntu_ensure || return ¤?
            __vsc_ubuntu_run apt-get update && __vsc_ubuntu_run apt-get upgrade -y ;;
        search|s)
            __vsc_ubuntu_ensure || return ¤?
            __vsc_ubuntu_run apt-cache search "¤@" ;;
        show)
            __vsc_ubuntu_ensure || return ¤?
            __vsc_ubuntu_run apt-cache show "¤@" ;;
        list-installed)
            __vsc_ubuntu_ensure || return ¤?
            __vsc_ubuntu_run apt list --installed ;;
        files)
            __vsc_ubuntu_ensure || return ¤?
            __vsc_ubuntu_run dpkg -L "¤@" ;;
        clean)
            __vsc_ubuntu_ensure || return ¤?
            __vsc_ubuntu_run apt-get clean ;;
        *)
            echo "Usage: pkg <install|uninstall|update|upgrade|search|show|list-installed|files|clean> [packages]" >&2
            echo "Also: ubuntu (shell), ubuntu-setup, ubuntu-remove" >&2
            [ "¤c" = help ] || [ "¤c" = -h ] || [ "¤c" = --help ] || return 1 ;;
    esac
}

# A command the Android side does not have, but Ubuntu does, runs in Ubuntu. Tools that exist on
# both sides keep running on the Android side. Bash 4+ only; older bash ignores this function.
command_not_found_handle() {
    local n="¤1"
    if [ -n "¤n" ] && case "¤n" in */*) false ;; *) true ;; esac && __vsc_ubuntu_ready; then
        local p
        for p in usr/local/bin usr/bin bin usr/local/sbin usr/sbin sbin; do
            if [ -e "¤__vsc_root/¤p/¤n" ] || [ -L "¤__vsc_root/¤p/¤n" ]; then
                __vsc_ubuntu_run "¤@"
                return ¤?
            fi
        done
    fi
    echo "bash: ¤n: command not found" >&2
    return 127
}
:
"""
        )

        // No dollar signs anywhere in this script, so it needs no escaping in a Kotlin raw string.
        // No template literals either, for the same reason.
        internal const val SETUP_JS = """'use strict';
// Downloads the Ubuntu 24.04 base image for arm64, checks its sha256 against Ubuntu's own
// SHA256SUMS, unpacks it under proot, and writes the few files apt needs to work there.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');
const cp = require('child_process');

const E = process.env;
const ROOT = E.VSC_ROOT, FILES = E.VSC_FILES, PROOT = E.VSC_PROOT;
const LOADER = E.VSC_LOADER, TMP = E.VSC_TMP;
const BASE = 'https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/';

function die(msg) {
  console.error('\nubuntu-setup: ' + msg);
  process.exit(1);
}

async function getText(url) {
  const r = await fetch(url);
  if (!r.ok) die('HTTP ' + r.status + ' for ' + url);
  return r.text();
}

function mb(n) { return (n / 1048576).toFixed(1) + ' MB'; }

async function download(url, dest, sha) {
  const res = await fetch(url);
  if (!res.ok || !res.body) die('HTTP ' + res.status + ' for ' + url);
  const total = Number(res.headers.get('content-length')) || 0;
  const out = fs.createWriteStream(dest);
  const hash = crypto.createHash('sha256');
  const reader = res.body.getReader();
  let got = 0, last = 0;
  for (;;) {
    const x = await reader.read();
    if (x.done) break;
    hash.update(x.value);
    got += x.value.length;
    if (!out.write(x.value)) await new Promise(function (r) { out.once('drain', r); });
    const now = Date.now();
    if (now - last > 400) {
      last = now;
      process.stdout.write('\rDownloading ' + mb(got) + (total ? ' / ' + mb(total) : '') + '   ');
    }
  }
  await new Promise(function (r) { out.end(r); });
  process.stdout.write('\rDownloaded ' + mb(got) + '                    \n');
  if (hash.digest('hex') !== sha) {
    fs.rmSync(dest, { force: true });
    die('the download does not match Ubuntu\'s checksum, so it was discarded');
  }
}

function put(rel, content, mode) {
  const p = path.join(ROOT, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.rmSync(p, { force: true });
  fs.writeFileSync(p, content, { mode: mode || 420 });
}

async function main() {
  console.log('Finding the latest Ubuntu 24.04 base image...');
  const sums = await getText(BASE + 'SHA256SUMS');
  let name = null, sha = null;
  sums.split('\n').forEach(function (line) {
    const m = /^([0-9a-f]{64}) \*?(\S+)/.exec(line);
    if (m && /^ubuntu-base-24\.04(\.[0-9]+)?-base-arm64\.tar\.gz/.test(m[2]) &&
        m[2].endsWith('.tar.gz')) { sha = m[1]; name = m[2]; }
  });
  if (!name) die('no arm64 base image is listed at ' + BASE);

  fs.mkdirSync(TMP, { recursive: true });
  const tarball = path.join(TMP, name);
  await download(BASE + name, tarball, sha);

  console.log('Unpacking (this takes a minute)...');
  fs.mkdirSync(ROOT, { recursive: true });
  const env = Object.assign({}, process.env, { PROOT_LOADER: LOADER, PROOT_TMP_DIR: TMP });
  const tar = cp.spawn(
    PROOT,
    ['--link2symlink', '-0', '/system/bin/tar', '-xf', '-', '-C', ROOT,
     '--exclude=./dev/*', '--exclude=dev/*'],
    { stdio: ['pipe', 'inherit', 'inherit'], env: env });
  tar.stdin.on('error', function () {});
  await new Promise(function (resolve) {
    tar.on('close', resolve);
    tar.on('error', function (e) { die('could not start proot: ' + e.message); });
    fs.createReadStream(tarball).pipe(zlib.createGunzip()).pipe(tar.stdin);
  });
  fs.rmSync(tarball, { force: true });

  if (!fs.existsSync(path.join(ROOT, 'usr/bin/bash'))) {
    die('the image did not unpack. Nothing usable was left, so run ubuntu-remove and try again.');
  }

  // apt and dpkg need these to work under proot and with no network namespace of their own.
  put('etc/resolv.conf', 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n');
  put('etc/hosts', '127.0.0.1 localhost\n::1 localhost\n');
  // apt drops to the _apt user for downloads, which proot's fake root cannot do.
  put('etc/apt/apt.conf.d/99vscodroid', 'APT::Sandbox::User "root";\nAcquire::Retries "3";\n');
  put('etc/dpkg/dpkg.cfg.d/99vscodroid', 'force-unsafe-io\n');
  // No services to start in here, so tell dpkg not to try.
  put('usr/sbin/policy-rc.d', '#!/bin/sh\nexit 101\n', 493);
  // The mount point for the app's files directory, so its paths read the same inside.
  fs.mkdirSync(path.join(ROOT, FILES), { recursive: true });
  try { fs.chmodSync(path.join(ROOT, 'tmp'), 1023); } catch (e) {}

  console.log('\nUbuntu is ready.');
  console.log('  pkg install python3 gcc vim    install packages');
  console.log('  ubuntu                         open a shell inside Ubuntu');
  console.log('A command that exists only in Ubuntu runs there automatically.');
}

main().catch(function (e) { die(String(e && e.message || e)); });
"""
    }
}
