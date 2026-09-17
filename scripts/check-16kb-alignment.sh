#!/usr/bin/env bash
# Verify that an APK supports 16 KB page sizes (required by Google Play for
# apps targeting Android 15+, enforced on 64-bit devices). Two things must
# hold:
#   1. zip alignment — uncompressed shared libraries sit on 16 KB boundaries
#      inside the APK (build-tools zipalign -P 16, needs build-tools 35.0.0+)
#   2. ELF alignment — every bundled arm64-v8a/x86_64 .so has PT_LOAD segments
#      aligned to at least 16384 bytes (readelf)
#
# Google's requirement covers 64-bit ABIs only; 16 KB devices run no 32-bit
# code, so armeabi-v7a/x86 libraries are reported as informational and do not
# fail the check (matching the official check_elf_alignment.sh scope). Some
# SDKs (e.g. ML Kit barcode-scanning) ship 4 KB-aligned 32-bit libs by design.
#
# Usage: scripts/check-16kb-alignment.sh <apk> [<apk> ...]
#
# Exits non-zero if any required check fails. Run this after bumping any
# dependency that ships native libraries.
set -uo pipefail

if [ "$#" -eq 0 ]; then
    echo "Usage: $0 <apk> [<apk> ...]" >&2
    exit 2
fi

for apk in "$@"; do
    [ -f "$apk" ] || { echo "error: no such file: $apk" >&2; exit 2; }
done

# Locate the newest installed build-tools zipalign that supports -P <pagesize>
# (the flag was added in build-tools 35.0.0).
find_zipalign() {
    local roots=()
    [ -n "${ANDROID_HOME:-}" ] && roots+=("$ANDROID_HOME")
    [ -n "${ANDROID_SDK_ROOT:-}" ] && roots+=("$ANDROID_SDK_ROOT")
    if [ -f local.properties ]; then
        roots+=("$(sed -n 's/^sdk\.dir=//p' local.properties | tr -d '\r')")
    fi
    roots+=("$HOME/Android/Sdk")

    local root z help
    for root in "${roots[@]}"; do
        [ -d "$root/build-tools" ] || continue
        for z in $(ls -1 "$root"/build-tools/*/zipalign 2>/dev/null | sort -rV); do
            help=$("$z" 2>&1 || true) # no args prints usage
            case "$help" in
                *"-P <pagesize"*)
                    echo "$z"
                    return 0
                    ;;
            esac
            break # newest build-tools lacks -P; older ones will too
        done
    done
    return 1
}

ZIPALIGN=$(find_zipalign) || {
    echo "error: no zipalign with -P support found (install build-tools 35.0.0+)" >&2
    exit 2
}
echo "using $ZIPALIGN"

check_zip_alignment() {
    local apk=$1 out
    out=$("$ZIPALIGN" -c -P 16 -v 4 "$apk" 2>&1)
    if [ $? -eq 0 ]; then
        echo "  zip alignment: OK (uncompressed .so entries on 16 KB boundaries)"
        return 0
    fi
    echo "  zip alignment: FAIL"
    echo "$out" | grep -v '(OK' | sed 's/^/    /'
    return 1
}

check_elf_alignment() {
    local apk=$1 tmpdir failed=0
    tmpdir=$(mktemp -d)
    trap 'rm -rf "$tmpdir"' RETURN

    # lib/<abi>/<name>.so paths are preserved so same-named libs across ABIs
    # are checked separately.
    unzip -q -o "$apk" 'lib/*' -d "$tmpdir" >/dev/null 2>&1 || true

    local sos
    sos=$(find "$tmpdir/lib" -name '*.so' 2>/dev/null)
    if [ -z "$sos" ]; then
        echo "  elf alignment: OK (no native libraries bundled)"
        return 0
    fi

    local so rel align dec min_dec abi required
    while IFS= read -r so; do
        rel=${so#"$tmpdir"/}
        abi=$(basename "$(dirname "$so")")
        case "$abi" in
            arm64-v8a | x86_64) required=1 ;;
            *) required=0 ;;
        esac
        min_dec=999999999999
        while IFS= read -r align; do
            dec=$((align))
            (( dec < min_dec )) && min_dec=$dec
        done < <(readelf -lW "$so" 2>/dev/null | awk '$1 == "LOAD" {print $NF}')
        if [ "$min_dec" -ge 16384 ]; then
            echo "  elf alignment: OK   $rel (min PT_LOAD align $min_dec)"
        elif [ "$required" -eq 1 ]; then
            echo "  elf alignment: FAIL $rel (min PT_LOAD align $min_dec < 16384)"
            failed=1
        else
            echo "  elf alignment: info $rel (32-bit ABI, exempt: min PT_LOAD align $min_dec)"
        fi
    done < <(echo "$sos")
    return $failed
}

overall=0
for apk in "$@"; do
    echo "==> $apk"
    ok=1
    check_zip_alignment "$apk" || ok=0
    check_elf_alignment "$apk" || ok=0
    if [ "$ok" -eq 1 ]; then
        echo "  PASS"
    else
        echo "  FAIL"
        overall=1
    fi
done

exit $overall
