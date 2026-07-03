resource "aws_ecr_repository" "core" {
  name                 = "${var.project_name}-core"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name = "${var.project_name}-core-repo"
  }
}

resource "aws_ecr_repository" "agent" {
  name                 = "${var.project_name}-agent"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name = "${var.project_name}-agent-repo"
  }
}

resource "aws_ecr_repository" "image_gen" {
  name                 = "${var.project_name}-image-gen"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name = "${var.project_name}-image-gen-repo"
  }
}
