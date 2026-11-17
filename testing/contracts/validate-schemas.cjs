const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
// Optional development validation. Supply a separate installation of AJV 8 + ajv-formats;
// neither library becomes a dependency of a ShardPilot distribution or consumer build.
if (!process.argv[2]) throw Error('Supply the directory containing the AJV validator installation');
const requireValidator = createRequire(path.resolve(process.argv[2], 'package.json'));
const Ajv = requireValidator('ajv/dist/2020');
const formats = requireValidator('ajv-formats');
process.chdir(path.resolve(__dirname, '../..'));
const ajv = new Ajv({strict: true, allErrors: true, allowUnionTypes: true});
formats(ajv);
const read = p => JSON.parse(fs.readFileSync(p, 'utf8'));
const base = 'https://sjacek.github.io/shardpilot/schemas/';
for (const name of ['execution-evidence-v1', 'expected-cohort-v1', 'execution-report-v1', 'invocation-ledger-v1', 'execution-selection-v2', 'execution-report-v2']) {
  ajv.addSchema(read(`docs/schemas/${name}.schema.json`));
}
const root = 'docs/examples/execution-evidence-testdata';
let positives = 0, negatives = 0;
for (const [name, schema] of [['expected.json', 'expected-cohort-v1'], ['node-1/evidence.json','execution-evidence-v1'], ['node-2/evidence.json','execution-evidence-v1'], ['report.json','execution-report-v1'],['report-missing-shard.json','execution-report-v1']]) {
  const validator = ajv.getSchema(base+schema+'.schema.json');
  if (!validator(read(`${root}/${name}`))) throw Error(JSON.stringify(validator.errors));
  positives++;
}
const evidence = read(`${root}/node-1/evidence.json`);
const bindingRoot = 'docs/examples/execution-selection-testdata';
for (const [name, schema] of [['selection.json','execution-selection-v2'], ['report.json','execution-report-v2'],
  ['expected.json','expected-cohort-v1'], ['nodes/node-1/evidence.json','execution-evidence-v1']]) {
  const validate = ajv.getSchema(base+schema+'.schema.json');
  if (!validate(read(`${bindingRoot}/${name}`))) throw Error(JSON.stringify(validate.errors));
  positives++;
}
for (const change of [x=>delete x.provenance, x=>x.provenance.extra=true,
  x=>x.provenance.compiledArtifactsSha256='invalid', x=>delete x.provenance.planSha256,
  x=>x.provenance.planSha256['0']='a'.repeat(64), x=>x.schemaVersion=1]) {
  const modified=read(`${bindingRoot}/selection.json`); change(modified);
  if (ajv.getSchema(base+'execution-selection-v2.schema.json')(modified)) throw Error('Invalid selection shape accepted');
  negatives++;
}
const changes = [
  x=>delete x.cohort,
  x=>x.schemaVersion=2,
  x=>x.cohort.revision='short-sha',
  x=>x.cohort.attempt=0,
  x=>x.cohort.runner='failsafe',
  x=>x.cohort.tool='maven',
  x=>x.shard.index=0,
  x=>x.shard.count=1025,
  x=>x.selection.sha256='z'.repeat(64),
  x=>x.process.exitCode=null,
  x=>x.process.finishedAt=null,
  x=>x.process.startedAt='not-a-time',
  x=>x.process.state='success',
  x=>x.observed.skipped=-1,
  x=>x.reports[0].bytes=0,
  x=>x.reports[0].bytes=67108865,
  x=>x.reports[0].sha256='0',
  x=>x.stdout='private output',
  ...['/TEST-a.xml','../TEST-a.xml','reports/../../TEST-a.xml','reports//TEST-a.xml','C:/TEST-a.xml','reports\\TEST-a.xml','reports/./TEST-a.xml'].map(p=>x=>x.reports[0].path=p)
];
for (const change of changes) {
  const modified = structuredClone(evidence); change(modified);
  if (ajv.getSchema(base+'execution-evidence-v1.schema.json')(modified)) throw Error('Invalid shape accepted');
  negatives++;
}
// Schema-valid is not equivalent to passed: acceptance of these shapes is deliberate.
for (const change of [x=>x.process.exitCode=137, x=>x.cohort.attempt=2, x=>x.shard.index=3]) {
 const modified=structuredClone(evidence); change(modified);
 if (!ajv.getSchema(base+'execution-evidence-v1.schema.json')(modified)) throw Error('Semantic rule incorrectly presented as schema validation');
}
const result={status:'PASSED',validator:'AJV '+requireValidator('ajv/package.json').version,draft:'2020-12',schemas:6,validExamples:positives,invalidShapeControls:negatives,semanticBoundaryControls:3,network:'not used'};
console.log(result);
