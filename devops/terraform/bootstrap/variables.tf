variable "aws_region" {
  type        = string
  default     = "us-east-1"
  description = "AWS region. Must match the region used by the main Terraform stack."
}

variable "project_name" {
  type        = string
  default     = "mercala"
  description = "Project name prefix for resource naming. Must match the main Terraform stack."
}

variable "github_repository" {
  type        = string
  default     = "hallelx2/mercala"
  description = "The owner/repo allowed to assume the deploy role."
}

variable "allowed_git_refs" {
  type        = list(string)
  default     = ["refs/heads/main"]
  description = <<-EOT
    Git refs permitted to assume the deploy role, as they appear in the OIDC `sub` claim.
    Deliberately narrow: only `main` deploys. Add e.g. "refs/tags/v*" for tag-triggered
    releases. Never widen this to "*" — that would let any branch on the repo, including
    one opened by a pull request, deploy to production.
  EOT

  # The warning above is only a comment, and comments do not stop a future edit. This
  # makes the constraint fail at plan time instead of silently granting every branch on
  # the repository the ability to assume the deploy role.
  validation {
    condition = alltrue([
      for ref in var.allowed_git_refs : ref != "*" && ref != "**" && !startswith(ref, "*")
    ])
    error_message = "allowed_git_refs must not be a bare wildcard — that would let any branch or pull request assume the deploy role. Name the refs explicitly, e.g. [\"refs/heads/main\"]."
  }

  validation {
    condition     = length(var.allowed_git_refs) > 0
    error_message = "allowed_git_refs must not be empty, or no workflow could assume the role."
  }
}
