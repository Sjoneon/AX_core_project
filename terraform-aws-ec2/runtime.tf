locals {
  parameter_prefix = "/${var.project_name}/${var.environment}"
  runtime_parameters = {
    OPENAI_MODEL                  = "gpt-4o-mini"
    JWT_EXPIRATION_MS             = "3600000"
    SERVER_ADDRESS                = "0.0.0.0"
    SERVER_PORT                   = "8080"
    SPRING_DATASOURCE_USERNAME    = "homecare_app"
    SPRING_JPA_HIBERNATE_DDL_AUTO = "validate"
    SPRING_DATASOURCE_URL         = "jdbc:mysql://${aws_db_instance.mysql.address}:3306/project_db?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/app/config/rds-truststore.p12&trustCertificateKeyStorePassword=changeit&trustCertificateKeyStoreType=PKCS12"
  }
}

resource "aws_ssm_parameter" "runtime" {
  for_each = local.runtime_parameters
  name     = "${local.parameter_prefix}/${each.key}"
  type     = "String"
  value    = each.value
}

resource "aws_cloudwatch_log_group" "runtime" {
  for_each          = toset(["backend", "nginx", "mcp", "deploy"])
  name              = "${local.parameter_prefix}/${each.key}"
  retention_in_days = 14
}

resource "aws_iam_role_policy" "ec2_runtime" {
  name = "${local.name}-runtime"
  role = aws_iam_role.ec2.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuthentication"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid      = "PullServiceImages"
        Effect   = "Allow"
        Action   = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"]
        Resource = [for repository in aws_ecr_repository.service : repository.arn]
      },
      {
        Sid      = "ReadReleaseFiles"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = "${aws_s3_bucket.releases.arn}/releases/*"
      },
      {
        Sid      = "ReadAppParameters"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter", "ssm:GetParameters"]
        Resource = "arn:aws:ssm:${var.aws_region}:${var.aws_account_id}:parameter${local.parameter_prefix}/*"
      },
      {
        Sid         = "RestrictManagedSsmParameterAccess"
        Effect      = "Deny"
        Action      = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
        NotResource = "arn:aws:ssm:${var.aws_region}:${var.aws_account_id}:parameter${local.parameter_prefix}/*"
      },
      {
        Sid      = "WriteServiceLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:DescribeLogStreams", "logs:PutLogEvents"]
        Resource = [for group in aws_cloudwatch_log_group.runtime : "${group.arn}:*"]
      },
      {
        Sid      = "ReportServiceMetrics"
        Effect   = "Allow"
        Action   = "cloudwatch:PutMetricData"
        Resource = "*"
        Condition = {
          StringEquals = { "cloudwatch:namespace" = "${var.project_name}/${var.environment}" }
        }
      }
    ]
  })
}
