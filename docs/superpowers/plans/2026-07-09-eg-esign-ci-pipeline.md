# eg-esign CI Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the `Davinci-Technology/eg_esign` fork its own Jenkins CI/CD pipeline that builds and auto-deploys `staging → eg-esign-staging` and `main → eg-esign-prod` on the Epic Group AKS cluster, fully separated from the SaaS `davinci-sign` pipeline.

**Architecture:** One Kustomize base (`k8s/eg-esign/base/`) seeded from the existing SaaS `k8s/production/` manifests — renamed `davinci-sign → eg-esign`, namespace/host/image-tag stripped out — **plus an in-cluster Postgres StatefulSet that the SaaS never had** (SaaS used Azure PostgreSQL). Two thin overlays (`overlays/staging`, `overlays/prod`) supply namespace, host, TLS secret name, webapp URL, and image tag. A single `Jenkinsfile.eg` multibranch pipeline resolves env from branch name, builds+pushes to `epicregistry.azurecr.io`, and deploys with `kubectl apply -k`.

**Tech Stack:** Kubernetes + Kustomize, Jenkins declarative pipeline (Kaniko build, `alpine/k8s` deploy agent), in-cluster Postgres 17 + MinIO, cert-manager `letsencrypt-prod`, ingress-nginx, Documenso (Next.js, port 3000).

**Scope:** This plan covers **only the repo-side deliverables** — the manifests, overlays, `Jenkinsfile.eg`, and the `staging` branch. Cluster/secrets provisioning (runbook Part 2), Jenkins credential + job creation (Part 2.4–2.6, 3.1), Azure AD app registration (Part 4a), and eg_intranet wiring (Part 4b) are **external one-time steps performed in Jenkins/Azure/AKS consoles**, documented here as a checklist (Task 6) but not code.

---

## Ground-truth deltas — why the runbook sketch can't be copied verbatim

Reading the real seed files surfaced these load-bearing differences. Each is handled in the tasks below; flagged here so the executor doesn't "fix" them back.

1. **No Postgres exists to rename.** `k8s/production/` has no Postgres manifest — the SaaS `configmap.yaml` points `DB_HOST` at Azure PG (`davinci-sign-postgres-prod.postgres.database.azure.com`, `DB_SSLMODE: require`). We **author** `postgres-statefulset.yaml` + `postgres-pvc.yaml` fresh (Task 2), and repoint `DB_HOST → eg-esign-postgres`, `DB_SSLMODE → disable`. The runbook's "model on eg-erp's deployment-postgres.yaml" refers to a file in the *eg_intranet* repo, which is not present here — so Task 2 gives the full manifest inline.

2. **`commonLabels: {app: eg-esign}` in the base kustomization is a trap.** MinIO and (new) Postgres pods use `app: minio` / `app: eg-esign-postgres` selectors. `commonLabels` rewrites *every* object's labels **and immutable selectors** to `app: eg-esign`, which breaks the MinIO/Postgres StatefulSet + Service selectors. **Do not use `commonLabels`/`labels` with selector inclusion in the base.** Keep each resource's own `app:` label as-authored (Task 3).

3. **The `migrate` init container hardcodes the image.** In SaaS `app-deployment.yaml:64` the init container image is `davinciai.azurecr.io/davinci-sign:production`, written out literally. For Kustomize's `images:` transformer to rewrite **both** the app container and the migrate init container, they must reference the **same image name string** `epicregistry.azurecr.io/davinci-technology/eg-esign`. Task 1 sets both to that identical string so one overlay `images:` entry rewrites both.

4. **OIDC + signup lockdown env is new.** SaaS `configmap.yaml` has OIDC commented out and `NEXT_PUBLIC_DISABLE_SIGNUP: "false"`. eg base enables OIDC (Epic Group tenant well-known URL), `NEXT_PUBLIC_DISABLE_SIGNUP: "true"`, `NEXT_PRIVATE_ALLOWED_SIGNUP_DOMAINS: "epicgroup.ca"`, and the app-deployment must **add two env vars** `NEXT_PRIVATE_OIDC_CLIENT_ID` / `NEXT_PRIVATE_OIDC_CLIENT_SECRET` sourced from `eg-esign-secrets` (SaaS deployment has neither). The Jenkinsfile creates those secret keys.

5. **`storageClassName` — the runbook is wrong here; use `default`, not `azurefile`.** Verified against eg_intranet (`helm/eg-erp/values.yaml`): the eg-erp **Postgres PVC uses `storageClass: "default"`** (Azure managed disk, RWO, 20Gi); `azurefile` is used **only** for the RWX media share (`accessMode: ReadWriteMany`). The runbook's "azurefile everywhere" over-generalized its own source. Postgres on azurefile (SMB/CIFS) risks fsync/byte-range-locking corruption — never do it. So: **both Postgres and MinIO PVCs get `storageClassName: default`**, `accessModes: [ReadWriteOnce]` (matches SaaS minio-pvc, matches eg-erp postgres). Nothing in eg-esign needs RWX.

6. **Cert mount path.** SaaS mounts the `.p12` at `/opt/davinci-sign`; configmap signing path is `/opt/davinci-sign/cert.p12`. Both must move to `/opt/eg-esign` **consistently** (volumeMount `mountPath` + configmap `NEXT_PRIVATE_SIGNING_LOCAL_FILE_PATH`).

7. **Pipeline branch guards.** SaaS `Jenkinsfile` gates Build-Image and Deploy on `when { branch 'main' }`. `Jenkinsfile.eg` must gate on `anyOf { branch 'main'; branch 'staging' }` and derive env from `BRANCH_NAME` (Task 5).

8. **Registry auth.** SaaS relies on Jenkins **folder properties** (`withFolderProperties()` → `REGISTRY_HOSTNAME`, `REGISTRY_USERNAME`) that point at `davinciai.azurecr.io`. `Jenkinsfile.eg` must **not** inherit those — it hardcodes `REGISTRY = 'epicregistry.azurecr.io'` in its `environment {}` block (env wins over folder props) and binds eg's ACR creds `epicregistry-username` / `epicregistry-token`.

---

## Decisions (resolved)

- **D1 — Postgres image & size.** `postgres:17`, `10Gi` PVC on `storageClassName: default` (managed disk — see delta #5; eg-erp uses `default`/20Gi for its Postgres), resources `256Mi/250m` req → `1Gi/1000m` lim. Adjustable later.
- **D2 — Sequencing: both envs live from the start.** ✅ *Resolved:* create `staging` and `main` together and let **both deploy on first push** (no staging-first gate). Task 6 reflects this. ⚠️ Consequence: prod (`esign.epicgroup.ca`) goes live on the very first `main` build, before staging has been exercised — so **all external prod prerequisites (real `.p12`, prod Jenkins creds, prod DNS, Azure AD prod redirect URI) must be in place before the first `main` push**, or the prod deploy will fail its health check and `rollout undo`.
- **D3 — Keep `k8s/production/` as reference.** ✅ *Resolved:* leave `k8s/production/` in the fork, untouched, as a SaaS comparison point. Not deleted. (Accepted foot-gun: never `kubectl apply -f k8s/production` against the eg cluster — it targets the wrong registry/cluster/namespace.)

---

## File structure

```
k8s/eg-esign/
  base/
    kustomization.yaml            # NEW — lists resources, NO commonLabels (delta #2)
    configmap.yaml                # from production/configmap.yaml, env-agnostic (delta #1,#4,#6)
    app-deployment.yaml           # from production/app-deployment.yaml (delta #3,#4,#6)
    ingress.yaml                  # from production/ingress.yaml, host/tls stripped
    postgres-statefulset.yaml     # NEW (delta #1) — Postgres 17 + Service eg-esign-postgres
    postgres-pvc.yaml             # NEW (delta #1,#5) — storageClass default
    minio-statefulset.yaml        # from production/minio-statefulset.yaml, renamed
    minio-pvc.yaml                # from production/minio-pvc.yaml, storageClass default (delta #5)
    pdb.yaml                      # from production/pdb.yaml, renamed
  overlays/
    staging/
      kustomization.yaml          # namespace eg-esign-staging, newTag staging
      configmap-patch.yaml        # NEXT_PUBLIC_WEBAPP_URL staging
      ingress-patch.yaml          # host esign-staging.epicgroup.ca, tls eg-esign-staging-tls
    prod/
      kustomization.yaml          # namespace eg-esign-prod, newTag prod
      configmap-patch.yaml        # NEXT_PUBLIC_WEBAPP_URL prod
      ingress-patch.yaml          # host esign.epicgroup.ca, tls eg-esign-prod-tls
Jenkinsfile.eg                    # NEW — multibranch, branch→env→namespace
```

Everything under `k8s/eg-esign/` and `Jenkinsfile.eg` is added **identically on both `main` and `staging`** — the running env is resolved at deploy time from the branch, so the tree does not differ per branch.

---

## Task 1: Scaffold base app-deployment, configmap, ingress, minio, pdb (rename + strip)

**Files:**
- Create: `k8s/eg-esign/base/app-deployment.yaml`
- Create: `k8s/eg-esign/base/configmap.yaml`
- Create: `k8s/eg-esign/base/ingress.yaml`
- Create: `k8s/eg-esign/base/minio-statefulset.yaml`
- Create: `k8s/eg-esign/base/minio-pvc.yaml`
- Create: `k8s/eg-esign/base/pdb.yaml`
- Reference (do not modify): `k8s/production/*.yaml`

**Transformation rules applied to every copied file:**
- `davinci-sign` → `eg-esign` (names, labels, selectors, service names) — **globally consistent**.
- Remove every `namespace: davinci-sign-production` line (overlay `namespace:` stamps it).
- Remove every `environment: production` label (env-specific; overlays don't need it and it invites drift).
- Image `davinciai.azurecr.io/davinci-sign:production` → `epicregistry.azurecr.io/davinci-technology/eg-esign` **with no tag** on both the `app` container and the `migrate` init container (delta #3). Overlay supplies the tag.
- Cert `mountPath: /opt/davinci-sign` → `/opt/eg-esign` (delta #6).
- `minio-pvc.yaml`: keep `storageClassName: default` (delta #5 — do NOT switch to azurefile).

- [ ] **Step 1: Create `base/configmap.yaml`** (env-agnostic, in-cluster Postgres, OIDC on)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: eg-esign-config
  labels:
    app: eg-esign
data:
  PORT: "3000"
  NODE_ENV: "production"
  LOG_LEVEL: "info"

  # URLs — NEXT_PUBLIC_WEBAPP_URL is patched per overlay
  NEXT_PUBLIC_WEBAPP_URL: "https://esign.epicgroup.ca"
  NEXT_PRIVATE_INTERNAL_WEBAPP_URL: "http://eg-esign:3000"

  # Database — in-cluster Postgres (delta #1)
  POSTGRES_USER: "eg_esign"
  POSTGRES_DB: "eg_esign"
  DB_HOST: "eg-esign-postgres"
  DB_PORT: "5432"
  DB_SSLMODE: "disable"

  # MinIO (in-cluster)
  NEXT_PUBLIC_UPLOAD_TRANSPORT: "s3"
  NEXT_PRIVATE_UPLOAD_ENDPOINT: "http://minio:9000"
  NEXT_PRIVATE_UPLOAD_FORCE_PATH_STYLE: "true"
  NEXT_PRIVATE_UPLOAD_REGION: "us-east-1"
  NEXT_PRIVATE_UPLOAD_BUCKET: "eg-esign"
  NEXT_PRIVATE_UPLOAD_ACCESS_KEY_ID: "eg-esign"

  # SMTP (SendGrid)
  NEXT_PRIVATE_SMTP_TRANSPORT: "smtp-auth"
  NEXT_PRIVATE_SMTP_HOST: "smtp.sendgrid.net"
  NEXT_PRIVATE_SMTP_PORT: "587"
  NEXT_PRIVATE_SMTP_USERNAME: "apikey"
  NEXT_PRIVATE_SMTP_FROM_NAME: "Epic Group e-Sign"
  NEXT_PRIVATE_SMTP_FROM_ADDRESS: "noreply@epicgroup.ca"
  NEXT_PRIVATE_SMTP_SECURE: "false"

  # Document signing (local .p12) — mount path /opt/eg-esign (delta #6)
  NEXT_PRIVATE_SIGNING_TRANSPORT: "local"
  NEXT_PRIVATE_SIGNING_LOCAL_FILE_PATH: "/opt/eg-esign/cert.p12"

  # OIDC (Azure AD, Epic Group tenant) — delta #4
  NEXT_PRIVATE_OIDC_WELL_KNOWN: "https://login.microsoftonline.com/f42ecea6-d935-484f-91e2-f3c236207764/v2.0/.well-known/openid-configuration"
  NEXT_PRIVATE_OIDC_PROVIDER_LABEL: "Sign in with Microsoft"

  # Signup lockdown — delta #4
  NEXT_PUBLIC_DISABLE_SIGNUP: "true"
  NEXT_PRIVATE_ALLOWED_SIGNUP_DOMAINS: "epicgroup.ca"

  # Features
  NEXT_PUBLIC_FEATURE_BILLING_ENABLED: "false"
  NEXT_PUBLIC_DISABLE_ONBOARDING: "false"
  NEXT_PUBLIC_DOCUMENT_LINK_ENABLED: "true"
  NEXT_PUBLIC_DOCUMENT_SIZE_UPLOAD_LIMIT: "50"

  DOCUMENSO_DISABLE_TELEMETRY: "true"
```

- [ ] **Step 2: Create `base/app-deployment.yaml`** — copy SaaS `app-deployment.yaml`, apply transformation rules, and **add the two OIDC env vars** to the `app` container (delta #4). The migrate init container and app container both use image `epicregistry.azurecr.io/davinci-technology/eg-esign` (no tag). Add under the existing `env:` block of the `app` container:

```yaml
            - name: NEXT_PRIVATE_OIDC_CLIENT_ID
              valueFrom:
                secretKeyRef:
                  name: eg-esign-secrets
                  key: NEXT_PRIVATE_OIDC_CLIENT_ID
            - name: NEXT_PRIVATE_OIDC_CLIENT_SECRET
              valueFrom:
                secretKeyRef:
                  name: eg-esign-secrets
                  key: NEXT_PRIVATE_OIDC_CLIENT_SECRET
```

  Keep the `initContainers` (create-bucket, migrate), probes (`/api/health` with `Host: localhost`), `imagePullSecrets: [acr-pull-secret]`, securityContext, and the `Service` at the bottom — all renamed. Cert volumeMount `mountPath: /opt/eg-esign`.

- [ ] **Step 3: Create `base/ingress.yaml`** — copy SaaS ingress, rename, keep all `nginx.ingress.kubernetes.io/*` annotations + `cert-manager.io/cluster-issuer: letsencrypt-prod`, keep `ingressClassName: nginx`. Set `metadata.name: eg-esign-ingress`. Leave a placeholder host (`esign.epicgroup.ca`) and tls (`hosts: [esign.epicgroup.ca]`, `secretName: eg-esign-prod-tls`) — the overlay `ingress-patch.yaml` overrides both. Backend service `eg-esign:3000`.

- [ ] **Step 4: Create `base/minio-statefulset.yaml`** — copy, rename `davinci-sign` → `eg-esign` only where it is a *reference to the app* (the `MINIO_ROOT_USER: "davinci-sign"` value and secret name `davinci-sign-secrets` → `eg-esign-secrets`). Keep `app: minio` labels/selectors **unchanged** (delta #2). Keep the MinIO `Service`.

- [ ] **Step 5: Create `base/minio-pvc.yaml`** — copy verbatim apart from labels: `storageClassName: default` stays (delta #5), name `minio-data`, keep `app: minio`.

- [ ] **Step 6: Create `base/pdb.yaml`** — copy both PDBs, rename `davinci-sign-pdb` → `eg-esign-pdb` (selector `app: eg-esign`), keep `minio-pdb` (`app: minio`).

- [ ] **Step 7: Commit**

```bash
git add k8s/eg-esign/base/configmap.yaml k8s/eg-esign/base/app-deployment.yaml \
        k8s/eg-esign/base/ingress.yaml k8s/eg-esign/base/minio-statefulset.yaml \
        k8s/eg-esign/base/minio-pvc.yaml k8s/eg-esign/base/pdb.yaml
git commit -m "feat(k8s): scaffold eg-esign base from SaaS manifests"
```

---

## Task 2: Author in-cluster Postgres (new — no SaaS equivalent)

**Files:**
- Create: `k8s/eg-esign/base/postgres-statefulset.yaml`
- Create: `k8s/eg-esign/base/postgres-pvc.yaml`

- [ ] **Step 1: Create `base/postgres-pvc.yaml`**

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: eg-esign-postgres-data
  labels:
    app: eg-esign-postgres
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi
  storageClassName: default
```

- [ ] **Step 2: Create `base/postgres-statefulset.yaml`** — Postgres 17, password from `eg-esign-secrets`, Service `eg-esign-postgres:5432`. Selector `app: eg-esign-postgres` (delta #2 — distinct from `eg-esign`).

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: eg-esign-postgres
  labels:
    app: eg-esign-postgres
spec:
  serviceName: eg-esign-postgres
  replicas: 1
  selector:
    matchLabels:
      app: eg-esign-postgres
  template:
    metadata:
      labels:
        app: eg-esign-postgres
    spec:
      containers:
        - name: postgres
          image: postgres:17
          ports:
            - containerPort: 5432
              name: postgres
          env:
            - name: POSTGRES_USER
              valueFrom:
                configMapKeyRef:
                  name: eg-esign-config
                  key: POSTGRES_USER
            - name: POSTGRES_DB
              valueFrom:
                configMapKeyRef:
                  name: eg-esign-config
                  key: POSTGRES_DB
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: eg-esign-secrets
                  key: POSTGRES_PASSWORD
            - name: PGDATA
              value: /var/lib/postgresql/data/pgdata
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
          resources:
            requests:
              memory: "256Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
          livenessProbe:
            exec:
              command: ["sh", "-c", "pg_isready -U $POSTGRES_USER -d $POSTGRES_DB"]
            initialDelaySeconds: 30
            periodSeconds: 15
            timeoutSeconds: 5
            failureThreshold: 3
          readinessProbe:
            exec:
              command: ["sh", "-c", "pg_isready -U $POSTGRES_USER -d $POSTGRES_DB"]
            initialDelaySeconds: 10
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: eg-esign-postgres-data
---
apiVersion: v1
kind: Service
metadata:
  name: eg-esign-postgres
  labels:
    app: eg-esign-postgres
spec:
  selector:
    app: eg-esign-postgres
  ports:
    - port: 5432
      targetPort: 5432
      name: postgres
  type: ClusterIP
```

> Note: `PGDATA` is set to a subdir so the `lost+found` dir on a fresh managed-disk (ext4) mount doesn't trip Postgres' "directory not empty" init check — same pattern as eg-erp's `deployment-postgres.yaml:44-45`.

- [ ] **Step 3: Verify Postgres YAML parses**

Run: `kubectl apply --dry-run=client -f k8s/eg-esign/base/postgres-statefulset.yaml -f k8s/eg-esign/base/postgres-pvc.yaml`
Expected: `... created (dry run)` for StatefulSet, Service, PVC — no schema errors. (Requires a kubeconfig context; if none locally, defer to Task 4's `kustomize build`.)

- [ ] **Step 4: Commit**

```bash
git add k8s/eg-esign/base/postgres-statefulset.yaml k8s/eg-esign/base/postgres-pvc.yaml
git commit -m "feat(k8s): add in-cluster Postgres 17 for eg-esign"
```

---

## Task 3: Base kustomization (NO commonLabels — delta #2)

**Files:**
- Create: `k8s/eg-esign/base/kustomization.yaml`

- [ ] **Step 1: Create `base/kustomization.yaml`**

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
# NOTE: intentionally NO commonLabels/labels-with-selectors — the app, minio,
# and postgres workloads have distinct immutable `app:` selectors (delta #2).
resources:
  - configmap.yaml
  - app-deployment.yaml
  - ingress.yaml
  - postgres-statefulset.yaml
  - postgres-pvc.yaml
  - minio-statefulset.yaml
  - minio-pvc.yaml
  - pdb.yaml
```

- [ ] **Step 2: Verify base builds**

Run: `kubectl kustomize k8s/eg-esign/base` (or `kustomize build k8s/eg-esign/base`)
Expected: renders all objects; **no** `namespace:` on any object yet (overlay adds it); image is `epicregistry.azurecr.io/davinci-technology/eg-esign` with no tag; MinIO/Postgres selectors still `app: minio` / `app: eg-esign-postgres`.

- [ ] **Step 3: Commit**

```bash
git add k8s/eg-esign/base/kustomization.yaml
git commit -m "feat(k8s): eg-esign base kustomization"
```

---

## Task 4: Overlays (staging + prod)

**Files:**
- Create: `k8s/eg-esign/overlays/staging/kustomization.yaml`
- Create: `k8s/eg-esign/overlays/staging/configmap-patch.yaml`
- Create: `k8s/eg-esign/overlays/staging/ingress-patch.yaml`
- Create: `k8s/eg-esign/overlays/prod/kustomization.yaml`
- Create: `k8s/eg-esign/overlays/prod/configmap-patch.yaml`
- Create: `k8s/eg-esign/overlays/prod/ingress-patch.yaml`

- [ ] **Step 1: `overlays/staging/kustomization.yaml`**

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: eg-esign-staging
resources:
  - ../../base
images:
  - name: epicregistry.azurecr.io/davinci-technology/eg-esign
    newTag: staging
patches:
  - path: configmap-patch.yaml
  - path: ingress-patch.yaml
```

- [ ] **Step 2: `overlays/staging/configmap-patch.yaml`**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: eg-esign-config
data:
  NEXT_PUBLIC_WEBAPP_URL: "https://esign-staging.epicgroup.ca"
```

- [ ] **Step 3: `overlays/staging/ingress-patch.yaml`**

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: eg-esign-ingress
spec:
  tls:
    - hosts: ["esign-staging.epicgroup.ca"]
      secretName: eg-esign-staging-tls
  rules:
    - host: esign-staging.epicgroup.ca
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: eg-esign
                port:
                  number: 3000
```

- [ ] **Step 4: `overlays/prod/kustomization.yaml`** — same as staging with `namespace: eg-esign-prod` and `newTag: prod`.

- [ ] **Step 5: `overlays/prod/configmap-patch.yaml`** — `NEXT_PUBLIC_WEBAPP_URL: "https://esign.epicgroup.ca"`.

- [ ] **Step 6: `overlays/prod/ingress-patch.yaml`** — host `esign.epicgroup.ca`, `secretName: eg-esign-prod-tls`.

- [ ] **Step 7: Verify both overlays build and stamp correctly**

Run:
```bash
kubectl kustomize k8s/eg-esign/overlays/staging | grep -E "namespace: eg-esign-staging|eg-esign:staging|esign-staging.epicgroup.ca" | head
kubectl kustomize k8s/eg-esign/overlays/prod    | grep -E "namespace: eg-esign-prod|eg-esign:prod|host: esign.epicgroup.ca" | head
```
Expected: staging build shows `namespace: eg-esign-staging`, image tag `:staging`, host `esign-staging.epicgroup.ca`; prod build shows the prod equivalents. Confirm **every** object in each build carries the right `namespace:`.

- [ ] **Step 8: Commit**

```bash
git add k8s/eg-esign/overlays
git commit -m "feat(k8s): eg-esign staging + prod overlays"
```

---

## Task 5: `Jenkinsfile.eg` (branch → env → namespace)

**Files:**
- Create: `Jenkinsfile.eg`
- Reference (do not modify): `Jenkinsfile` (SaaS — copy Build Check + Kaniko verbatim except registry/name/tag)

**Structure:** `Checkout → Resolve Env → Build Check → Build Image → Deploy`. Copy the SaaS `Checkout` and `Build Check` stages **verbatim** (same node:22-alpine agent, `npm install -g npm@11.11.0`, `npm ci`, `npm run translate:compile`, `NODE_OPTIONS=--max-old-space-size=8192 npm run build`, dummy encryption keys in the pod env). Differences below.

- [ ] **Step 1: `environment {}` block** (delta #8 — do NOT inherit SaaS folder props for the registry)

```groovy
  environment {
    REGISTRY = 'epicregistry.azurecr.io'
    IMAGE_NAME = 'davinci-technology/eg-esign'
    GIT_CREDENTIAL_ID = 'f1b484af-24eb-4f28-a57a-66db51117a73'
    KUBECONFIG_CREDENTIAL_ID = 'eg-aks-kubeconfig'
  }
```

- [ ] **Step 2: `Resolve Env` stage** — runs on every branch; sets env from `BRANCH_NAME`, errors on non-deploy branches, computes immutable `IMAGE_TAG`.

```groovy
    stage('Resolve Env') {
      agent { kubernetes { yaml "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n  - name: jnlp\n    image: jenkins/inbound-agent:3345.v03dee9b_f88fc-6" } }
      steps {
        script {
          if (env.BRANCH_NAME == 'main') {
            env.ENVN='prod';    env.NAMESPACE='eg-esign-prod';    env.HOST='esign.epicgroup.ca';         env.TAG='prod';    env.CRED='-prod'
          } else if (env.BRANCH_NAME == 'staging') {
            env.ENVN='staging'; env.NAMESPACE='eg-esign-staging'; env.HOST='esign-staging.epicgroup.ca'; env.TAG='staging'; env.CRED='-staging'
          } else { error "Branch ${env.BRANCH_NAME} is not an eg-esign deploy branch" }
          env.IMAGE_TAG = "${env.TAG}-${BUILD_NUMBER}-${GIT_COMMIT.take(7)}"
        }
      }
    }
```

- [ ] **Step 3: `Build Image` stage** — Kaniko, gated `anyOf { branch 'main'; branch 'staging' }` (delta #7), auth from `epicregistry-username`/`epicregistry-token` (delta #8), pushes both `:${IMAGE_TAG}` and `:${TAG}`, `--dockerfile=./docker/Dockerfile`, cache-repo `${REGISTRY}/${IMAGE_NAME}-cache`.

- [ ] **Step 4: `Deploy` stage** — gated `anyOf { branch 'main'; branch 'staging' }`, `alpine/k8s:1.32.1` agent. Bind kubeconfig + registry creds + all `eg-esign-*${CRED}` secret creds (incl. OIDC id/secret + `.p12` file). **Adapt the SaaS `Jenkinsfile` deploy `sh` block (lines 216–306) with these deltas** — do not invent the shell from scratch:
  1. All names `davinci-sign*` → `eg-esign*`; `${NAMESPACE}` comes from Resolve Env.
  2. Secret `eg-esign-secrets` gets **two extra keys** the SaaS block lacks:
     `--from-literal=NEXT_PRIVATE_OIDC_CLIENT_ID="${OIDC_CLIENT_ID}"` and
     `--from-literal=NEXT_PRIVATE_OIDC_CLIENT_SECRET="${OIDC_CLIENT_SECRET}"`.
  3. **Add** `acr-pull-secret` creation (SaaS never created it in-pipeline):
     ```bash
     kubectl create secret docker-registry acr-pull-secret -n ${NAMESPACE} \
       --docker-server=${REGISTRY} --docker-username=${REGISTRY_USERNAME} \
       --docker-password=${REGISTRY_TOKEN} --dry-run=client -o yaml | kubectl apply -f -
     ```
  4. Replace the per-file `kubectl apply -f k8s/production/...` list with a single
     `kubectl apply -k k8s/eg-esign/overlays/${ENVN}`.
  5. `kubectl set image deployment/eg-esign app=${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG} -n ${NAMESPACE}` (note `${REGISTRY}`, not the SaaS `${REGISTRY_HOSTNAME}` folder prop).
  6. Keep the SaaS health-check loop (30×10s poll of `/api/health` expecting `ok`, `rollout undo` + `exit 1` on failure) verbatim, renamed.

  Additional deploy waits (in-cluster stateful deps that SaaS didn't have in-cluster), placed **before** the app `rollout status` so the migrate init container has a DB to talk to — soft-fail with `|| true` like the SaaS MinIO wait, since first-deploy PVC provisioning + image pull can exceed the timeout and the app rollout status still guards correctness:
```bash
kubectl -n ${NAMESPACE} rollout status statefulset/eg-esign-postgres --timeout=5m || true
kubectl -n ${NAMESPACE} rollout status statefulset/minio --timeout=3m || true
```

- [ ] **Step 5: Lint the Jenkinsfile** (if a Jenkins is reachable)

Run: `curl -s -X POST -F "jenkinsfile=<Jenkinsfile.eg" <JENKINS_URL>/pipeline-model-converter/validate` (or paste into Jenkins "Replay" validator).
Expected: `Jenkinsfile successfully validated.` If no Jenkins reachable, at minimum verify Groovy brace/quote balance by eye and that every `withCredentials` id has a matching `-${CRED}` suffix.

- [ ] **Step 6: Commit**

```bash
git add Jenkinsfile.eg
git commit -m "feat(ci): add Jenkinsfile.eg branch->env->namespace pipeline"
```

---

## Task 6: Branch + external wiring checklist (not code)

**Files:** none (git branch + Jenkins/Azure/AKS console steps).

> **D2 (both envs live from the start):** All external prerequisites for **both** environments must be in place before their respective first push. Because `main` deploys prod on first build with no staging gate, the **prod** prerequisites (real `.p12`, `-prod` Jenkins creds, prod DNS, Azure AD prod redirect URI) are required up front, not deferred.

- [ ] **Step 1: External one-time prerequisites (both envs)** — these gate the first pipeline run of each branch. Perform in the respective consoles (runbook Part 2/3/4a):
  - [ ] DNS: **both** `esign-staging.epicgroup.ca` and `esign.epicgroup.ca` → `130.107.18.187` (create **before** first deploy so ACME HTTP-01 resolves).
  - [ ] Jenkins Secret file `eg-aks-kubeconfig` (eg AKS kubeconfig — the same file eg_intranet uses).
  - [ ] Jenkins Secret text `epicregistry-username` / `epicregistry-token` (eg_intranet's ACR creds).
  - [ ] Jenkins per-env secret creds `eg-esign-*-staging` **and `eg-esign-*-prod`**: nextauth, encryption-key, encryption-key-2, postgres-password, minio-password, smtp-password, cert-passphrase, certificate-p12 (file), oidc-client-id, oidc-client-secret. **Encryption keys are load-bearing — set once, never rotate casually; distinct per env; never the SaaS keys.**
  - [ ] Azure AD: new **single-tenant** app registration in tenant `f42ecea6-…`, redirect URIs `https://esign.epicgroup.ca/api/auth/callback/oidc` **and** `https://esign-staging.epicgroup.ca/api/auth/callback/oidc`, delegated `openid profile email` only. Client id/secret → the Jenkins OIDC creds. (Do **not** reuse the HR app `bbe95a94-…`.)
  - [ ] Signing `.p12`: self-signed OK for staging; **real Epic Group cert for prod (required up front — prod deploys on first `main` push).**

- [ ] **Step 2: Publish both branches.** With all repo tasks (Tasks 1–5) committed, ensure `Jenkinsfile.eg` + `k8s/eg-esign/` are present on **both** `main` and `staging`:

```bash
# on the branch holding the work (e.g. a feature branch merged to main first, or committed directly)
git checkout main && git push
git checkout -b staging && git push -u origin staging   # staging == main at infra level
```

- [ ] **Step 3: Create the Jenkins multibranch job** "eg-esign" on `Davinci-Technology/eg_esign`, branches `main` + `staging`, Jenkinsfile path `Jenkinsfile.eg`. Scan → **both** branches build and deploy their env (`main → eg-esign-prod`, `staging → eg-esign-staging`).

- [ ] **Step 4: Verify both envs (runbook §3.5)** — repeat per namespace:

```bash
for NS in eg-esign-staging eg-esign-prod; do
  kubectl -n $NS get pods,ingress,certificate
  kubectl -n $NS exec deployment/eg-esign -- wget -qO- http://localhost:3000/api/health   # ok
done
curl -I https://esign-staging.epicgroup.ca    # 200 + LE TLS
curl -I https://esign.epicgroup.ca            # 200 + LE TLS
```
Then browser (each host): Azure AD sign-in, upload → sign → download a test doc (exercises MinIO + `.p12` + in-cluster Postgres).

- [ ] **Step 5: Ongoing promotion model.** After go-live, changes still flow `staging → main` by merge so staging remains the pre-prod proving ground:

```bash
git checkout main && git merge staging && git push   # auto-builds + deploys eg-esign-prod
```

> **D3:** `k8s/production/` is **kept as SaaS reference** — do not delete, and never `kubectl apply -f k8s/production` against the eg cluster.

---

## Validation summary (what "done" means)

- `kubectl kustomize k8s/eg-esign/overlays/staging` and `.../prod` both render cleanly, every object namespaced, correct image tag + host per env.
- `Jenkinsfile.eg` validates and, on push to `staging`, produces a green `eg-esign-staging` deploy with a passing `/api/health` and LE TLS.
- Promotion `merge staging → main` produces a green `eg-esign-prod` deploy.
- SaaS `davinci-sign` pipeline is untouched and shares nothing (separate registry, cluster, namespace, credentials).
```
