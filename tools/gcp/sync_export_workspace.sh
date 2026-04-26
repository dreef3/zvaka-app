#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"
load_gcp_defaults

INSTANCE_NAME=${1:-$GCP_INSTANCE_NAME}

LOCAL_WORKTREE=${LOCAL_WORKTREE:-/home/ae/weight-loss-app/.worktrees/litertlm-npu-setting}
LOCAL_LITERT_TORCH=${LOCAL_LITERT_TORCH:-/home/ae/src/litert-torch}

REMOTE_USER=$(
  gcloud compute ssh "$INSTANCE_NAME" \
    --project "$GCP_PROJECT" \
    --zone "$GCP_ZONE" \
    --command "whoami" \
    --quiet
)

REMOTE_IP=$(
  gcloud compute instances describe "$INSTANCE_NAME" \
    --project "$GCP_PROJECT" \
    --zone "$GCP_ZONE" \
    --format='get(networkInterfaces[0].accessConfigs[0].natIP)'
)

if [[ -z "$REMOTE_USER" || -z "$REMOTE_IP" ]]; then
  echo "Failed to determine remote SSH user or IP for $INSTANCE_NAME" >&2
  exit 1
fi

gcloud compute ssh "$INSTANCE_NAME" \
  --project "$GCP_PROJECT" \
  --zone "$GCP_ZONE" \
  --command "mkdir -p ~/src/litert-build ~/src && (command -v rsync >/dev/null 2>&1 || (sudo apt-get update && sudo apt-get install -y rsync))"

RSYNC_SSH=(ssh -i "$HOME/.ssh/google_compute_engine" -o StrictHostKeyChecking=accept-new)

rsync -az --delete \
  --exclude='.git/' \
  --exclude='__pycache__/' \
  --exclude='*.pyc' \
  -e "${RSYNC_SSH[*]}" \
  "$LOCAL_WORKTREE/tools/" \
  "$REMOTE_USER@$REMOTE_IP:~/src/litert-build/tools/"

rsync -az --delete \
  --exclude='.git/' \
  --exclude='__pycache__/' \
  --exclude='*.pyc' \
  -e "${RSYNC_SSH[*]}" \
  "$LOCAL_LITERT_TORCH/" \
  "$REMOTE_USER@$REMOTE_IP:~/src/litert-torch/"

echo
echo "Incrementally synced tools -> ~/src/litert-build/tools"
echo "Incrementally synced litert-torch -> ~/src/litert-torch"
