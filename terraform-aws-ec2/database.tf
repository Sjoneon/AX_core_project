resource "aws_security_group" "database" {
  name        = "${local.name}-database"
  description = "MySQL from the application security group only"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-database" }
}

resource "aws_vpc_security_group_ingress_rule" "database" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.ec2.id
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  description                  = "Application MySQL connection"
}

resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = [for subnet in aws_subnet.database : subnet.id]
}

resource "aws_db_parameter_group" "mysql" {
  name   = "${local.name}-mysql84"
  family = "mysql8.4"
  parameter {
    name  = "require_secure_transport"
    value = "1"
  }
}

resource "aws_db_instance" "mysql" {
  identifier     = "${local.name}-mysql"
  engine         = "mysql"
  engine_version = var.mysql_engine_version
  instance_class = "db.t4g.micro"
  db_name        = "project_db"
  username       = "dbadmin"

  manage_master_user_password = true
  storage_type                = "gp3"
  allocated_storage           = 20
  max_allocated_storage       = 100
  storage_encrypted           = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  parameter_group_name   = aws_db_parameter_group.mysql.name
  vpc_security_group_ids = [aws_security_group.database.id]
  availability_zone      = aws_subnet.public.availability_zone
  multi_az               = false
  publicly_accessible    = false

  backup_retention_period   = var.db_backup_retention_days
  backup_window             = "18:00-19:00"
  maintenance_window        = "sun:19:00-sun:20:00"
  copy_tags_to_snapshot     = true
  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.name}-mysql-final"

  auto_minor_version_upgrade  = false
  allow_major_version_upgrade = false
  apply_immediately           = false
  engine_lifecycle_support    = "open-source-rds-extended-support-disabled"
}
