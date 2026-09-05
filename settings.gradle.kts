rootProject.name = "dora-loop"

// Modules are included only once they contain sources. An included module with
// no source set reports NO-SOURCE and rolls up into BUILD SUCCESSFUL -- a green
// result standing in for a build that never happened. See the emptyModuleCheck
// task in build.gradle.kts, which fails the build if this rule is broken.
// api is included in the same commit as its first source file: emptyModuleCheck
// fails on an included module with no sources, by design.
include("core", "api")
