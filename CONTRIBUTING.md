# Contributing to the Alternator Load Balancing

First off, thanks for taking the time to contribute!

## Java package

### Publish a new release to Maven Central

On GitHub, go to the [publish Java package workflow](https://github.com/scylladb/alternator-load-balancing/actions/workflows/publish-java-package.yml) and click “Run workflow”. Enter the release version (e.g. “1.2.3”) and confirm.

After the release, bump the `project.properties.revision` property in the `pom.xml` and add the suffix `-SNAPSHOT`. For instance, after the release of version `1.2.3`, set the revision to `1.2.4-SNAPSHOT`. 
