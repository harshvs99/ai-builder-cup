variable "project_id" {
  description = "GCP project ID that hosts Relay."
  type        = string
}

variable "region" {
  description = "Region for Cloud Run, Artifact Registry and Firestore."
  type        = string
  default     = "us-central1"
}

variable "gemini_api_key" {
  description = "Google AI Studio (Gemini API) key, paid tier. Stored in Secret Manager as gemini-api-key. deploy.sh passes it via TF_VAR_gemini_api_key from $GEMINI_API_KEY."
  type        = string
  sensitive   = true
}

variable "gemini_model" {
  description = "Gemini model name passed to the backend as GEMINI_MODEL."
  type        = string
  default     = "gemini-3.8-flash"
}

variable "sitrep_gemini_model" {
  description = "Model that writes the hourly sitrep, passed as SITREP_GEMINI_MODEL. Falls back to gemini_model if a call fails."
  type        = string
  default     = "gemini-pro-latest"
}

variable "image" {
  description = "Full container image URI for the relay service, e.g. us-central1-docker.pkg.dev/PROJECT/relay/relay:TAG. deploy.sh builds it and passes it in."
  type        = string
}

variable "max_instances" {
  description = "Cloud Run max instances (min is always 0, scale to zero)."
  type        = number
  default     = 3
}
