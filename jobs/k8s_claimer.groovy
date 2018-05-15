def workspace = new File(".").getAbsolutePath()
if (!new File("${workspace}/common.groovy").canRead()) { workspace = "${WORKSPACE}"}
evaluate(new File("${workspace}/common.groovy"))

slackChannel = '#ops'

job('k8s-claimer-pr') {
  description """
  <p>Run the tests for k8s-claimer</p>
  <p>
    K8s-Claimer serves as a Kubernetes cluster leaser for running Workflow E2E tests in CI.
  </p>
  """.stripIndent().trim()

  scm {
    git {
      remote {
        github("teamhephy/k8s-claimer")
        credentials(defaults.github.credentialsID)
        refspec('+refs/pull/*:refs/remotes/origin/pr/*')
      }
      branch('${sha1}')
    }
  }

  def statusesToNotify = ['FAILURE']
  publishers {
    postBuildScripts {
      onlyIfBuildSucceeds(false)
      steps {
        statusesToNotify.each { buildStatus ->
          conditionalSteps {
            condition {
              status(buildStatus, buildStatus)
              steps {
                shell new File("${workspace}/bash/scripts/slack_notify.sh").text +
                  """
                    slack-notify "${slackChannel}" "${buildStatus}"
                  """.stripIndent().trim()
              }
            }
          }
        }
      }
    }
  }

  concurrentBuild()
  throttleConcurrentBuilds {
    maxPerNode(defaults.maxBuildsPerNode)
    maxTotal(defaults.maxTotalConcurrentBuilds)
  }

  logRotator {
    daysToKeep defaults.daysToKeep
  }

  triggers {
    githubPullRequest {
      admin('teamhephy-admin')
      cron('H/5 * * * *')
      useGitHubHooks()
      triggerPhrase('OK to test')
      orgWhitelist(['teamhephy'])
      allowMembersOfWhitelistedOrgsAsAdmin()
      // this plugin will update PR status no matter what,
      // so until we fix this, here are our default messages:
      extensions {
        commitStatus {
          context('ci/jenkins/pr')
          triggeredStatus("Triggering k8s-claimer build/test pipeline...")
          startedStatus("Starting k8s-claimer build/test pipeline...")
          completedStatus('SUCCESS', "k8s-claimer build/test pipeline SUCCESS!")
          completedStatus('FAILURE', "k8s-claimer build/test pipeline FAILURE.")
          completedStatus('ERROR', 'Something went wrong.')
        }
      }
    }
  }

  parameters {
    stringParam('sha1', 'master', 'Specific Git SHA to test')
  }

  triggers {
    githubPush()
  }

  wrappers {
    timestamps()
    colorizeOutput 'xterm'
    credentialsBinding {
      string("GITHUB_ACCESS_TOKEN", defaults.github.accessTokenCredentialsID)
      string("SLACK_INCOMING_WEBHOOK_URL", defaults.slack.webhookURL)
      string("CODECOV_TOKEN", "38103128-b4b3-4ed9-ac6b-231ef93f0671")
    }
  }

  steps {
    main = [
      new File("${workspace}/bash/scripts/get_actual_commit.sh").text,
      new File("${workspace}/bash/scripts/find_required_commits.sh").text,
    ].join('\n')

    shell """
      #!/usr/bin/env bash
      set -eo pipefail
      make bootstrap test-cover docker-build-cli build || true
    """.stripIndent().trim()
  }  
}

job('k8s-claimer-build-cli') {
  description """
  <p>Builds the k8s-claimer CLI and uploads to Azure Blob storage </p>
  <p>
    K8s-Claimer serves as a Kubernetes cluster leaser for running Workflow E2E tests in CI.
  </p>
  """.stripIndent().trim()

  scm {
    git {
      remote {
        github('teamhephy/k8s-claimer')
        credentials(defaults.github.credentialsID)
      }
      branch('master')
    }
  }

  logRotator {
    daysToKeep defaults.daysToKeep
  }

  publishers {
    def statusesToNotify = ['SUCCESS', 'FAILURE']
    postBuildScripts {
      onlyIfBuildSucceeds(false)
      steps {
        statusesToNotify.each { buildStatus ->
          conditionalSteps {
            condition {
             status(buildStatus, buildStatus)
              steps {
                shell new File("${workspace}/bash/scripts/slack_notify.sh").text +
                  """
                    slack-notify '${slackChannel}' "${buildStatus}"
                  """.stripIndent().trim()
              }
            }
          }
        }
      }
    }
  }


  wrappers {
    timestamps()
    colorizeOutput 'xterm'
    credentialsBinding {
      string("AZURE_STORAGE_ACCOUNT", defaults.azure.storageAccount)
      string("AZURE_STORAGE_KEY", defaults.azure.storageAccountKeyID)
      string("GITHUB_ACCESS_TOKEN", defaults.github.accessTokenCredentialsID)
      string("SLACK_INCOMING_WEBHOOK_URL", defaults.slack.webhookURL)
    }
  }

  steps {
    shell """
      #!/usr/bin/env bash
      set -eo pipefail

      #build the CLI for darwin/linux platforms
      make bootstrap build-cli-cross

      #upload to azure blob storage
      az storage blob upload-batch --content-cache-control="max-age=0" -s _dist -d cli
    """.stripIndent().trim()
  }

  steps {
    downstreamParameterized {
      trigger('k8s-claimer-deploy')
    }
  }
}

job('k8s-claimer-deploy') {
  description """
  <p>Compiles and deploys <a href="https://github.com/teamhephy/k8s-claimer">k8s-claimer</a>
    to the Hephy Workflow staging cluster.
  </p>
  <p>
    K8s-Claimer serves as a Kubernetes cluster leaser for running Workflow E2E tests in CI.
  </p>
  """.stripIndent().trim()

  scm {
    git {
      remote {
        github('teamhephy/k8s-claimer')
        credentials(defaults.github.credentialsID)
      }
      branch('master')
    }
  }

  logRotator {
    daysToKeep defaults.daysToKeep
  }

  publishers {
    def statusesToNotify = ['SUCCESS', 'FAILURE']
    postBuildScripts {
      onlyIfBuildSucceeds(false)
      steps {
        statusesToNotify.each { buildStatus ->
          conditionalSteps {
            condition {
             status(buildStatus, buildStatus)
              steps {
                shell new File("${workspace}/bash/scripts/slack_notify.sh").text +
                  """
                    slack-notify '${slackChannel}' "${buildStatus}"
                  """.stripIndent().trim()
              }
            }
          }
        }
      }
    }
  }

  parameters {
    stringParam('QUAY_USERNAME', 'hephy+jenkins', 'Quay account name')
    stringParam('QUAY_EMAIL', 'kingdon@teamhephy.com', 'Quay email address')
  }

  wrappers {
    timestamps()
    colorizeOutput 'xterm'
    credentialsBinding {
      string("KUBECONFIG_BASE64", "f9d0660d-29d5-4ac5-82cc-b96a1a833344")
      string("K8S_CLAIMER_SSH_KEY", "691ba06a-6c7c-4e94-8fad-7304a2ff0aad")
      string("GOOGLE_CLOUD_ACCOUNT_FILE", "ba7ab317-a820-4e70-9399-a54cf3a59949")
      string("AUTH_TOKEN", "8fbcb93a-0c6e-4594-96c9-e63d08bab61c")
      string("AZURE_SUBSCRIPTION_ID", "1b2376bb-38ed-480b-8bcc-81250ebaa327")
      string("AZURE_CLIENT_ID", "862dbc6f-2fbe-4342-a797-c6433efb6761")
      string("AZURE_CLIENT_SECRET", "3d5c3d60-8648-42f4-8401-7d96e08ca080")
      string("AZURE_TENANT_ID", "528070f3-4799-4c1a-94d6-20a16177487a")
      string("GITHUB_ACCESS_TOKEN", defaults.github.accessTokenCredentialsID)
      string("SLACK_INCOMING_WEBHOOK_URL", defaults.slack.webhookURL)
      string("QUAY_PASSWORD", "40ea7a06-8e1d-4d09-81be-45f0ce07ce27")
    }
  }

  steps {
    shell new File("${workspace}/bash/scripts/helm_chart_actions.sh").text +
      """
      #!/usr/bin/env bash
      set -eo pipefail
      echo \$KUBECONFIG_BASE64 | base64 --decode > kubeconfig

      download-and-init-helm

      export DEV_REGISTRY=quay.io/
      docker login -e="\$QUAY_EMAIL" -u="\$QUAY_USERNAME" -p="\$QUAY_PASSWORD" quay.io

      DOCKER_BUILD_FLAGS="--pull --no-cache" KUBECONFIG=kubeconfig ARGS=config.ssh_key=\${K8S_CLAIMER_SSH_KEY},config.google.account_file=\${GOOGLE_CLOUD_ACCOUNT_FILE},config.google.project_id=hephy-e2e-leasable,config.auth_token=\${AUTH_TOKEN},config.namespace=k8sclaimer,config.azure.subscription_id=\${AZURE_SUBSCRIPTION_ID},config.azure.client_id=\${AZURE_CLIENT_ID},config.azure.client_secret=\${AZURE_CLIENT_SECRET},config.azure.tenant_id=\${AZURE_TENANT_ID} make bootstrap build push upgrade
      """.stripIndent().trim()
  }
}
