# Phase 2 resources: the running system. One `terraform apply` creates all of it.

locals {
  # Literal audience for Pub/Sub push OIDC tokens. The real Cloud Run URL is not
  # known until the service exists, and the subscription needs it as its
  # endpoint, so the audience is a fixed string that the backend checks. See
  # README "Backend contract".
  pubsub_audience = "relay-triage"
}

resource "random_id" "db_suffix" {
  byte_length = 3 # 6 hex chars
}

# ---------------------------------------------------------------- identities

resource "google_service_account" "run" {
  project      = var.project_id
  account_id   = "relay-run"
  display_name = "Relay Cloud Run runtime"

  depends_on = [google_project_service.apis]
}

resource "google_service_account" "push" {
  project      = var.project_id
  account_id   = "relay-push"
  display_name = "Relay Pub/Sub push invoker"

  depends_on = [google_project_service.apis]
}

# Project-level because Vertex AI and Cloud Logging have no useful
# resource-level grants.
resource "google_project_iam_member" "run_roles" {
  for_each = toset([
    "roles/aiplatform.user",
    "roles/logging.logWriter",
  ])

  project = var.project_id
  role    = each.value
  member  = google_service_account.run.member
}

# Firestore access is conditioned on Relay's own database, so the runtime
# cannot read or write other databases in a shared project.
resource "google_project_iam_member" "run_firestore" {
  project = var.project_id
  role    = "roles/datastore.user"
  member  = google_service_account.run.member

  condition {
    title      = "relay-db-only"
    expression = "resource.name == \"projects/${var.project_id}/databases/${google_firestore_database.relay.name}\""
  }
}

# ----------------------------------------------------------------- firestore

resource "google_firestore_database" "relay" {
  project                 = var.project_id
  name                    = "relay-${random_id.db_suffix.hex}"
  location_id             = var.region
  type                    = "FIRESTORE_NATIVE"
  deletion_policy         = "DELETE"
  delete_protection_state = "DELETE_PROTECTION_DISABLED"

  depends_on = [google_project_service.apis]
}

# -------------------------------------------------------------------- secret

resource "random_password" "device_key" {
  length  = 32
  special = false
}

resource "google_secret_manager_secret" "device_key" {
  project   = var.project_id
  secret_id = "relay-device-key"

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "device_key" {
  secret      = google_secret_manager_secret.device_key.id
  secret_data = random_password.device_key.result
}

resource "google_secret_manager_secret_iam_member" "run_reads_device_key" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.device_key.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = google_service_account.run.member
}

# ------------------------------------------------------------------- pub/sub

resource "google_pubsub_topic" "packets" {
  project = var.project_id
  name    = "relay-packets"

  depends_on = [google_project_service.apis]
}

resource "google_pubsub_topic" "packets_dlq" {
  project = var.project_id
  name    = "relay-packets-dlq"

  depends_on = [google_project_service.apis]
}

# Without a subscription, dead-lettered messages are dropped. This pull
# subscription retains them for 7 days so they can be inspected and replayed.
resource "google_pubsub_subscription" "packets_dlq_hold" {
  project                    = var.project_id
  name                       = "relay-packets-dlq-hold"
  topic                      = google_pubsub_topic.packets_dlq.id
  message_retention_duration = "604800s"
  expiration_policy {
    ttl = ""
  }
}

resource "google_pubsub_topic_iam_member" "run_publishes" {
  project = var.project_id
  topic   = google_pubsub_topic.packets.name
  role    = "roles/pubsub.publisher"
  member  = google_service_account.run.member
}

# The Pub/Sub service agent may not exist yet on a fresh project; this creates it.
resource "google_project_service_identity" "pubsub" {
  provider = google-beta

  project = var.project_id
  service = "pubsub.googleapis.com"

  depends_on = [google_project_service.apis]
}

# Mints the OIDC token for push deliveries on behalf of relay-push.
resource "google_service_account_iam_member" "pubsub_mints_push_token" {
  service_account_id = google_service_account.push.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = google_project_service_identity.pubsub.member
}

resource "google_pubsub_topic_iam_member" "pubsub_publishes_dlq" {
  project = var.project_id
  topic   = google_pubsub_topic.packets_dlq.name
  role    = "roles/pubsub.publisher"
  member  = google_project_service_identity.pubsub.member
}

resource "google_pubsub_subscription_iam_member" "pubsub_acks_dead_letters" {
  project      = var.project_id
  subscription = google_pubsub_subscription.triage.name
  role         = "roles/pubsub.subscriber"
  member       = google_project_service_identity.pubsub.member
}

resource "google_pubsub_subscription" "triage" {
  project              = var.project_id
  name                 = "relay-packets-triage"
  topic                = google_pubsub_topic.packets.id
  ack_deadline_seconds = 60

  retry_policy {
    minimum_backoff = "10s"
    maximum_backoff = "300s"
  }

  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.packets_dlq.id
    max_delivery_attempts = 5
  }

  expiration_policy {
    ttl = ""
  }

  push_config {
    push_endpoint = "${google_cloud_run_v2_service.relay.uri}/internal/triage"

    oidc_token {
      service_account_email = google_service_account.push.email
      audience              = local.pubsub_audience
    }
  }

  depends_on = [
    google_service_account_iam_member.pubsub_mints_push_token,
    google_pubsub_topic_iam_member.pubsub_publishes_dlq,
  ]
}

# ----------------------------------------------------------------- cloud run

resource "google_cloud_run_v2_service" "relay" {
  project             = var.project_id
  name                = "relay"
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL"
  deletion_protection = false

  template {
    service_account = google_service_account.run.email
    timeout         = "120s"

    scaling {
      min_instance_count = 0
      max_instance_count = var.max_instances
    }

    containers {
      image = var.image

      ports {
        container_port = 8080
      }

      resources {
        cpu_idle = true
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
      }

      startup_probe {
        http_get {
          path = "/healthz"
        }
        period_seconds    = 3
        timeout_seconds   = 2
        failure_threshold = 20
      }

      env {
        name  = "RELAY_STORE"
        value = "firestore"
      }
      env {
        name  = "RELAY_ENV"
        value = "prod"
      }
      env {
        name  = "RELAY_STATIC_DIR"
        value = "/app/static"
      }
      env {
        name  = "RELAY_FIRESTORE_DB"
        value = google_firestore_database.relay.name
      }
      env {
        name  = "RELAY_PUBSUB_TOPIC"
        value = google_pubsub_topic.packets.id
      }
      env {
        name  = "RELAY_PUBSUB_AUDIENCE"
        value = local.pubsub_audience
      }
      env {
        name  = "RELAY_PUBSUB_INVOKER_SA"
        value = google_service_account.push.email
      }
      env {
        name  = "GOOGLE_GENAI_USE_VERTEXAI"
        value = "true"
      }
      env {
        name  = "GOOGLE_CLOUD_PROJECT"
        value = var.project_id
      }
      env {
        name  = "GOOGLE_CLOUD_LOCATION"
        value = var.vertex_location
      }
      env {
        name  = "GEMINI_MODEL"
        value = var.gemini_model
      }
      env {
        name = "RELAY_DEVICE_KEY"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.device_key.secret_id
            version = google_secret_manager_secret_version.device_key.version
          }
        }
      }
    }
  }

  depends_on = [
    google_project_service.apis,
    google_project_iam_member.run_roles,
    google_project_iam_member.run_firestore,
    google_secret_manager_secret_iam_member.run_reads_device_key,
    google_pubsub_topic_iam_member.run_publishes,
  ]
}

# Public: phones and the dashboard call it directly. Device-key and OIDC checks
# happen inside the app.
resource "google_cloud_run_v2_service_iam_member" "public" {
  project  = var.project_id
  location = google_cloud_run_v2_service.relay.location
  name     = google_cloud_run_v2_service.relay.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_cloud_run_v2_service_iam_member" "push_invokes" {
  project  = var.project_id
  location = google_cloud_run_v2_service.relay.location
  name     = google_cloud_run_v2_service.relay.name
  role     = "roles/run.invoker"
  member   = google_service_account.push.member
}
