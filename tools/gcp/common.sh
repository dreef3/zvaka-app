#!/usr/bin/env bash
set -euo pipefail

BACKEND_TFVARS_DEFAULT=/home/ae/calorie-drive-upload-api/terraform/terraform.tfvars

read_tfvars_value() {
  local tfvars_path=$1
  local key=$2
  if [[ ! -f "$tfvars_path" ]]; then
    return 1
  fi
  sed -nE "s/^${key}[[:space:]]*=[[:space:]]*\"([^\"]+)\"/\1/p" "$tfvars_path" | head -n 1
}

default_zone_for_region() {
  local region=$1
  case "$region" in
    europe-west1) echo "europe-west1-b" ;;
    us-central1) echo "us-central1-a" ;;
    us-east1) echo "us-east1-b" ;;
    us-east4) echo "us-east4-a" ;;
    *) echo "${region}-a" ;;
  esac
}

load_gcp_defaults() {
  local tfvars_path=${BACKEND_TFVARS_PATH:-$BACKEND_TFVARS_DEFAULT}
  local backend_project=
  local backend_region=

  backend_project=$(read_tfvars_value "$tfvars_path" project_id || true)
  backend_region=$(read_tfvars_value "$tfvars_path" region || true)

  export GCP_PROJECT=${GCP_PROJECT:-${backend_project:-$(gcloud config get-value project 2>/dev/null || true)}}
  export GCP_REGION=${GCP_REGION:-europe-west1}
  export GCP_ZONE=${GCP_ZONE:-$(default_zone_for_region "$GCP_REGION")}
  export GCP_INSTANCE_NAME=${GCP_INSTANCE_NAME:-litert-export-spot}
  export GCP_MACHINE_TYPE=${GCP_MACHINE_TYPE:-n4-standard-16}
  export GCP_BOOT_DISK_SIZE=${GCP_BOOT_DISK_SIZE:-200GB}
  export GCP_BOOT_DISK_TYPE=${GCP_BOOT_DISK_TYPE:-}
  export GCP_IMAGE_FAMILY=${GCP_IMAGE_FAMILY:-ubuntu-2404-lts-amd64}
  export GCP_IMAGE_PROJECT=${GCP_IMAGE_PROJECT:-ubuntu-os-cloud}

  if [[ -z "$GCP_PROJECT" ]]; then
    echo "GCP project is not set. Configure gcloud or set GCP_PROJECT." >&2
    exit 1
  fi
}
