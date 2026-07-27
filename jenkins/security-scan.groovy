// Secret scanning (gitleaks) + dependency CVE scanning (Trivy) on the source tree.
//
// Adapted from Epic-Group-Software/Project-Operations/jenkins/security-scan.groovy.
// That version shells out to `docker run` on the static `docker` agent; here
// the scanners are containers in the pod spec instead, so no Docker socket is
// needed. Findings are formatted with jq rather than python3 (not in these images).
//
// Usage: def securityScan = load 'jenkins/security-scan.groovy'; securityScan()

def call() {
    def helpers = load 'jenkins/helpers.groovy'
    def outputFile = helpers.outputFileFor('Security Scan')
    def failures = []

    // --- Gitleaks -------------------------------------------------------
    // gitleaks.toml extends the default ruleset and excludes dependency trees
    // plus upstream Documenso fixtures (the public example cert.p12, the docs
    // tree's illustrative API keys). Verified clean on this tree 2026-07-27.
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

    // --- Trivy filesystem ----------------------------------------------
    // node_modules is skipped: Trivy reads package-lock.json for the full
    // dependency graph, so walking the installed tree adds many minutes and
    // no findings. --ignore-unfixed keeps this actionable — we only fail on
    // CVEs that an upgrade can actually resolve.
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
                if [ "$COUNT" = "0" ] || [ -z "$COUNT" ]; then
                    # Non-zero exit with no parsed vulnerabilities means the
                    # scanner itself failed (DB download, rate limit). Say so
                    # rather than reporting a phantom vulnerability.
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
