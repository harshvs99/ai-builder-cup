# Phase 1 resources: everything needed to build and push the image.
# deploy.sh applies exactly these via -target before running Cloud Build.
# Keep the target list in deploy.sh in sync with this file.

locals {
  services = [
    "serviceusage.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "run.googleapis.com",
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
    "firestore.googleapis.com",
    "pubsub.googleapis.com",
    "secretmanager.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
  ]
}

resource "google_project_service" "apis" {
  for_each = toset(local.services)

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

# Docker repo. Cleanup keeps the 5 most recent versions and deletes anything
# older than a day; KEEP wins over DELETE, so the newest 5 always survive.
resource "google_artifact_registry_repository" "relay" {
  project       = var.project_id
  location      = var.region
  repository_id = "relay"
  format        = "DOCKER"
  description   = "Relay service images"

  cleanup_policy_dry_run = false

  cleanup_policies {
    id     = "keep-latest-5"
    action = "KEEP"
    most_recent_versions {
      keep_count = 5
    }
  }

  cleanup_policies {
    id     = "delete-older"
    action = "DELETE"
    condition {
      older_than = "86400s"
    }
  }

  depends_on = [google_project_service.apis]
}

# Build identity. New projects no longer grant Cloud Build's default account
# enough rights, so builds run as a dedicated least-privilege service account.
resource "google_service_account" "build" {
  project      = var.project_id
  account_id   = "relay-build"
  display_name = "Relay Cloud Build"

  depends_on = [google_project_service.apis]
}

# Staging bucket for build sources and nothing else. force_destroy so
# destroy.sh can remove it cleanly.
resource "google_storage_bucket" "build" {
  project                     = var.project_id
  name                        = "${var.project_id}-relay-build"
  location                    = var.region
  uniform_bucket_level_access = true
  force_destroy               = true

  lifecycle_rule {
    condition {
      age = 7
    }
    action {
      type = "Delete"
    }
  }

  depends_on = [google_project_service.apis]
}

resource "google_storage_bucket_iam_member" "build_reads_source" {
  bucket = google_storage_bucket.build.name
  role   = "roles/storage.objectViewer"
  member = google_service_account.build.member
}

resource "google_artifact_registry_repository_iam_member" "build_pushes" {
  project    = var.project_id
  location   = google_artifact_registry_repository.relay.location
  repository = google_artifact_registry_repository.relay.name
  role       = "roles/artifactregistry.writer"
  member     = google_service_account.build.member
}

# Build logs go to Cloud Logging (cloudbuild.yaml sets CLOUD_LOGGING_ONLY).
resource "google_project_iam_member" "build_logs" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = google_service_account.build.member
}
