#!/usr/bin/env bash

set -eo pipefail

# build-darwin-cli-binary builds the darwin-amd64 workflow-cli binary and is
# intended to be run on a Mac OSX slave
build-darwin-cli-binary() {
  make_target="${1}"

  # set up go/docker env
  export GOPATH="${WORKSPACE}/golang"
  export PATH=$GOPATH/bin:/usr/local/bin:$PATH
  eval "$(docker-machine env default)"

  # clean workspace and bootstrap
  rm -rf vendor/ _dist/
  glide install

  GIT_TAG="$(git describe --abbrev=0 --tags)"
  DIST_DIR="_dist"
  GO_LDFLAGS="-X github.com/hephyci-admin/workflow-cli/version.Version=${GIT_TAG}"
  case "${make_target}" in
    "build-tag")
    go build -a -ldflags "${GO_LDFLAGS}" -o "${DIST_DIR}/${GIT_TAG}/hephy-${GIT_TAG}-darwin-amd64" .
    ;;
    "build-stable")
    go build -a -ldflags "${GO_LDFLAGS}" -o "${DIST_DIR}/hephy-stable-darwin-amd64" .
    ;;
  esac
}
