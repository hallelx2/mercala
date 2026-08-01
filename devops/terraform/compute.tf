data "aws_ami" "ubuntu" {
  most_recent = true
  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
  owners = ["099720109477"] # Canonical
}

resource "aws_spot_instance_request" "app_server" {
  ami                  = data.aws_ami.ubuntu.id
  instance_type        = var.instance_type
  spot_price           = var.spot_price
  spot_type            = "one-time"
  wait_for_fulfillment = true

  key_name               = var.ssh_key_name
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app_sg.id]

  # Gives the host ECR pull + S3 access without any static credentials.
  iam_instance_profile = aws_iam_instance_profile.app_host.name

  root_block_device {
    volume_size           = 30
    volume_type           = "gp3"
    delete_on_termination = true
  }

  tags = {
    Name = "${var.project_name}-spot-server"
  }
}

resource "aws_eip" "app_ip" {
  domain = "vpc"

  tags = {
    Name = "${var.project_name}-app-eip"
  }
}

resource "aws_eip_association" "app_ip_assoc" {
  instance_id   = aws_spot_instance_request.app_server.spot_instance_id
  allocation_id = aws_eip.app_ip.id
}
