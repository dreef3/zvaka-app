#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"
load_gcp_defaults

INSTANCE_NAME=${1:-$GCP_INSTANCE_NAME}

CREATE_ARGS=(
  --project "$GCP_PROJECT"
  --zone "$GCP_ZONE"
  --machine-type "$GCP_MACHINE_TYPE"
  --provisioning-model SPOT
  --instance-termination-action DELETE
  --maintenance-policy TERMINATE
  --image-family "$GCP_IMAGE_FAMILY"
  --image-project "$GCP_IMAGE_PROJECT"
  --boot-disk-size "$GCP_BOOT_DISK_SIZE"
  --scopes cloud-platform
)

if [[ -n "$GCP_BOOT_DISK_TYPE" ]]; then
  CREATE_ARGS+=(--boot-disk-type "$GCP_BOOT_DISK_TYPE")
fi

gcloud compute instances create "$INSTANCE_NAME" "${CREATE_ARGS[@]}"

echo
echo "Created $INSTANCE_NAME in $GCP_ZONE ($GCP_PROJECT)"
