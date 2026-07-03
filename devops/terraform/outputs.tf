output "public_ip" {
  value       = aws_eip.app_ip.public_ip
  description = "The public Elastic IP assigned to the instance"
}

output "ssh_command" {
  value       = "ssh -i ~/.ssh/${var.ssh_key_name}.pem ubuntu@${aws_eip.app_ip.public_ip}"
  description = "The command to SSH into the EC2 instance"
}

output "ecr_repository_urls" {
  value = {
    core      = aws_ecr_repository.core.repository_url
    agent     = aws_ecr_repository.agent.repository_url
    image_gen = aws_ecr_repository.image_gen.repository_url
  }
  description = "The URLs of the ECR repositories"
}

output "s3_bucket_name" {
  value       = aws_s3_bucket.media.id
  description = "The name of the S3 bucket for media"
}
