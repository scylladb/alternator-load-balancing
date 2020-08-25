#!/bin/bash
#
# A simple example based on AWS SDK:
# 1. Creates a table
# 2. Adds an item into the table
# 3. Reads the item
#
# The example classes are modified in order to use an Alternator client
# which uses simple round-robin load balancing.
#
# FIXME: looks like AWS SDK examples do not close the client properly
# and hang instead of ending. That needs to be fixed - it's not caused
# by the updateThread which periodically updates the list of nodes,
# since the code hangs in the original examples as well.

mvn exec:java -Dexec.mainClass=com.scylladb.alternator.CreateTable -Dexec.args=Music\ Artist
mvn exec:java -Dexec.mainClass=com.scylladb.alternator.PutItem -Dexec.args='Music Artist Famous Album Song Awards 10 SongTitle Happy'
mvn exec:java -Dexec.mainClass=com.scylladb.alternator.GetItem -Dexec.args='Music Artist Famous'
