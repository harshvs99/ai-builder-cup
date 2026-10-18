#!/usr/bin/env bash
# Tear down everything deploy.sh created.
#   ./infra/destroy.sh <PROJECT_ID> [REGION]
# Env: RELAY_AUTO_APPROVE=1 skips the terraform confirmation prompt.
set -euo pipefail

PROJECT_ID="${1:-}"
REGION="${2:-us-central1}"
[[ -n "${PROJECT_ID}" ]] || {
  echo "usage: $0 <PROJECT_ID> [REGION]" >&2
  exit 2
}

# shellcheck source=_common.sh
source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

require_tools
require_auth
# The variable is required but irrelevant when destroying.
export TF_VAR_gemini_api_key="unused-on-destroy"

TF=(terraform -chdir="${TF_DIR}")

echo "==> terraform init (workspace: ${PROJECT_ID})"
tf_select_workspace "${PROJECT_ID}"

echo "==> terraform destroy"
# The image variable is required but irrelevant when destroying.
"${TF[@]}" destroy "${TF_APPROVE[@]}" \
  -var "project_id=${PROJECT_ID}" -var "region=${REGION}" \
  -var "image=us-docker.pkg.dev/cloudrun/container/hello"

# Terraform deletes the Artifact Registry repo together with its images. Only
# act if the repo somehow survived (for example a partial earlier destroy).
if gcloud artifacts repositories describe relay --location "${REGION}" --project "${PROJECT_ID}" \
  >/dev/null 2>&1; then
  echo "==> Artifact Registry repo still present, deleting it and its images"
  gcloud artifacts repositories delete relay --location "${REGION}" --project "${PROJECT_ID}" --quiet
fi

# Drop the now-empty workspace; ignore failure if state is not empty.
"${TF[@]}" workspace select default >/dev/null 2>&1 || true
"${TF[@]}" workspace delete "${PROJECT_ID}" >/dev/null 2>&1 || true

cat <<MSG

Destroyed. Intentionally left in place:
  - Enabled APIs (disable_on_destroy = false; disabling can break other workloads).
  - Cloud Build run history and Cloud Logging entries (retained per log bucket policy).
  - The Pub/Sub service agent identity.
Firestore database IDs are random per deploy, so rebuilding does not collide
with the deleted database. Rebuild with: ./infra/deploy.sh ${PROJECT_ID}
MSG
