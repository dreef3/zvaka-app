#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"
load_gcp_defaults

INSTANCE_NAME=${1:-$GCP_INSTANCE_NAME}
REMOTE_PATH=${2:-~/src/litert-build/tmp/gemma4-mt6985-build-512-stable214/gemma-4-e2b-it_mt6985_cpuaux.litertlm}
LOCAL_PATH=${3:-/home/ae/src/model-artifacts/gemma4-mt6985-cpuaux-gcp.litertlm}

mkdir -p "$(dirname "$LOCAL_PATH")"

gcloud compute scp \
  --project "$GCP_PROJECT" \
  --zone "$GCP_ZONE" \
  "$INSTANCE_NAME:$REMOTE_PATH" \
  "$LOCAL_PATH"

echo
echo "Fetched artifact to $LOCAL_PATH"
