output "instance_id" {
  description = "EC2 Instance ID"
  value       = aws_instance.ubuntu.id
}

output "public_ip" {
  description = "EC2 Public IPv4"
  value       = aws_instance.ubuntu.public_ip
}

output "private_ip" {
  description = "EC2 Private IPv4"
  value       = aws_instance.ubuntu.private_ip
}

output "vpc_id" {
  description = "Created VPC ID"
  value       = aws_vpc.main.id
}