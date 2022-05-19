# Folklore

_It's a kind of saga._

An implementation of the [saga pattern](https://www.baeldung.com/cs/saga-pattern-microservices) in Clojure.

## Usage

See [`test/walterl/folklore_test.clj`][./test/walterl/folklore_test.clj].

## Ops
Run the project's tests:

    $ clojure -T:build test

Run the project's CI pipeline and build a JAR (this will fail until you edit the tests to pass):

    $ clojure -T:build ci

This will produce an updated `pom.xml` file with synchronized dependencies inside the `META-INF`
directory inside `target/classes` and the JAR in `target`. You can update the version (and SCM tag)
information in generated `pom.xml` by updating `build.clj`.

Install it locally (requires the `ci` task be run first):

    $ clojure -T:build install


Copyright © 2022 Walter

Distributed under the Eclipse Public License version 1.0.
