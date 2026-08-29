# AWS Production Deployment Architecture

**Status:** Accepted
**Date:** 2026-08-28
**Decision owner:** Codex
**Reviewer:** Antigravity
**Human approval required before production deployment:** Yes
**Scope:** Target AWS services, network and deployment topology, operational safeguards, observability, scaling, and cost tradeoffs.

---

## 1. Context

The final Real-Time Collaborative Editor must be deployed to AWS and demonstrate:

* public application hosting,
* HTTPS,
* WebSocket support,
* horizontally scaled Spring Boot instances,
* managed PostgreSQL,
* managed Redis,
* load balancing,
* secure secrets,
* automated deployment,
* observability,
* production smoke testing,
* load testing.

The deployment should be technically substantial enough to demonstrate cloud architecture while remaining reasonable for a portfolio project to operate.

## 1.1 Problem

The project needs a secure, observable AWS topology that supports long-lived WebSockets, multiple stateless connection-owning backend tasks, private managed data services, repeatable deployment, and credible load testing without introducing infrastructure that is disproportionate to a single-developer portfolio project.

## 1.2 Alternatives Considered

The decision considers serving the frontend from Spring Boot, EC2-hosted backend compute, and EKS/Kubernetes, together with lower-cost single-instance and non-Multi-AZ deployment variants. Later sections record why the target uses separate static hosting and ECS Fargate while allowing actual environment size and availability to remain cost-dependent.

---

# 2. Decision

The target production architecture will use:

```text
React frontend
→ S3
→ CloudFront

Java/Spring Boot backend
→ ECS Fargate
→ Application Load Balancer

PostgreSQL
→ Amazon RDS for PostgreSQL

Redis
→ Amazon ElastiCache

Container images
→ Amazon ECR

Secrets
→ AWS Secrets Manager

Logging / metrics
→ Amazon CloudWatch

DNS
→ Route 53 when custom domain is used

TLS certificates
→ AWS Certificate Manager
```

---

# 3. High-Level Architecture

```text
                         Internet
                            │
                            ▼
                      Route 53
                            │
                            ▼
                 Public edge routes
                    ┌───────┴────────┐
                    │                │
                    ▼                ▼
               CloudFront       API / WS route
                    │                │
                    ▼                ▼
                   S3        Application Load
             React assets          Balancer
                                     │
                          ┌──────────┼──────────┐
                          │          │          │
                          ▼          ▼          ▼
                       Fargate    Fargate    Fargate
                       Spring     Spring     Spring
                          │          │          │
                          └──────┬───┴───┬──────┘
                                 │       │
                                 ▼       ▼
                           ElastiCache   RDS
                              Redis   PostgreSQL
```

---

# 4. Frontend Hosting

React production assets are built as static assets.

They are stored in:

```text
Amazon S3
```

and distributed through:

```text
CloudFront
```

The S3 bucket should not require unrestricted public access.

CloudFront provides the public delivery layer.

---

# 5. Why S3 + CloudFront

Advantages:

* inexpensive static hosting,
* CDN caching,
* separation between frontend and backend compute,
* HTTPS support,
* simple CI/CD,
* no frontend server process required.

---

# 6. Alternative: Serve React from Spring Boot

Rejected because:

* couples frontend deployment to backend containers,
* wastes backend compute for static files,
* makes independent frontend deployment harder,
* does not demonstrate a clean cloud boundary.

---

# 7. Backend Compute

The Spring Boot backend runs as Docker containers on:

```text
Amazon ECS with Fargate
```

Reasons:

* no EC2 host management,
* natural container deployment,
* horizontal task scaling,
* integration with ALB,
* appropriate complexity for the project.

---

# 8. Alternative: EC2

Rejected as the primary deployment because it introduces:

```text
instance patching
host provisioning
capacity management
```

that do not materially improve the collaborative-editor demonstration.

---

# 9. Alternative: EKS/Kubernetes

Rejected for the initial project.

Kubernetes would add substantial infrastructure complexity without being necessary for the target scale.

The project should demonstrate:

```text
distributed application architecture
```

rather than:

```text
Kubernetes administration
```

---

# 10. Backend Container

The backend image contains:

```text
Java runtime
Spring Boot application
application configuration
health endpoints
```

It does not contain:

```text
PostgreSQL
Redis
secrets
```

---

# 11. Container Registry

Backend Docker images are stored in:

```text
Amazon ECR
```

Images should be tagged with:

```text
Git commit SHA
```

and optionally:

```text
release version
```

Do not rely only on:

```text
latest
```

for production traceability.

---

# 12. Load Balancer

An:

```text
Application Load Balancer
```

routes API and WebSocket traffic to ECS tasks.

The application must not depend on a specific client always reaching the same backend task.

---

# 13. Sticky Sessions

Sticky sessions are:

> **Not required for correctness.**

The Redis architecture already allows collaborators connected through separate backend instances to share a document.

Affinity may be evaluated later as a performance optimization, but it must not become a correctness dependency.

---

# 14. WebSocket Routing

The ALB forwards WebSocket connections to healthy ECS tasks.

Once established, a WebSocket remains associated with that backend connection for its lifetime.

After task loss:

```text
socket disconnects
↓
client reconnects
↓
ALB may choose another task
↓
client resynchronizes
```

---

# 15. PostgreSQL

Durable database:

```text
Amazon RDS for PostgreSQL
```

RDS stores:

* users,
* documents,
* permissions,
* operation batches,
* operation IDs,
* snapshots,
* versions,
* refresh-token metadata.

---

# 16. Why RDS PostgreSQL

Advantages:

* managed backups,
* managed storage,
* monitoring,
* standard PostgreSQL compatibility,
* simpler operation than self-hosting PostgreSQL.

---

# 17. Database Network Access

RDS must not be publicly reachable.

Only approved backend security groups should access its PostgreSQL port.

Conceptually:

```text
Internet
  X
  │
RDS

ECS security group
  ↓ allowed
RDS
```

---

# 18. Database Availability

Development/staging may use lower-cost configuration.

Production architecture should support:

```text
Multi-AZ
```

if cost permits.

The portfolio does not require unnecessary high-availability spending merely to claim scale.

The final README should accurately describe the configuration actually deployed.

---

# 19. Redis

Managed Redis runtime:

```text
Amazon ElastiCache
```

Redis supports:

* leader leases,
* Pub/Sub,
* presence,
* cursor propagation,
* real-time tickets,
* selected ephemeral caches.

---

# 20. Redis Network Access

ElastiCache must not be exposed to the public internet.

Only backend ECS tasks should have access.

---

# 21. Redis Durability

The application does not treat ElastiCache as the durable document store.

Redis replacement/restart may lose:

```text
presence
tickets
leases
ephemeral state
```

but not committed document history.

---

# 22. Virtual Private Cloud

Backend infrastructure runs inside a VPC.

Conceptual layout:

```text
VPC
│
├── public subnets
│   └── Application Load Balancer
│
└── private subnets
    ├── ECS tasks
    ├── RDS PostgreSQL
    └── ElastiCache Redis
```

Exact subnet topology may be adapted for cost and deployment constraints.

---

# 23. Public Backend Exposure

Only the load balancer should receive normal public backend traffic.

ECS task ports should not be directly public.

---

# 24. Security Groups

Conceptual rules:

## ALB

Inbound:

```text
443 from internet
80 only for redirect if used
```

Outbound:

```text
backend application port to ECS
```

## ECS

Inbound:

```text
application port from ALB security group
```

Outbound:

```text
RDS
Redis
AWS services as required
```

## RDS

Inbound:

```text
5432 from ECS security group
```

## Redis

Inbound:

```text
Redis port from ECS security group
```

---

# 25. TLS

Public production traffic must use:

```text
HTTPS
WSS
```

TLS certificates are managed through:

```text
AWS Certificate Manager
```

Plain HTTP should redirect to HTTPS.

---

# 26. DNS

If the application uses a custom domain:

```text
Route 53
```

manages DNS.

Possible layout:

```text
editor.example.com
api.editor.example.com
```

or a single-domain routing architecture.

---

# 27. Same-Origin Preference

Where practical, CloudFront can route:

```text
/
→ frontend

/api/*
→ ALB

/ws/*
→ ALB
```

This can simplify:

* CORS,
* cookies,
* browser deployment configuration.

Whether CloudFront fronts WebSocket/API traffic or separate subdomains are used should be validated during infrastructure implementation.

The external URLs must remain configurable.

---

# 28. Secrets

Production secrets belong in:

```text
AWS Secrets Manager
```

Examples:

```text
database password
access-token signing secret/key when required by the selected token format
Redis credentials where configured
application secrets
```

Secrets must not appear in:

```text
Git
Docker images
frontend bundles
CI logs
```

---

# 29. Environment Configuration

Nonsecret configuration may use:

```text
ECS environment variables
task definitions
parameterized deployment configuration
```

Examples:

```text
database hostname
Redis hostname
active Spring profile
allowed frontend origin
batch thresholds
snapshot interval
```

---

# 30. IAM

ECS tasks receive a least-privilege IAM role.

They should receive only permissions required for runtime.

CI/CD receives a separate deployment role.

Do not use personal administrator credentials inside GitHub Actions.

---

# 31. CI/CD

GitHub Actions is the authoritative CI system.

Expected pipeline:

```text
commit / pull request
        ↓
backend compile
        ↓
backend tests
        ↓
frontend typecheck
        ↓
frontend tests
        ↓
integration tests
        ↓
Playwright
        ↓
Docker build
        ↓
security/dependency checks
        ↓
small performance regression suite
        ↓
merge
```

---

# 32. Deployment Pipeline

Production/staging deployment:

```text
approved main commit
        ↓
build frontend
        ↓
upload frontend assets to S3
        ↓
CloudFront invalidation/versioned asset rollout
        ↓
build backend Docker image
        ↓
push image to ECR
        ↓
update ECS service
        ↓
rolling deployment
        ↓
health checks
        ↓
production smoke tests
```

---

# 33. Database Migrations

Schema migrations run through version-controlled migration tooling.

Recommended:

```text
Flyway
```

Migration strategy must ensure that multiple ECS tasks starting simultaneously do not independently corrupt migration execution.

---

# 34. Deployment Compatibility

When practical, schema changes should follow:

```text
expand
↓
deploy compatible application
↓
migrate usage
↓
contract later
```

rather than requiring perfect instantaneous replacement of all running containers.

For a portfolio deployment, complexity should remain proportional to actual need.

---

# 35. ECS Health Checks

Backend exposes:

```text
liveness
readiness
```

health endpoints.

Readiness should fail if the application cannot safely receive normal traffic.

---

# 36. Graceful Shutdown

During an ECS rolling deployment:

```text
task removed from ALB
        ↓
stop accepting new connections
        ↓
release/relinquish document leadership
        ↓
finish safe persistence work
        ↓
close WebSockets
        ↓
clients reconnect
```

The system must tolerate users moving between backend tasks.

---

# 37. Autoscaling

ECS should support horizontal scaling.

Potential signals include:

```text
CPU
memory
active WebSocket connections
operations/sec
```

Initial implementation may use CPU/memory-based scaling.

Custom WebSocket metrics can be evaluated after observability exists.

---

# 38. Minimum Backend Count

Development/staging may use:

```text
1 backend task
```

Production multi-instance verification must use at least:

```text
2 backend tasks
```

because a single task cannot prove Redis-based horizontal collaboration.

---

# 39. Scaling Test

Production/staging verification must demonstrate:

```text
User A → task A
User B → task B
```

editing the same document successfully.

Testing only through multiple connections without proving task separation is insufficient.

---

# 40. Observability

Application logs and metrics go to:

```text
CloudWatch
```

Track at minimum:

```text
HTTP requests
HTTP latency
WebSocket connections
operations/sec
OT transformation latency
sync latency
Redis latency
PostgreSQL latency
persistence batch size
errors
leader changes
reconnects
JVM CPU
JVM memory
```

---

# 41. Structured Logging

Application logs should use structured output.

Useful fields:

```text
timestamp
level
requestId
documentId
userId where appropriate
connectionId
instanceId
event
duration
errorCode
```

Do not log full document contents in normal production logs.

---

# 42. Log Privacy

Avoid logging:

```text
passwords
access tokens
refresh tokens
real-time tickets
document contents
AWS credentials
database credentials
```

---

# 43. Metrics Endpoint

Spring Boot exposes application metrics through:

```text
Micrometer / Actuator
```

or equivalent.

Metrics may be exported into the chosen AWS monitoring pipeline.

---

# 44. Alerts

Useful initial alerts include:

```text
backend unhealthy
high 5xx rate
database unavailable
Redis unavailable
p95 API latency spike
WebSocket connection failures
repeated leader churn
```

Alerts should be configured after stable baseline behavior exists.

---

# 45. Backups

RDS automated backups should be enabled in the actual production deployment.

A recovery plan should document:

```text
restore database
configure application
restart service
verify documents
```

---

# 46. Backup Verification

At least once during project validation:

1. create sample documents,
2. create versions,
3. generate operations,
4. back up database,
5. restore to an isolated environment,
6. run recovery verification.

A configured backup alone does not prove recovery works.

---

# 47. Frontend Deployment

Frontend builds should use immutable hashed asset names where supported by the build tool.

This allows CloudFront to cache static assets aggressively.

The HTML entry point should use a shorter cache policy so new deployments become visible.

---

# 48. Rollback

Backend:

```text
redeploy previous known-good ECR image
```

Frontend:

```text
restore previous static build/version
```

Database rollback should not rely casually on reversing destructive migrations.

Migration compatibility should reduce rollback risk.

---

# 49. Production Smoke Tests

After deployment, Antigravity runs automated smoke tests against the deployed application.

Must verify:

```text
register/login
create document
open document
share document
two-user collaboration
presence
cursor
disconnect/reconnect
version history
```

---

# 50. Multi-Instance Smoke Test

Deployment verification must additionally confirm:

```text
two users
same document
different backend instances
```

and test:

```text
editing
presence
disconnect
reconnect
```

---

# 51. Load Testing Environment

Full performance tests should not be run blindly against the production user-facing environment.

Use a controlled staging/load-test environment when practical.

The environment should resemble production sufficiently for results to be meaningful.

---

# 52. Benchmark Metadata

Every AWS benchmark should record:

```text
ECS task CPU/memory
number of ECS tasks
RDS configuration
Redis configuration
region
client load-generator location
commit SHA
test duration
client count
operations/sec
latency percentiles
```

Without this information the result is not reproducible.

---

# 53. Cost Controls

Because this is a portfolio project:

* use modest service sizes initially,
* shut down dedicated load-test resources when not needed,
* configure AWS budgets/alerts,
* avoid permanently running unnecessary capacity.

Architecture targets should not require wasteful spending.

---

# 54. Environment Separation

At minimum distinguish:

```text
local
production
```

Prefer:

```text
local
staging
production
```

once deployment testing becomes significant.

Production credentials and data must not be reused casually in development.

---

# 55. Infrastructure as Code

AWS resources should eventually be defined using Infrastructure as Code.

Recommended options:

```text
Terraform
or
AWS CDK
```

The project should select one before the deployment phase.

For this architecture, the default recommendation is:

> **Terraform**

because it clearly exposes cloud-resource relationships and is widely readable outside the Java codebase.

If Terraform is selected, add a dedicated infrastructure task before implementation.

This choice is intentionally unresolved; the recommendation is not an accepted Terraform decision.

---

# 56. Infrastructure Directory

Recommended eventual repository structure:

```text
infrastructure/
├── modules/
├── environments/
│   ├── staging/
│   └── production/
└── README.md
```

This extends the earlier top-level repository structure.

---

# 57. Terraform State

If Terraform is used, remote state should be protected appropriately.

State may contain sensitive infrastructure information.

Do not commit Terraform state files to Git.

---

# 58. AWS Region

The deployed region should be chosen based on:

```text
developer proximity
load-test location
cost
service availability
```

The benchmark documentation must record the actual region.

The ADR does not freeze one region.

---

# 59. Availability vs Cost

The architecture distinguishes:

```text
architectural ability
```

from:

```text
resources actually paid for continuously
```

For example, the architecture may support multiple ECS tasks and Multi-AZ RDS while a low-cost development environment runs fewer resources.

Public project claims must describe what was actually benchmarked.

---

# 60. Security Requirements

Production deployment must not:

* expose PostgreSQL publicly,
* expose Redis publicly,
* store secrets in GitHub repository files,
* place AWS administrator credentials in CI,
* permit unrestricted security-group ingress,
* use HTTP for authenticated production traffic,
* disable document authorization for smoke/load testing.

---

# 61. Dependency Failure Behavior

## RDS unavailable

Application cannot durably accept collaboration operations.

Readiness/degraded state should reflect the failure.

---

## Redis unavailable

Multi-instance real-time coordination becomes unavailable.

The backend must not create unsafe independent sequencers.

---

## One ECS task dies

Other tasks continue.

Clients reconnect.

Document leadership transfers.

---

## CloudFront/S3 issue

Frontend may be inaccessible, but backend state remains durable.

---

# 62. Scaling Targets

The deployment must eventually be tested against:

```text
500+ concurrent WebSocket connections
50+ editors/document
1,000+ operations/sec
<100 ms p95 synchronization latency
```

These remain targets until benchmark evidence exists.

---

# 63. 40% Latency Improvement Claim

The project has a planned optimization target of:

```text
40% synchronization latency reduction
```

This may only become a resume claim if AWS/local benchmark evidence records:

```text
baseline
optimized result
identical workload
```

and computes the improvement.

---

# 64. 60% DB Write Reduction Claim

Likewise:

```text
60% database-write reduction
```

requires measured transaction/write counts against the naive persistence baseline.

The architecture does not guarantee this result.

---

# 65. Consequences

## Positive

* managed database and Redis,
* horizontally scalable backend,
* realistic cloud architecture,
* low host-management burden,
* clear CI/CD story,
* strong resume-level infrastructure experience,
* controlled frontend CDN delivery.

## Negative

* more expensive than single-server deployment,
* several AWS services must be configured,
* network/security configuration adds complexity,
* testing distributed failures becomes necessary,
* observability becomes more important.

---

# 66. Frozen Decisions

ADR-005 freezes the target production use of:

1. S3 for React static assets.
2. CloudFront as frontend CDN.
3. ECS Fargate for Spring Boot containers.
4. ECR for backend images.
5. Application Load Balancer for backend HTTP/WebSocket traffic.
6. RDS PostgreSQL for durable database.
7. ElastiCache for Redis.
8. Secrets Manager for production secrets.
9. CloudWatch for core logging/metrics.
10. Private database/Redis networking.
11. HTTPS/WSS for production.
12. No correctness dependency on sticky sessions.
13. GitHub Actions as CI/CD authority.
14. At least two backend tasks for distributed deployment verification.

---

# 67. Deferred Decisions

ADR-005 intentionally does not freeze:

```text
exact ECS CPU size
exact ECS memory size
exact RDS instance class
exact Redis node size
exact AWS region
exact autoscaling thresholds
custom domain name
exact CloudWatch dashboard layout
```

These depend on measured workload and cost.

---

# 68. Superseding This ADR

A move to a substantially different platform such as:

```text
Kubernetes/EKS
EC2-only deployment
Lambda-first backend
another cloud provider
self-hosted PostgreSQL
self-hosted Redis
```

requires a new ADR explaining why the architecture changed.
