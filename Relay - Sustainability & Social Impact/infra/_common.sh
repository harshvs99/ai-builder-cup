# shellcheck shell=bash
# Variables below are consumed by the sourcing scripts.
# shellcheck disable=SC2034
# Sourced by deploy.sh and destroy.sh. Not executable on its own.

INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_DIR="${INFRA_DIR}/terraform"
ROOT_DIR="$(cd "${INFRA_DIR}/.." && pwd)"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

require_tools() {
  command -v gcloud >/dev/null 2>&1 || die "gcloud not found. Install the Google Cloud SDK."
  command -v terraform >/dev/null 2>&1 || die "terraform not found on PATH."
}

require_auth() {
  local acct
  acct="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null || true)"
  [[ -n "${acct}" ]] || die "No active gcloud account. Run: gcloud auth login"
  # Terraform's google provider reads Application Default Credentials.
  gcloud auth application-default print-access-token >/dev/null 2>&1 \
    || die "No Application Default Credentials. Run: gcloud auth application-default login"
  echo "gcloud account: ${acct}"
}

# State lives in a versioned GCS bucket in the target project, created here
# outside Terraform because the backend must exist before init. One workspace
# per project keeps state separate when one checkout targets several projects.
tf_select_workspace() {
  local project="$1"
  local bucket="${project}-relay-tfstate"
  if ! gcloud storage buckets describe "gs://${bucket}" --project "${project}" >/dev/null 2>&1; then
    echo "    creating state bucket gs://${bucket}"
    gcloud storage buckets create "gs://${bucket}" --project "${project}" --location "${REGION}" \
      --uniform-bucket-level-access --public-access-prevention >/dev/null
    gcloud storage buckets update "gs://${bucket}" --versioning >/dev/null
  fi
  terraform -chdir="${TF_DIR}" init -input=false -reconfigure -backend-config="bucket=${bucket}" >/dev/null
  terraform -chdir="${TF_DIR}" workspace select -or-create "${project}" >/dev/null
}

# Deploy needs the deployer's AI Studio key (paid tier) from $GEMINI_API_KEY; it is
# handed to Terraform through the environment and never printed.
require_gemini_key() {
  [[ -n "${GEMINI_API_KEY:-}" ]] \
    || die "GEMINI_API_KEY is not set. Create a paid-tier (billing-enabled) Google AI Studio key and run: export GEMINI_API_KEY=..."
  export TF_VAR_gemini_api_key="${GEMINI_API_KEY}"
}

TF_APPROVE=()
if [[ "${RELAY_AUTO_APPROVE:-0}" == "1" ]]; then
  TF_APPROVE=(-auto-approve)
fi
