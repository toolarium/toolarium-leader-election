[![License](https://img.shields.io/github/license/toolarium/toolarium-leader-election)](https://github.com/toolarium/toolarium-leader-election/blob/master/LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.toolarium/toolarium-leader-election/0.8.0)](https://search.maven.org/artifact/com.github.toolarium/toolarium-leader-election/0.8.0/jar)
[![javadoc](https://javadoc.io/badge2/com.github.toolarium/toolarium-leader-election/javadoc.svg)](https://javadoc.io/doc/com.github.toolarium/toolarium-leader-election)

# toolarium-leader-election

Implements the leader election api. The implementation supports kubernetes and jgroup binding.


## Built With

* [cb](https://github.com/toolarium/common-build) - The toolarium common build

## Versioning

We use [SemVer](http://semver.org/) for versioning. For the versions available, see the [tags on this repository]. 


## Usage

```java
// initialize
ILeaderElector leaderElector = LeaderElectionFactory.getInstance().getLeaderElection(
    new LeaderElectionInformation("namespace", "name", "test"), new LeaderElectionConfiguration(10 /* duration of 10 seconds */));

if (leaderElector.isLeader()) {
    // get in lead
} else {
    // not in lead
}
```