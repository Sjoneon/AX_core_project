output "instance_id" {
  value = aws_instance.ubuntu.id
}

output "public_ip" {
  value = aws_eip.app.public_ip
}

output "private_ip" {
  value = aws_instance.ubuntu.private_ip
}

output "vpc_id" {
  value = aws_vpc.main.id
}

output "rds_endpoint" {
  value = aws_db_instance.mysql.endpoint
}

output "rds_master_secret_arn" {
  description = "Secret reference only; the password is managed by RDS."
  value       = aws_db_instance.mysql.master_user_secret[0].secret_arn
}

output "ecr_repositories" {
  value = { for name, repository in aws_ecr_repository.service : name => repository.repository_url }
}

output "release_bucket" {
  value = aws_s3_bucket.releases.id
}

output "ssm_start_session" {
  value = "aws ssm start-session --region ${var.aws_region} --target ${aws_instance.ubuntu.id}"
}

output "github_deploy_role_arn" {
  value = aws_iam_role.github_deploy.arn
}

output "parameter_prefix" {
  value = local.parameter_prefix
}

output "log_groups" {
  value = { for name, group in aws_cloudwatch_log_group.runtime : name => group.name }
}
