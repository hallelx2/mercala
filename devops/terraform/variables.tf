variable "aws_region" {
  type        = string
  default     = "us-east-1"
  description = "AWS region to deploy resources"
}

variable "project_name" {
  type        = string
  default     = "mercala"
  description = "Project name suffix for resource naming"
}

variable "instance_type" {
  type        = string
  default     = "t3.small"
  description = "Compute instance type (t3.small has 2GB RAM)"
}

variable "ssh_key_name" {
  type        = string
  default     = "mercala-deploy-key"
  description = "Name of the AWS EC2 SSH Key Pair"
}

variable "spot_price" {
  type        = string
  default     = "0.015"
  description = "Maximum hourly bid price for the spot instance"
}
