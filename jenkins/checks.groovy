// PR quality checks: lint, unit tests, build + typecheck.
//
// All three run in ONE pod after a single `npm ci`. Splitting them into
// parallel Jenkins branches (the Project-Operations shape) would mean a fresh
// pod and a fresh 4-minute `npm ci` per check. They still report as three
// independent GitHub commit statuses via helpers.runCheck, and a failure in one
// does not stop the others — see the note on runCheck in helpers.groovy.
//
// Usage: def checks = load 'jenkins/checks.groovy'; checks()

def call() {
    def helpers = load 'jenkins/helpers.groovy'
    def failures = []

    container('node') {
        // node:22-alpine ships npm 10.9.8, but package.json engines requires
        // >=11.11.0. npm 10 mis-resolves the apps/docs typescript ^5.9.3 /
        // root 5.6.2 overlap and fails `npm ci` with
        // "Missing: typescript@5.9.3 from lock file".
        sh '''#!/bin/sh
            set -e
            apk add --no-cache openssl libc6-compat jq git
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
 * Biome, scoped to the files this change actually touches.
 *
 * A full-tree `biome check .` reports 41 errors and 895 warnings on the
 * inherited Documenso tree (measured 2026-07-27), so it can never be a
 * blocking gate as-is. Scoping to the diff gates new code without holding this
 * fork responsible for upstream debt — and the daily upstream-sync merges mean
 * a repo-wide reformat would just be re-fought every night.
 */
def lint(helpers) {
    def outputFile = helpers.outputFileFor('Lint')
    // CHANGE_TARGET on PRs; on staging/main fall back to the previous commit.
    def base = env.CHANGE_TARGET ? "origin/${env.CHANGE_TARGET}" : (env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: 'HEAD~1')

    def status = sh(returnStatus: true, script: """#!/bin/sh
        set -e
        set -o pipefail
        git config --global --add safe.directory "\$(pwd)"
        # The PR's target branch is not fetched by default in a merge-strategy checkout.
        git rev-parse --verify ${base} >/dev/null 2>&1 || git fetch --no-tags --depth=50 origin ${env.CHANGE_TARGET ?: 'staging'}:${base} || true

        # Only files that still exist, and only ones Biome handles.
        git diff --name-only --diff-filter=ACMR ${base}...HEAD \
            | grep -E '\\.(js|jsx|ts|tsx|json|jsonc|css)\$' > .ci-lint-files.txt || true

        if [ ! -s .ci-lint-files.txt ]; then
            echo "No lintable files changed."
            exit 0
        fi

        echo "Linting \$(wc -l < .ci-lint-files.txt) changed file(s):"
        cat .ci-lint-files.txt
        # `xargs -a` is GNU-only; this image's xargs is busybox, so redirect.
        # -r is load-bearing: with empty input, plain xargs still invokes
        # `biome check` with no paths, which lints the WHOLE tree (2156 files,
        # 41 pre-existing errors) and fails every PR that touches no lintable
        # file. The guard above catches that too — this is the second line.
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

/**
 * Vitest across the workspace via the turbo `test` task (added to turbo.json
 * alongside this pipeline — before that, `turbo run test` matched no task and
 * the suites in packages/lib were unreachable from any runner).
 */
def unitTests(helpers) {
    def outputFile = helpers.outputFileFor('Unit Tests')

    // pipefail, not PIPESTATUS: this image's shell is busybox ash, where
    // PIPESTATUS is unset and the pipeline would always report tee's success.
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
 * The real typecheck: apps/remix `build` runs `react-router typegen && tsc`
 * before bundling, so this is both the compile gate and the type gate.
 *
 * Filtered to @documenso/remix. `npm run build` builds apps/docs and
 * apps/openpage-api too, neither of which ships — the Dockerfile only ever
 * builds `--filter=@documenso/remix...`. Dropping them saves several minutes
 * per PR and stops unshipped code from blocking a deploy.
 */
def buildAndTypecheck(helpers) {
    def outputFile = helpers.outputFileFor('Build & Typecheck')

    def status = sh(returnStatus: true, script: '''#!/bin/sh
        set -o pipefail
        npm run translate:compile
        # tsc exhausts Node's default heap on this monorepo and aborts with
        # "JavaScript heap out of memory" (exit 134). The pod allows 10Gi.
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
