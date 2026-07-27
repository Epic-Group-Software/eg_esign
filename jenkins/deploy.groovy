// Deploy eg-esign to the Epic Group AKS cluster (eg-k8s-01).
//
// Kubeconfig comes from `epic-fleet-kubeconfig` — the same global credential
// Project-Operations uses to reach this cluster. Per-environment app secrets
// come from job-scoped credentials suffixed -staging / -prod (env.CRED).
//
// Usage: def deploy = load 'jenkins/deploy.groovy'; deploy()

def call() {
    def helpers = load 'jenkins/helpers.groovy'

    withCredentials([
        file(credentialsId: 'epic-fleet-kubeconfig', variable: 'KUBECONFIG_FILE'),
        string(credentialsId: 'REGISTRY_TOKEN', variable: 'REGISTRY_TOKEN'),
        string(credentialsId: "eg-esign-nextauth-secret${env.CRED}", variable: 'NEXTAUTH_SECRET'),
        string(credentialsId: "eg-esign-encryption-key${env.CRED}", variable: 'ENCRYPTION_KEY'),
        string(credentialsId: "eg-esign-encryption-key-2${env.CRED}", variable: 'ENCRYPTION_KEY_2'),
        string(credentialsId: "eg-esign-postgres-password${env.CRED}", variable: 'POSTGRES_PASSWORD'),
        string(credentialsId: "eg-esign-minio-password${env.CRED}", variable: 'MINIO_PASSWORD'),
        string(credentialsId: "eg-esign-smtp-password${env.CRED}", variable: 'SMTP_PASSWORD'),
        string(credentialsId: "eg-esign-cert-passphrase${env.CRED}", variable: 'CERT_PASSPHRASE'),
        file(credentialsId: "eg-esign-certificate-p12${env.CRED}", variable: 'CERT_FILE'),
        string(credentialsId: "eg-esign-oidc-client-id${env.CRED}", variable: 'OIDC_CLIENT_ID'),
        string(credentialsId: "eg-esign-oidc-client-secret${env.CRED}", variable: 'OIDC_CLIENT_SECRET'),
    ]) {
        container('kubectl') {
            sh '''#!/bin/bash
                set -euo pipefail
                export KUBECONFIG="${KUBECONFIG_FILE}"

                echo "Deploying eg-esign to ${ENVN}"
                echo "  Namespace: ${NAMESPACE}"
                echo "  Image:     ${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"
                kubectl cluster-info

                kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

                # The deployment declares imagePullSecrets: acr-pull-secret, so it
                # must exist before the manifests are applied.
                kubectl create secret docker-registry acr-pull-secret \
                    --namespace="${NAMESPACE}" \
                    --docker-server="${REGISTRY}" \
                    --docker-username="${REGISTRY_USERNAME}" \
                    --docker-password="${REGISTRY_TOKEN}" \
                    --dry-run=client -o yaml | kubectl apply -f -

                kubectl create secret generic eg-esign-secrets \
                    --namespace="${NAMESPACE}" \
                    --from-literal=NEXTAUTH_SECRET="${NEXTAUTH_SECRET}" \
                    --from-literal=NEXT_PRIVATE_ENCRYPTION_KEY="${ENCRYPTION_KEY}" \
                    --from-literal=NEXT_PRIVATE_ENCRYPTION_SECONDARY_KEY="${ENCRYPTION_KEY_2}" \
                    --from-literal=POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
                    --from-literal=MINIO_ROOT_PASSWORD="${MINIO_PASSWORD}" \
                    --from-literal=NEXT_PRIVATE_UPLOAD_SECRET_ACCESS_KEY="${MINIO_PASSWORD}" \
                    --from-literal=NEXT_PRIVATE_SMTP_PASSWORD="${SMTP_PASSWORD}" \
                    --from-literal=NEXT_PRIVATE_SIGNING_PASSPHRASE="${CERT_PASSPHRASE}" \
                    --from-literal=NEXT_PRIVATE_OIDC_CLIENT_ID="${OIDC_CLIENT_ID}" \
                    --from-literal=NEXT_PRIVATE_OIDC_CLIENT_SECRET="${OIDC_CLIENT_SECRET}" \
                    --dry-run=client -o yaml | kubectl apply -f -

                kubectl create secret generic eg-esign-certificate \
                    --namespace="${NAMESPACE}" \
                    --from-file=cert.p12="${CERT_FILE}" \
                    --dry-run=client -o yaml | kubectl apply -f -

                # Pin the immutable per-build tag on every container that uses
                # this image. Doing it through kustomize rather than a follow-up
                # `kubectl set image` means one source of truth: `set image`
                # patches only the container named on the command line, so any
                # other container keeps whatever moving tag the overlay declared.
                cd "k8s/eg-esign/overlays/${ENVN}"
                kustomize edit set image \
                    "${REGISTRY}/${IMAGE_NAME}=${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"
                cd - >/dev/null

                kubectl apply -k "k8s/eg-esign/overlays/${ENVN}"

                # Soft-fail: on a first deploy these wait on PVC provisioning and
                # image pulls that can exceed the timeout. The app rollout below
                # is the real gate.
                kubectl -n "${NAMESPACE}" rollout status statefulset/eg-esign-postgres --timeout=5m || true
                kubectl -n "${NAMESPACE}" rollout status statefulset/minio --timeout=3m || true

                kubectl -n "${NAMESPACE}" rollout status deployment/eg-esign --timeout=10m
            '''

            // Health gate. /api/health returns
            //   {"status":<overall>,"timestamp":...,"checks":{...}}
            // with "status" as the FIRST key. The pattern is anchored at
            // position 0 so a nested checks.*.status can never satisfy it — a
            // bare `grep ok` would pass a top-level "error" body whose
            // untouched checks.certificate still reads "ok". "warning" is
            // accepted deliberately (e.g. cert unavailable, still HTTP 200) to
            // match the pod's own readinessProbe, which passes on any 2xx.
            // Only a top-level "error" (HTTP 500) fails the deploy.
            sh '''#!/bin/bash
                set -uo pipefail
                export KUBECONFIG="${KUBECONFIG_FILE}"

                for i in $(seq 1 30); do
                    if kubectl exec deployment/eg-esign -n "${NAMESPACE}" -- \
                        wget -q -O- http://localhost:3000/api/health 2>/dev/null \
                        | grep -qE '^\\{"status":"(ok|warning)"'; then
                        echo "Health check passed"
                        kubectl get pods -n "${NAMESPACE}"
                        echo "URL: https://${HOST}"
                        exit 0
                    fi
                    echo "Waiting for health... ($i/30)"
                    sleep 10
                done

                echo "Health check failed - rolling back"
                kubectl rollout undo deployment/eg-esign -n "${NAMESPACE}"
                kubectl rollout status deployment/eg-esign -n "${NAMESPACE}" --timeout=5m || true
                exit 1
            '''
        }
    }
}

return this
