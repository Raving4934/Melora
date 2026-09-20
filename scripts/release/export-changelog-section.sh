#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'HELP'
Usage:
  export-changelog-section.sh --version VERSION --section "HEADING" --output PATH [--changelog PATH]

Extract one level-3 release-note section from CHANGELOG.md without mixing product channels.
The section heading is supplied without the leading "### ".
HELP
}

fail() {
  printf 'CHANGELOG export failed: %s\n' "$1" >&2
  exit 1
}

VERSION=
SECTION=
OUTPUT=
CHANGELOG="CHANGELOG.md"
while (( $# > 0 )); do
  case "$1" in
    --version)
      (( $# >= 2 )) || fail '--version requires a value.'
      VERSION=$2
      shift 2
      ;;
    --section)
      (( $# >= 2 )) || fail '--section requires a value.'
      SECTION=$2
      shift 2
      ;;
    --output)
      (( $# >= 2 )) || fail '--output requires a value.'
      OUTPUT=$2
      shift 2
      ;;
    --changelog)
      (( $# >= 2 )) || fail '--changelog requires a value.'
      CHANGELOG=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]] || fail 'VERSION must be a semantic version.'
[[ -n "$SECTION" ]] || fail '--section is required.'
[[ -n "$OUTPUT" ]] || fail '--output is required.'
[[ -f "$CHANGELOG" ]] || fail "CHANGELOG does not exist: $CHANGELOG"

mkdir -p -- "$(dirname -- "$OUTPUT")"
TMP_OUTPUT="$(mktemp "${OUTPUT}.tmp.XXXXXX")"
trap 'rm -f -- "$TMP_OUTPUT"' EXIT

if awk -v version="[${VERSION}]" -v section="### ${SECTION}" '
  BEGIN {
    in_release = 0
    in_section = 0
    found_release = 0
    found_section = 0
  }
  substr($0, 1, length("## " version)) == "## " version {
    in_release = 1
    found_release = 1
    next
  }
  in_release && substr($0, 1, 3) == "## " {
    in_release = 0
    in_section = 0
  }
  in_release && $0 == section {
    in_section = 1
    found_section = 1
    print
    next
  }
  in_release && in_section && (substr($0, 1, 4) == "### " || substr($0, 1, 3) == "## ") {
    in_section = 0
  }
  in_release && in_section {
    print
  }
  END {
    if (!found_release) exit 2
    if (!found_section) exit 3
  }
' "$CHANGELOG" > "$TMP_OUTPUT"; then
  :
else
  status=$?
  case "$status" in
    2) fail "release section [${VERSION}] was not found in ${CHANGELOG}." ;;
    3) fail "section '${SECTION}' was not found under [${VERSION}] in ${CHANGELOG}." ;;
    *) fail "unable to read ${CHANGELOG}." ;;
  esac
fi

[[ -s "$TMP_OUTPUT" ]] || fail "section '${SECTION}' is empty."
{
  printf '# Melora %s release notes\n\n' "$VERSION"
  printf '> Exported from `CHANGELOG.md` section `### %s`.\n\n' "$SECTION"
  cat "$TMP_OUTPUT"
} > "$OUTPUT"
rm -f -- "$TMP_OUTPUT"
trap - EXIT
