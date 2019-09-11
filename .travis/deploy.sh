#! /bin/bash

# site-deploy does not work due to outdated ssh algorithms in wagoon-git. need to fix later.
# mvn -Prelease deploy site-deploy -s .travis/settings.xml -DskipTests=true -Dinvoker.skip=true -B
mvn -Prelease deploy -s .travis/settings.xml -DskipTests=true -Dinvoker.skip=true -B
