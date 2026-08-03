# This bucket is not only media. The Terraform backend keeps its state here, the deploy
# playbook syncs Let's Encrypt's private keys here, the nightly pg_dump lands here, and
# merchants' uploaded photographs join them. Nothing in it may ever be world-readable —
# a prefix-scoped public policy would be one typo away from publishing the database.
#
# Product imagery therefore reaches browsers as presigned URLs, minted per request by the
# API, rather than through any public grant on this bucket.
#
# The eventual shape is a second, public-read bucket holding nothing but finished images,
# behind a CDN, with content-addressed keys (HAL-577). Creating it needs the CI deploy role
# to be allowed s3:CreateBucket and s3:PutBucketPolicy on a second name, which is a
# one-time local apply of devops/terraform/bootstrap. Until that has happened this stack
# cannot create the bucket, and attempting it fails the entire deploy — which is exactly
# what it did (see the Terraform Apply step of run 30805171485).
resource "aws_s3_bucket" "media" {
  bucket        = "${var.project_name}-media-storage-${var.aws_region}"
  force_destroy = true

  tags = {
    Name = "${var.project_name}-media-storage"
  }
}

resource "aws_s3_bucket_public_access_block" "media_block" {
  bucket = aws_s3_bucket.media.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
