def workspace = new File(".").getAbsolutePath()
if (!new File("${workspace}/common.groovy").canRead()) { workspace = "${WORKSPACE}"}
evaluate(new File("${workspace}/common.groovy"))

name = 'teamhephy-com-deploy'
slackChannel = '#marketing'

job(name) {
  description """
    <ol>
      <li>Compiles and deploys <a href="https://teamhephy.com">teamhephy.com</a></li>
    </ol>
  """.stripIndent().trim()

  multiscm {
   git {
     remote {
         github('teamhephy/gutenberg')
         credentials(defaults.github.credentialsID)
     }
     extensions {
         relativeTargetDirectory('gutenberg')
     }
     branch('master')
   }
   git {
     remote {
         github('teamhephy/workflow')
         credentials(defaults.github.credentialsID)
     }
     extensions {
         relativeTargetDirectory('workflow')
     }
     branch('master')
     branch('*/tags/*')
   }
   git {
     remote {
         github('teamhephy/teamhephy.com')
         credentials(defaults.github.credentialsID)
     }
     extensions {
         relativeTargetDirectory('teamhephy.com')
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
      string("GITHUB_ACCESS_TOKEN", defaults.github.accessTokenCredentialsID)
      string("SLACK_INCOMING_WEBHOOK_URL", defaults.slack.webhookURL)
      string("QUAY_PASSWORD", "8317a529-10f7-40b5-abd4-a42f242f22f0")
      string("DEIS_URL", "368837f6-a5d1-4754-af84-0645473824a6")
      string("DEIS_USERNAME", "342bc0d5-3c82-4e5c-aa79-8b4df0cc7298")
      string("DEIS_PASSWORD", "201494de-a097-4a60-aaa3-6d16a930dabd")
    }
    parameters {
      stringParam('WORKFLOW_DOCS_SOURCE', '${WORKSPACE}/workflow', 'Relative Workflow source')
      stringParam('DEIS_COM_SOURCE', '${WORKSPACE}/teamhephy.com', 'Relative Teamhephy.com source')
      stringParam('QUAY_USERNAME', 'teamhephy+jenkins', 'Quay account name')
      stringParam('QUAY_EMAIL', 'team+jenkins@teamhephy.com', 'Quay email address')
    }
  }

  steps {
    shell '''
      #!/usr/bin/env bash

      set -eo pipefail

      cd "${WORKSPACE}/gutenberg"

      rm -rf build/

      docker login -e="\$QUAY_EMAIL" -u="\$QUAY_USERNAME" -p="\$QUAY_PASSWORD" quay.io
      make prep build build-image push

      curl -sSL http://teamhephy.info/hephy-cli/install-v2.sh | bash
      export PATH="\$(pwd):\$PATH"

      make deploy
    '''.stripIndent().trim()
  }
}
