#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"
load_gcp_defaults

INSTANCE_NAME=${1:-$GCP_INSTANCE_NAME}
REMOTE_CMD=${REMOTE_CMD:-'bash ~/src/litert-build/tools/run_stable_export_cache128.sh'}

gcloud compute ssh "$INSTANCE_NAME" \
  --project "$GCP_PROJECT" \
  --zone "$GCP_ZONE" \
  --command "export PATH=\$HOME/.local/bin:\$PATH; bash ~/src/litert-build/tools/gcp/remote_bootstrap_stable_env.sh && $REMOTE_CMD"
