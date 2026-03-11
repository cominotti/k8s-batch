#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

readonly JAVA_SPDX='// SPDX-License-Identifier: Apache-2.0'
readonly SHELL_SPDX='# SPDX-License-Identifier: Apache-2.0'

apply_java_headers() {
  while IFS= read -r -d '' file; do
    first_line=$(sed -n '1p' "$file")
    if [[ "$first_line" == "$JAVA_SPDX" ]]; then
      continue
    fi

    tmp=$(mktemp)
    {
      printf '%s\n\n' "$JAVA_SPDX"
      cat "$file"
    } > "$tmp"
    cat "$tmp" > "$file"
    rm -f "$tmp"
    echo "Added Java SPDX header: $file"
  done < <(find . -type d \( -name .git -o -name target \) -prune -o -type f -name '*.java' -print0 | sort -z)
}

apply_shell_headers() {
  while IFS= read -r -d '' file; do
    first_line=$(sed -n '1p' "$file")
    second_line=$(sed -n '2p' "$file")

    if [[ "$first_line" == "$SHELL_SPDX" ]]; then
      continue
    fi
    if [[ "$first_line" == '#!'* ]] && [[ "$second_line" == "$SHELL_SPDX" ]]; then
      continue
    fi

    tmp=$(mktemp)
    if [[ "$first_line" == '#!'* ]]; then
      {
        printf '%s\n' "$first_line"
        printf '%s\n\n' "$SHELL_SPDX"
        tail -n +2 "$file"
      } > "$tmp"
    else
      {
        printf '%s\n\n' "$SHELL_SPDX"
        cat "$file"
      } > "$tmp"
    fi

    cat "$tmp" > "$file"
    rm -f "$tmp"
    echo "Added shell SPDX header: $file"
  done < <(find . -type d \( -name .git -o -name target \) -prune -o -type f -name '*.sh' -print0 | sort -z)
}

apply_java_headers
apply_shell_headers

echo "SPDX header application complete."
