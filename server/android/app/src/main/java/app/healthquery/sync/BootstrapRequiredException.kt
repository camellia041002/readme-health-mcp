package app.healthquery.sync

/** Incremental work encountered a missing/expired cursor; a separate bootstrap job must run. */
class BootstrapRequiredException : IllegalStateException("Health Connect bootstrap is required")
