# Two buckets, and the split between them is the whole point.
#
# The private one is not only media: the Terraform backend keeps its state here, the deploy
# playbook syncs Let's Encrypt's private keys here, and the nightly pg_dump lands here.
# Merchants' own uploaded photographs join them. Nothing in it may ever be world-readable,
# which is why the public-read policy lives on a separate bucket rather than on a prefix of
# this one — a prefix condition is one typo away from publishing the database.
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

# Finished product imagery, and nothing else. Shoppers load these directly from an <img>
# tag with no credentials and no session, so they have to be anonymously readable; the
# blast radius of that policy is exactly the set of files that are meant to be seen.
resource "aws_s3_bucket" "public_media" {
  bucket        = "${var.project_name}-public-media-${var.aws_region}"
  force_destroy = true

  tags = {
    Name       = "${var.project_name}-public-media"
    Visibility = "public-read"
  }
}

# ACLs stay blocked even here. The bucket policy below is the single, reviewable place
# that grants public access — an object-level ACL would be a second one, and the two
# would eventually disagree.
resource "aws_s3_bucket_public_access_block" "public_media_block" {
  bucket = aws_s3_bucket.public_media.id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = false
  restrict_public_buckets = false
}

data "aws_iam_policy_document" "public_media_read" {
  statement {
    sid    = "AnonymousObjectRead"
    effect = "Allow"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    # Read only, and objects only. No ListBucket: a shopper needs the picture the
    # storefront links to, not an index of every image every merchant has ever generated.
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.public_media.arn}/*"]
  }
}

resource "aws_s3_bucket_policy" "public_media" {
  bucket = aws_s3_bucket.public_media.id
  policy = data.aws_iam_policy_document.public_media_read.json

  # The policy is rejected while block_public_policy is still true, so the access block
  # has to be relaxed first. Terraform cannot infer that ordering from the arguments.
  depends_on = [aws_s3_bucket_public_access_block.public_media_block]
}
