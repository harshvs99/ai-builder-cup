output "service_url" {
  description = "Base URL of the Relay API and dashboard."
  value       = google_cloud_run_v2_service.relay.uri
}

output "dashboard_url" {
  description = "Dashboard (served by the same Cloud Run service)."
  value       = google_cloud_run_v2_service.relay.uri
}

output "firestore_database" {
  value = google_firestore_database.relay.name
}

output "pubsub_topic" {
  value = google_pubsub_topic.packets.id
}

output "artifact_repo" {
  description = "Docker repository path (without image name)."
  value       = "${google_artifact_registry_repository.relay.location}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.relay.repository_id}"
}

output "build_bucket" {
  description = "Cloud Build source staging bucket."
  value       = google_storage_bucket.build.name
}

output "build_service_account" {
  description = "Service account Cloud Build runs as."
  value       = google_service_account.build.email
}

output "device_key" {
  description = "Value for the X-Relay-Key header. Read with: terraform output -raw device_key"
  value       = random_password.device_key.result
  sensitive   = true
}
