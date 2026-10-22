# Hourly situation report: Cloud Scheduler calls POST /v1/sitreps/run on the relay service, which
# writes one report per district (in parallel; Pro first, then the Flash fallback).
#
# NOT APPLIED. Added for review; `terraform plan` it before the next deploy.
#
# Auth mirrors the Pub/Sub push: a dedicated service account that holds run.invoker on the
# service, with an OIDC token minted by the scheduler. The service itself is public (allUsers,
# see main.tf) and, like the other dashboard routes, /v1/sitreps/run does not check the token in
# the app, so the OIDC token only matters if the service is later made private.

resource "google_project_service" "scheduler" {
  project            = var.project_id
  service            = "cloudscheduler.googleapis.com"
  disable_on_destroy = false
}

resource "google_service_account" "sitrep" {
  project      = var.project_id
  account_id   = "relay-sitrep"
  display_name = "Relay sitrep scheduler"

  depends_on = [google_project_service.apis]
}

resource "google_cloud_run_v2_service_iam_member" "sitrep_invokes" {
  project  = var.project_id
  location = google_cloud_run_v2_service.relay.location
  name     = google_cloud_run_v2_service.relay.name
  role     = "roles/run.invoker"
  member   = google_service_account.sitrep.member
}

resource "google_cloud_scheduler_job" "sitrep" {
  project     = var.project_id
  region      = var.region
  name        = "relay-sitrep-hourly"
  description = "Generate the hourly Relay situation report"
  schedule    = "0 * * * *"
  time_zone   = "Asia/Kolkata"

  attempt_deadline = "120s"

  retry_config {
    retry_count          = 2
    min_backoff_duration = "30s"
    max_backoff_duration = "300s"
  }

  http_target {
    http_method = "POST"
    uri         = "${google_cloud_run_v2_service.relay.uri}/v1/sitreps/run"

    oidc_token {
      service_account_email = google_service_account.sitrep.email
      audience              = google_cloud_run_v2_service.relay.uri
    }
  }

  depends_on = [
    google_project_service.scheduler,
    google_cloud_run_v2_service_iam_member.sitrep_invokes,
  ]
}
