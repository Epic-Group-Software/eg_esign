// Secret scanning (gitleaks) + dependency CVE scanning (Trivy).
//
// Adapted from Project-Operations/jenkins/security-scan.groovy, but the
// scanners are pod containers rather than `docker run`, so no Docker socket
// is needed. Formatting uses jq — these images have no python3.

def call() {
    def helpers = load 'jenkins/helpers.groovy'
    def outputFile = helpers.outputFileFor('Security Scan')
    def failures = []

    // gitleaks.toml excludes dependency trees and upstream Documenso fixtures.
    def gitleaksStatus = container('gitleaks') {
        sh(returnStatus: true, script: '''#!/bin/sh
            gitleaks detect \
                --source=. \
                --no-git \
                --config=gitleaks.toml \
                --report-format json \
                --report-path=.ci-gitleaks.json \
                --exit-code 1
        ''')
    }

    if (gitleaksStatus != 0) {
        def table = container('trivy') {
            sh(returnStdout: true, script: '''#!/bin/sh
                echo "### Gitleaks: secrets detected ($(jq 'length' .ci-gitleaks.json) found)"
                echo
                echo "| File | Line | Rule |"
                echo "|------|------|------|"
                jq -r '.[:20][] | "| `\\(.File)` | \\(.StartLine) | \\(.RuleID) |"' .ci-gitleaks.json
            ''')
        }
        failures.add(table.trim())
    }

    // node_modules is skipped — Trivy reads package-lock.json for the full
    // graph, and walking the installed tree adds minutes for no extra findings.
    def trivyStatus = container('trivy') {
        sh(returnStatus: true, script: '''#!/bin/sh
            trivy fs . \
                --scanners vuln \
                --severity HIGH,CRITICAL \
                --ignore-unfixed \
                --ignorefile .trivyignore \
                --skip-dirs "**/node_modules" \
                --skip-dirs "**/.next" \
                --timeout 15m \
                --exit-code 1 \
                --format json \
                --output .ci-trivy-fs.json
        ''')
    }

    if (trivyStatus != 0) {
        def table = container('trivy') {
            sh(returnStdout: true, script: '''#!/bin/sh
                COUNT=$(jq '[.Results[]?.Vulnerabilities[]?] | length' .ci-trivy-fs.json)
                # Non-zero with no findings means the scanner itself failed
                # (DB download, rate limit) — don't report a phantom vuln.
                if [ "$COUNT" = "0" ] || [ -z "$COUNT" ]; then
                    echo "### Trivy: scan did not complete"
                    echo
                    echo "Trivy exited non-zero but produced no findings. See the build log."
                    exit 0
                fi
                echo "### Trivy: dependency vulnerabilities ($COUNT found)"
                echo
                echo "| Package | Installed | Fixed in | CVE | Severity |"
                echo "|---------|-----------|----------|-----|----------|"
                jq -r '[.Results[]?.Vulnerabilities[]?][:30][]
                    | "| \\(.PkgName) | \\(.InstalledVersion) | \\(.FixedVersion // "-") | \\(.VulnerabilityID) | \\(.Severity) |"' \
                    .ci-trivy-fs.json
            ''')
        }
        failures.add(table.trim())
    }

    sh 'rm -f .ci-gitleaks.json .ci-trivy-fs.json || true'

    if (failures) {
        writeFile file: outputFile, text: failures.join('\n\n---\n\n')
        error('Security scan failed')
    }
}

return this
