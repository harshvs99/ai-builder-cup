#!/usr/bin/env bash
# Build and deploy Relay to a GCP project.
#   ./infra/deploy.sh <PROJECT_ID> [REGION]
# Env: RELAY_AUTO_APPROVE=1 skips the terraform confirmation prompts.
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
gcloud projects describe "${PROJECT_ID}" >/dev/null 2>&1 \
  || die "Project ${PROJECT_ID} not found or not accessible."

TF=(terraform -chdir="${TF_DIR}")
PLACEHOLDER_IMAGE="us-docker.pkg.dev/cloudrun/container/hello"

echo "==> Bootstrap APIs that Terraform itself needs"
gcloud services enable serviceusage.googleapis.com cloudresourcemanager.googleapis.com \
  --project "${PROJECT_ID}"

echo "==> terraform init (workspace: ${PROJECT_ID})"
tf_select_workspace "${PROJECT_ID}"

FIRST_RUN=0
gcloud storage buckets describe "gs://${PROJECT_ID}-relay-build" --project "${PROJECT_ID}" \
  >/dev/null 2>&1 || FIRST_RUN=1

echo "==> Phase 1: APIs, Artifact Registry, build service account and bucket"
"${TF[@]}" apply "${TF_APPROVE[@]}" \
  -var "project_id=${PROJECT_ID}" -var "region=${REGION}" -var "image=${PLACEHOLDER_IMAGE}" \
  -target=google_project_service.apis \
  -target=google_artifact_registry_repository.relay \
  -target=google_service_account.build \
  -target=google_storage_bucket.build \
  -target=google_storage_bucket_iam_member.build_reads_source \
  -target=google_artifact_registry_repository_iam_member.build_pushes \
  -target=google_project_iam_member.build_logs

if [[ "${FIRST_RUN}" == "1" ]]; then
  echo "    first run: waiting 30s for API and IAM propagation"
  sleep 30
fi

SHA="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo nogit)"
TAG="${SHA}-$(date -u +%Y%m%d%H%M%S)"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/relay/relay:${TAG}"
BUILD_SA="projects/${PROJECT_ID}/serviceAccounts/relay-build@${PROJECT_ID}.iam.gserviceaccount.com"

echo "==> Building ${IMAGE} with Cloud Build"
(
  cd "${ROOT_DIR}"
  gcloud builds submit \
    --project "${PROJECT_ID}" \
    --region "${REGION}" \
    --config infra/cloudbuild.yaml \
    --ignore-file infra/.gcloudignore \
    --substitutions "_IMAGE=${IMAGE}" \
    --service-account "${BUILD_SA}" \
    --gcs-source-staging-dir "gs://${PROJECT_ID}-relay-build/source" \
    .
)

echo "==> Phase 2: full apply"
"${TF[@]}" apply "${TF_APPROVE[@]}" \
  -var "project_id=${PROJECT_ID}" -var "region=${REGION}" -var "image=${IMAGE}"

SERVICE_URL="$("${TF[@]}" output -raw service_url)"

# Cloud Run reserves /healthz at its front end, so check the public alias.
echo "==> Health check: ${SERVICE_URL}/v1/health"
ok=0
for attempt in $(seq 1 12); do
  body="$(curl -fsS --max-time 20 "${SERVICE_URL}/v1/health" 2>/dev/null || true)"
  # Accepts {"ok": true} (current backend) or {"status": "ok"}.
  if grep -Eq '"ok"[[:space:]]*:[[:space:]]*true|"status"[[:space:]]*:[[:space:]]*"ok"' <<<"${body}"; then
    ok=1
    echo "    healthy (attempt ${attempt}): ${body}"
    break
  fi
  echo "    not ready (attempt ${attempt}/12), retrying in 5s"
  sleep 5
done
[[ "${ok}" == "1" ]] || die "/v1/health did not return ok at ${SERVICE_URL}. Check logs: gcloud run services logs read relay --region ${REGION} --project ${PROJECT_ID}"

cat <<MSG

Relay is live.
  Service URL:   ${SERVICE_URL}
  Dashboard URL: ${SERVICE_URL}
  Image:         ${IMAGE}

Device key (send as header X-Relay-Key from gateways):
  terraform -chdir="${TF_DIR}" workspace select ${PROJECT_ID} && terraform -chdir="${TF_DIR}" output -raw device_key

Tear down: ./infra/destroy.sh ${PROJECT_ID}
MSG
