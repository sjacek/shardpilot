// Surefire and Failsafe share test cases and comparisons, but run in separate native invocations.
return evaluate(new File(basedir, '../../../../scripts/verify-execution-equivalence.groovy'))
