def workspace = new File(".").getAbsolutePath()
if (!new File("${workspace}/common.groovy").canRead()) { workspace = "${WORKSPACE}"}
evaluate(new File("${workspace}/common.groovy"))

job('component-promote') {
  description """
    Promotes a component image to the production 'hephyci' registry org on e2e success after a merge to master
  """.stripIndent().trim()


  concurrentBuild()
  throttleConcurrentBuilds {
    maxPerNode(defaults.maxBuildsPerNode)
    maxTotal(defaults.maxTotalConcurrentBuilds)
  }

  logRotator {
    daysToKeep defaults.daysToKeep
  }

  publishers {
    postBuildScripts {
      onlyIfBuildSucceeds(false)
      steps {
        defaults.statusesToNotify.each { buildStatus ->
          conditionalSteps {
            condition {
              status(buildStatus, buildStatus)
              steps {
                shell new File("${workspace}/bash/scripts/slack_notify.sh").text +
                  "slack-notify \${UPSTREAM_SLACK_CHANNEL} '${buildStatus}'"
              }
            }
          }
        }
      }
    }
  }

  parameters {
    stringParam('DOCKER_USERNAME', 'hephyci', 'Docker Hub account name')
    stringParam('DOCKER_EMAIL', 'kingdon@teamhephy.com', 'Docker Hub email address')
    stringParam('QUAY_USERNAME', 'hephyci', 'Quay account name')
    stringParam('QUAY_EMAIL', 'team@teamhephy.com', 'Quay email address')
    stringParam('COMPONENT_NAME', '', 'Component name')
    stringParam('COMPONENT_SHA', '', 'Commit sha used for image tag')
    stringParam('UPSTREAM_SLACK_CHANNEL', defaults.slack.channel, "Upstream Slack channel")
  }

  wrappers {
    buildName('${COMPONENT_NAME} #${BUILD_NUMBER}')
    timestamps()
    colorizeOutput 'xterm'
    credentialsBinding {
      string("GITHUB_ACCESS_TOKEN", defaults.github.accessTokenCredentialsID)
      string("DOCKER_PASSWORD", "171dca49-defe-44a9-8d31-b66f69509133")
      string("QUAY_PASSWORD", "40ea7a06-8e1d-4d09-81be-45f0ce07ce27")
      string("SLACK_INCOMING_WEBHOOK_URL", defaults.slack.webhookURL)
    }
  }

  steps {
    shell new File("${workspace}/bash/scripts/promote_component.sh").text
  }
}
