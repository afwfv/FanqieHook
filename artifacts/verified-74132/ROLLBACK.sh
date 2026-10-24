#!/usr/bin/env sh
set -eu

if [ "$#" -ne 1 ]; then
  printf 'usage: %s TARGET_COPY\n' "$0" >&2
  exit 2
fi

target=$1
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
baseline=$script_dir/ORIGINAL_FanqieModule.kt

if [ ! -f "$baseline" ]; then
  printf 'baseline not found: %s\n' "$baseline" >&2
  exit 3
fi

cp -- "$baseline" "$target"
printf 'restored=%s\n' "$target"
