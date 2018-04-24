def workspace = new File(".").getAbsolutePath()
if (!new File("${workspace}/common.groovy").canRead()) { workspace = "${WORKSPACE}"}
evaluate(new File("${workspace}/common.groovy"))

def name = "storagetype_e2e"

def bucketNames = [
  builder: "ci-storage-backend-builder-\${BUILD_NUMBER}",
  database: "ci-storage-backend-database-\${BUILD_NUMBER}",
  registry: "ci-storage-backend-registry-\${BUILD_NUMBER}",
]

job(name) {
  description """
    <p>Nightly Job runs the <a href="https://github.com/teamhephy/workflow-e2e">e2e tests</a> against a <a href="https://github.com/teamhephy/charts/tree/master/workflow-dev">workflow-dev</a> chart configured to GCS by default </p>
  """.stripIndent().trim()

  scm {
    git {
      remote {
        github("teamhephy/e2e-runner")
      }
      branch('master')
    }
  }

  logRotator {
    daysToKeep defaults.daysToKeep
  }

  parameters {
   choiceParam('STORAGE_TYPE', ['gcs', 's3'], "storage backend for helm chart, default is gcs")
   stringParam('HELM_VERSION', defaults.helm.version, 'Version of Helm to download/use')
   stringParam('E2E_RUNNER_IMAGE', 'quay.io/hephyci/e2e-runner:canary', "The e2e-runner image")
   stringParam('E2E_DIR', '/home/jenkins/workspace/$JOB_NAME/$BUILD_NUMBER', "Directory for storing workspace files")
   stringParam('E2E_DIR_LOGS', '${E2E_DIR}/logs', "Directory for storing logs. This directory is mounted into the e2e-runner container")
   stringParam('WORKFLOW_TAG', '', 'Workflow chart version (default: empty, will pull latest from chart repo)')
   stringParam('WORKFLOW_E2E_TAG', '', 'Workflow-E2E chart version (default: empty, will pull latest from chart repo)')
   choiceParam('CHART_REPO_TYPE', ['dev', 'pr', 'staging', 'production'], 'Type of chart repo for fetching workflow charts (default: dev)')
   stringParam('CLOUD_PROVIDER', defaults.e2eRunner.provider)
  }

  triggers {
    cron('@daily')
  }

  publishers {
    archiveJunit('${BUILD_NUMBER}/logs/junit*.xml') {
      retainLongStdout(false)
      allowEmptyResults(true)
    }

    archiveArtifacts {
      pattern('${BUILD_NUMBER}/logs/**')
      onlyIfSuccessful(false)
      fingerprint(false)
      allowEmpty(true)
    }

    postBuildScripts {
      onlyIfBuildSucceeds(false)

      steps {
        // Clean up buckets created during e2e run
        shell new File("${workspace}/bash/scripts/off_cluster_storage.sh").text +
          "cleanup \"${bucketNames.builder} ${bucketNames.database} ${bucketNames.registry}\""
      }

      // Dispatch Slack notification
      def statusesToNotify = ['FAILURE', 'SUCCESS']
      steps {
        statusesToNotify.each { buildStatus ->
          conditionalSteps {
            condition {
             status(buildStatus, buildStatus)
              steps {
                // Dispatch Slack notification
                shell new File("${workspace}/bash/scripts/slack_notify.sh").text +
                  """
                    slack-notify '#testing' ${buildStatus}
                  """.stripIndent().trim()
              }
            }
          }
        }
      }
    }
  }

  wrappers {
   buildName('${STORAGE_TYPE} #${BUILD_NUMBER}')
    timeout {
      absolute(30)
      failBuild()
    }
    timestamps()
    colorizeOutput 'xterm'
    credentialsBinding {
      string("GITHUB_ACCESS_TOKEN", defaults.github.accessTokenCredentialsID)
      // For slack notification
      string("SLACK_INCOMING_WEBHOOK_URL", defaults.slack.webhookURL)
      // gcloud/gsutil
      string("GCS_KEY_JSON", "71dd890b-e4ce-4483-97ca-a7c286c2381c")
      // Workflow consumes:
      string("AWS_SECRET_KEY","033f76e3-21b8-4bae-a7b3-7f43e07138d3")
      string("AWS_ACCESS_KEY","02a5c84e-3248-4776-acc5-6b6a1fb24dfc")
      // aws cli consumes:
      string("AWS_SECRET_ACCESS_KEY","033f76e3-21b8-4bae-a7b3-7f43e07138d3")
      string("AWS_ACCESS_KEY_ID","02a5c84e-3248-4776-acc5-6b6a1fb24dfc")
      // k8s-claimer auth
      string("AUTH_TOKEN", "a62d7fe9-5b74-47e3-9aa5-2458ba32da52")
    }
  }

  steps {
    shell """
      #!/usr/bin/env bash

      set -eo pipefail

      mkdir -p ${defaults.tmpPath}

      { echo REGISTRY_BUCKET="${bucketNames.registry}"; \
        echo BUILDER_BUCKET="${bucketNames.builder}"; \
        echo DATABASE_BUCKET="${bucketNames.database}"; } >> ${defaults.envFile}
    """.stripIndent().trim()

    shell e2eRunnerJob
  }
}
