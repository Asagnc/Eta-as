#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

sta_repo=$(cd "$(dirname "$0")/.." && pwd)
sta_ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [[ -z "$sta_ndk" ]]; then
    printf '%s\n' '请设置 ANDROID_NDK_HOME，指向 Android NDK r29。' >&2
    exit 64
fi
python3 - "$sta_ndk/source.properties" "$sta_repo/gradle/libs.versions.toml" <<'PY'
import pathlib, re, sys
properties = pathlib.Path(sys.argv[1])
catalog = pathlib.Path(sys.argv[2]).read_text()
version = re.search(r'^ndk\s*=\s*"([^"]+)"\s*$', catalog, re.MULTILINE)
if version is None:
    raise SystemExit('版本目录缺少 ndk 条目')
expected = version.group(1)
if not properties.is_file():
    raise SystemExit('无法读取 NDK source.properties：' + str(properties))
values = dict(line.split('=', 1) for line in properties.read_text().splitlines() if '=' in line)
revision = next((value.strip() for key, value in values.items() if key.strip() == 'Pkg.Revision'), '')
if revision != expected:
    raise SystemExit('此构建固定使用 NDK ' + expected + '，实际版本：' + (revision or '未知'))
PY
case $(uname -s) in
    Darwin) sta_host=darwin-x86_64 ;;
    Linux) sta_host=linux-x86_64 ;;
    *) printf '%s\n' '仅支持 macOS 或 Linux 构建主机。' >&2; exit 64 ;;
esac
sta_toolchain="$sta_ndk/toolchains/llvm/prebuilt/$sta_host/bin"
sta_sources=${STA_NATIVE_SOURCES:-$sta_repo/.analysis/sta-native-sources}
sta_build=${STA_NATIVE_BUILD:-$sta_repo/.analysis/sta-native-build}
sta_output="$sta_repo/app/src/main/jniLibs"
sta_api=34
trap 'printf "本地构建失败，请检查 %s 下的 configure.log / build.log。\n" "$sta_build" >&2' ERR
mkdir -p "$sta_sources" "$sta_build/tools" "$sta_output"
ln -sf "$sta_toolchain/llvm-readelf" "$sta_build/tools/readelf"
export PATH="$sta_build/tools:$PATH"
export PYTHONHASHSEED=1
export PYTHON=python3
export LC_ALL=C

fetch_source() {
    local name=$1 url=$2 digest=$3 directory=$4
    local archive="$sta_sources/$name.tar.gz"
    if [[ -f "$sta_sources/$name.tgz" ]]; then
        archive="$sta_sources/$name.tgz"
    fi
    if [[ ! -f "$archive" ]]; then
        if [[ -f "$sta_repo/app/src/main/assets/native-sources/$name.tgz" ]]; then
            cp "$sta_repo/app/src/main/assets/native-sources/$name.tgz" "$archive"
        else
            curl --fail --location --retry 3 "$url" -o "$archive.partial"
            mv "$archive.partial" "$archive"
        fi
    fi
    python3 - "$archive" "$digest" <<'PY'
import hashlib, pathlib, sys
actual = hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()
if actual != sys.argv[2]:
    raise SystemExit('源码 SHA-256 不匹配：' + sys.argv[1])
PY
    if [[ ! -d "$sta_sources/$directory" ]]; then
        tar -xzf "$archive" -C "$sta_sources"
    fi
}

fetch_source proot https://github.com/termux/proot/archive/refs/tags/v5.1.107.92.tar.gz \
    a1b070f55ec32b78e5033621476533d7230eb110275fe0cc3ee79c4fb334cfaa proot-5.1.107.92
fetch_source talloc https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz \
    dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd talloc-2.4.3
fetch_source shmem https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz \
    1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867 libandroid-shmem-0.7

for sta_abi in arm64-v8a x86_64 armeabi-v7a x86; do
    case "$sta_abi" in
        arm64-v8a) sta_target=aarch64-linux-android ;;
        x86_64) sta_target=x86_64-linux-android ;;
        armeabi-v7a) sta_target=armv7a-linux-androideabi ;;
        x86) sta_target=i686-linux-android ;;
    esac
    sta_cc="$sta_toolchain/$sta_target$sta_api-clang"
    sta_abi_build="$sta_build/$sta_abi"
    mkdir -p "$sta_abi_build" "$sta_output/$sta_abi"
    printf '编译 PTY：%s\n' "$sta_abi"
    "$sta_cc" -O2 -Wall -Wextra -Werror -fPIE -pie \
        -Wl,-z,max-page-size=16384 -Wl,-z,relro,-z,now \
        -ffile-prefix-map="$sta_repo"=. \
        "$sta_repo/app/src/main/cpp/sta_pty.c" -o "$sta_output/$sta_abi/libsta_pty.so"
    "$sta_toolchain/llvm-strip" "$sta_output/$sta_abi/libsta_pty.so"
    if [[ "$sta_abi" != arm64-v8a && "$sta_abi" != x86_64 ]]; then continue; fi

    sta_prefix="$sta_abi_build/prefix"
    mkdir -p "$sta_prefix/include/sys" "$sta_prefix/lib"
    # 在临时构建副本生成配置，固定源码缓存保持原样。
    if [[ ! -d "$sta_abi_build/talloc" ]]; then
        cp -R "$sta_sources/talloc-2.4.3" "$sta_abi_build/talloc"
    fi
    cat > "$sta_abi_build/talloc/cross-answers.txt" <<'ANSWERS'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
ANSWERS
    (
        cd "$sta_abi_build/talloc"
        export CC="$sta_cc" AR="$sta_toolchain/llvm-ar"
        export CFLAGS="-O2 -fPIC -D__STDC_WANT_LIB_EXT1__=1 -ffile-prefix-map=$sta_repo=."
        ./configure --prefix="$sta_prefix" --disable-rpath --disable-python \
            --cross-compile --cross-answers=cross-answers.txt > configure.log 2>&1
        python3 buildtools/bin/waf build --targets=talloc > build.log 2>&1
        "$sta_toolchain/llvm-ar" rcs "$sta_prefix/lib/libtalloc.a" bin/default/talloc.c.*.o
        cp talloc.h "$sta_prefix/include/talloc.h"
    )
    mkdir -p "$sta_abi_build/shmem"
    cp "$sta_sources/libandroid-shmem-0.7/shmem.c" "$sta_sources/libandroid-shmem-0.7/shm.h" "$sta_abi_build/shmem/"
    patch -s -p1 -d "$sta_abi_build/shmem" < "$sta_repo/scripts/native/shmem-app-temp.patch"
    "$sta_cc" -O2 -fPIC -std=c11 -ffile-prefix-map="$sta_repo"=. \
        -c "$sta_abi_build/shmem/shmem.c" -o "$sta_abi_build/shmem.o"
    "$sta_toolchain/llvm-ar" rcs "$sta_prefix/lib/libandroid-shmem.a" "$sta_abi_build/shmem.o"
    cp "$sta_sources/libandroid-shmem-0.7/shm.h" "$sta_prefix/include/sys/shm.h"
    mkdir -p "$sta_abi_build/proot"
    cp -R "$sta_sources/proot-5.1.107.92/." "$sta_abi_build/proot/"
    patch -s -p1 -d "$sta_abi_build/proot" < "$sta_repo/scripts/native/proot-bionic-headers.patch"
    (
        cd "$sta_abi_build/proot/src"
        sta_loader_address=$("$sta_cc" -E -dM -DNO_LIBC_HEADER arch.h | awk '$2 == "LOADER_ADDRESS" {print $3}')
        make -j4 \
            CC="$sta_cc" STRIP="$sta_toolchain/llvm-strip" \
            OBJCOPY="$sta_toolchain/llvm-objcopy" OBJDUMP="$sta_toolchain/llvm-objdump" \
            GIT=false HAS_LOADER_32BIT= PROOT_UNBUNDLE_LOADER=/dev/null PROOT_WITH_LIBANDROID_SHMEM=1 \
            CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$sta_prefix/include" \
            CFLAGS="-O2 -Wall -Wextra -fPIE -fno-stack-protector -ffile-prefix-map=$sta_repo=. -DWITH_LIBANDROID_SHMEM -DPROOT_UNBUNDLE_LOADER=\"\\\"/dev/null\\\"\" -DVERSION=\"\\\"5.1.107.92\\\"\"" \
            LDFLAGS="-pie -L$sta_prefix/lib -ltalloc -landroid-shmem -landroid -llog -Wl,-z,noexecstack,-z,max-page-size=16384,-z,relro,-z,now" \
            LOADER_LDFLAGS="-static -nostdlib -Wl,--build-id=none,-Ttext=$sta_loader_address,--rosegment,-z,noexecstack,-z,max-page-size=16384" \
            > build.log 2>&1
        cp proot "$sta_output/$sta_abi/libproot_exec.so"
        cp loader/loader "$sta_output/$sta_abi/libproot_loader.so"
        "$sta_toolchain/llvm-strip" "$sta_output/$sta_abi/libproot_exec.so" "$sta_output/$sta_abi/libproot_loader.so"
    )
    printf 'PRoot 与配对 loader 已生成：%s\n' "$sta_abi"
done

# 源码分发包从实际构建入口生成，避免 APK 内维护另一份脚本和补丁。
python3 - "$sta_repo" <<'PY'
import gzip, io, pathlib, tarfile, sys
root = pathlib.Path(sys.argv[1])
paths = [root / 'scripts/build-terminal-native.sh', root / 'app/src/main/cpp/sta_pty.c',
         root / 'gradle/libs.versions.toml']
paths += sorted((root / 'scripts/native').glob('*.patch'))
paths += [root / 'scripts/native/README.md']
destination = root / 'app/src/main/assets/native-sources/sta-native-build.tgz'
with destination.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed:
    with tarfile.open(fileobj=compressed, mode='w') as archive:
        for path in paths:
            data = path.read_bytes()
            info = tarfile.TarInfo(str(path.relative_to(root)))
            info.size = len(data)
            info.mode = 0o755 if path.suffix == '.sh' else 0o644
            archive.addfile(info, io.BytesIO(data))
PY

python3 - "$sta_output" "$sta_toolchain/llvm-readelf" <<'PY'
import hashlib, pathlib, re, subprocess, sys
root = pathlib.Path(sys.argv[1])
for path in sorted(root.glob('*/*.so')):
    headers = subprocess.check_output([sys.argv[2], '-lW', str(path)], text=True)
    alignments = [int(line.split()[-1], 16) for line in headers.splitlines() if line.lstrip().startswith('LOAD')]
    if not alignments or min(alignments) < 16384:
        raise SystemExit('ELF 未满足 16 KiB 页对齐：' + str(path))
    dynamic = subprocess.check_output([sys.argv[2], '-d', str(path)], text=True)
    libraries = re.findall(r'Shared library: \[(.+?)\]', dynamic)
    if set(libraries) - {'libc.so', 'libdl.so', 'libm.so', 'libandroid.so', 'liblog.so'}:
        raise SystemExit('ELF 存在未打包的动态依赖：' + str(path))
    print(hashlib.sha256(path.read_bytes()).hexdigest(), path.relative_to(root))
PY
