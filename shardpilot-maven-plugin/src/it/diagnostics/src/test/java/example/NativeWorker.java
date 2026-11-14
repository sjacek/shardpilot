package example;

/** Synthetic command has no framework dependency and preserves its requested native exit code. */
public final class NativeWorker {
    private NativeWorker() { }
    /** Exits without framework handling so the supervisor observes the operating system result.
     * @param args one controlled exit code
     */
    public static void main(String[] args) { System.exit(Integer.parseInt(args[0])); }
}
