##
# Instance role for the app host.
#
# The spot instance pulls images from ECR, reads and writes product imagery in S3, and
# ships nightly database backups there. Previously all of that ran on AWS access keys
# injected into the container environment by Ansible. It now runs on this role, handed
# to the instance by AWS itself — the AWS SDKs and CLI pick it up from the instance
# metadata service automatically, so there is nothing to inject, store, or rotate.
##

data "aws_iam_policy_document" "ec2_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app_host" {
  name               = "${var.project_name}-app-host"
  description        = "Role assumed by the ${var.project_name} application host for ECR and S3 access"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume_role.json

  tags = {
    Name    = "${var.project_name}-app-host"
    Project = var.project_name
  }
}

data "aws_iam_policy_document" "app_host" {
  # Pull images. Read-only — the host never pushes.
  statement {
    sid       = "EcrAuth"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "EcrPull"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [
      aws_ecr_repository.core.arn,
      aws_ecr_repository.agent.arn,
      aws_ecr_repository.image_gen.arn,
    ]
  }

  # Product imagery written by mercala-image-gen, plus the nightly pg_dump.
  statement {
    sid    = "MediaBucketObjects"
    effect = "Allow"
    actions = [
      "s3:AbortMultipartUpload",
      "s3:DeleteObject",
      "s3:GetObject",
      "s3:PutObject",
    ]
    resources = ["${aws_s3_bucket.media.arn}/*"]
  }

  statement {
    sid    = "MediaBucketList"
    effect = "Allow"
    actions = [
      "s3:GetBucketLocation",
      "s3:ListBucket",
    ]
    resources = [aws_s3_bucket.media.arn]
  }
}

resource "aws_iam_role_policy" "app_host" {
  name   = "${var.project_name}-app-host"
  role   = aws_iam_role.app_host.id
  policy = data.aws_iam_policy_document.app_host.json
}

resource "aws_iam_instance_profile" "app_host" {
  name = "${var.project_name}-app-host"
  role = aws_iam_role.app_host.name

  tags = {
    Project = var.project_name
  }
}
