/*
 * Copyright (c) 2025 by Delphix. All rights reserved.
 */

@Library('pipeline-shared') _

import static com.delphix.jenkins.pipeline.util.Util.getFqdn

import com.delphix.jenkins.pipeline.dcenter.DCenter
import com.delphix.jenkins.pipeline.github.CommitStatusState

import org.apache.maven.artifact.versioning.ComparableVersion
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

currentBuild.result = 'SUCCESS'
def PR_GROUP = 'delphix'

// The default run config and type for the Engine setup
def runConfig = 'ORACLE_19.11.0.0.0_RHEL_8.4_SI'
def runTypeName = 'qualys_security_setup'
def emailAddresses = 'sanjeev.rohila@perforce.com'
def teamName = 'security-qa'
def qaGateGitUrl = 'https://github.com/delphix/dlpx-qa-gate.git'
def qaInfraGitUrl = 'https://github.com/delphix/qa-infra.git'
def slackChannel = params.SLACK ?: '#dlpx-qa-security-regr'

/*
 For simplicity, we will use the platform and dcenter
 hosts fixed with default value, since we are running
 the scan on the AWS only.
 */
def dcPlatform = 'AWS'
def dcCliHost = 'dlpxdc.co'

/*
 * This run should not use an ephemeral agent.
 */
def ephemeralAgent = false


def timeoutDays = params.TIMEOUT_DAYS as Double
def timeoutLengthHours = (timeoutDays * 24) as Integer

def pythonRoot = ""
def vmsToCleanUp = []


def testFunc(groupname) {
    def lastPart = groupname.tokenize('-').last()
    
    if (lastPart ==~ /\d+(\.\d+)*$/) {
        println "✅ Last part is numeric version: ${lastPart}"
    } else {
        println "✅ Last part is string/word: ${lastPart}"
    }
}

println "Testing with version branch"
testFunc("dlpx-external-standard-2025.5.0.0")

println "Testing with word branch"
testFunc("dlpx-external-standard-develop")



// Define qaGateGitBranch variable with fallback
def qaGateGitBranch
if (params.QA_GATE_GIT_BRANCH) {
  qaGateGitBranch = params.QA_GATE_GIT_BRANCH
} else if (params.RELEASE_VERSION) {
  qaGateGitBranch = "dlpx-${params.RELEASE_VERSION}"
} else {
  echo "Neither QA_GATE_GIT_BRANCH nor RELEASE_VERSION parameters were provided. Exiting pipeline."
  currentBuild.result = 'ABORTED'
  return  // This exits the pipeline execution
}


def findSpecificVMs(List<String> vmList) {
  // Find the first VM with "-0." in its name
  def vm0 = vmList.find { it.contains("-0.") }

  // Find the first VM with "-1." in its name
  def vm1 = vmList.find { it.contains("-1.") }

  // Return both values as a list [vm0, vm1]
  return [vm0, vm1]
}

def revisionToRefSpec(revision) {
  if (revision.startsWith("+")) {
    return revision
  } else {
    def refSpec = revision.startsWith('refs/heads') ? revision : "refs/heads/${revision}"
    return "+${refSpec}:${refSpec.replace('refs/heads', 'refs/remotes/origin')}"
  }
}

def installDependencies(String pythonRoot, List<String> components) {
  components.each { component ->
    dir("qa-infra/${component}") {
      sh("${pythonRoot} -m pip install -r requirements.txt")
    }
  }
}

// Method to generate the hostname for the VM based on the prefix, VM name, and DCenter host.
def generateHostname(String prefix, String vmName, String dcenterHost) {
  return dcenterHost.contains('dlpxdc.co') ? "${prefix}-${vmName}.${dcenterHost}" : "${prefix}-${vmName}.${dcenterHost}.delphix.com"
}


def getDcenterGroup(){
  if (params.DCENTER_GROUP) {
    return params.DCENTER_GROUP
  } else if (params.RELEASE_VERSION) {
    return "dlpx-external-standard-${params.RELEASE_VERSION}"
  } else {
    // If neither is set, we need to set the group name to the standard name
    return "dlpx-external-standard-release"
  }
}
def dcenterGroup = getDcenterGroup()


// Function that builds the python command
def buildBranchFinderCmd(String repo, String version) {
    def parts = [
        "from dlpx.version.mapping.cli import _find_branches_by_version_wrapper",
        "import argparse",
        "args=argparse.Namespace(repo='${repo}', version='${version}')",
        "print(_find_branches_by_version_wrapper(args))"
    ]
    // Join Python code with semicolons (no leading spaces!)
    def pyCode = parts.join("; ")

    // Wrap in python3 -c "..."
    return "python3 -c \"${pyCode}\""
}

// Get the Release branch CMD
def getActiveBranchesCmd(String repo) {
    def parts = [
        "import argparse",
        "from dlpx.version.mapping.cli import _find_active_branches_wrapper",
        "args = argparse.Namespace(repo='${repo}')",
        "_find_active_branches_wrapper(args)"   // no join(), just call it
    ]
    def pyCode = parts.join("; ")
    return "python3 -c \"${pyCode}\""
}


def getReleaseBranch(branchData) {
    // split into array
    def branches = branchData.readLines()
    echo "All active branches: ${branches}"

    if (branches.size() > 1) {
        echo "Second active branch: ${branches[1]}"
        return branches[1]
    } else {
        error "No release branch found (less than 2 branches in list)"
    }
}

println "DCenter Host: ${dcCliHost}"
println "DCenter Platform: ${dcPlatform}"

timeout(time: timeoutLengthHours, unit: 'HOURS') {
  blackboxNode(
          dcenterHost: dcCliHost,
          runType: runTypeName,
          extraParams: params.EXTRA_PARAMS,
          timeoutDays: timeoutDays,
          onPremEphemeralAgent: ephemeralAgent,
          cloudPlatform: dcPlatform
          ) { onSharedNode ->
            // When running the Blackbox tests we always want coverage and color
            // output enabled
            def runnerArgs = ""
            if (params.COVERAGE_ENABLED) {
              runnerArgs += " --coverage"
            }
            def qaGateGitRepo = "dlpx-qa-gate"
            releaseVersion = "2025.5.0.0"
            
            stage('Find Branches By Version') {
                venvHelpers.createVenv(name: 'venv', packages: ['dlpx.version.mapping'])
                venvHelpers.withVenv('venv') {
                    print("Calculate the release branch")
                    def cmd = getActiveBranchesCmd(qaGateGitRepo)
                    print "------"
                    print "Command : ${cmd}"
                    print"------"
                    def output = sh(script: cmd, returnStdout: true).trim()
                    
                    print "------"
                    print "Output : ${output}"
                    print"------"
                    def qaGateGitBranchName = getReleaseBranch(output)
                    print "Version:  ${releaseVersion}, Branche : ${qaGateGitBranchName}"
                    print "----------FINISHED------"
                    
                    
                    
                    print "FOR dlpx-qa-gate 2025.5.0.0"
                    def verToBranchCmd = buildBranchFinderCmd("dlpx-qa-gate", "2025.5.0.0")
                    print "CMD ${verToBranchCmd}"
    
                    def verToBranchOutput = sh(script: verToBranchCmd, returnStdout: true).trim()
                    print "Branch ${verToBranchOutput}"
                    
                    print "FOR dlpx-qa-gate 2025.6.0.0"
                    def verToBranchCmd1 = buildBranchFinderCmd("dlpx-qa-gate", "2025.6.0.0")
                    print "CMD ${verToBranchCmd1}"
    
                    def verToBranchOutput1 = sh(script: verToBranchCmd1, returnStdout: true).trim()
                    print "Branch ${verToBranchOutput1}"
                    
                    // def qaGateGitBranchName = sh(
                    //         script: qaGateBranchCommand,
                    //         returnStdout: true
                    //     ).trim()
                    // def branches = sh(
                    //     script: """
                    //         python3 -c "from dlpx.version.mapping.cli import _find_branches_by_version_wrapper; import argparse; args=argparse.Namespace(repo='${repo}', version='${version}'); print(_find_branches_by_version_wrapper(args))"
                    //     """,
                    //     returnStdout: true
                    // ).trim()
            
                    
                }
            }
    
            return

            try {
              stage('Checkout dlpx-qa-gate repository') {
                // We have already set the qaGateGitBranch variable above, so we can use it here
                // with the RELEASE_VERSION if it exists, or default to the value provided in
                // param QA_GATE_GIT_BRANCH
                def qaGateGitRefspec = "+refs/heads/${qaGateGitBranch}:refs/remotes/origin/${qaGateGitBranch}"
                retry(3) {
                  checkout(
                          changelog: false,
                          poll: false,
                          scm: [
                            $class: 'GitSCM',
                            userRemoteConfigs: [
                              [name: 'origin', url: qaGateGitUrl,
                                refspec: qaGateGitRefspec,
                                credentialsId: inferCredentials(qaGateGitUrl)]
                            ],
                            branches: [[name: qaGateGitBranch]],
                            extensions: [
                              [$class: 'CloneOption', shallow: true, timeout: 60, honorRefspec: true],
                              [$class: 'RelativeTargetDirectory', relativeTargetDir: 'dlpx-qa-gate'],
                              [$class: 'WipeWorkspace']
                            ]])
                }
              }

              def pythonEnvName = 'run3'
              dir('dlpx-qa-gate') {
                stage('Install BlackBox dependencies') {
                  sh "./gradlew InstallBlackbox${pythonEnvName} --info --stacktrace --no-daemon"
                }
              }
              pythonRoot = "${pwd()}/dlpx-qa-gate/build/py-envs/${pythonEnvName}/bin/python"
              pythonBin = "${pwd()}/dlpx-qa-gate/build/py-envs/${pythonEnvName}/bin"

              stage('Checkout qa-infra repository and upgrade QARunner if needed') {
                def qiBranch = params.QA_INFRA_GIT_BRANCH ?: 'master'
                def qiRefspec = "+refs/heads/${qiBranch}:refs/remotes/origin/${qiBranch}"
                retry(3) {
                  checkout(
                          changelog: false,
                          poll: false,
                          scm: [
                            $class: 'GitSCM',
                            userRemoteConfigs: [
                              [
                                name: 'origin',
                                url: qaInfraGitUrl,
                                refspec: qiRefspec,
                                credentialsId: inferCredentials(qaInfraGitUrl)
                              ]
                            ],
                            branches: [[name: qiBranch]],
                            extensions: [
                              [$class: 'CloneOption', shallow: true, timeout: 60, honorRefspec: true],
                              [$class: 'RelativeTargetDirectory', relativeTargetDir: 'qa-infra'],
                              [$class: 'WipeWorkspace']
                            ]]
                          )
                }
                // Installing dependencies locally
                dir('qa-infra/qa-runner') { sh("${pythonRoot} -m pip install -r requirements.txt") }
                dir('qa-infra/qa_security') { sh("${pythonRoot} -m pip install -r requirements.txt") }
              }

              stage('Set Up Engines') {
                dir('dlpx-qa-gate') {
                  env.DLPX_DC_CLOUD = dcPlatform
                  def qaRunnerEnvvarList = []
                  if (emailAddresses) {
                    qaRunnerEnvvarList.add("QARUNNER_EMAIL=${emailAddresses}")
                    qaRunnerEnvvarList.add("QARUNNER_EMAIL_REMOTE_PATH=${env.BUILD_URL}BlackBox_20Report_20_28results_29/")
                  }
                  def qaRunnerBaseCommandList = []
                  qaRunnerBaseCommandList.add("${pythonRoot}")
                  qaRunnerBaseCommandList.add("-m")
                  qaRunnerBaseCommandList.add("qarunner")
                  qaRunnerBaseCommandList.add("--preserve-vms=PRESERVE")

                  def qaRunnerCommandList = []
                  qaRunnerCommandList.add("-d ${getFqdn(dcCliHost)}")
                  if (params.EXPIRE) {
                    qaRunnerCommandList.add("--expire ${params.EXPIRE}")
                  }

                  qaRunnerCommandList.add("group")
                  // We need 2 VMs to run the scan
                  qaRunnerCommandList.add("--num-vms=2")

                  if (params.APPLIANCE_HOST) {
                    qaRunnerCommandList.add("-i=${params.APPLIANCE_HOST}")
                  } else {
                    qaRunnerCommandList.add("-g=${dcenterGroup}")
                    if (params.CLONE_ARGS) {
                      qaRunnerCommandList.add("--clone-args='${params.CLONE_ARGS}'")
                    }
                  }
                  qaRunnerCommandList.add("nightly -r='${runTypeName}' --run-config='${runConfig}'")
                  // * Run tests:
                  qaRunnerCommandList.add("local")
                  if (params.EXTRA_PARAMS) {
                    qaRunnerCommandList.add("--run-params='${params.EXTRA_PARAMS} ${runnerArgs}'")
                  } else {
                    qaRunnerCommandList.add("--run-params='${runnerArgs}'")
                  }

                  // Since the commands are run in bash -x the command printed
                  // out to the screen by jenkins is not always the same as
                  // the command being run. Here we print out the command to
                  // make it easier to copy and paste.
                  println "---------- Command ----------"
                  println "QARunner " + qaRunnerCommandList.join(" ")
                  println "-----------------------------"

                  def dcTags = ""
                  if (teamName) {
                    dcTags = "\"TEAM=${teamName}"
                    if (emailAddresses) {
                      dcTags += ",OWNER=${emailAddresses.split(',')[0]}"
                    }
                    dcTags += "\""
                  }
                  def systemdRun
                  if (!onSharedNode) {
                    systemdRun = []
                  } else {
                    systemdRun = blackboxNode.getSystemdRunCmdList()
                  }
                  withEnv(["DLPX_DC_TAGS=${dcTags}"]) {
                    blackBoxHelpers.runQARunnerCommand(
                            (
                            qaRunnerEnvvarList
                            + systemdRun
                            + qaRunnerBaseCommandList
                            + qaRunnerCommandList
                            ).join(" ")
                            )
                  }
                }
              } // Stage 'Set Up Engines' ends

              stage('Run Scan') {
                def allVMSFile = 'dlpx-qa-gate/.previous_runs/clonedvms.txt'

                if (!fileExists(allVMSFile)) {
                  error("VM list file not found: ${allVMSFile}")
                }

                def allVms = blackBoxHelpers.collectVMsToCleanup(allVMSFile)
                def result = findSpecificVMs(allVms)
                def virtVM = result[0] ?: 'NONE'
                def maskVM = result[1] ?: 'NONE'

                if (virtVM == 'NONE' || maskVM == 'NONE') {
                  error("Required VMs not found. VirtVM: ${virtVM}, MaskVM: ${maskVM}")
                }

                echo "Using VirtVM: ${virtVM}, MaskVM: ${maskVM}"

                dir('dlpx-qa-gate') {
                  def scanCMDList = []
                  scanCMDList.add("${pythonRoot} -m qa_security.qualys_scan.runner")
                  scanCMDList.add("--virt-ip=${virtVM}")
                  scanCMDList.add("--mask-ip=${maskVM}")
                  scanCMDList.add("--log-level=${params.LOG_LEVEL}")
                  scanCMDList.add("--auth-scan=${params.AUTH_SCAN}")
                  scanCMDList.add("--build-type=GA")
                  if (params.SCAN_REPORT_ARTIFACT) {
                    scanCMDList.add("--artifact=${params.SCAN_REPORT_ARTIFACT}")
                  }
                  if (params.CREATE_JIRA_ISSUE == false) {
                    scanCMDList.add("--dry-run=true")
                  } else{
                    scanCMDList.add("--dry-run=false")
                  }
                  // We will not invoke the CVE resolution process
                  // during the code complete scan.
                  scanCMDList.add("--resolve-cve=false")


                  def scanCMDString = scanCMDList.join(" ")
                  println "---------- Command ----------"
                  println "scan execution cmd " + scanCMDString
                  println "-----------------------------"

                  echo "Running security scan with command: ${scanCMDString}"
                  withCredentials([sshUserPrivateKey(credentialsId: 'auth-nlb', keyFileVariable: 'IDENTITY_NLB')]) {
                    withEnv(["VIRT_VM=${virtVM}", "MASK_VM=${maskVM}"]) {
                      def scanStatus = sh(script: scanCMDString, returnStatus: true)
                      if (scanStatus == 0) {
                        echo "Scan completed successfully"
                      } else {
                        echo "Scan failed with exit code ${scanStatus}"
                        currentBuild.result = 'FAILURE'
                        error("Security scan failed with status ${scanStatus}")
                      }
                    }
                  }
                }
              } // Stage 'Run Scan' ends
            } catch (FlowInterruptedException fie) {
              currentBuild.result = fie.result.toString()
              throw fie
            } catch (err) {
              currentBuild.result = 'FAILURE'
              throw err
            } finally {
              // Archive scan results from either location
              if (params.SCAN_REPORT_ARTIFACT == true) {
                def scanResultsDir = 'results/scan_results'
                if (fileExists(scanResultsDir)) {
                  archiveArtifacts artifacts: "${scanResultsDir}/**/*", allowEmptyArchive: true
                  println "Scan results archived from ${scanResultsDir}"
                } else {
                  println "No scan results found in ${scanResultsDir}"
                }
              } else {
                println "Skipping scan report artifact archiving as per parameter setting."
              }

              // Artifact the Debug Logs
              sh '''
              if [ -d "/tmp/qa_infra_logs" ]; then
                mkdir -p workspace_logs
                cp /tmp/qa_infra_logs/*.log workspace_logs/ 2>/dev/null || true
              fi
              '''
              if (fileExists('workspace_logs')) {
                println "Archiving logs from workspace_logs"
                archiveArtifacts artifacts: 'workspace_logs/*.log', allowEmptyArchive: true, fingerprint: true
              } else {
                println "No logs found to archive in workspace_logs"
              }

              // Clean up VMs
              if (params.CLEANUP_VMS == true) {
                // Find all vms to cleanup
                def vmsToCleanUpFile = 'dlpx-qa-gate/.previous_runs/clonedvms.txt'
                if (fileExists(vmsToCleanUpFile)) {
                  vmsToCleanUp = blackBoxHelpers.collectVMsToCleanup(vmsToCleanUpFile)
                }
                println "VMs to clean ${vmsToCleanUp.join(" ")}"
                if (vmsToCleanUp) {
                  build(job: '/dcenter-suspend', propagate: false, wait: false, waitForStart: true, parameters: [
                    booleanParam(name: 'UNREGISTER_ONLY', value: currentBuild.result != 'SUCCESS'),
                    string(name: 'DCENTER_HOST', value: dcCliHost),
                    string(name: 'VM_NAMES', value: vmsToCleanUp.join(" "))
                  ])
                }
              } else {
                println "Skipping VM cleanup as per parameter setting."
              }

              // Send Slack notification
              def msg = "${currentBuild.result} : Qualys Scan Job '${env.JOB_BASE_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL})"
              slackSend channel: slackChannel, color: slackHelpers.getSlackColor(currentBuild.result), message: msg
            }
          }
}
