#! /bin/bash

mvn -Prelease deploy site-deploy -s .travis/settings.xml -DskipTests=true -Dinvoker.skip=true -B
