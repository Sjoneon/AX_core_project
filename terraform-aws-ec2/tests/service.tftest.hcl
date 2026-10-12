mock_provider "aws" {
  mock_resource "aws_instance" {
    defaults = { key_name = "" }
  }
  mock_data "aws_ami" {
    defaults = { id = "ami-0c713e716602facc2" }
  }
  mock_resource "aws_db_instance" {
    defaults = {
      master_user_secret = [{
        secret_arn    = "arn:aws:secretsmanager:ap-northeast-2:528215464049:secret:test"
        kms_key_id    = "arn:aws:kms:ap-northeast-2:528215464049:key/test"
        secret_status = "active"
      }]
    }
  }
}

variables {
  aws_account_id           = "528215464049"
  ami_id                   = "ami-0c713e716602facc2"
  db_backup_retention_days = 7
}

run "free_plan_backup_retention" {
  command = plan
  variables {
    db_backup_retention_days = 1
  }
  assert {
    condition     = aws_db_instance.mysql.backup_retention_period == 1 && aws_db_instance.mysql.deletion_protection && !aws_db_instance.mysql.skip_final_snapshot
    error_message = "Free-plan backup retention must retain deletion protection and a final snapshot."
  }
}

run "service_security_and_topology" {
  command = apply

  assert {
    condition = length(aws_subnet.database) == 2 && length(toset([
      for subnet in aws_subnet.database : subnet.availability_zone
    ])) == 2 && alltrue([for subnet in aws_subnet.database : !subnet.map_public_ip_on_launch])
    error_message = "Database subnets must be private and span two AZs."
  }
  assert {
    condition     = length(aws_route_table.database.route) == 0
    error_message = "Database route table must not contain internet or NAT routes."
  }
  assert {
    condition     = toset([for rule in aws_vpc_security_group_ingress_rule.web : rule.from_port]) == toset([80, 443])
    error_message = "Only HTTP and HTTPS may be exposed publicly."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.database.referenced_security_group_id == aws_security_group.ec2.id && aws_vpc_security_group_ingress_rule.database.from_port == 3306 && aws_vpc_security_group_ingress_rule.database.cidr_ipv4 == null
    error_message = "MySQL must accept traffic from the app security group only."
  }
  assert {
    condition     = !aws_db_instance.mysql.publicly_accessible && aws_db_instance.mysql.storage_encrypted && aws_db_instance.mysql.manage_master_user_password && aws_db_instance.mysql.password == null
    error_message = "RDS must be private, encrypted, and use an RDS-managed password."
  }
  assert {
    condition     = aws_db_instance.mysql.backup_retention_period == 7 && aws_db_instance.mysql.deletion_protection && !aws_db_instance.mysql.skip_final_snapshot
    error_message = "RDS backups and deletion safeguards must be enabled."
  }
  assert {
    condition     = aws_instance.ubuntu.key_name == "" && aws_instance.ubuntu.iam_instance_profile == aws_iam_instance_profile.ec2.name && aws_instance.ubuntu.metadata_options[0].http_tokens == "required" && aws_instance.ubuntu.root_block_device[0].encrypted
    error_message = "EC2 must use SSM without an SSH key, require IMDSv2, and encrypt its disk."
  }
  assert {
    condition     = output.public_ip == aws_eip.app.public_ip
    error_message = "The public endpoint must reference the Elastic IP."
  }
  assert {
    condition     = alltrue([for repository in aws_ecr_repository.service : repository.image_tag_mutability == "IMMUTABLE" && !repository.force_delete])
    error_message = "Release image tags must be immutable and repositories must not force-delete images."
  }
  assert {
    condition     = aws_s3_bucket_public_access_block.releases.block_public_acls && aws_s3_bucket_public_access_block.releases.block_public_policy && aws_s3_bucket_public_access_block.releases.ignore_public_acls && aws_s3_bucket_public_access_block.releases.restrict_public_buckets && aws_s3_bucket_versioning.releases.versioning_configuration[0].status == "Enabled"
    error_message = "Release files must be private and versioned."
  }
  assert {
    condition     = jsondecode(aws_s3_bucket_policy.releases.policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false"
    error_message = "Release bucket must reject unencrypted transport."
  }
}
