// Check execution, not the reporting semantics that this experiment is intended to discover.
for (String runner : ['surefire', 'failsafe']) {
    File attemptFile = new File(basedir, "target/${runner}-attempts.txt")
    if (attemptFile.exists()) {
        List<String> attempts = attemptFile.readLines('UTF-8')
        assert attempts.size() == 2
        assert attempts[0].startsWith('1\t')
        assert attempts[1].startsWith('2\t')
        assert new File(basedir, "target/${runner}-reports/TEST-example.FlakyTest.xml").isFile()
    } else {
        assert basedir.parentFile.name == 'it-no-retry'
    }
    for (String type : ['OuterTest', 'DisabledClassTest', 'DisabledMethodsTest']) {
        assert new File(basedir, "target/${runner}-reports/TEST-example.${type}.xml").isFile()
    }
    // Nested tests must actually run, regardless of which XML file contains their testcase.
    List<File> reports = new File(basedir, "target/${runner}-reports").listFiles()
            .findAll { File file -> file.name.startsWith('TEST-') && file.name.endsWith('.xml') }
    assert reports.any { File file -> file.getText('UTF-8').contains('classname="example.OuterTest$Inner"') }
}
return true
