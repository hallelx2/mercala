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
