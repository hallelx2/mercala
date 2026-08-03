##
# One-time bootstrap. Run this ONCE, locally, with your own authenticated AWS CLI.
#
# It creates the GitHub OIDC trust relationship and the IAM role that GitHub Actions
# assumes on every deploy. After this exists, no AWS access keys are ever minted,
# stored, or rotated again — the only thing in GitHub is the role ARN, which is not
# a secret.
#
#   cd devops/terraform/bootstrap
#   terraform init
#   terraform apply
#
# State is local and gitignored. These resources are created once and effectively
# never change; if you lose the state, `terraform import` or recreate.
##

terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

data "aws_caller_identity" "current" {}

##
# GitHub's OIDC identity provider.
#
# This is an ACCOUNT-WIDE SINGLETON: AWS permits exactly one provider per issuer URL, and
# every project in the account shares it. So by default this stack only references the
# existing one rather than managing it.
#
# That is deliberate. If Mercala owned the provider, `terraform destroy` here would delete
# it out from under every other project trusting it — this account already has
# VoxtarGitHubDeployRole depending on the same provider.
#
# Set create_oidc_provider = true only in an account where it does not exist yet. The
# first project to bootstrap creates it; everyone after references it.
#
# AWS stopped validating GitHub's thumbprints in 2023, but the argument is still required.
##
resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  thumbprint_list = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1c58a3a8518e8759bf075b76b750d4f2df264fcd",
  ]

  tags = {
    Name    = "${var.project_name}-github-oidc"
    Project = var.project_name
  }
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 0 : 1

  url = "https://token.actions.githubusercontent.com"
}

locals {
  github_oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn
}

##
# The role GitHub Actions assumes.
#
# The `sub` condition is what makes this safe: only workflows running on the named
# branches of the named repo can assume it. Any other repo, fork, or branch presenting
# a valid GitHub token is rejected by STS.
##
data "aws_iam_policy_document" "github_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [for ref in var.allowed_git_refs : "repo:${var.github_repository}:ref:${ref}"]
    }
  }
}

resource "aws_iam_role" "github_actions" {
  name                 = "${var.project_name}-github-actions"
  description          = "Assumed by GitHub Actions via OIDC to deploy ${var.project_name}. No static keys."
  assume_role_policy   = data.aws_iam_policy_document.github_assume_role.json
  max_session_duration = 3600

  tags = {
    Name    = "${var.project_name}-github-actions"
    Project = var.project_name
  }
}

##
# What the deploy role is allowed to do.
#
# Terraform manages the VPC, spot instance, EIP and security groups, so the EC2 grant
# is necessarily broad. Everything else is scoped to this project's resources.
##
data "aws_iam_policy_document" "deploy" {
  # Terraform-managed networking and compute.
  statement {
    sid       = "ManageProjectInfrastructure"
    effect    = "Allow"
    actions   = ["ec2:*"]
    resources = ["*"]
  }

  # ECR login is account-wide by design; the token it returns is still scoped by the
  # repository-level grant below.
  statement {
    sid       = "EcrAuth"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPushPull"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:CreateRepository",
      "ecr:DescribeRepositories",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:ListTagsForResource",
      "ecr:PutImage",
      "ecr:PutImageScanningConfiguration",
      "ecr:TagResource",
      "ecr:UploadLayerPart",
    ]
    resources = ["arn:aws:ecr:${var.aws_region}:${data.aws_caller_identity.current.account_id}:repository/${var.project_name}-*"]
  }

  # Terraform remote state + the media bucket.
  #
  # The Get* list is long because refreshing an aws_s3_bucket resource reads the bucket's
  # entire configuration surface — ACL, CORS, logging, lifecycle, encryption and the rest
  # — even when the config sets none of them. Granting only the few this project actually
  # configures fails the refresh with AccessDenied on whichever it happens to read first,
  # which is one broken deploy per missing action. These are all read-only and scoped to
  # this project's bucket.
  statement {
    sid    = "StateAndMediaBuckets"
    effect = "Allow"
    actions = [
      "s3:CreateBucket",
      "s3:DeleteObject",
      "s3:GetAccelerateConfiguration",
      "s3:GetAnalyticsConfiguration",
      "s3:GetBucketAcl",
      "s3:GetBucketCORS",
      "s3:GetBucketLocation",
      "s3:GetBucketLogging",
      "s3:GetBucketNotification",
      "s3:GetBucketObjectLockConfiguration",
      "s3:GetBucketOwnershipControls",
      "s3:GetBucketPolicy",
      "s3:GetBucketPublicAccessBlock",
      "s3:GetBucketRequestPayment",
      "s3:GetBucketTagging",
      "s3:GetBucketVersioning",
      "s3:GetBucketWebsite",
      "s3:GetEncryptionConfiguration",
      "s3:GetInventoryConfiguration",
      "s3:GetLifecycleConfiguration",
      "s3:GetMetricsConfiguration",
      "s3:GetObject",
      "s3:GetReplicationConfiguration",
      "s3:ListBucket",
      # Setting and clearing a bucket policy: needed to grant anonymous read on the
      # public media bucket, and nowhere else. Without them Terraform can create that
      # bucket and then fail on the statement that makes it useful.
      "s3:DeleteBucketPolicy",
      "s3:PutBucketPolicy",
      "s3:PutBucketPublicAccessBlock",
      "s3:PutBucketTagging",
      "s3:PutObject",
    ]
    # Scoped to this project's namespace rather than to one bucket by name. The stack now
    # wants a second bucket — public-read, holding nothing but finished product imagery,
    # so that the private one can keep the Terraform state, the TLS archive and the
    # database backups without a public policy anywhere near them (HAL-425, HAL-577).
    #
    # This file is applied by hand, locally, with a human's credentials. Until somebody
    # runs that apply the deploy role cannot create the second bucket, which is why the
    # main stack does not yet declare it.
    resources = [
      "arn:aws:s3:::${var.project_name}-*",
      "arn:aws:s3:::${var.project_name}-*/*",
    ]
  }

  # Terraform creates and attaches the EC2 instance profile, so it needs to manage
  # that role and hand it to the instance.
  #
  # As with the S3 statement, the read actions are enumerated exhaustively rather than
  # minimally. Terraform reads back every resource it writes to confirm the result and to
  # refresh state, so a create that succeeds still fails on the follow-up Get if that one
  # action is missing — costing a whole deploy per omission. All of these stay scoped to
  # the mercala-* namespace.
  statement {
    sid    = "ManageInstanceRole"
    effect = "Allow"
    actions = [
      "iam:AddRoleToInstanceProfile",
      "iam:AttachRolePolicy",
      "iam:CreateInstanceProfile",
      "iam:CreatePolicy",
      "iam:CreatePolicyVersion",
      "iam:CreateRole",
      "iam:DeleteInstanceProfile",
      "iam:DeletePolicy",
      "iam:DeletePolicyVersion",
      "iam:DeleteRole",
      "iam:DeleteRolePolicy",
      "iam:DetachRolePolicy",
      "iam:GetInstanceProfile",
      "iam:GetPolicy",
      "iam:GetPolicyVersion",
      "iam:GetRole",
      "iam:GetRolePolicy",
      "iam:ListAttachedRolePolicies",
      "iam:ListInstanceProfileTags",
      "iam:ListInstanceProfilesForRole",
      "iam:ListPolicyTags",
      "iam:ListPolicyVersions",
      "iam:ListRolePolicies",
      "iam:ListRoleTags",
      "iam:PutRolePolicy",
      "iam:RemoveRoleFromInstanceProfile",
      "iam:TagInstanceProfile",
      "iam:TagPolicy",
      "iam:TagRole",
      "iam:UntagInstanceProfile",
      "iam:UntagPolicy",
      "iam:UntagRole",
      "iam:UpdateAssumeRolePolicy",
      "iam:UpdateRole",
      "iam:UpdateRoleDescription",
    ]
    resources = [
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${var.project_name}-*",
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:policy/${var.project_name}-*",
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:instance-profile/${var.project_name}-*",
    ]
  }

  ##
  # PassRole is separated out so it can carry an iam:PassedToService condition.
  #
  # Without that condition, holding PassRole over the mercala-* namespace would let the
  # deploy role hand any project role — including its own, which can create and attach
  # IAM policies — to any service that accepts one. That is a privilege-escalation path.
  # Restricting the target service to EC2 keeps it to what the deploy actually does:
  # attach the app-host instance profile to the spot instance.
  ##
  statement {
    sid       = "PassInstanceRoleToEc2Only"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${var.project_name}-*"]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_policy" "deploy" {
  name        = "${var.project_name}-deploy"
  description = "Permissions for the ${var.project_name} GitHub Actions deploy role"
  policy      = data.aws_iam_policy_document.deploy.json

  tags = {
    Project = var.project_name
  }
}

resource "aws_iam_role_policy_attachment" "deploy" {
  role       = aws_iam_role.github_actions.name
  policy_arn = aws_iam_policy.deploy.arn
}
