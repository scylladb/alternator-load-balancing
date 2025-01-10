clean:
	mvn clean

verify:
	mvn verify
	mvn javadoc:test-javadoc
	mvn javadoc:test-aggregate
	mvn javadoc:test-jar
	mvn javadoc:test-resource-bundle

fix:
	mvn com.coveo:fmt-maven-plugin:format
	echo y | mvn javadoc:fix
 	echo y | mvn javadoc:test-fix

compile:
	mvn compile

compile-test:
	mvn test-compile
