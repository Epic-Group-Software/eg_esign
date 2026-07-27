// Shared utilities for the eg-esign Jenkins pipeline (Jenkinsfile.eg).
// Usage: def helpers = load 'jenkins/helpers.groovy'
//
// Adapted from Epic-Group-Software/Project-Operations/jenkins/helpers.groovy.
// Deliberately smaller: JSON is built in Groovy via JsonOutput rather than by
// shelling out to python3, because these run in the jnlp sidecar of a
// Kubernetes pod agent, which has curl and git but no python.

import groovy.json.JsonOutput

def getRepoName() {
    def parts = env.JOB_NAME.split('/')
    return parts.length > 1 ? parts[parts.length - 2] : parts[0]
}

// For PRs, Jenkins builds a local merge commit whose SHA does not exist on
// GitHub. STATUS_COMMIT_SHA is the real PR head, resolved in the Init stage.
def getCommitSha() {
    return env.STATUS_COMMIT_SHA ?: env.GIT_COMMIT
}

def withGitHubCredentials(Closure body) {
    withCredentials([usernamePassword(
        credentialsId: 'github-app-davinci',
        usernameVariable: 'GIT_USER',
        passwordVariable: 'GIT_PASS'
    )]) {
        body()
    }
}

// POST a JSON body to the GitHub API. The body goes through a file so it is
// never interpolated into a shell command line — payloads contain arbitrary
// build output, including quotes and newlines.
def githubPost(String path, Map payload) {
    writeFile file: '.ci-gh-payload.json', text: JsonOutput.toJson(payload)
    withGitHubCredentials {
        sh """#!/bin/bash
            curl -sS -X POST -u "\$GIT_USER:\$GIT_PASS" \
                -H "Content-Type: application/json" \
                "https://api.github.com/repos/Epic-Group-Software/${getRepoName()}/${path}" \
                --data-binary @.ci-gh-payload.json > /dev/null
        """
    }
    sh 'rm -f .ci-gh-payload.json || true'
}

def notifyGitHub(String context, String state, String description) {
    def sha = getCommitSha()
    if (!sha) {
        echo "No commit SHA available — skipping GitHub status for '${context}'"
        return
    }
    try {
        githubPost("statuses/${sha}", [
            state      : state,
            context    : context,
            target_url : env.BUILD_URL,
            // GitHub truncates at 140 chars and rejects longer values outright.
            description: description.take(139),
        ])
    } catch (err) {
        echo "WARNING: could not post GitHub status '${context}': ${err.message}"
    }
}

def outputFileFor(String context) {
    return ".ci-output-${context.replaceAll('[^a-zA-Z0-9]', '-')}.md"
}

def postPRComment(String context, String body) {
    if (!env.CHANGE_ID) {
        return
    }
    def full = "## :x: ${context} failed\n\n" +
               truncateOutput(body) +
               "\n\n[Full build log](${env.BUILD_URL}console)"
    try {
        githubPost("issues/${env.CHANGE_ID}/comments", [body: full])
    } catch (err) {
        echo "WARNING: could not post PR comment for '${context}': ${err.message}"
    }
}

def truncateOutput(String output, int maxLen = 60000) {
    if (!output || output.length() <= maxLen) {
        return output
    }
    def headSize = 5000
    def tailSize = maxLen - headSize - 100
    return output.take(headSize) +
           "\n\n... (truncated ${output.length() - headSize - tailSize} characters) ...\n\n" +
           output.drop(output.length() - tailSize)
}

/**
 * Run one check, reporting its own GitHub commit status and PR comment.
 *
 * Unlike Project-Operations — which gets independent statuses by giving every
 * check its own parallel branch and therefore its own agent — the eg-esign
 * checks share a single pod so that `npm ci` runs once instead of three times.
 * Sharing a pod means running sequentially, so a plain throw would leave every
 * later check unreported. Failures are recorded here and re-raised by
 * failIfAny() once all checks have had their turn.
 *
 * Returns true on success, false on failure.
 */
def runCheck(List failures, String context, Closure body) {
    notifyGitHub(context, 'pending', "Running ${context}...")
    try {
        body()
        notifyGitHub(context, 'success', "${context} passed")
        return true
    } catch (err) {
        notifyGitHub(context, 'failure', "${context} failed")
        def output = ''
        try { output = readFile(outputFileFor(context)).trim() } catch (ignored) { }
        postPRComment(context, output ?: 'Check failed. See build log for details.')
        failures.add(context)
        echo "CHECK FAILED: ${context} — ${err.message}"
        return false
    }
}

def failIfAny(List failures) {
    if (failures) {
        error("Failed checks: ${failures.join(', ')}")
    }
}

/** Single-check wrapper for stages that own their agent (e.g. Security Scan). */
def withGitHubStatus(String context, Closure body) {
    notifyGitHub(context, 'pending', "Running ${context}...")
    try {
        body()
        notifyGitHub(context, 'success', "${context} passed")
    } catch (err) {
        notifyGitHub(context, 'failure', "${context} failed")
        def output = ''
        try { output = readFile(outputFileFor(context)).trim() } catch (ignored) { }
        postPRComment(context, output ?: 'Check failed. See build log for details.')
        throw err
    }
}

return this
