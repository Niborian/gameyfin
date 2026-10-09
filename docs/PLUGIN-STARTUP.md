# Ordered plugin startup

Refs #61 (image startup/health evidence). PR126 run37914906539 failed during AOT
training before readiness. The first exception was `ArrayIndexOutOfBoundsException`
in `HashMap.valuesToArray`, called by PF4J `getPlugins` while PlatformService's
ApplicationReady listener enumerated extensions. The plugin loader handled the same
event asynchronously and mutated PF4J's registry concurrently. Later closed-context
Flyway/plugin exceptions were shutdown fallout, not the originating failure.

Startup loading and starting now run synchronously at highest ApplicationReady event
precedence. Platform initialization runs afterward. Plugin-state callbacks arriving
before initialization return without enumerating the partial registry; initialization
computes the complete snapshot. Normal post-start plugin-state callbacks remain
asynchronous. Platform calculation takes one extension snapshot per calculation.

This deliberately moves plugin loading into the startup-ready sequence: readiness
waits for successful loading rather than allowing concurrent dependent initialization.
It does not claim general PF4J thread safety for runtime upload/unload or HTTP calls
before health/readiness. It does not change production configuration, source files,
deployment or release tags. Actual-image CI startup remains required in addition to
the regression test that publishes a real Spring ApplicationReady event with async
processing enabled and reverse listener registration order.
