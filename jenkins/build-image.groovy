// docker build on the shared runner, Trivy scan of the local image, then push
// to Epic Group ACR. Scanning before the push means an image with a fixable
// HIGH/CRITICAL is never published, not even under the moving env tag.
// Same shape as Project-Operations/jenkins/build-container.groovy.

def call() {
    def helpers = load 'jenkins/helpers.groovy'
    def image = "${env.REGISTRY}/${env.IMAGE_NAME}"
    // Per-build docker login dir: the runner is shared, so no credentials are
    // left in a common ~/.docker/config.json.
    def dockerCfg = "${env.WORKSPACE}@tmp/docker-config-${env.BUILD_NUMBER}"

    try {
        // tsc in the image build runs with an 8Gi heap (see docker/Dockerfile).
        sh """#!/bin/bash
            set -euo pipefail
            DOCKER_BUILDKIT=1 docker build --pull \
                -f docker/Dockerfile \
                --label "ci.build_url=\${BUILD_URL}" \
                -t ${image}:\${IMAGE_TAG} \
                .
        """

        // Failing here blocks the push and the deploy.
        helpers.withGitHubStatus('Image Scan') {
            def outputFile = helpers.outputFileFor('Image Scan')
            def status = helpers.inImage('aquasec/trivy:0.72.0', '-v /var/run/docker.sock:/var/run/docker.sock') {
                sh(returnStatus: true, script: """#!/bin/sh
                    # Default 5m aborts with "context deadline exceeded" on
                    # this image's node_modules tree.
                    trivy image "${image}:\${IMAGE_TAG}" \
                        --image-src docker \
                        --scanners vuln \
                        --severity HIGH,CRITICAL \
                        --ignore-unfixed \
                        --ignorefile .trivyignore-image \
                        --timeout 30m \
                        --exit-code 1 \
                        --format table \
                        --output .ci-trivy-image.txt
                """)
            }

            if (status != 0) {
                def report = ''
                try { report = readFile('.ci-trivy-image.txt').trim() } catch (ignored) { }
                writeFile file: outputFile, text: report
                    ? "### Trivy: image vulnerabilities\n\n```\n${helpers.truncateOutput(report, 20000)}\n```"
                    : '### Image scan did not complete\n\nTrivy exited non-zero but produced no report. See the build log.'
                error('Image vulnerability scan failed')
            }
        }

        // Two tags: the immutable per-build one the deploy pins, and the
        // moving env tag, added only now so a failed scan never moves it.
        withCredentials([string(credentialsId: 'REGISTRY_TOKEN', variable: 'REGISTRY_TOKEN')]) {
            sh """#!/bin/bash
                set -euo pipefail
                export DOCKER_CONFIG='${dockerCfg}'
                rm -rf "\$DOCKER_CONFIG"; mkdir -p -m 700 "\$DOCKER_CONFIG"
                trap 'rm -rf "\$DOCKER_CONFIG"' EXIT
                echo "\$REGISTRY_TOKEN" | docker login "\$REGISTRY" -u "\$REGISTRY_USERNAME" --password-stdin
                docker tag ${image}:\${IMAGE_TAG} ${image}:\${TAG}
                docker push ${image}:\${IMAGE_TAG}
                docker push ${image}:\${TAG}
            """
        }
    } finally {
        // Shared runner: drop this build's tags (layers stay as build cache).
        sh "rm -rf '${dockerCfg}'; docker image rm ${image}:\${IMAGE_TAG} ${image}:\${TAG} 2>/dev/null || true"
    }
}

return this
