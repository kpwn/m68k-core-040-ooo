#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_script="$script_dir/agent_worktree_pool.sh"
test_root="$(mktemp -d)"
trap 'rm -rf -- "$test_root"' EXIT

repo="$test_root/repo"
pool="$test_root/pool"
mkdir -p "$repo/tools"
cp "$source_script" "$repo/tools/agent_worktree_pool.sh"
git -C "$repo" init -q
git -C "$repo" config user.email test@example.invalid
git -C "$repo" config user.name "worktree pool test"
printf 'payload\n' > "$repo/payload"
cat > "$repo/.agent-reservation" <<'EOF'
agent=old-agent
task=completed task
reserved_at=2026-01-01T00:00:00+00:00
released_at=2026-01-02T00:00:00+00:00
head=old
EOF
git -C "$repo" add tools/agent_worktree_pool.sh payload .agent-reservation
git -C "$repo" commit -qm "historical tracked reservation"

# Reproduce a slot created while the released marker was still tracked.
M68K040_WORKTREE_POOL="$pool" M68K040_WORKTREE_POOL_SIZE=1 \
  "$repo/tools/agent_worktree_pool.sh" init

# The fixed revision removes runtime metadata from source control.
printf '/.agent-reservation\n' > "$repo/.gitignore"
git -C "$repo" rm -q .agent-reservation
git -C "$repo" add .gitignore
git -C "$repo" commit -qm "untrack reservation metadata"

slot="$({
  M68K040_WORKTREE_POOL="$pool" M68K040_WORKTREE_POOL_SIZE=1 \
    "$repo/tools/agent_worktree_pool.sh" reserve test-agent "regression test"
})"
[[ "$slot" == "$pool/agent-01" ]]
grep -qx 'agent=test-agent' "$slot/.agent-reservation"
git -C "$slot" diff --quiet
[[ -z "$(git -C "$slot" status --porcelain)" ]]

M68K040_WORKTREE_POOL="$pool" M68K040_WORKTREE_POOL_SIZE=1 \
  "$repo/tools/agent_worktree_pool.sh" free agent-01
[[ ! -e "$slot/.agent-reservation" ]]

echo "agent worktree pool tests passed"
