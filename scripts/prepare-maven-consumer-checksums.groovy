// Invoker installs this reactor before running hooks. Extend only its disposable consumer baseline;
// the reviewed third-party baseline remains unchanged and unknown dependencies still fail closed.
def verifier = new File(verificationRoot, 'scripts/maven_verification.py')
def process = new ProcessBuilder(System.getenv('SHARDPILOT_VERIFICATION_PYTHON'), '-B', verifier.absolutePath,
        'consumers', '--repository', verificationRepository, '--version', verificationVersion)
        .inheritIO().start()
assert process.waitFor() == 0 : 'Cannot prepare verified local reactor candidates'
