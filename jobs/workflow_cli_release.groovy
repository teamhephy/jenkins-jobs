def workspace = new File(".").getAbsolutePath()
if (!new File("${workspace}/common.groovy").canRead()) { workspace = "${WORKSPACE}"}
evaluate(new File("${workspace}/common.groovy"))

def repoName = 'workflow-cli'
def repo = repos.find{ it.name == repoName }

def gitInfo = [
  repo: "kingdonb/${repoName}",
  creds: defaults.github.credentialsID,
  refspec: '+refs/tags/*:refs/remotes/origin/tags/*',
  branch: '*/tags/*',
]

def downstreamJobs = [
  [
    name: "${repoName}-build-tag",
    target: 'build-tag',
  ],
  [
    name: "${repoName}-build-stable",
    target: 'build-stable',
  ],
]

job("${repoName}-release") {
  description """
    <li>Watches the ${repoName} repo for a git tag push. (It can also be triggered manually, supplying a value for TAG.)</li>
    <li>The commit at HEAD of tag is then used to locate the release candidate image(s).</li>
  """.stripIndent().trim()

  scm {
    git {
      remote {
        github(gitInfo.repo)
        credentials(gitInfo.creds)
        refspec(gitInfo.refspec)
      }
      branch(gitInfo.branch)
    }
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
                  "slack-notify '${repo.slackChannel}' '${buildStatus}'"
              }
            }
          }
        }
      }
    }
  }

  logRotator {
    daysToKeep defaults.daysToKeep
  }

  parameters {
    stringParam('TAG', '', 'Specific tag to release')
  }

  triggers {
    githubPush()
  }

  wrappers {
    buildName('${GIT_BRANCH} ${TAG} #${BUILD_NUMBER}')
    timestamps()
    colorizeOutput 'xterm'
    credentialsBinding {
      string("SLACK_INCOMING_WEBHOOK_URL", "95f29b21-3cd5-44b3-9a7b-c0b8bbf77b5d")
    }
  }

  steps {
    shell new File("${workspace}/bash/scripts/get_latest_tag.sh").text +
      """
        mkdir -p ${defaults.tmpPath}
        tag="\$(get-latest-tag ${repoName})"

        echo TAG="\${tag}" > ${defaults.envFile}
      """.stripIndent().trim()

    downstreamParameterized {
      // For now, only kick off the 'build-tag' variant
      trigger('workflow-cli-build-tag') {
        block {
          buildStepFailure('FAILURE')
          failure('FAILURE')
          unstable('UNSTABLE')
        }
        parameters {
          propertiesFile(defaults.envFile)
        }
      }
    }
  }
}

downstreamJobs.each{ Map thisJob ->
  def bucket = "gs://hephy-cli-release"

  def headers  = "-h 'x-goog-meta-ci-job:\${JOB_NAME}' "
      headers += "-h 'x-goog-meta-ci-number:\${BUILD_NUMBER}' "
      headers += "-h 'x-goog-meta-ci-url:\${BUILD_URL}'"

  def upload_script = "echo \${GCS_KEY_JSON} | base64 -d - > /tmp/key.json "
      upload_script += "&& gcloud auth activate-service-account -q --key-file /tmp/key.json "
      upload_script += "&& gsutil -mq ${headers} cp -a public-read -r _dist/* ${bucket}"

  // default variants
  job(thisJob.name) {
    scm {
      git {
        remote {
          github(gitInfo.repo)
          credentials(gitInfo.creds)
          refspec(gitInfo.refspec)
        }
        branch(gitInfo.branch)
      }
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
                    "slack-notify '${repo.slackChannel}' '${buildStatus}'"
                }
              }
            }
          }
        }
      }
    }

    logRotator {
      daysToKeep defaults.daysToKeep
    }

    parameters {
      stringParam('TAG', '', 'Specific tag to release')
    }

    wrappers {
      buildName("\${TAG} #\${BUILD_NUMBER}")
      timestamps()
      colorizeOutput 'xterm'
      credentialsBinding {
        string("GCSKEY", "GCSKEY")
        string("SLACK_INCOMING_WEBHOOK_URL", "95f29b21-3cd5-44b3-9a7b-c0b8bbf77b5d")
      }
    }

    steps {
      shell """
        #!/usr/bin/env bash

        set -eo pipefail

        git_commit="\$(git checkout "\${TAG}" && git rev-parse HEAD)"
        revision_image=quay.io/kingdonb/workflow-cli-dev:"\${git_commit:0:7}"

        docker run \
          -e GCS_KEY_JSON=\""\${GCSKEY}"\" \
          -e GIT_TAG="\$(git describe --abbrev=0 --tags)" \
          --rm "\${revision_image}" sh -c 'make ${thisJob.target} && ${upload_script}'
      """.stripIndent().trim()

      downstreamParameterized {
        // trigger job for (cgo-enabled) darwin amd64 build/upload
        trigger("${thisJob.name}-darwin-amd64") {
          block {
            buildStepFailure('FAILURE')
            failure('FAILURE')
            unstable('UNSTABLE')
          }
          parameters {
            predefinedProp('TAG', '${TAG}')
            nodeLabel('node', 'darwin')
          }
        }
      }
    }
  }

  // darwin-amd64 variants
  job("${thisJob.name}-darwin-amd64") {
    def workdir = "golang/src/github.com/teamhephy/workflow-cli"

    scm {
      git {
        remote {
          github(gitInfo.repo)
          credentials(gitInfo.creds)
          refspec(gitInfo.refspec)
        }
        branch(gitInfo.branch)
        extensions {
          relativeTargetDirectory(workdir)
        }
      }
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
                    "slack-notify '${repo.slackChannel}' '${buildStatus}'"
                }
              }
            }
          }
        }
      }
    }

    logRotator {
      daysToKeep defaults.daysToKeep
    }

    parameters {
      stringParam('CGO_ENABLED', '1', 'CGO_ENABLED value for darwin build (default is 1/enabled)')
      stringParam('TAG', '', 'Specific tag to release')
      nodeParam('DARWIN_HOST') {
        description('Darwin host to run on')
        defaultNodes(['node5-boulder'])
      }
    }

    wrappers {
      buildName("\${TAG} #\${BUILD_NUMBER}")
      timestamps()
      colorizeOutput 'xterm'
      credentialsBinding {
        string("GITHUB_ACCESS_TOKEN", "GITHUB_ACCESS_TOKEN")
        string("GCSKEY", "GCSKEY")
        string("SLACK_INCOMING_WEBHOOK_URL", "95f29b21-3cd5-44b3-9a7b-c0b8bbf77b5d")
      }
    }

    steps {
      shell new File("${workspace}/bash/scripts/build_darwin_cli_binary.sh").text +
        """
          #!/usr/bin/env bash

          set -eo pipefail

          cd ${workdir}

          git_commit="\$(git checkout "\${TAG}" && git rev-parse HEAD)"
          revision_image=quay.io/kingdonb/workflow-cli-dev:"\${git_commit:0:7}"

          build-darwin-cli-binary ${thisJob.target}

          docker run \
            -e GCS_KEY_JSON=\"\${GCSKEY}\" \
            -v "\${GOPATH}/src/github.com/teamhephy/workflow-cli/_dist":/workdir/_dist \
            -w /workdir \
            --rm "\${revision_image}" sh -c '${upload_script}'
        """.stripIndent().trim()
    }
  }
}
