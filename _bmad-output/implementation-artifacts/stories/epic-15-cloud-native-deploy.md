# Epic 15: Cloud-Native Deployment

**Story Points:** 29
**Priority:** P2
**Status:** Planned

---

## Story 15.1: Kubernetes Manifests with Helm

**ID:** 15.1
**Title:** K8s Manifests with Helm
**Points:** 5
**Priority:** P1

### User Story
- **As a** DevOps engineer
- **I want** Helm charts that deploy koncerto with configurable project configs as ConfigMaps
- **So that** the platform can be deployed consistently across environments

### Acceptance Criteria
- [ ] Helm chart with Deployment, Service, ConfigMap, Secret templates
- [ ] Chart values.yaml with sensible defaults (single-replica, small resource requests)
- [ ] Project configs mountable via ConfigMap volumes per tenant
- [ ] Secrets managed via Kubernetes Secrets (not ConfigMaps)
- [ ] Helm chart supports multiple environments via values-{env}.yaml overrides
- [ ] Configurable Java heap, JVM flags, and Spring profiles via Helm values
- [ ] Helm chart includes NOTES.txt with post-install usage instructions
- [ ] `helm lint` passes on the chart
- [ ] Chart published as OCI artifact in CI pipeline

### Technical Notes
- Chart located in `deploy/charts/koncerto/`
- Deployment uses Spring Boot Docker image built from existing Dockerfile
- Project configs mounted to `/etc/koncerto/projects/` directory
- App reads from `koncerto.projects-dir` property pointing to mounted configs
- Helm chart must support both single-tenant and multi-tenant deployments

### Implementation
- Create: `deploy/charts/koncerto/Chart.yaml`
- Create: `deploy/charts/koncerto/values.yaml`
- Create: `deploy/charts/koncerto/templates/deployment.yaml`
- Create: `deploy/charts/koncerto/templates/service.yaml`
- Create: `deploy/charts/koncerto/templates/configmap.yaml`
- Create: `deploy/charts/koncerto/templates/secret.yaml`
- Create: `deploy/charts/koncerto/templates/_helpers.tpl`
- Create: `deploy/charts/koncerto/NOTES.txt`
- Create: `deploy/charts/koncerto/values-production.yaml`

---

## Story 15.2: Horizontal Pod Autoscaler

**ID:** 15.2
**Title:** Horizontal Pod Autoscaler
**Points:** 5
**Priority:** P1

### User Story
- **As a** platform operator
- **I want** the koncerto deployment to auto-scale based on agent demand and queue depth
- **So that** capacity matches workload without manual intervention

### Acceptance Criteria
- [ ] HPA manifest targeting koncerto Deployment based on custom metrics (agent queue depth)
- [ ] Prometheus Adapter installed and configured to expose `koncerto_queue_depth` metric
- [ ] HPA minReplicas=2, maxReplicas=10 by default (configurable in Helm values)
- [ ] HPA scales up when queue depth per replica exceeds threshold (configurable, default 5)
- [ ] HPA scales down with cooldown period to avoid thrashing (default 300s)
- [ ] Integration test: simulate queue growth and verify scale-up trigger
- [ ] Documentation: HPA tuning guide in deploy/README.md

### Technical Notes
- Custom metric: `koncerto_queue_depth` from PrometheusMetricsBinder
- Prometheus Adapter config matches metric name and exposes as `pods` metric type
- Scale-up based on: sum(queue_depth) / number_of_pods > threshold
- Use `behavior` block in HPA spec for stabilization windows
- HPA manifest in `deploy/charts/koncerto/templates/hpa.yaml`

### Implementation
- Create: `deploy/charts/koncerto/templates/hpa.yaml`
- Create: `deploy/charts/koncerto/templates/prometheus-adapter.yaml` (ConfigMap)
- Modify: `deploy/charts/koncerto/values.yaml` (add autoscaling section)
- Modify: `koncerto-metrics/src/main/kotlin/com/anomaly/koncerto/metrics/PrometheusMetricsBinder.kt` (expose queue depth)

---

## Story 15.3: Readiness/Liveness Probes and Graceful Shutdown

**ID:** 15.3
**Title:** Readiness/Liveness Probes and Graceful Shutdown
**Points:** 3
**Priority:** P1

### User Story
- **As a** platform operator
- **I want** Kubernetes readiness and liveness probes configured with graceful shutdown handling
- **So that** rolling deployments are zero-downtime and failed instances are detected automatically

### Acceptance Criteria
- [ ] Spring Boot Actuator health endpoint exposed at /actuator/health
- [ ] Readiness probe: /actuator/health/readiness — reports DOWN when orchestrator not ready
- [ ] Liveness probe: /actuator/health/liveness — reports DOWN when JVM is unhealthy
- [ ] Probe configured in Helm deployment template with initialDelaySeconds, periodSeconds, timeoutSeconds
- [ ] Graceful shutdown enabled (server.shutdown=graceful with 30s timeout)
- [ ] PreStop hook sends SIGTERM, waits for in-flight agents to complete (up to 60s)
- [ ] In-flight agent state persisted before shutdown for resumption on new pod
- [ ] Unit tests: health indicators for orchestrator readiness
- [ ] Integration test: SIGTERM handling with in-flight agent state preservation

### Technical Notes
- Existing OrchestratorHealthIndicator provides orchestrator readiness
- PreStop hook implemented as a shell exec in the container lifecycle
- Graceful shutdown timeout configurable via Helm values
- In-flight state persisted to MetricsRepository or a dedicated StateStore before exit

### Implementation
- Modify: `deploy/charts/koncerto/templates/deployment.yaml` (add probes, preStop)
- Modify: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/OrchestratorHealthIndicator.kt`
- Create: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/StatePersister.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/StatePersisterTest.kt`

---

## Story 15.4: Prometheus ServiceMonitor and Grafana Dashboards

**ID:** 15.4
**Title:** Prometheus ServiceMonitor and Grafana Dashboards
**Points:** 8
**Priority:** P2

### User Story
- **As a** platform operator
- **I want** a Prometheus ServiceMonitor and pre-built Grafana dashboards for koncerto
- **So that** observability is codified and repeatable across environments

### Acceptance Criteria
- [ ] ServiceMonitor CRD manifest targeting koncerto service with label selector
- [ ] ServiceMonitor configured with 15s scrape interval and /actuator/prometheus path
- [ ] Grafana dashboard JSON: agent activity panel (active agents, queue depth, throughput)
- [ ] Grafana dashboard JSON: workflow lifecycle panel (created, completed, failed, stalled)
- [ ] Grafana dashboard JSON: resource usage panel (heap, CPU, per-tenant quotas)
- [ ] Grafana dashboard JSON: latency panel (dispatch time, agent execution time)
- [ ] dashboards are versioned and installable via ConfigMap or Grafana provisioning
- [ ] All Prometheus metrics documented in deploy/metrics-reference.md
- [ ] Dashboards tested by importing and validating panel data sources

### Technical Notes
- ServiceMonitor manifest in `deploy/charts/koncerto/templates/servicemonitor.yaml`
- Grafana dashboards as JSON in `deploy/grafana/dashboards/`
- Grafana datasource configured via `deploy/grafana/datasources/prometheus.yaml`
- Labels: `app.kubernetes.io/name: koncerto` for service discovery
- Ensure existing PrometheusMetricsBinder exposes all required metrics

### Implementation
- Create: `deploy/charts/koncerto/templates/servicemonitor.yaml`
- Create: `deploy/grafana/dashboards/koncerto-agent-activity.json`
- Create: `deploy/grafana/dashboards/koncerto-workflows.json`
- Create: `deploy/grafana/dashboards/koncerto-resources.json`
- Create: `deploy/grafana/datasources/prometheus.yaml`
- Create: `deploy/metrics-reference.md`

---

## Story 15.5: Terraform/Pulumi IaC Modules

**ID:** 15.5
**Title:** Infrastructure as Code Modules
**Points:** 5
**Priority:** P2

### User Story
- **As a** DevOps engineer
- **I want** Terraform (or Pulumi) modules to provision the supporting infrastructure for koncerto
- **So that** the full deployment is reproducible from scratch

### Acceptance Criteria
- [ ] Terraform root module with GKE/EKS/AKS cluster provisioning
- [ ] Terraform sub-module for koncerto-specific resources (namespace, service account, IAM)
- [ ] Terraform sub-module for Prometheus stack (kube-prometheus-stack helm release)
- [ ] Terraform outputs cluster kubeconfig, koncerto service endpoint
- [ ] Terraform variables for cluster size, node pool config, region
- [ ] README with usage examples for each module
- [ ] `terraform validate` and `terraform plan` pass in CI
- [ ] Optionally: Pulumi equivalent in deploy/pulumi/ directory

### Technical Notes
- Modules in `deploy/terraform/modules/` directory
- Use official helm_release Terraform provider for chart deployment
- State stored in cloud backend (GCS/S3) — documented in backend.tf example
- Variables have sensible defaults for dev deployments

### Implementation
- Create: `deploy/terraform/main.tf`
- Create: `deploy/terraform/variables.tf`
- Create: `deploy/terraform/outputs.tf`
- Create: `deploy/terraform/modules/koncerto-cluster/main.tf`
- Create: `deploy/terraform/modules/koncerto-cluster/variables.tf`
- Create: `deploy/terraform/modules/koncerto-infra/main.tf`
- Create: `deploy/terraform/modules/koncerto-infra/variables.tf`
- Create: `deploy/terraform/README.md`

---

## Story 15.6: Structured Alerting and SLOs

**ID:** 15.6
**Title:** Structured Alerting and SLOs
**Points:** 3
**Priority:** P2

### User Story
- **As a** platform operator
- **I want** pre-configured Prometheus alerting rules and SLO burn-rate alerts
- **So that** I am notified when koncerto health or performance degrades

### Acceptance Criteria
- [ ] PrometheusRule CRD manifest with alert rules for:
  - [ ] KoncertoDown: /actuator/health returning DOWN for > 60s
  - [ ] QueueBacklog: queue_depth > 20 for > 5 minutes
  - [ ] AgentStallRate: stalled agents / total > 10% over 10m window
  - [ ] ChainFailureRate: cross-project chain failures > 5% over 30m
  - [ ] HighQuotaUsage: per-tenant quota usage > 80% for > 15m
  - [ ] HighErrorRate: 5xx responses > 1% over 5m (dashboard API)
- [ ] SLO targets defined in service-level indicator comments on dashboards
- [ ] Alert severity levels: critical (page), warning (ticket), info (slack)
- [ ] Alertmanager routing config for each severity level
- [ ] Integration test: verify PrometheusRule YAML is valid

### Technical Notes
- PrometheusRule manifest in `deploy/charts/koncerto/templates/prometheusrule.yaml`
- Alertmanager config in `deploy/charts/koncerto/templates/alertmanager-config.yaml`
- Route critical alerts to PagerDuty/Opsgenie, warnings to Slack, info to log
- SLO targets: 99.5% uptime, 95% of agents complete within 30m, < 1% chain failure rate

### Implementation
- Create: `deploy/charts/koncerto/templates/prometheusrule.yaml`
- Create: `deploy/charts/koncerto/templates/alertmanager-config.yaml`
- Create: `deploy/slo-documentation.md`
