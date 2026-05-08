#!/usr/bin/env bash
# Fetches the built liblitertlm_jni.so from the GCP spot VM.
# Usage: ./tools/gcp/fetch_jni_artifact.sh [INSTANCE_NAME] [LOCAL_DEST]
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"
load_gcp_defaults

INSTANCE_NAME=${1:-$GCP_INSTANCE_NAME}
REPO_ROOT=$(cd "$SCRIPT_DIR/../../../.." && pwd)
LOCAL_DEST=${2:-"$REPO_ROOT/.worktrees/npu-rebase/android/app/src/main/jniLibs/arm64-v8a/liblitertlm_jni.so"}

# Bazel output path for android_arm64 shared library
REMOTE_SO="~/src/LiteRT-LM/bazel-bin/kotlin/java/com/google/ai/edge/litertlm/jni/liblitertlm_jni.so"

echo "Fetching liblitertlm_jni.so from $INSTANCE_NAME..."
gcloud compute scp \
  --project "$GCP_PROJECT" \
  --zone "$GCP_ZONE" \
  "$INSTANCE_NAME:$REMOTE_SO" \
  "$LOCAL_DEST"

echo "Fetched to: $LOCAL_DEST"
echo ""
echo "Verify prefill signature:"
strings "$LOCAL_DEST" | grep -E 'prefill_[0-9]+'
