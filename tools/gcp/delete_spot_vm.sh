#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"
load_gcp_defaults

INSTANCE_NAME=${1:-$GCP_INSTANCE_NAME}

gcloud compute instances delete "$INSTANCE_NAME" \
  --project "$GCP_PROJECT" \
  --zone "$GCP_ZONE" \
  --quiet

echo
echo "Deleted $INSTANCE_NAME from $GCP_ZONE ($GCP_PROJECT)"
