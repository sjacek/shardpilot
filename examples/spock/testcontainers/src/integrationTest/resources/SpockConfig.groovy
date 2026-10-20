// Shard parallelism is provided by isolated CI jobs, not concurrent access to shared specification state.
// Gradle's maxParallelForks does not control Spock's own feature/iteration threads.
runner {
    parallel {
        enabled false
    }
}

// Give every data-table iteration its own report entry without splitting its specification across shards.
// Timing history still records the binary specification name, not the expanded report labels.
unroll {
    unrollByDefault true
}
