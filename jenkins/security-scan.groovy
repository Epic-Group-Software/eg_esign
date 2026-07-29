// Secret scanning (gitleaks) + dependency CVE scanning (Trivy).
//
// Adapted from Project-Operations/jenkins/security-scan.groovy, but the
// scanners are pod containers rather than `docker run`, so no Docker socket is
// needed. Both images are minimal — no bash, no jq, no python — so reports use
// each tool's own output rather than hand-built markdown.

def call() {
    def helpers = load 'jenkins/helpers.groovy'
    def outputFile = helpers.outputFileFor('Security Scan')
    def failures = []

    // gitleaks.toml extends the default ruleset and excludes dependency trees
    // and upstream Documenso fixtures.
    def gitleaksStatus = container('gitleaks') {
        // No pipe here: piping to tee would report tee's exit status and
        // silently swallow the "leaks found" signal.
        sh(returnStatus: true, script: '''#!/bin/sh
            gitleaks detect \
                --source=. \
                --no-git \
                --config=gitleaks.toml \
                --report-format csv \
                --report-path=.ci-gitleaks.csv \
                --exit-code 1
        ''')
    }

    if (gitleaksStatus != 0) {
        def report = ''
        try { report = readFile('.ci-gitleaks.csv').trim() } catch (ignored) { }
        failures.add("### Gitleaks: secrets detected\n\n```\n${helpers.truncateOutput(report ?: 'See build log.', 20000)}\n```")
    }

    // node_modules is skipped — Trivy reads package-lock.json for the full
    // dependency graph, so walking the installed tree costs minutes for no
    // extra findings.
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
                --format table \
                --output .ci-trivy-fs.txt
        ''')
    }

    if (trivyStatus != 0) {
        def report = ''
        try { report = readFile('.ci-trivy-fs.txt').trim() } catch (ignored) { }
        // Non-zero with an empty report means the scanner itself failed (DB
        // download, rate limit) — say so rather than invent a finding.
        failures.add(report
            ? "### Trivy: dependency vulnerabilities\n\n```\n${helpers.truncateOutput(report, 20000)}\n```"
            : '### Trivy: scan did not complete\n\nTrivy exited non-zero but produced no report. See the build log.')
    }

    sh 'rm -f .ci-gitleaks.csv .ci-gitleaks.log .ci-trivy-fs.txt || true'

    if (failures) {
        writeFile file: outputFile, text: failures.join('\n\n---\n\n')
        error('Security scan failed')
    }
}

return this
