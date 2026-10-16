terraform {
  required_version = ">= 1.6"

  # Partial config: _common.sh passes bucket=<project>-relay-tfstate at init.
  # Each workspace (one per project) is stored under relay/<workspace>.tfstate.
  backend "gcs" {
    prefix = "relay"
  }

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.0"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
      version = "~> 7.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# google-beta is used only for google_project_service_identity, which is not in
# the GA provider.
provider "google-beta" {
  project = var.project_id
  region  = var.region
}
