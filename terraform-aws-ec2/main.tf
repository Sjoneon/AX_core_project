terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

# --------------------------------------------------
# Provider
# --------------------------------------------------

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "terraform-aws-ec2"
      ManagedBy = "Terraform"
    }
  }
}

# --------------------------------------------------
# Ubuntu AMI
# --------------------------------------------------

data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# --------------------------------------------------
# VPC
# --------------------------------------------------

resource "aws_vpc" "main" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "aws-ec2-vpc"
  }
}

# --------------------------------------------------
# Public Subnet
# --------------------------------------------------

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.20.1.0/24"
  map_public_ip_on_launch = true

  tags = {
    Name = "aws-ec2-public-subnet"
  }
}

# --------------------------------------------------
# Internet Gateway
# --------------------------------------------------

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "aws-ec2-igw"
  }
}

# --------------------------------------------------
# Public Route Table
# --------------------------------------------------

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "aws-ec2-public-rt"
  }
}

# --------------------------------------------------
# Route Table Association
# --------------------------------------------------

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# --------------------------------------------------
# Security Group
# --------------------------------------------------

resource "aws_security_group" "ec2" {
  name_prefix = "aws-ec2-sg-"
  description = "Allow SSH from my IP"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "SSH from my public IP"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Allow all outbound IPv4"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "aws-ec2-sg"
  }
}

# --------------------------------------------------
# SSH Key Pair
# --------------------------------------------------

resource "aws_key_pair" "ec2" {
  key_name_prefix = "aws-ec2-key-"

  public_key = file(
    pathexpand("~/.ssh/aws-ec2-key.pub")
  )

  tags = {
    Name = "aws-ec2-key"
  }
}

# --------------------------------------------------
# EC2 Instance
# --------------------------------------------------

resource "aws_instance" "ubuntu" {
  ami           = data.aws_ami.ubuntu.id
  instance_type = "t3.micro"

  subnet_id                   = aws_subnet.public.id
  associate_public_ip_address = true
  vpc_security_group_ids      = [aws_security_group.ec2.id]
  key_name                    = aws_key_pair.ec2.key_name

  # 유료 상세 모니터링 비활성화
  monitoring = false

  # T3 Unlimited CPU 추가 과금 방지
  credit_specification {
    cpu_credits = "standard"
  }

  # EC2 종료 시 루트 EBS 자동 삭제
  root_block_device {
    volume_type           = "gp3"
    volume_size           = 12
    encrypted             = true
    delete_on_termination = true
  }

  # EBS 볼륨 식별용 태그
  volume_tags = {
    Name = "aws-ec2-root"
  }

  # IMDSv2 필수
  metadata_options {
    http_tokens = "required"
  }

  # Public Route Table 구성 이후 EC2 생성
  depends_on = [
    aws_route_table_association.public
  ]

  tags = {
    Name = "aws-ec2-ubuntu"
  }
}