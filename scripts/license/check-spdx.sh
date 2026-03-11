#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

readonly JAVA_SPDX='// SPDX-License-Identifier: Apache-2.0'
readonly SHELL_SPDX='# SPDX-License-Identifier: Apache-2.0'

missing=0

echo "Checking SPDX headers in Java sources..."
while IFS= read -r -d '' file; do
  first_line=$(sed -n '1p' "$file")
  if [[ "$first_line" != "$JAVA_SPDX" ]]; then
    echo "Missing Java SPDX header: $file"
    missing=$((missing + 1))
  fi
done < <(find . -type d \( -name .git -o -name target \) -prune -o -type f -name '*.java' -print0 | sort -z)

echo "Checking SPDX headers in shell sources..."
while IFS= read -r -d '' file; do
  first_line=$(sed -n '1p' "$file")
  second_line=$(sed -n '2p' "$file")

  if [[ "$first_line" == '#!'* ]]; then
    if [[ "$second_line" != "$SHELL_SPDX" ]]; then
      echo "Missing shell SPDX header after shebang: $file"
      missing=$((missing + 1))
    fi
  elif [[ "$first_line" != "$SHELL_SPDX" ]]; then
    echo "Missing shell SPDX header: $file"
    missing=$((missing + 1))
  fi
done < <(find . -type d \( -name .git -o -name target \) -prune -o -type f -name '*.sh' -print0 | sort -z)

if [[ "$missing" -gt 0 ]]; then
  echo ""
  echo "ERROR: $missing file(s) are missing required SPDX headers."
  echo "Java files must start with: // SPDX-License-Identifier: Apache-2.0"
  echo "Shell files must include: # SPDX-License-Identifier: Apache-2.0"
  exit 1
fi

echo "All checked source files have proper Apache-2.0 SPDX headers."
