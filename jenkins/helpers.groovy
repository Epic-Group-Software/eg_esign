// GitHub commit-status and PR-comment helpers for Jenkinsfile.eg.
// Usage: def helpers = load 'jenkins/helpers.groovy'
//
// Adapted from Project-Operations/jenkins/helpers.groovy. JSON is built with
// JsonOutput instead of python3 — these run in the jnlp sidecar, which has
// curl but no python.

import groovy.json.JsonOutput

def getRepoName() {
    def parts = env.JOB_NAME.split('/')
    return parts.length > 1 ? parts[parts.length - 2] : parts[0]
}

// STATUS_COMMIT_SHA is the real PR head (see Init stage); GIT_COMMIT on a PR
// is a local merge commit that doesn't exist on GitHub.
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

// Body goes through a file — payloads contain arbitrary build output.
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
        echo "No commit SHA — skipping GitHub status for '${context}'"
        return
    }
    try {
        githubPost("statuses/${sha}", [
            state      : state,
            context    : context,
            target_url : env.BUILD_URL,
            description: description.take(139),  // GitHub rejects longer
        ])
    } catch (err) {
        echo "WARNING: GitHub status '${context}' failed: ${err.message}"
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
        echo "WARNING: PR comment for '${context}' failed: ${err.message}"
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
 * Run one check with its own GitHub status, recording failures instead of
 * throwing so later checks in the same pod still run and report.
 * Re-raised by failIfAny().
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

/** Single-check wrapper for stages that own their agent. */
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
