variable "aws_region" {
  type    = string
  default = "ap-northeast-2"
}

variable "aws_account_id" {
  description = "Expected AWS account; prevents deployment to another account."
  type        = string
}

variable "project_name" {
  type    = string
  default = "homecare"
}

variable "environment" {
  type    = string
  default = "prod"
}

variable "ami_id" {
  description = "Pinned Canonical Ubuntu 24.04 LTS x86_64 AMI in the target region."
  type        = string
}

variable "mysql_engine_version" {
  type    = string
  default = "8.4.11"
}

variable "db_backup_retention_days" {
  description = "Backup retention; use 1 for the current AWS free plan and 7 for service operation."
  type        = number
  default     = 7
  validation {
    condition     = var.db_backup_retention_days >= 1 && var.db_backup_retention_days <= 35 && floor(var.db_backup_retention_days) == var.db_backup_retention_days
    error_message = "Backup retention must be an integer from 1 to 35 days."
  }
}
