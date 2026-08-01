output "deploy_role_arn" {
  value       = aws_iam_role.github_actions.arn
  description = "Set this as the AWS_DEPLOY_ROLE_ARN GitHub repository variable."
}

output "oidc_provider_arn" {
  value       = aws_iam_openid_connect_provider.github.arn
  description = "The GitHub OIDC provider ARN registered in this account."
}

output "next_step" {
  value       = <<-EOT

    Bootstrap complete. Register the role with GitHub:

      gh variable set AWS_DEPLOY_ROLE_ARN --repo ${var.github_repository} --body "${aws_iam_role.github_actions.arn}"

    Then delete the now-unused key secrets:

      gh secret delete AWS_ACCESS_KEY_ID     --repo ${var.github_repository}
      gh secret delete AWS_SECRET_ACCESS_KEY --repo ${var.github_repository}
      gh secret delete AWS_SESSION_TOKEN     --repo ${var.github_repository}
  EOT
  description = "Copy-pasteable follow-up commands."
}
