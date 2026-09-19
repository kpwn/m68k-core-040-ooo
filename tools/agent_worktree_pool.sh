#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pool_root="${M68K040_WORKTREE_POOL:-${repo_root}-worktrees}"
pool_size="${M68K040_WORKTREE_POOL_SIZE:-10}"
lock_file="${pool_root}/.pool.lock"

usage() {
  cat <<'USAGE'
Usage: tools/agent_worktree_pool.sh COMMAND [ARGS]

Commands:
  init                 Create the fixed agent worktree pool.
  status               Show reservation and HEAD state for every slot.
  reserve AGENT TASK   Reserve one free slot, sync it to main HEAD, print path.
  free SLOT|PATH       Free a reserved slot after confirming it is clean.

Environment:
  M68K040_WORKTREE_POOL       Override pool directory.
  M68K040_WORKTREE_POOL_SIZE  Override slot count; default 10.
USAGE
}

main_head() {
  git -C "$repo_root" rev-parse HEAD
}

slot_name() {
  printf "agent-%02d" "$1"
}

slot_path() {
  printf "%s/%s" "$pool_root" "$1"
}

reservation_file() {
  printf "%s/.agent-reservation" "$1"
}

is_reserved() {
  local path="$1"
  local file
  file="$(reservation_file "$path")"
  [[ -f "$file" ]] || return 1

  # Older revisions accidentally committed this runtime marker.  A released
  # tracked marker can therefore be inherited by every newly-created slot; it
  # is metadata from the source worktree, not a reservation of this slot.
  if git -C "$path" ls-files --error-unmatch -- .agent-reservation >/dev/null 2>&1 \
      && grep -q '^released_at=' "$file"; then
    return 1
  fi
  return 0
}

is_clean() {
  local path="$1"
  local status
  status="$(git -C "$path" status --porcelain | grep -vE '^.. \.agent-reservation$' || true)"
  [[ -z "$status" ]]
}

require_clean() {
  local path="$1"
  if ! is_clean "$path"; then
    echo "worktree is dirty: $path" >&2
    git -C "$path" status --short | grep -vE '^.. \.agent-reservation$' >&2 || true
    exit 1
  fi
}

sync_slot() {
  local path="$1"
  require_clean "$path"
  git -C "$path" checkout -q --detach "$(main_head)" >/dev/null
}

init_pool() {
  mkdir -p "$pool_root"
  for idx in $(seq 1 "$pool_size"); do
    local name path
    name="$(slot_name "$idx")"
    path="$(slot_path "$name")"
    if [[ -d "$path/.git" || -f "$path/.git" ]]; then
      if ! is_reserved "$path" && is_clean "$path"; then
        sync_slot "$path"
      fi
    else
      git -C "$repo_root" worktree add -q --detach "$path" "$(main_head)" >/dev/null
    fi
  done
}

status_pool() {
  for idx in $(seq 1 "$pool_size"); do
    local name path state head note
    name="$(slot_name "$idx")"
    path="$(slot_path "$name")"
    if [[ ! -e "$path" ]]; then
      printf "%s missing %s\n" "$name" "$path"
      continue
    fi
    head="$(git -C "$path" rev-parse --short HEAD)"
    if is_reserved "$path"; then
      state="reserved"
      note="$(tr '\n' ' ' < "$(reservation_file "$path")")"
    elif ! is_clean "$path"; then
      state="free-dirty"
      note="preserved; not eligible for reuse"
    else
      state="free"
      note=""
    fi
    printf "%s %-8s %s %s %s\n" "$name" "$state" "$head" "$path" "$note"
  done
}

reserve_slot() {
  local agent="${1:-}"
  local task="${2:-}"
  if [[ -z "$agent" || -z "$task" ]]; then
    echo "reserve requires AGENT and TASK" >&2
    usage >&2
    exit 1
  fi

  init_pool
  for idx in $(seq 1 "$pool_size"); do
    local name path
    name="$(slot_name "$idx")"
    path="$(slot_path "$name")"
    if ! is_reserved "$path" && is_clean "$path"; then
      sync_slot "$path"
      {
        printf "agent=%s\n" "$agent"
        printf "task=%s\n" "$task"
        printf "reserved_at=%s\n" "$(date -Is)"
        printf "head=%s\n" "$(main_head)"
      } > "$(reservation_file "$path")"
      printf "%s\n" "$path"
      return
    fi
  done

  echo "no clean free worktree slots" >&2
  exit 1
}

free_slot() {
  local arg="${1:-}"
  if [[ -z "$arg" ]]; then
    echo "free requires SLOT or PATH" >&2
    usage >&2
    exit 1
  fi

  local path
  if [[ "$arg" = /* ]]; then
    path="$arg"
  else
    path="$(slot_path "$arg")"
  fi

  if [[ ! -e "$path" ]]; then
    echo "unknown slot: $arg" >&2
    exit 1
  fi
  require_clean "$path"
  local file
  file="$(reservation_file "$path")"
  if git -C "$path" ls-files --error-unmatch -- .agent-reservation >/dev/null 2>&1; then
    # Migration path for reservations made while the marker was tracked.  A
    # later init/reserve sync will check out a revision where it is untracked.
    if ! grep -q '^released_at=' "$file"; then
      printf "released_at=%s\n" "$(date -Is)" >> "$file"
    fi
  else
    rm -f "$file"
  fi
}

cmd="${1:-}"
if [[ "$cmd" != "-h" && "$cmd" != "--help" && "$cmd" != "help" && "$cmd" != "" ]]; then
  mkdir -p "$pool_root"
  exec 9>"$lock_file"
  flock 9
fi

case "$cmd" in
  init)
    init_pool
    ;;
  status)
    status_pool
    ;;
  reserve)
    shift
    reserve_slot "$@"
    ;;
  free)
    shift
    free_slot "$@"
    ;;
  -h|--help|help|"")
    usage
    ;;
  *)
    echo "unknown command: $cmd" >&2
    usage >&2
    exit 1
    ;;
esac
