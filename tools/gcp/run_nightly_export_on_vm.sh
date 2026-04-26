#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"
load_gcp_defaults

INSTANCE_NAME=${1:-$GCP_INSTANCE_NAME}

gcloud compute ssh "$INSTANCE_NAME" \
  --project "$GCP_PROJECT" \
  --zone "$GCP_ZONE" \
  --command "export PATH=\$HOME/.local/bin:\$PATH; cd ~/src/litert-build && WORK_ROOT=\$HOME/src/litert-build SRC_ROOT=\$HOME/src bash ~/src/litert-build/tools/bootstrap_litert_export_env.sh && bash ~/src/litert-build/tools/run_nightly_export_cache128.sh"
