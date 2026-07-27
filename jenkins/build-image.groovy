// Kaniko build + push to Epic Group ACR, then Trivy scan of the pushed image.
// Kaniko rather than `docker build` because these are pod agents with no
// Docker socket; Trivy scans over the registry API for the same reason.

def call() {
    def helpers = load 'jenkins/helpers.groovy'

    withCredentials([string(credentialsId: 'REGISTRY_TOKEN', variable: 'REGISTRY_TOKEN')]) {
        container('kaniko') {
            sh '''#!/busybox/sh
                set -e
                mkdir -p /kaniko/.docker
                AUTH=$(echo -n "${REGISTRY_USERNAME}:${REGISTRY_TOKEN}" | base64 | tr -d '\\n')
                cat > /kaniko/.docker/config.json <<EOF
{"auths":{"${REGISTRY}":{"auth":"${AUTH}"}}}
EOF
                # Two tags: the immutable per-build one the deploy pins, and
                # the moving env tag. --single-snapshot caps peak memory.
                /kaniko/executor \
                    --context=. \
                    --dockerfile=./docker/Dockerfile \
                    --destination=${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG} \
                    --destination=${REGISTRY}/${IMAGE_NAME}:${TAG} \
                    --cache=true \
                    --cache-ttl=168h \
                    --cache-repo=${REGISTRY}/${IMAGE_NAME}-cache \
                    --single-snapshot
            '''
        }

        // Failing here blocks the deploy — an image with a fixable
        // HIGH/CRITICAL never reaches a cluster.
        helpers.withGitHubStatus('Image Scan') {
            def outputFile = helpers.outputFileFor('Image Scan')
            def status = container('trivy') {
                sh(returnStatus: true, script: '''#!/bin/sh
                    export TRIVY_USERNAME="${REGISTRY_USERNAME}"
                    export TRIVY_PASSWORD="${REGISTRY_TOKEN}"
                    # Default 5m aborts with "context deadline exceeded" on
                    # this image's node_modules tree.
                    trivy image "${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}" \
                        --scanners vuln \
                        --severity HIGH,CRITICAL \
                        --ignore-unfixed \
                        --ignorefile .trivyignore \
                        --timeout 30m \
                        --exit-code 1 \
                        --format json \
                        --output .ci-trivy-image.json
                ''')
            }

            if (status != 0) {
                def table = container('trivy') {
                    sh(returnStdout: true, script: '''#!/bin/sh
                        COUNT=$(jq '[.Results[]?.Vulnerabilities[]?] | length' .ci-trivy-image.json 2>/dev/null)
                        if [ "$COUNT" = "0" ] || [ -z "$COUNT" ]; then
                            echo "### Image scan did not complete"
                            echo
                            echo "Trivy exited non-zero but produced no findings. See the build log."
                            exit 0
                        fi
                        echo "### Trivy: image vulnerabilities ($COUNT found)"
                        echo
                        echo "| Package | Installed | Fixed in | CVE | Severity |"
                        echo "|---------|-----------|----------|-----|----------|"
                        jq -r '[.Results[]?.Vulnerabilities[]?][:30][]
                            | "| \\(.PkgName) | \\(.InstalledVersion) | \\(.FixedVersion // "-") | \\(.VulnerabilityID) | \\(.Severity) |"' \
                            .ci-trivy-image.json
                    ''')
                }
                writeFile file: outputFile, text: table.trim()
                error('Image vulnerability scan failed')
            }
        }
    }
}

return this
