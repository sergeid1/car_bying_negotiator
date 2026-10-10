resource "aws_vpc" "pilot" {
  cidr_block           = "10.99.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
}
resource "aws_internet_gateway" "pilot" {
  vpc_id = aws_vpc.pilot.id
}
resource "aws_subnet" "origin" {
  vpc_id            = aws_vpc.pilot.id
  cidr_block        = "10.99.1.0/24"
  availability_zone = "${var.region}a"
}
resource "aws_subnet" "database" {
  count             = 2
  vpc_id            = aws_vpc.pilot.id
  cidr_block        = "10.99.${count.index + 10}.0/24"
  availability_zone = "${var.region}${count.index == 0 ? "a" : "b"}"
}
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.pilot.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.pilot.id
  }
}
resource "aws_route_table_association" "origin" {
  subnet_id      = aws_subnet.origin.id
  route_table_id = aws_route_table.public.id
}
resource "aws_security_group" "origin" {
  name   = "${var.name}-origin"
  vpc_id = aws_vpc.pilot.id
  # Outbound TLS: S3, SSM, image registry and approved evidence providers. Provider addresses change, so the
  # destinations cannot be pinned to CIDRs; inbound stays limited to Cloudflare.
  #trivy:ignore:AWS-0104:exp:2027-04-10
  egress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  # SES SMTP submission (ticket 97); SES endpoints are not published as fixed CIDRs.
  #trivy:ignore:AWS-0104:exp:2027-04-10
  egress {
    from_port   = 587
    to_port     = 587
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [aws_vpc.pilot.cidr_block]
  }
}
resource "aws_vpc_security_group_ingress_rule" "cloudflare" {
  for_each          = var.cloudflare_ipv4_ranges
  security_group_id = aws_security_group.origin.id
  cidr_ipv4         = each.value
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}
resource "aws_security_group" "database" {
  name   = "${var.name}-database"
  vpc_id = aws_vpc.pilot.id
}
resource "aws_vpc_security_group_ingress_rule" "database" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.origin.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}
resource "aws_db_subnet_group" "pilot" {
  name       = var.name
  subnet_ids = aws_subnet.database[*].id
}
resource "aws_db_instance" "pilot" {
  identifier                  = var.name
  engine                      = "postgres"
  engine_version              = "17"
  instance_class              = "db.t4g.micro"
  allocated_storage           = 20
  max_allocated_storage       = 40
  storage_type                = "gp3"
  storage_encrypted           = true
  db_name                     = "deal_analyzer"
  username                    = "pilot_owner"
  manage_master_user_password = true
  publicly_accessible         = false
  db_subnet_group_name        = aws_db_subnet_group.pilot.name
  vpc_security_group_ids      = [aws_security_group.database.id]
  backup_retention_period     = 7
  backup_window               = "04:00-05:00"
  maintenance_window          = "sun:05:00-sun:06:00"
  deletion_protection         = true
  skip_final_snapshot         = false
  final_snapshot_identifier   = "${var.name}-final"
  auto_minor_version_upgrade  = true
  apply_immediately           = false
}
resource "aws_s3_bucket" "documents" {
  bucket        = var.document_bucket_name
  force_destroy = false
  lifecycle { prevent_destroy = true }
  # Versioning, Object Lock and replication intentionally never enabled.
}
resource "aws_s3_bucket_public_access_block" "documents" {
  bucket                  = aws_s3_bucket.documents.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_ownership_controls" "documents" {
  bucket = aws_s3_bucket.documents.id
  rule { object_ownership = "BucketOwnerEnforced" }
}
# SSE-S3 is deliberate (ticket 98): the application encrypts every document with its own rotatable key (ticket 80)
# before upload, so a customer-managed KMS key would add cost without protecting the plaintext.
#trivy:ignore:AWS-0132:exp:2027-04-10
resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}
resource "aws_s3_bucket_policy" "documents" {
  bucket = aws_s3_bucket.documents.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "RequireTLS", Effect = "Deny", Principal = "*", Action = "s3:*"
      Resource  = [aws_s3_bucket.documents.arn, "${aws_s3_bucket.documents.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.documents]
}
resource "aws_ecr_repository" "runtime" {
  name                 = var.name
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
}
resource "aws_iam_role" "origin" {
  name = "${var.name}-origin"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}
resource "aws_iam_role_policy" "runtime" {
  role = aws_iam_role.origin.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:DeleteObjectVersion"], Resource = "${aws_s3_bucket.documents.arn}/*" },
      { Effect = "Allow", Action = ["s3:ListBucket", "s3:ListBucketVersions", "s3:GetBucketVersioning", "s3:GetLifecycleConfiguration", "s3:GetBucketPublicAccessBlock", "s3:GetBucketPolicyStatus", "s3:GetBucketAcl"], Resource = aws_s3_bucket.documents.arn },
      { Effect = "Allow", Action = ["ssm:GetParameter"], Resource = var.runtime_parameter_arn },
      { Effect = "Allow", Action = ["kms:Decrypt"], Resource = var.runtime_kms_key_arn, Condition = { StringEquals = { "kms:ViaService" = "ssm.${var.region}.amazonaws.com" } } },
      { Effect = "Allow", Action = ["ses:SendRawEmail"], Resource = aws_ses_domain_identity.company.arn },
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      { Effect = "Allow", Action = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"], Resource = aws_ecr_repository.runtime.arn }
    ]
  })
}
resource "aws_iam_role_policy_attachment" "management" {
  role       = aws_iam_role.origin.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}
resource "aws_iam_role" "image_build" {
  count = var.github_repository == null ? 0 : 1
  name  = "${var.name}-image-build"
  lifecycle {
    precondition {
      condition     = var.github_oidc_provider_arn != null
      error_message = "GitHub build role requires the company OIDC provider ARN."
    }
  }
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow", Principal = { Federated = var.github_oidc_provider_arn }, Action = "sts:AssumeRoleWithWebIdentity"
      Condition = { StringEquals = {
        "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        "token.actions.githubusercontent.com:sub" = "repo:${var.github_repository}:environment:pilot"
      } }
    }]
  })
}
resource "aws_iam_role_policy" "image_build" {
  count = var.github_repository == null ? 0 : 1
  role  = aws_iam_role.image_build[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      { Effect = "Allow", Action = ["ecr:BatchGetImage", "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage"], Resource = aws_ecr_repository.runtime.arn }
    ]
  })
}
resource "aws_iam_instance_profile" "origin" {
  name = var.name
  role = aws_iam_role.origin.name
}
resource "aws_instance" "origin" {
  ami                         = var.ubuntu_ami
  instance_type               = "t3.medium"
  subnet_id                   = aws_subnet.origin.id
  vpc_security_group_ids      = [aws_security_group.origin.id]
  associate_public_ip_address = true
  iam_instance_profile        = aws_iam_instance_profile.origin.name
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
    instance_metadata_tags      = "disabled"
  }
  root_block_device {
    volume_size = 30
    volume_type = "gp3"
    encrypted   = true
  }
  # Host configuration is an explicit SSM operator step, not secret-bearing user data.
}
resource "aws_eip" "origin" {
  domain   = "vpc"
  instance = aws_instance.origin.id
}
resource "aws_ses_domain_identity" "company" { domain = "otdog.com" }
resource "aws_ses_domain_dkim" "company" { domain = aws_ses_domain_identity.company.domain }
data "aws_caller_identity" "current" {}
# Bounce and complaint notices carry recipient addresses, so their topics are encrypted. SES cannot publish to a
# topic under the AWS-managed SNS key, so this customer-managed key grants SES the use it needs.
resource "aws_kms_key" "notifications" {
  description         = "${var.name} SES bounce and complaint topics"
  enable_key_rotation = true
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "AccountAdministration", Effect = "Allow", Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }, Action = "kms:*", Resource = "*" },
      { Sid = "SesPublish", Effect = "Allow", Principal = { Service = "ses.amazonaws.com" }, Action = ["kms:GenerateDataKey*", "kms:Decrypt"], Resource = "*",
      Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id } } }
    ]
  })
}
resource "aws_sns_topic" "feedback" {
  for_each          = toset(["Bounce", "Complaint"])
  name              = "${var.name}-${lower(each.value)}"
  kms_master_key_id = aws_kms_key.notifications.arn
}
resource "aws_sns_topic_policy" "feedback" {
  for_each = aws_sns_topic.feedback
  arn      = each.value.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow", Principal = { Service = "ses.amazonaws.com" }, Action = "sns:Publish", Resource = each.value.arn
      Condition = { StringEquals = { "AWS:SourceAccount" = split(":", aws_ses_domain_identity.company.arn)[4] }, ArnEquals = { "AWS:SourceArn" = aws_ses_domain_identity.company.arn } }
    }]
  })
}
resource "aws_ses_identity_notification_topic" "feedback" {
  for_each                 = aws_sns_topic.feedback
  identity                 = aws_ses_domain_identity.company.domain
  notification_type        = each.key
  topic_arn                = each.value.arn
  include_original_headers = false
  depends_on               = [aws_sns_topic_policy.feedback]
}
output "origin_ip" { value = aws_eip.origin.public_ip }
output "database_address" { value = aws_db_instance.pilot.address }
output "document_bucket" { value = aws_s3_bucket.documents.id }
output "image_repository" { value = aws_ecr_repository.runtime.repository_url }
output "ses_dkim_tokens" { value = aws_ses_domain_dkim.company.dkim_tokens }
output "ses_verification_token" { value = aws_ses_domain_identity.company.verification_token }
output "ses_topic_arns" { value = [for topic in aws_sns_topic.feedback : topic.arn] }
output "image_build_role" { value = var.github_repository == null ? null : aws_iam_role.image_build[0].arn }
