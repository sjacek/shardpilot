// Shard parallelism is provided by isolated CI jobs, not concurrent access to shared specification state.
runner {
    parallel {
        enabled false
    }
}

// Give every data-table iteration its own report entry without splitting its specification across shards.
unroll {
    unrollByDefault true
}
