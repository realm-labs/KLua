# KLua v1 Release Checklist

This checklist prepares a reproducible v1 release but grants no authority to publish, sign, push, or tag. The current repository version remains `0.1.0-SNAPSHOT`. Steps marked "release action" require an explicit user instruction at release time.

## Candidate verification

- Use a clean checkout of the intended commit on JDK 17 with no untracked release inputs.
- Confirm `git status --short` is empty and review `git diff <previous-release>...HEAD` when a previous release exists.
- Run `./gradlew --version` and confirm the daemon JVM and toolchain are JDK 17-compatible.
- Run `./gradlew releaseCandidateCheck`.
- Confirm `klua-tools/build/distributions/klua-<version>.zip` and `.tar` exist and that the installed-launcher smoke task passed.
- Confirm `./gradlew checkKotlinAbi` reports no unreviewed supported-module ABI changes.
- Confirm the accepted JDK 17 performance baseline still names the candidate runtime commit and that no code change after it affects interpreter behavior.
- Review [the conformance matrix](KLua_Conformance_Gaps.md), [release contract](KLua_Release_Contract.md), and [draft release notes](KLua_Release_Notes_1.0.0.md) for stale claims.
- Generate SHA-256 checksums for every file intended for publication and retain them beside the release record.

## Version and tag sequence — release action

1. Change `klua.version` in `gradle.properties` from the snapshot value to `1.0.0`.
2. Replace "draft" and snapshot disclaimers in the release notes only after the candidate commit is final.
3. Run `./gradlew clean releaseCandidateCheck` from a clean checkout.
4. Inspect every generated POM, binary/source JAR, ZIP/TAR distribution, manifest, license entry, and checksum.
5. Commit only the reviewed release metadata with a Conventional Commit message such as `chore(release): prepare 1.0.0`.
6. Create an annotated `v1.0.0` tag on that commit and verify the tag target locally.
7. Publish artifacts, push the commit/tag, create a hosted release, or use signing/repository credentials only when each external action has been explicitly authorized and its destination is known.
8. Verify published checksums, POM dependency scopes, downloadable distributions, and a fresh consumer build before announcing availability.

## Post-release preparation — separate authorized change

- Advance `klua.version` to the next development snapshot.
- Record any discovered migration, security, or compatibility note in the next release plan.
- Keep M22 JVM bytecode generation deferred until it receives its own bounded package.
