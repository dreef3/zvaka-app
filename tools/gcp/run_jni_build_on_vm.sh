#!/usr/bin/env bash
# Syncs patched LiteRT-LM source (including prebuilt .so LFS files) to the GCP spot VM
# and builds liblitertlm_jni.so for android_arm64.
# Creates/uses the spot VM. Run from repo root.
# Usage: ./tools/gcp/run_jni_build_on_vm.sh [INSTANCE_NAME]
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/common.sh"
load_gcp_defaults

INSTANCE_NAME=${1:-$GCP_INSTANCE_NAME}
LITERT_LM_SRC=/home/ae/src/LiteRT-LM
REMOTE_SRC="~/src/LiteRT-LM"
REMOTE_LOG="~/src/jni-build.log"
NDK_PATH="\$HOME/android-ndk/android-ndk-r29"

echo "=== Syncing LiteRT-LM WORKSPACE and executor patch ==="
gcloud compute scp \
  --project "$GCP_PROJECT" \
  --zone "$GCP_ZONE" \
  "$LITERT_LM_SRC/WORKSPACE" \
  "$INSTANCE_NAME:$REMOTE_SRC/WORKSPACE"

gcloud compute scp \
  --project "$GCP_PROJECT" \
  --zone "$GCP_ZONE" \
  "$LITERT_LM_SRC/runtime/executor/llm_litert_npu_compiled_model_executor.cc" \
  "$INSTANCE_NAME:$REMOTE_SRC/runtime/executor/llm_litert_npu_compiled_model_executor.cc"

echo "=== Syncing prebuilt .so files (LFS content) ==="
gcloud compute scp \
  --project "$GCP_PROJECT" \
  --zone "$GCP_ZONE" \
  "$LITERT_LM_SRC/prebuilt/android_arm64/"*.so \
  "$INSTANCE_NAME:$REMOTE_SRC/prebuilt/android_arm64/"

gcloud compute scp \
  --project "$GCP_PROJECT" \
  --zone "$GCP_ZONE" \
  "$LITERT_LM_SRC/prebuilt/linux_x86_64/"*.so \
  "$INSTANCE_NAME:$REMOTE_SRC/prebuilt/linux_x86_64/"

echo "=== Bootstrapping build environment on VM ==="
gcloud compute ssh "$INSTANCE_NAME" --zone="$GCP_ZONE" --project="$GCP_PROJECT" \
  --command="bash $REMOTE_SRC/tools/gcp/bootstrap_jni_build_env.sh 2>&1"

echo "=== Building liblitertlm_jni.so on VM (background, log: $REMOTE_LOG) ==="
gcloud compute ssh "$INSTANCE_NAME" --zone="$GCP_ZONE" --project="$GCP_PROJECT" \
  --command="
    NDK=$NDK_PATH
    cd $REMOTE_SRC
    nohup bash -c '
      export ANDROID_NDK_HOME=$NDK_PATH
      cd $REMOTE_SRC
      bazel build //kotlin/java/com/google/ai/edge/litertlm/jni:litertlm_jni \
        --config=android_arm64 \
        --jobs=12 \
        --repo_env=ANDROID_NDK_HOME=$NDK_PATH \
        2>&1 | tee $REMOTE_LOG
    ' &
    disown
    echo \"JNI build started. PID: \$!\"
    echo \"Log: $REMOTE_LOG\"
    echo \"Tail: gcloud compute ssh $INSTANCE_NAME --zone=$GCP_ZONE -- tail -f $REMOTE_LOG\"
  "

echo ""
echo "=== JNI build running on VM. To check progress: ==="
echo "  gcloud compute ssh $INSTANCE_NAME --zone=$GCP_ZONE -- tail -f $REMOTE_LOG"
echo ""
echo "=== When done, fetch the .so: ==="
echo "  ./tools/gcp/fetch_jni_artifact.sh $INSTANCE_NAME"
