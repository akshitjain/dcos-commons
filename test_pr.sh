#!/bin/bash
set -x
random_id=$(cat /dev/urandom | tr -dc 'a-z0-9' | fold -w 10 | head -n 1)
set -e
# Create cluster
cat <<EOF > config.yaml
launch_config_version: 1
deployment_name: dcos-ci-test-infinity-$random_id
template_url: https://s3.amazonaws.com/downloads.mesosphere.io/dcos-enterprise/testing/master/cloudformation/ee.single-master.cloudformation.json
provider: aws
aws_region: us-west-2
key_helper: true
template_parameters:
    AdminLocation: 0.0.0.0/0
    PublicSlaveInstanceCount: 1
    SlaveInstanceCount: 6
ssh_user: core
EOF

# launch our test cluster and let it provision in the background while we build
dcos-launch create
# Push this back to the volume mount so we can delete after the test
cp cluster_info.json /dcos-commons/cluster_info.json

# Build and upload our framework
FRAMEWORK=$1
export UNIVERSE_URL_PATH=/dcos-commons/frameworks/$FRAMEWORK/$FRAMEWORK-universe-url
GOPATH=/foo ./dcos-commons/frameworks/$FRAMEWORK/build.sh aws
if [ ! -f "$UNIVERSE_URL_PATH" ]; then
    echo "Missing universe URL file: $UNIVERSE_URL_PATH"
    exit 1
fi
export STUB_UNIVERSE_URL=$(cat $UNIVERSE_URL_PATH)
rm -f $UNIVERSE_URL_PATH

# Wait for our cluster to finish
dcos-launch wait
mkdir -p ~/.ssh/
cat cluster_info.json | jq -r .ssh_private_key > ~/.ssh/id_rsa
chmod 600 ~/.ssh/id_rsa
CLUSTER_URL=http://`dcos-launch describe | jq -r .masters[0].public_ip`

dcos config set core.dcos_url $CLUSTER_URL
dcos config set core.reporting True
dcos config set core.ssl_verify false
dcos config set core.timeout 5
dcos config show
./dcos-commons/tools/dcos_login.py
set +e
py.test -vv -s -m "sanity" /dcos-commons/frameworks/$FRAMEWORK/tests
dcos-launch delete
