# Contributing to LocalAI

Thanks for helping to improve LocalAI. This guide is short on purpose: the
project is CI-first, so nobody needs a local Android toolchain to contribute.

## How to submit a change

1. Push a branch (to this repository or your fork).
2. Open a pull request against `main`.
3. Wait for the checks. Three workflows run remotely on every PR:
   - **CI** — debug build plus all unit tests.
   - **Quality** — the detekt gate.
   - **CodeQL** — automated security analysis.
4. Push fixes to the same branch until everything is green.

That is the whole loop. If you happen to have JDK 17 and Android SDK 34
installed you can also build locally (see [BUILD.md](BUILD.md)), but it is
never required.

## Detekt gate

New detekt findings fail the Quality workflow. Fix the code. Extend
[config/detekt/baseline.xml](config/detekt/baseline.xml) only when a finding
is a justified false positive, and explain why in the PR. Do not weaken
`config/detekt/detekt.yml` without discussing it first.

## Architecture contracts

The module boundaries between `:app`, `:core`, and `:api-server` are frozen in
[docs/CONTRACTS.md](docs/CONTRACTS.md). Read it before touching anything that
crosses a module boundary. If your change requires a contract to change, call
it out in the PR description and update the contracts document in the same PR.

## Releases

Releases are automated end to end:

1. Bump the `VERSION` file (format `MAJOR.MINOR.PATCH`, for example `0.2.0`).
2. Merge to `main`. CI auto-tags the merge commit `v<VERSION>`.
3. The Release workflow builds a signed APK/AAB and publishes a GitHub
   Release with SHA-256 checksums.

Do not tag releases manually.

## Dependencies

Dependencies are managed by [Dependabot](.github/dependabot.yml):

- Patch and minor bumps arrive as grouped PRs every week; they are merged
  when CI is green.
- Major version bumps are handled deliberately by maintainers, one at a time,
  with a migration note in the PR description.
- All version coordinates live in the version catalog
  [gradle/libs.versions.toml](gradle/libs.versions.toml). Do not hardcode
  dependency versions in module build files.

## Security

Never commit tokens, API keys, or keystores — CI scans for secrets on every
push and will fail the build. Report vulnerabilities privately following
[SECURITY.md](SECURITY.md).

## Commit messages

Use Conventional Commits: `feat`, `fix`, `chore`, `docs`, `build`, `ci`, for
example `feat(server): add request logging toggle`.

## Questions and bugs

Use the bug report or feature request templates to open an issue. For
anything else, start a conversation in
[GitHub Discussions](https://github.com/SecretArrow/LocalAI/discussions).
