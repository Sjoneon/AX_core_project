resource "aws_cloudwatch_dashboard" "service" {
  dashboard_name = local.name
  dashboard_body = jsonencode({
    widgets = [
      {
        type = "text", x = 0, y = 0, width = 24, height = 2
        properties = {
          markdown = "# Homecare production\nAPI: https://${aws_eip.app.public_ip}/actuator/health · Two containers: backend, nginx. Host metrics run every minute; EC2 metrics every five minutes. Email and budget notifications are not configured. Missing host metrics require checking the EC2 instance and homecare-metrics.timer."
        }
      },
      {
        type = "metric", x = 0, y = 2, width = 12, height = 6
        properties = {
          title = "EC2 CPU and memory (%)", view = "timeSeries", region = var.aws_region, period = 300, stat = "Average"
          metrics = [
            ["AWS/EC2", "CPUUtilization", "InstanceId", aws_instance.ubuntu.id],
            ["${var.project_name}/${var.environment}", "MemoryUsedPercent", "InstanceId", aws_instance.ubuntu.id]
          ]
          yAxis = { left = { min = 0, max = 100 } }
        }
      },
      {
        type = "metric", x = 12, y = 2, width = 12, height = 6
        properties = {
          title       = "EC2 root disk used (%)", view = "timeSeries", region = var.aws_region, period = 60, stat = "Maximum"
          metrics     = [["${var.project_name}/${var.environment}", "DiskUsedPercent", "InstanceId", aws_instance.ubuntu.id]]
          yAxis       = { left = { min = 0, max = 100 } }
          annotations = { horizontal = [{ value = 85, label = "Investigate disk usage" }] }
        }
      },
      {
        type = "metric", x = 0, y = 8, width = 8, height = 5
        properties = {
          title   = "HTTPS health (1 = UP)", view = "singleValue", region = var.aws_region, period = 60, stat = "Minimum"
          metrics = [["${var.project_name}/${var.environment}", "ServiceHealthy", "InstanceId", aws_instance.ubuntu.id]]
        }
      },
      {
        type = "metric", x = 8, y = 8, width = 8, height = 5
        properties = {
          title       = "TLS certificate remaining hours", view = "singleValue", region = var.aws_region, period = 60, stat = "Minimum"
          metrics     = [["${var.project_name}/${var.environment}", "CertificateRemainingHours", "InstanceId", aws_instance.ubuntu.id]]
          annotations = { horizontal = [{ value = 48, label = "Check renewal before expiry" }] }
        }
      },
      {
        type = "metric", x = 16, y = 8, width = 8, height = 5
        properties = {
          title   = "Certbot renewal timer (1 = active)", view = "singleValue", region = var.aws_region, period = 60, stat = "Minimum"
          metrics = [["${var.project_name}/${var.environment}", "CertbotTimerActive", "InstanceId", aws_instance.ubuntu.id]]
        }
      },
      {
        type = "metric", x = 0, y = 13, width = 12, height = 6
        properties = {
          title = "EC2 status checks and CPU credits", view = "timeSeries", region = var.aws_region, period = 300, stat = "Maximum"
          metrics = [
            ["AWS/EC2", "StatusCheckFailed", "InstanceId", aws_instance.ubuntu.id],
            ["AWS/EC2", "CPUCreditBalance", "InstanceId", aws_instance.ubuntu.id, { yAxis = "right", stat = "Minimum" }]
          ]
        }
      },
      {
        type = "metric", x = 12, y = 13, width = 12, height = 6
        properties = {
          title = "RDS storage and connections", view = "timeSeries", region = var.aws_region, period = 300, stat = "Minimum"
          metrics = [
            ["AWS/RDS", "FreeStorageSpace", "DBInstanceIdentifier", aws_db_instance.mysql.identifier],
            ["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", aws_db_instance.mysql.identifier, { yAxis = "right", stat = "Maximum" }]
          ]
        }
      }
    ]
  })
}
