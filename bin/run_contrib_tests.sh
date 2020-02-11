#!/bin/bash

# Execute contrib/ tests, assumes it is being run within the pipeline
# docker image. For a helper script to execute this in a docker container
# from the host environment see bin/c.

set -e

PATH=$PATH:/usr/local/go/bin
export PATH

cd /root/project/contrib
go test -v ./...
(cd duopull && go test -v)
(cd slackbot-background && go test -v)