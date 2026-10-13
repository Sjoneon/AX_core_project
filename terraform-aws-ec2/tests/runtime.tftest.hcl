mock_provider "aws" {
  mock_resource "aws_iam_openid_connect_provider" {
    defaults = { arn = "arn:aws:iam::528215464049:oidc-provider/token.actions.githubusercontent.com" }
  }
  mock_resource "aws_db_instance" {
    defaults = {
      address = "test.ap-northeast-2.rds.amazonaws.com"
      master_user_secret = [{
        secret_arn    = "arn:aws:secretsmanager:ap-northeast-2:528215464049:secret:test"
        kms_key_id    = "arn:aws:kms:ap-northeast-2:528215464049:key/test"
        secret_status = "active"
      }]
    }
  }
}

variables {
  aws_account_id = "528215464049"
  ami_id         = "ami-0c713e716602facc2"
}

run "runtime_access_and_oidc" {
  command = apply
  assert {
    condition = jsondecode(aws_iam_role.github_deploy.assume_role_policy).Statement[0].Condition.StringEquals == {
      "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
      "token.actions.githubusercontent.com:sub" = "repo:gwonu1@190448546/core_project@1414051183:environment:production"
    }
    error_message = "GitHub trust must match this repository's immutable IDs and production environment exactly."
  }
  assert {
    condition = alltrue([for statement in jsondecode(aws_iam_role_policy.github_deploy.policy).Statement :
      !contains(flatten([statement.Action]), "ssm:GetParameters") &&
      !contains(flatten([statement.Action]), "secretsmanager:GetSecretValue") &&
      !contains(flatten([statement.Action]), "s3:GetObject")
    ])
    error_message = "GitHub must not read app secrets or Terraform state directly."
  }
  assert {
    condition     = jsondecode(aws_iam_role_policy.ec2_runtime.policy).Statement[4].Effect == "Deny" && jsondecode(aws_iam_role_policy.ec2_runtime.policy).Statement[4].NotResource == "arn:aws:ssm:ap-northeast-2:528215464049:parameter/homecare/prod/*"
    error_message = "The managed SSM policy must not allow reading other applications' parameters."
  }
  assert {
    condition     = alltrue([for parameter in aws_ssm_parameter.runtime : parameter.type == "String"]) && !contains(keys(aws_ssm_parameter.runtime), "JWT_SECRET") && !contains(keys(aws_ssm_parameter.runtime), "OPENAI_API_KEY") && !contains(keys(aws_ssm_parameter.runtime), "SPRING_DATASOURCE_PASSWORD")
    error_message = "App secret values must be registered outside Terraform and never stored in state."
  }
  assert {
    condition     = alltrue([for group in aws_cloudwatch_log_group.runtime : group.retention_in_days == 14]) && strcontains(aws_ssm_parameter.runtime["SPRING_DATASOURCE_URL"].value, "sslMode=VERIFY_IDENTITY") && aws_ssm_parameter.runtime["SPRING_JPA_HIBERNATE_DDL_AUTO"].value == "validate"
    error_message = "Runtime settings must require verified DB TLS and schema validation, and bound log retention."
  }
}

run "dashboard_and_metric_scope" {
  command = apply
  assert {
    condition = anytrue([for statement in jsondecode(aws_iam_role_policy.ec2_runtime.policy).Statement :
      statement.Sid == "ReportServiceMetrics" ? statement.Condition.StringEquals["cloudwatch:namespace"] == "homecare/prod" : false
    ])
    error_message = "The instance must publish custom metrics only to its service namespace."
  }
  assert {
    condition = alltrue([for metric in ["MemoryUsedPercent", "DiskUsedPercent", "ServiceHealthy", "CertificateRemainingHours", "CertbotTimerActive", "FreeStorageSpace", "DatabaseConnections", "StatusCheckFailed"] :
      contains(flatten([for widget in jsondecode(aws_cloudwatch_dashboard.service.dashboard_body).widgets :
        [for item in try(widget.properties.metrics, []) : item[1]]
      ]), metric)
    ])
    error_message = "The dashboard must include host, HTTPS, certificate and RDS observations."
  }
}
