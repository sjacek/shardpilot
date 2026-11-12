# Security Policy

## Reporting a vulnerability

Report suspected vulnerabilities through [GitHub Private Vulnerability Reporting](https://github.com/sjacek/shardpilot/security/advisories/new).
Sign in to GitHub, open this repository's **Security and quality** tab, and choose **Report a vulnerability**.
Do not disclose vulnerabilities, exploit details, confidential source code or credentials in public
issues, pull requests, discussions, build logs or uploaded CI artifacts.

Private reporting is a separate GitHub repository setting; adding this file does not enable it.
If the reporting form is unavailable, open an issue asking only for a private security contact or
for private reporting to be enabled. Do not include the vulnerability, affected private project,
exploit or sensitive logs in that public request. Wait for a private channel before sending details.

Include privately, using synthetic data where possible:

- Affected ShardPilot version or commit, artifact coordinates and build-tool adapter.
- Gradle or Maven, JDK, operating system and relevant test-runner versions.
- Minimal reproduction, expected behavior and actual behavior.
- Potential impact and the privileges, metadata or CI access an attacker needs.
- Sanitized diagnostics and any proposed mitigation; never send live secrets or private keys.

Ordinary configuration questions, performance observations and non-sensitive defects can use
public issues. If a correctness issue could conceal a malicious selection change, expose data or
compromise a build, use the private security channel instead.

## Supported versions

Security maintenance focuses on the latest stable ShardPilot release. Reports are welcome for
all versions, but fixes normally target the latest release; backports to older release lines and
development snapshots are not guaranteed. Check the [release history](CHANGELOG.md) and actual
publication status before upgrading. As of this policy's initial preparation on 12 November 2026,
the reference stable tag is `v0.4.0`.

This is a maintainer-run project. Investigation, fixes and backports are best-effort; no fixed
acknowledgement or remediation deadline is promised. Maintainers and reporters should agree on
disclosure timing privately and, when appropriate, publish an advisory with affected versions,
mitigations and the fixed release. A private report does not imply a confirmed vulnerability.

## Scope and limits

This policy covers the ShardPilot core, Gradle and Maven adapters, metadata/report processing,
project publication workflows and distributed artifacts. ShardPilot executes inside the build
process with that process's permissions; it is not a sandbox for untrusted projects or test code.
Use least-privilege CI jobs and keep production or publication secrets out of ordinary test jobs.

Class-selection verification does not prove that every test method ran or passed. Native test
results and complete CI execution remain independent required gates. A passing compatibility
matrix, a signature, or an AI-generated assessment is not a comprehensive security audit.

See the [security and trust-boundary guide](docs/security.md) and the
[12 November 2026 review follow-up](docs/security-review-2026-11-12.md) for evidence and open work.
