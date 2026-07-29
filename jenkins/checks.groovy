// PR quality checks: lint, unit tests, build + typecheck.
//
// All three share one pod so `npm ci` runs once instead of three times. They
// still report as three independent GitHub statuses via helpers.runCheck.

def call() {
    def helpers = load 'jenkins/helpers.groovy'
    def failures = []

    container('node') {
        // node:22-alpine ships npm 10.9.8; package.json requires >=11.11.0.
        // npm 10 fails `npm ci` with "Missing: typescript@5.9.3 from lock file".
        sh '''#!/bin/sh
            set -e
            apk add --no-cache openssl libc6-compat jq git curl
            npm install -g npm@11.11.0
            npm ci --no-audit --no-fund
        '''

        helpers.runCheck(failures, 'Lint') { lint(helpers) }
        helpers.runCheck(failures, 'Unit Tests') { unitTests(helpers) }
        helpers.runCheck(failures, 'Build & Typecheck') { buildAndTypecheck(helpers) }
    }

    helpers.failIfAny(failures)
}

/**
 * Biome, scoped to changed files. A full-tree `biome check .` reports 41
 * errors / 895 warnings of inherited Documenso debt (measured 2026-07-27), so
 * it can't be a blocking gate; scoping to the diff gates new code instead.
 */
def lint(helpers) {
    def outputFile = helpers.outputFileFor('Lint')
    def base = env.CHANGE_TARGET ? "origin/${env.CHANGE_TARGET}" : (env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: 'HEAD~1')

    def status = sh(returnStatus: true, script: """#!/bin/sh
        set -e
        set -o pipefail
        git config --global --add safe.directory "\$(pwd)"
        git rev-parse --verify ${base} >/dev/null 2>&1 || git fetch --no-tags --depth=50 origin ${env.CHANGE_TARGET ?: 'staging'}:${base} || true

        git diff --name-only --diff-filter=ACMR ${base}...HEAD \
            | grep -E '\\.(js|jsx|ts|tsx|json|jsonc|css)\$' > .ci-lint-files.txt || true

        if [ ! -s .ci-lint-files.txt ]; then
            echo "No lintable files changed."
            exit 0
        fi

        echo "Linting \$(wc -l < .ci-lint-files.txt) changed file(s):"
        cat .ci-lint-files.txt
        # -r is load-bearing: with empty input plain xargs would run biome with
        # no paths, linting the whole tree. busybox xargs has no -a.
        xargs -r npx biome check --max-diagnostics=100 < .ci-lint-files.txt 2>&1 | tee .ci-lint-raw.txt
    """)

    if (status != 0) {
        def raw = ''
        try { raw = readFile('.ci-lint-raw.txt').trim() } catch (ignored) { }
        writeFile file: outputFile,
                  text: "### Biome found issues in changed files\n\n```\n${helpers.truncateOutput(raw, 20000)}\n```"
        error('Lint failed')
    }
}

/** Vitest via the turbo `test` task (added in turbo.json alongside this pipeline). */
def unitTests(helpers) {
    def outputFile = helpers.outputFileFor('Unit Tests')

    // pipefail, not PIPESTATUS — busybox ash doesn't set PIPESTATUS.
    def status = sh(returnStatus: true, script: '''#!/bin/sh
        set -o pipefail
        npx turbo run test --output-logs=new-only 2>&1 | tee .ci-test-raw.txt
    ''')

    if (status != 0) {
        def raw = ''
        try { raw = readFile('.ci-test-raw.txt').trim() } catch (ignored) { }
        writeFile file: outputFile,
                  text: "### Unit tests failed\n\n```\n${helpers.truncateOutput(raw, 20000)}\n```"
        error('Unit tests failed')
    }
}

/**
 * apps/remix `build` runs `react-router typegen && tsc` before bundling, so
 * this is both the compile and type gate. Filtered to @documenso/remix: docs
 * and openpage-api don't ship, and skipping them halves the build (5m45 vs 10m52).
 */
def buildAndTypecheck(helpers) {
    def outputFile = helpers.outputFileFor('Build & Typecheck')

    def status = sh(returnStatus: true, script: '''#!/bin/sh
        set -o pipefail
        npm run translate:compile
        # tsc OOMs on this monorepo at the default heap (exit 134).
        export NODE_OPTIONS="--max-old-space-size=8192"
        npx turbo run build --filter=@documenso/remix... 2>&1 | tee .ci-build-raw.txt
    ''')

    if (status != 0) {
        def raw = ''
        try { raw = readFile('.ci-build-raw.txt').trim() } catch (ignored) { }
        writeFile file: outputFile,
                  text: "### Build / typecheck failed\n\n```\n${helpers.truncateOutput(raw, 20000)}\n```"
        error('Build failed')
    }
}

return this
