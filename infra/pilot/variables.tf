variable "region" {
  type    = string
  default = "us-east-1"
}
variable "name" {
  type    = string
  default = "otdog-pilot"
}
variable "ubuntu_ami" {
  description = "Operator-reviewed Ubuntu 24.04 x86_64 AMI in the selected region."
  type        = string
}
variable "document_bucket_name" {
  type = string
}
variable "cloudflare_ipv4_ranges" {
  description = "All current Cloudflare IPv4 CIDRs from https://www.cloudflare.com/ips-v4; review before every plan. No IPv6 origin is allocated."
  type        = set(string)
  validation {
    condition     = length(var.cloudflare_ipv4_ranges) > 0 && alltrue([for cidr in var.cloudflare_ipv4_ranges : can(cidrnetmask(cidr)) && cidr != "0.0.0.0/0"])
    error_message = "Supply reviewed Cloudflare IPv4 CIDRs; world ingress is forbidden."
  }
}
variable "runtime_parameter_arn" {
  description = "Exact SSM SecureString ARN holding runtime JSON. Value is created outside Terraform."
  type        = string
}
variable "runtime_kms_key_arn" {
  description = "Customer KMS key encrypting that one runtime parameter."
  type        = string
}
variable "github_oidc_provider_arn" {
  description = "Existing company GitHub OIDC provider ARN. Both GitHub inputs must be supplied together."
  type        = string
  default     = null
}
variable "github_repository" {
  description = "Company owner/repository used by the manual image workflow; no account identifiers committed."
  type        = string
  default     = null
}
