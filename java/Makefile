mvn = mvn

ifdef IS_CICD
    mvn = mvn --no-transfer-progress
endif


clean:
	$mvn clean

verify:
	${mvn} verify
	${mvn} javadoc:test-javadoc javadoc:test-aggregate javadoc:test-aggregate-jar javadoc:test-jar javadoc:test-resource-bundle
	${mvn} javadoc:jar javadoc:aggregate javadoc:aggregate-jar javadoc:resource-bundle

fix:
	${mvn} com.coveo:fmt-maven-plugin:format
	echo y | ${mvn} javadoc:fix
 	echo y | ${mvn} javadoc:test-fix

compile:
	${mvn} compile

compile-test:
	${mvn} test-compile
