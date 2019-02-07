# sbt-scalabench

This sbt plugin allows to benchmark Scala compilation.

## Requirements

* sbt 0.13.13+ or 1.0+

## Install

Add the following lines to `./project/plugins.sbt`. See the section [Using Plugins](https://www.scala-sbt.org/release/docs/Using-Plugins.html) in the sbt website for more information.

```
resolvers += Resolver.url("Triplequote Plugins Releases",
  url("https://repo.triplequote.com/artifactory/sbt-plugins-release/"))(Resolver.ivyStylePatterns)
addSbtPlugin("com.triplequote" % "sbt-scalabench" % "0.1.0") // Check the latest version in the release tags
```

Mind the [Triplequote](http://triplequote.com) resolver is required to resolve the plugin.

## Quick start

All you need to do is entering the sbt shell and execute the `scalabench <#runs>` command, e.g.,

```
$ sbt
[info] ...
> scalabench 10
```

## Documentation

The argument to `scalabench` dictates how many runs to perform. The higher is the number of runs, the more accurate is the final report. Each run compiles both `main` and `test` sources for all dependent and aggregate projects of the currently selected project (when entering the sbt shell, the current project is the build root project - read the sbt documentation about [navigating projects interactively](https://www.scala-sbt.org/1.x/docs/Multi-Project.html#Navigating+projects+interactively) for details).

There are two important gotchas to ensure the reported results are meaningful:

1) All applications eating up CPU (e.g., IDEs, browsers, VMs, and similar) must be closed (if you are on a Unix/OS X machine, use top to check the machine is idle) . Ideally, you may even want to reboot your machine before starting the benchmark.

2) Check that sbt has enough memory to compile your project. If you are unsure that's the case, you can check your project's memory consumption with the help of a profiler.

Note that if you are using a laptop, it's highly recommended to run the benchmark when your machine is plugged to a power supply. Also, check that all energy savings settings are disabled.

If it looks all good, just go ahead and execute the `scalabench` command on your project:

```
$ sbt
[info] ...
> scalabench 10
...
[info] Warming up JVM.
```

The benchmark's execution starts by warming up the JVM. Why? Because each time we start `sbt` the JVM is in a “cold” state: the bytecode is not yet JIT-compiled and all the project dependencies are not in the OS filesystem cache yet. As the code runs the JVM “warms up” by JIT-compiling methods that are executed frequently, and build times improve quickly. All versions of the code benefit from a warm JVM. Therefore, for the collected compile times to be stable and meaningful, we must start by warming up the JVM. This boils down to execute a number of compile cycles without recording the compilation time. When the JVM is deemed warm, each of the subprojects is compiled N times (where N is the number of runs passed to the `scalabench` command), and the exact compile time for each subproject is tracked.

Once execution has completed, a report similar to the following is produced:

```
[info] Scala 2.12.8 compilation statistics summary (#runs = 10):
[info] 	coreJS/compile:compile  - median: 22.311s, min: 18.617s, max: 22.458s
[info] 	coreJS/test:compile     - median: 1m27s  , min: 1m25s  , max: 1m32s
[info] 	coreJVM/compile:compile - median: 12.463s, min: 12.078s, max: 13.324s
[info] 	coreJVM/test:compile    - median: 1m23s  , min: 1m18s  , max: 1m23s
[info] 	root/compile:compile    - median: 0.832s , min: 0.788s , max: 0.908s
[info] 	root/test:compile       - median: 0.839s , min: 0.793s , max: 0.886s
[info] The aggregate compile time is 3m26s.
```

The summary provide detailed information about how much time it took to compile each subprojects' `main` and `test` sources. In particular, for each project, the median, minimum and maximum compilation time is reported.

## Fine print

Benchmarking is hard, and automating it is not a panacea. Keep an eye on the numbers and make sure they make sense. Pay particular attention to large variations in measured times.
