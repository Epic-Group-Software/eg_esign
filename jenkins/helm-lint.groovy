// helm lint + render both environment overlays.
//
// Adapted from Project-Operations/jenkins/helm-lint.groovy — that version shells
// out to `docker run alpine/helm` on the static agent; here helm is already in
// the pod's kubectl container.
//
// Rendering each overlay matters as much as linting: `helm lint` alone will not
// catch a values key that only the production overlay references.

def call() {
    def helpers = load 'jenkins/helpers.groovy'
    def outputFile = helpers.outputFileFor('Helm Lint')

    def status = container('kubectl') {
        sh(returnStatus: true, script: '''#!/bin/bash
            set -o pipefail
            {
                helm lint helm/chart -f helm/chart/values.yaml --strict
                for env in staging production; do
                    echo "--- rendering $env ---"
                    helm template eg-esign helm/chart \
                        -f helm/chart/values.yaml \
                        -f "helm/chart/values-$env.yaml" > /dev/null
                done
            } 2>&1 | tee .ci-helm-lint.txt
        ''')
    }

    if (status != 0) {
        def raw = ''
        try { raw = readFile('.ci-helm-lint.txt').trim() } catch (ignored) { }
        writeFile file: outputFile,
                  text: "### Helm lint failed\n\n```\n${helpers.truncateOutput(raw, 20000)}\n```"
        error('Helm lint failed')
    }
}

return this
