def workspace = new File(".").getAbsolutePath()
if (!new File("${workspace}/common.groovy").canRead()) { workspace = "${WORKSPACE}"}
evaluate(new File("${workspace}/common.groovy"))

repo_name = 'teamhephy.com'
downstreamJobName = 'teamhephy-com-deploy'
slackChannel = '#ci'

job("teamhephy-com-master") {
  description """
    <ol>
      <li>Watches the <a href="https://github.com/teamhephy/${repo_name}">${repo_name}</a> repo for a commit to master</li>
      <li>Kicks off downstream ${downstreamJobName} job to deploy docs</li>
    </ol>
  """.stripIndent().trim()

  scm {
    git {
      remote {
        github("teamhephy/teamhephy.com")
        credentials(defaults.github.credentialsID)
      }
      branch('${DEIS_COM_BRANCH}')
    }
  }

  logRotator {
    daysToKeep defaults.daysToKeep
  }

  triggers {
    cron('H 7 * * *')
    githubPush()
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

    downstream(downstreamJobName, 'UNSTABLE')
  }

  wrappers {
    timestamps()
    colorizeOutput 'xterm'
    credentialsBinding {
      string("SLACK_INCOMING_WEBHOOK_URL", defaults.slack.webhookURL)
    }
    parameters {
      stringParam('DEIS_COM_BRANCH', 'master', 'teamhephy.com branch to build')
    }
  }

  steps {
    shell '''
      #!/usr/bin/env bash

      set -eo pipefail

      make test
    '''.stripIndent().trim()
  }
}

job("teamhephy-com-pr") {
  description """
    <ol>
      <li>Watches the ${repo_name} repo_name for pull requests</li>
    </ol>
  """.stripIndent().trim()

  scm {
    git {
      remote {
        github("teamhephy/${repo_name}")
        credentials(defaults.github.credentialsID)
        refspec('+refs/pull/*:refs/remotes/origin/pr/*')
      }
      branch('${sha1}')
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
    pullRequest {
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
          triggeredStatus("Triggering ${repo_name} tests...")
          startedStatus("Starting ${repo_name} tests...")
          completedStatus("SUCCESS", "Merge with caution! Test job(s) may still be in progress...")
          completedStatus("FAILURE", "Tests returned failure(s).")
          completedStatus("ERROR", "Something went wrong.")
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
    }
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

  steps {
    shell '''
      #!/usr/bin/env bash

      set -eo pipefail

      make test

    '''.stripIndent().trim()
  }
}
