// Deploy eg-esign to eg-k8s-01 (Epic Group AKS).
//
// Kubeconfig is epic-fleet-kubeconfig — the same credential Project-Operations
// uses for this cluster. App secrets come from job-scoped credentials suffixed
// -staging / -prod (env.CRED).

def call() {
    withCredentials([
        file(credentialsId: 'epic-fleet-kubeconfig', variable: 'KUBECONFIG_FILE'),
        string(credentialsId: 'REGISTRY_TOKEN', variable: 'REGISTRY_TOKEN'),
        string(credentialsId: "eg-esign-nextauth-secret${env.CRED}", variable: 'NEXTAUTH_SECRET'),
        string(credentialsId: "eg-esign-encryption-key${env.CRED}", variable: 'ENCRYPTION_KEY'),
        string(credentialsId: "eg-esign-encryption-key-2${env.CRED}", variable: 'ENCRYPTION_KEY_2'),
        string(credentialsId: "eg-esign-postgres-password${env.CRED}", variable: 'POSTGRES_PASSWORD'),
        string(credentialsId: "eg-esign-minio-password${env.CRED}", variable: 'MINIO_PASSWORD'),
        // Shared across environments on purpose — one SendGrid account sends
        // for both. Split into eg-esign-smtp-password{-staging,-prod} if prod
        // ever needs its own key.
        string(credentialsId: 'sendgrid-api-key', variable: 'SMTP_PASSWORD'),
        string(credentialsId: "eg-esign-cert-passphrase${env.CRED}", variable: 'CERT_PASSPHRASE'),
        file(credentialsId: "eg-esign-certificate-p12${env.CRED}", variable: 'CERT_FILE'),
        string(credentialsId: "eg-esign-oidc-client-id${env.CRED}", variable: 'OIDC_CLIENT_ID'),
        string(credentialsId: "eg-esign-oidc-client-secret${env.CRED}", variable: 'OIDC_CLIENT_SECRET'),
    ]) {
        container('kubectl') {
            sh '''#!/bin/bash
                set -euo pipefail
                export KUBECONFIG="${KUBECONFIG_FILE}"

                echo "Deploying eg-esign to ${ENVN} (ns ${NAMESPACE}, image ${IMAGE_TAG})"
                kubectl cluster-info
                kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

                # The deployment declares imagePullSecrets: acr-pull-secret.
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

                # kustomize, not `kubectl set image` — set image patches only
                # the named container, leaving any other on a moving tag.
                cd "k8s/eg-esign/overlays/${ENVN}"
                kustomize edit set image \
                    "${REGISTRY}/${IMAGE_NAME}=${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"
                cd - >/dev/null

                kubectl apply -k "k8s/eg-esign/overlays/${ENVN}"

                # Soft-fail: first deploy waits on PVC provisioning and image
                # pulls. The app rollout below is the real gate.
                kubectl -n "${NAMESPACE}" rollout status statefulset/eg-esign-postgres --timeout=5m || true
                kubectl -n "${NAMESPACE}" rollout status statefulset/minio --timeout=3m || true

                kubectl -n "${NAMESPACE}" rollout status deployment/eg-esign --timeout=10m
            '''

            // /api/health returns {"status":<overall>,...,"checks":{...}}. The
            // pattern is anchored at position 0 so a nested checks.*.status
            // can't satisfy it — a bare `grep ok` would pass a top-level
            // "error" body. "warning" is accepted to match the readinessProbe
            // (any 2xx); only top-level "error" fails the deploy.
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
