// Deploy eg-esign to eg-k8s-01 (Epic Group AKS) via Helm.
//
// Kubeconfig is epic-fleet-kubeconfig — the same credential Project-Operations
// uses for this cluster. App secrets come from job-scoped credentials suffixed
// -staging / -prod (env.CRED).

def call() {
    def release = "eg-esign"
    def valuesFile = (env.ENVN == 'prod') ? 'values-production.yaml' : 'values-staging.yaml'

    withCredentials([
        file(credentialsId: 'epic-fleet-kubeconfig', variable: 'KUBECONFIG_FILE'),
        string(credentialsId: 'REGISTRY_TOKEN', variable: 'REGISTRY_TOKEN'),
        string(credentialsId: "eg-esign-nextauth-secret${env.CRED}", variable: 'NEXTAUTH_SECRET'),
        string(credentialsId: "eg-esign-encryption-key${env.CRED}", variable: 'ENCRYPTION_KEY'),
        string(credentialsId: "eg-esign-encryption-key-2${env.CRED}", variable: 'ENCRYPTION_KEY_2'),
        string(credentialsId: "eg-esign-postgres-password${env.CRED}", variable: 'POSTGRES_PASSWORD'),
        string(credentialsId: "eg-esign-minio-password${env.CRED}", variable: 'MINIO_PASSWORD'),
        // Per-environment: a shared SendGrid key would let staging email real signers.
        string(credentialsId: "eg-esign-smtp-password${env.CRED}", variable: 'SMTP_PASSWORD'),
        string(credentialsId: "eg-esign-cert-passphrase${env.CRED}", variable: 'CERT_PASSPHRASE'),
        file(credentialsId: "eg-esign-certificate-p12${env.CRED}", variable: 'CERT_FILE'),
        string(credentialsId: "eg-esign-oidc-client-id${env.CRED}", variable: 'OIDC_CLIENT_ID'),
        string(credentialsId: "eg-esign-oidc-client-secret${env.CRED}", variable: 'OIDC_CLIENT_SECRET'),
        // Only staging runs mailpit, but the binding must exist for both so the
        // withCredentials block stays valid; prod simply never renders it.
        string(credentialsId: 'eg-esign-mailpit-ui-password', variable: 'MAILPIT_UI_PASSWORD'),
    ]) {
        container('kubectl') {
            sh """#!/bin/bash
                set -euo pipefail
                export KUBECONFIG="\${KUBECONFIG_FILE}"

                echo "Deploying eg-esign to \${ENVN} (ns \${NAMESPACE}, image \${IMAGE_TAG})"
                kubectl cluster-info
                kubectl create namespace "\${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

                # The chart references imagePullSecrets: acr-pull-secret, which
                # Helm does not own.
                kubectl create secret docker-registry acr-pull-secret \
                    --namespace="\${NAMESPACE}" \
                    --docker-server="\${REGISTRY}" \
                    --docker-username="\${REGISTRY_USERNAME}" \
                    --docker-password="\${REGISTRY_TOKEN}" \
                    --dry-run=client -o yaml | kubectl apply -f -

                # Binary secret; base64 here rather than --set-file, which would
                # mangle the .p12.
                CERT_B64=\$(base64 -w0 "\${CERT_FILE}")

                # --history-max 5: `--set` puts these secrets in the release
                # history, so old revisions retain rotated credentials.
                helm upgrade --install ${release} helm/chart \
                    --namespace "\${NAMESPACE}" \
                    --create-namespace \
                    -f helm/chart/values.yaml \
                    -f helm/chart/${valuesFile} \
                    --set namespace="\${NAMESPACE}" \
                    --set tag="\${IMAGE_TAG}" \
                    --set image.app.repository="\${REGISTRY}/\${IMAGE_NAME}" \
                    --set-string secrets.nextauthSecret="\${NEXTAUTH_SECRET}" \
                    --set-string secrets.encryptionKey="\${ENCRYPTION_KEY}" \
                    --set-string secrets.encryptionSecondaryKey="\${ENCRYPTION_KEY_2}" \
                    --set-string secrets.smtpPassword="\${SMTP_PASSWORD}" \
                    --set-string secrets.signingPassphrase="\${CERT_PASSPHRASE}" \
                    --set-string secrets.oidcClientId="\${OIDC_CLIENT_ID}" \
                    --set-string secrets.oidcClientSecret="\${OIDC_CLIENT_SECRET}" \
                    --set-string postgres.auth.password="\${POSTGRES_PASSWORD}" \
                    --set-string minio.auth.rootPassword="\${MINIO_PASSWORD}" \
                    --set-string certificate.p12Base64="\${CERT_B64}" \
                    --set-string mailpit.auth.password="\${MAILPIT_UI_PASSWORD}" \
                    --history-max 5 \
                    --wait \
                    --timeout 15m
            """

            // Health gate. /api/health puts "status" first in the body, so the
            // pattern is anchored at position 0 — a bare `grep ok` would pass a
            // top-level "error" whose nested checks.certificate still reads ok.
            // "warning" is accepted to match the readinessProbe (any 2xx); only
            // a top-level "error" (HTTP 500) fails the deploy.
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
                helm rollback eg-esign 0 -n "${NAMESPACE}" --wait --timeout 10m || true
                exit 1
            '''
        }
    }
}

return this
