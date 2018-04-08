def workspace = new File(".").getAbsolutePath()
if (!new File("${workspace}/common.groovy").canRead()) { workspace = "${WORKSPACE}"}
evaluate(new File("${workspace}/repo.groovy"))

defaults = [
  signingNode: ['hephy-signatory'],
  tmpPath: '/tmp/${JOB_NAME}/${BUILD_NUMBER}',
  envFile: '/tmp/${JOB_NAME}/${BUILD_NUMBER}/env.properties',
  daysToKeep: 14,
  testJob: [
    name: 'workflow-chart-e2e',
    timeoutMins: 30,
  ],
  maxBuildsPerNode: 1,
  maxTotalConcurrentBuilds: 3,
  maxWorkflowTestConcurrentBuilds: 5,
  cli: [
    release: 'stable',
  ],
  slack: [
    teamDomain: 'teamhephy',
    channel: '#testing',
    webhookURL: '95f29b21-3cd5-44b3-9a7b-c0b8bbf77b5d',
  ],
  helm: [
    version: 'v2.6.1',
  ],
  github: [
    username: 'teamhephy-admin',
    credentialsID: 'be091a9e-1bcb-4149-91a7-fc0c94d4d553',
    accessTokenCredentialsID: 'f19ff3f0-f660-4fda-80ae-c31c246fda55',
  ],
  azure: [
    storageAccount: '7f71d198-f5d7-4ded-9b2b-58280ce69ef2',
    storageAccountKeyID: '7ba177e3-b6de-4dbc-99f2-4bacf1a31cc0',
  ],
  statusesToNotify: ['SUCCESS', 'FAILURE'],
  "e2eRunner": [ provider: 'google', ],
]

e2eRunnerJob = new File("${workspace}/bash/scripts/run_e2e.sh").text +
  "run-e2e ${defaults.envFile}"

checkForChartChanges = new File("${workspace}/bash/scripts/get_merge_commit_changes.sh").text +
  '''
    changes="$(get-merge-commit-changes "$(git rev-parse --short HEAD)")"
    echo "${changes}" | grep 'charts/'
  '''.stripIndent().trim()
