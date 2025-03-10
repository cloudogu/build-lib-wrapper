#!groovy sharedBuildPipeline.groovy
import com.cloudogu.ces.cesbuildlib.*
import com.cloudogu.ces.dogubuildlib.*

/**
 * Custom pipeline that encapsulates common Dogu pipeline steps.
 * 
 * Expected config keys (with defaults in parentheses):
 * - preBuildAgent (default: 'docker')
 * - buildAgent (default: 'vagrant')
 * - doguName (required)
 * - doguDirectory (default: '/dogu')
 * - namespace (default: 'official')
 * - shellScripts (optional List<String>) for additional shellCheck in Lint stage
 * - checkMarkdown (default: true)
 * - runShellTests (default: false)
 * - runIntegrationTests (default: false)
 * - cypressImage (default: "cypress/included:13.15.2")
 * - upgradeCypressImage (default: "cypress/included:13.2.0")
 * - dependencies (optional List<String>) – names of dependencies to wait for
 * 
 * Additionally, the pipeline expects common parameters to be defined in the job:
 *   TestDoguUpgrade (boolean), OldDoguVersionForUpgradeTest (string),
 *   EnableVideoRecording (boolean), EnableScreenshotRecording (boolean),
 *   TrivySeverityLevels, TrivyStrategy, etc.
 */
def call(Map config) {
    // Use default node labels if not provided.
    def doguName            = config.doguName
    def backendUser         = config.backendUser
    def gitUserName         = config.gitUser
    def committerEmail      = config.committerEmail
    def gcloudCredentials   = config.gcloudCredentials
    def sshCredentials      = config.sshCredentials
    def preBuildAgent       = config.preBuildAgent ? config.preBuildAgent : 'docker'
    def buildAgent          = config.buildAgent ? config.buildAgent : 'vagrant'
    def doguDir             = config.doguDirectory ? config.doguDirectory : "/dogu"
    def namespace           = config.namespace ? config.namespace : "official"
    def waitForDepTime      = config.waitForDepTime ? config.waitForDepTime : 15 // Minutes
    def cypressImage        = config.cypressImage ? config.cypressImage : "cypress/included:13.15.2"
    def upgradeCypressImage = config.upgradeCypressImage ? config.upgradeCypressImage : "cypress/included:13.2.0"
    def shellScripts        = config.shellScripts ? config.shellScripts : '' // single string to paths delimited by whitespace
    def dependedDogus       = config.dependencies ? config.dependencies : ''
    def markdownVersion     = config.markdownVersion ? config.markdownVersion : "3.12.2" 
    def updateSubmodules    = config.updateSubmodules ? config.updateSubmodules : false 
    def runIntegrationTests = config.runIntegrationTests ? config.runIntegrationTests : true

    // PRE-BUILD STEPS (e.g. Checkout, Lint, Markdown, Shell tests) on preBuildAgent.
    node(preBuildAgent) {
        timestamps {
            // Checkout code (and update submodules if requested)
            stage('Checkout') {
                checkout scm
                if (config.updateSubmodules) {
                    sh 'git submodule update --init'
                }
            }
            stage('Lint') {
                // Lint the Dockerfile
                lintDockerfile()
            }
            if ( (config.checkMarkdown == null) || config.checkMarkdown ) {
                stage('Check Markdown Links') {
                    def mdVersion = markdownVersion
                    Markdown markdown = new Markdown(this, mdVersion)
                    markdown.check()
                }
            }
            if (shellScripts) {
                stage('Shellcheck') {
                    shellCheck(shellScripts)
                }
            }

            if (config.runShellTests) {
                stage('Shell Tests') {
                    executeShellTests()
                }
            }
        }
    } // end pre-build node

    // MAIN BUILD STEPS on buildAgent.
    node(buildAgent) {
        // Set common properties (these can be overridden by job configuration)
        timestamps {
            properties([
                buildDiscarder(logRotator(numToKeepStr: '10')),
                disableConcurrentBuilds(),
                parameters([
                        booleanParam(defaultValue: false, description: 'Test dogu upgrade from latest release or optionally from defined version below', name: 'TestDoguUpgrade'),
                        booleanParam(defaultValue: true, description: 'Enables cypress to record video of the integration tests.', name: 'EnableVideoRecording'),
                        booleanParam(defaultValue: true, description: 'Enables cypress to take screenshots of failing integration tests.', name: 'EnableScreenshotRecording'),
                        string(defaultValue: '', description: 'Old Dogu version for the upgrade test (optional; e.g. 4.1.0-3)', name: 'OldDoguVersionForUpgradeTest'),
                        choice(name: 'TrivySeverityLevels', choices: [TrivySeverityLevel.CRITICAL, TrivySeverityLevel.HIGH_AND_ABOVE, TrivySeverityLevel.MEDIUM_AND_ABOVE, TrivySeverityLevel.ALL], description: 'The levels to scan with trivy', defaultValue: TrivySeverityLevel.CRITICAL),
                        choice(name: 'TrivyStrategy', choices: [TrivyScanStrategy.UNSTABLE, TrivyScanStrategy.FAIL, TrivyScanStrategy.IGNORE], description: 'Define whether the build should be unstable, fail or whether the error should be ignored if any vulnerability was found.', defaultValue: TrivyScanStrategy.UNSTABLE),
                ])
            ])
            
            // Instantiate helper objects from the imported libraries.
            Git git = new Git(this, gitUserName)
            git.committerName = gitUserName
            git.committerEmail = committerEmail
            GitFlow gitflow = new GitFlow(this, git)
            GitHub github = new GitHub(this, git)
            Changelog changelog = new Changelog(this)
            EcoSystem ecoSystem = new EcoSystem(this, gcloudCredentials, sshCredentials)
            def vagrant = new Vagrant(this, gcloudCredentials, sshCredentials)

            try {
                stage('Provision') {
                    // For pre-release branches, adjust namespace.
                    if (gitflow.isPreReleaseBranch()) {
                        sh "make prerelease_namespace"
                    }
                    ecoSystem.provision(doguDir)
                }
                
                stage('Setup') {
                    ecoSystem.loginBackend(backendUser)
                    ecoSystem.setup()
                }
                
                if (dependedDogus) {
                    stage('Wait for dependencies') {
                        timeout(time: waitForDepTime, unit: 'MINUTES') {
                            dependedDogus.each { dep ->
                                ecoSystem.waitForDogu(dep)
                            }
                        }
                    }
                }
                
                stage('Build') {
                    ecoSystem.build(doguDir)
                }
                
                stage('Trivy scan') {
                    ecoSystem.copyDoguImageToJenkinsWorker(doguDir)
                    Trivy trivy = new Trivy(this)
                    trivy.scanDogu(".", params.TrivySeverityLevels, params.TrivyStrategy)
                    trivy.saveFormattedTrivyReport(TrivyScanFormat.TABLE)
                    trivy.saveFormattedTrivyReport(TrivyScanFormat.JSON)
                    trivy.saveFormattedTrivyReport(TrivyScanFormat.HTML)
                }
                
                stage('Verify') {
                    ecoSystem.verify(doguDir)
                }
                
                // Optional Integration Tests using Cypress.
                if (runIntegrationTests) {
                    stage('Integration Tests') {
                        ecoSystem.runCypressIntegrationTests([
                            cypressImage     : cypressImage,
                            enableVideo      : params.EnableVideoRecording,
                            enableScreenshots: params.EnableScreenshotRecording
                        ])
                    }
                }
                
                // Optional Upgrade Dogu test.
                if (params.TestDoguUpgrade) {
                    stage('Upgrade dogu') {
                        ecoSystem.purgeDogu(doguName)
                        if (params.OldDoguVersionForUpgradeTest && params.OldDoguVersionForUpgradeTest != "" && !params.OldDoguVersionForUpgradeTest.contains('v')) {
                            println "Installing user defined version of dogu: ${params.OldDoguVersionForUpgradeTest}"
                            ecoSystem.installDogu("${namespace}/${doguName} ${params.OldDoguVersionForUpgradeTest}")
                        } else {
                            println "Installing latest released version of dogu..."
                            ecoSystem.installDogu("${namespace}/${doguName}")
                        }
                        ecoSystem.startDogu(doguName)
                        ecoSystem.waitForDogu(doguName)
                        ecoSystem.upgradeDogu(ecoSystem)
                        // Wait again for healthy status.
                        ecoSystem.waitForDogu(doguName)
                        
                        // Optionally run integration tests after upgrade.
                        if (runIntegrationTests) {
                            stage('Integration Tests - After Upgrade') {
                                ecoSystem.runCypressIntegrationTests([
                                    cypressImage     : upgradeCypressImage,
                                    enableVideo      : params.EnableVideoRecording,
                                    enableScreenshots: params.EnableScreenshotRecording
                                ])
                            }
                        }
                    }
                }
                
                // Release steps if on a release branch.
                if (gitflow.isReleaseBranch()) {
                    String releaseVersion = git.getSimpleBranchName()
                    stage('Finish Release') {
                        // Optionally, target branch can be provided (default "main")
                        gitflow.finishRelease(releaseVersion, config.releaseTargetBranch ?: "main")
                    }
                    stage('Push Dogu to registry') {
                        ecoSystem.push(doguDir)
                    }
                    stage('Add Github-Release') {
                        github.createReleaseWithChangelog(releaseVersion, changelog, config.releaseTargetBranch ?: "main")
                    }
                } else if (gitflow.isPreReleaseBranch()) {
                    stage('Push Prerelease Dogu to registry') {
                        ecoSystem.pushPreRelease(doguDir)
                    }
                }
            } finally {
                stage('Clean') {
                    ecoSystem.destroy()
                }
            }
        } // end timestamps in build node
    } // end build node
}


// --- Local Utility Functions ---
// Execute shell tests using Bats in a Docker container.
void executeShellTests() {
    def bats_base_image = "bats/bats"
    def bats_custom_image = "cloudogu/bats"
    def bats_tag = "1.2.1"
    def batsImage = docker.build("${bats_custom_image}:${bats_tag}", "--build-arg=BATS_BASE_IMAGE=${bats_base_image} --build-arg=BATS_TAG=${bats_tag} ./batsTests")
    try {
        sh "mkdir -p target"
        sh "mkdir -p testdir"
        batsImage.inside("--entrypoint='' -v ${WORKSPACE}:/workspace -v ${WORKSPACE}/testdir:/usr/share/webapps") {
            sh "make unit-test-shell-ci"
        }
    } finally {
        junit allowEmptyResults: true, testResults: 'target/shell_test_reports/*.xml'
    }
}
