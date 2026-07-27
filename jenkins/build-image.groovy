// Build the eg-esign container with Kaniko, push to the Epic Group ACR, then
// scan the pushed image with Trivy.
//
// Kaniko (not `docker build`) because these run on Kubernetes pod agents with
// no Docker socket. Trivy scans the pushed tag over the registry API for the
// same reason.
//
// Usage: def buildImage = load 'jenkins/build-image.groovy'; buildImage()

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
                # --single-snapshot keeps peak memory down on this large tree.
                # Two destinations: the immutable per-build tag that the deploy
                # actually pins, and the moving env tag for humans.
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

        // Scan the tag we just pushed. Failing here blocks the deploy, which is
        // the point: an image with a fixable HIGH/CRITICAL never reaches a cluster.
        helpers.withGitHubStatus('Image Scan') {
            def outputFile = helpers.outputFileFor('Image Scan')
            def status = container('trivy') {
                sh(returnStatus: true, script: '''#!/bin/sh
                    export TRIVY_USERNAME="${REGISTRY_USERNAME}"
                    export TRIVY_PASSWORD="${REGISTRY_TOKEN}"
                    # --timeout: the default 5m is not enough for this image.
                    # It carries a production node_modules tree, and Trivy
                    # analyses every package.json in it; the default deadline
                    # aborts the scan with "context deadline exceeded".
                    trivy image "${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}" \
                        --scanners vuln \
                        --severity HIGH,CRITICAL \
                        --ignore-unfixed \
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
