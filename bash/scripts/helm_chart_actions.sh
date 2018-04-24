#!/usr/bin/env bash

set -eo pipefail

export DEIS_CHARTS_BASE_URL="http://charts.teamhephy.info"

# sign-and-package-helm-chart signs and packages the helm chart provided by chart,
# expecting a signing key passphrase in SIGNING_KEY_PASSPHRASE.
sign-and-package-helm-chart() {
  chart="${1}"

  if [ -z "${chart}" ]; then
    echo 'usage: sign-and-package-helm-chart <chart>'
    return 1
  fi

  if [ -z "${SIGNING_KEY_PASSPHRASE}" ]; then
    echo 'SIGNING_KEY_PASSPHRASE must be available in the env to sign a helm chart'
    return 1
  fi

  signing_key="${SIGNING_KEY:-Team Hephy (Helm chart signing key)}"
  keyring="${KEYRING:-${JENKINS_HOME}/.gnupg/secring.gpg}"

  echo "Signing packaged chart '${chart}' with key '${signing_key}' from keyring '${keyring}'..." >&2

  # HACK(vdice): create pseudo-terminal to emulate entering passphrase when prompted
  # Remove once helm supports gpg-agent/automated passphrase entry
  printf '%s\n' "${SIGNING_KEY_PASSPHRASE}" | \
    script -q -c "helm package --sign --key '${signing_key}' --keyring ${keyring} ${chart}" /dev/null &> /dev/null
}

# download-and-init-helm downloads helm based on HELM_VERSION and HELM_OS and
# runs 'helm init -c' using HELM_HOME
download-and-init-helm() {
  export HELM_VERSION="${HELM_VERSION:-canary}"
  export HELM_OS="${HELM_OS:-linux}"
  export HELM_HOME="/home/jenkins/workspace/${JOB_NAME}/${BUILD_NUMBER}"

  wget --quiet https://storage.googleapis.com/kubernetes-helm/helm-"${HELM_VERSION}"-"${HELM_OS}"-amd64.tar.gz \
    && tar -zxvf helm-"${HELM_VERSION}"-"${HELM_OS}"-amd64.tar.gz \
    && export PATH="${HELM_OS}-amd64:${PATH}" \
    && helm init -c
}

# publish-helm-chart publishes the given chart to the chart repo determined
# by the given repo_type, using the commit provided for versioning.
# Will also attempt to sign chart if SIGN_CHART is true OR repo_type is 'staging'
#
# usage: publish-helm-chart <chart name> <repo type> <commit for versioning>
#
publish-helm-chart() {
  local chart="${1}"
  local repo_type="${2}"
  local commit="${3}"

  # variable declarations
  local short_sha
  if [ -n "${commit}" ]; then
    short_sha="${commit:0:7}"
  else
    short_sha="$(git rev-parse --short HEAD)"
  fi

  git_tag="${RELEASE_TAG:-$(git describe --abbrev=0 --tags)}"
  timestamp="${TIMESTAMP:-$(date -u +%Y%m%d%H%M%S)}"
  chart_repo="$(echo "${chart}-${repo_type}" | sed -e 's/-production//g')"

  # chart assembly
  if [ -d "${PWD}"/charts ]; then
    cd "${PWD}"/charts
    download-and-init-helm

    chart_version="${git_tag}"
    # if dev/pr chart, will use incremented patch version (v1.2.3 -> v1.2.4) and add prerelease build info
    incremented_patch_version="$(( ${chart_version: -1} +1))"
    if [ "${chart_repo}" == "${chart}-dev" ]; then
      chart_version="${chart_version%?}${incremented_patch_version}-dev-${timestamp}-sha.${short_sha}"
    elif [ "${chart_repo}" == "${chart}-pr" ]; then
      chart_version="${chart_version%?}${incremented_patch_version}-${timestamp}-sha.${short_sha}"
    fi

    update-chart "${chart}" "${chart_version}" "${chart_repo}"

    if [ "${SIGN_CHART}" == true ]; then
      sign-and-package-helm-chart "${chart}"
      echo "Uploading chart provenance file ${chart}-${chart_version}.tgz.prov to chart repo ${chart_repo}..." >&2
      az storage blob upload -c "${chart_repo}" \
        -n "${chart}-${chart_version}".tgz.prov -f "${chart}-${chart_version}".tgz.prov
    else
      helm package "${chart}"
    fi

    # download index file from wabs container
    az storage blob download -c "${chart_repo}" -n index.yaml -f index.yaml

    # update index file
    helm repo index . --url "${DEIS_CHARTS_BASE_URL}/${chart_repo}" --merge ./index.yaml

    if [ "${repo_type}" == "staging" ]; then
      # set cache-control for chart artifact if staging repo
      echo "Chart repo type is staging; setting --cache-control max_age=0 on the chart artifact to prevent caching."
      az storage blob upload --content-cache-control="max-age=0" -c "${chart_repo}" \
        -n "${chart}-${chart_version}".tgz -f "${chart}-${chart_version}".tgz
    else
      echo "Uploading chart artifact ${chart}-${chart_version}.tgz to chart repo ${chart_repo}..." >&2
      az storage blob upload -c "${chart_repo}" \
        -n "${chart}-${chart_version}".tgz -f "${chart}-${chart_version}".tgz
    fi

    echo "Uploading updated index.yaml and values-${chart_version}.yaml to chart repo ${chart_repo}..." >&2
    az storage blob upload --content-cache-control="max-age=0" -c "${chart_repo}" -n index.yaml -f index.yaml \
      && az storage blob upload -c "${chart_repo}" -n values-"${chart_version}".yaml -f "${chart}"/values.yaml
  else
    echo "No 'charts' directory found at project level; nothing to publish."
  fi
}

# update-chart updates a given chart, using the provided chart, chart_version
# and chart_repo values.  If the chart is 'workflow', a space-delimited list of
# component charts is expected to be present in a COMPONENT_CHART_AND_REPOS env var
#
# usage: update-chart <chart name> <chart version> <chart repo name>
#
update-chart() {
  local chart="${1}"
  local chart_version="${2}"
  local chart_repo="${3}"

  # update the chart version
  perl -i -0pe "s/<Will be populated by the ci before publishing the chart>/${chart_version}/g" "${chart}"/Chart.yaml

  if [ "${chart}" != 'workflow' ]; then
    ## make component chart updates
    if [ "${chart_repo}" == "${chart}" ]; then
      ## chart repo is production repo; update values appropriately
      # update all org values to "teamhephy"
      perl -i -0pe 's/"hephyci"/"teamhephy"/g' "${chart}"/values.yaml
      # update the image pull policy to "IfNotPresent"
      perl -i -0pe 's/"Always"/"IfNotPresent"/g' "${chart}"/values.yaml
      # update the dockerTag value to chart_version
      perl -i -0pe "s/canary/${chart_version}/g" "${chart}"/values.yaml
    fi
    # send chart version on for use in downstream jobs
    echo "COMPONENT_CHART_VERSION=${chart_version}" >> "${ENV_FILE_PATH:-/dev/null}"

    # fetch all dependency charts based on requirements, if any
    if [ -f "${chart}/requirements.yaml" ]; then
      echo "fetching all dependency charts for ${chart} per requirements file..." 1>&2
      # use 'build' instead of 'update' as it will respect requirements.lock if exists
      # and act identical to 'update' if not.
      helm dependency build "${chart}"
    fi
  else
    ## make workflow chart updates
    # update requirements.yaml with correct chart version and chart repo for each component
    for component in ${COMPONENT_CHART_AND_REPOS}; do
      IFS=':' read -r -a chart_and_repo <<< "${component}"
      component_chart="${chart_and_repo[0]}"
      component_repo="${chart_and_repo[1]}"
      latest_tag="$(get-latest-component-release "${component_repo}")"

      component_chart_version="${latest_tag}"
      component_chart_repo="${component_chart}"
      # if COMPONENT_REPO matches this component repo and COMPONENT_CHART_VERSION is non-empty/non-null,
      # this signifies we need to set component chart version to correlate with PR artifact
      # shellcheck disable=SC2153
      if [ "${COMPONENT_REPO}" == "${component_repo}" ] && [ -n "${COMPONENT_CHART_VERSION}" ] && [ "${chart_repo}" == "${chart}-pr" ]; then
        component_chart_version="${COMPONENT_CHART_VERSION}"
        component_chart_repo="${component_chart}-pr"
      elif [ "${chart_version}" != "${git_tag}" ]; then
        # workflow chart version has build data; is -dev variant.
        # assign component version/repo accordingly
        component_chart_version=">=${latest_tag}-dev"
        component_chart_repo="${component_chart}-dev"
      fi

      # update chart version and chart repo in workflow/requirements.yaml
      perl -i -0pe 's/<'"${component_chart}"'-tag>/"'"${component_chart_version}"'"/g' "${chart}"/requirements.yaml
      perl -i -0pe 's='"${DEIS_CHARTS_BASE_URL}/${component_chart}\n"'='"${DEIS_CHARTS_BASE_URL}/${component_chart_repo}\n"'=g' "${chart}"/requirements.yaml
      helm repo add "${component_chart_repo}" "${DEIS_CHARTS_BASE_URL}/${component_chart_repo}"

      # DEBUG
      helm search "${component_chart_repo}"/"${component_chart}" -l
    done

    # DEBUG
    helm repo list

    # display resulting requirements.yaml to verify component chart versions
    cat "${chart}"/requirements.yaml

    # fetch all dependent charts based on above
    helm dependency update "${chart}"

    if [ "${chart_repo}" == "${chart}-staging" ]; then
      # 'stage' signed chart on production sans index.file (so chart may not be used
      # but is ready to copy to production repo with index.file if approved)
      sign-and-package-helm-chart "${chart}"

      echo "Uploading ${chart}-${chart_version}.tgz(.prov) and values-${chart_version}.yaml files to production chart repo ${chart}, sans index.yaml..."
      az storage blob upload -c "${chart}" -n "${chart}-${chart_version}".tgz -f "${chart}-${chart_version}".tgz \
        && az storage blob upload -c "${chart}" -n "${chart}-${chart_version}".tgz.prov -f "${chart}-${chart_version}".tgz.prov \
        && az storage blob upload -c "${chart}" -n values-"${chart_version}".yaml -f "${chart}"/values.yaml
    fi

    # if chart repo name does not match chart (i.e. workflow(-dev/-pr/-staging) != workflow), consider it non-production
    if [ "${chart_repo}" != "${chart}" ]; then
      # modify workflow-manager/doctor urls in values.yaml to point to staging
      perl -i -0pe "s/versions.teamhephy/versions-staging.teamhephy/g" "${chart}"/values.yaml
      perl -i -0pe "s/doctor.teamhephy/doctor-staging.teamhephy/g" "${chart}"/values.yaml
    fi

    # set WORKFLOW_TAG for downstream e2e job to read from
    echo "WORKFLOW_TAG=${chart_version}" >> "${ENV_FILE_PATH:-/dev/null}"
  fi
}
