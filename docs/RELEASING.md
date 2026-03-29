# Releasing `mill-bun-plugin`

This document describes how to publish `mill-bun-plugin` to Maven Central and create a GitHub release.

## Overview

- Releases are triggered by pushing a semver tag like `v0.1.0` or `v0.1.0-RC1`.
- The release workflow injects `PUBLISH_VERSION` from the tag, then runs `millbun.compile`, `millbun.test`, `millbun.integration`, and the Maven Central publish step.
- If the tag is annotated, the tag message is used as the GitHub release body. Otherwise GitHub generates release notes automatically.

## Prerequisites

Before the first public release:

1. Create a Sonatype Central account and verify the `com.tjclp` namespace.
2. Generate a GPG key and publish it to public keyservers.
3. Configure the GitHub Actions secrets listed below.

## Required GitHub Secrets

Configure these in `Settings > Secrets and variables > Actions`:

| Secret | Description |
|--------|-------------|
| `SONATYPE_USERNAME` | Sonatype Central username or user token |
| `SONATYPE_PASSWORD` | Sonatype Central password or user token |
| `PGP_SECRET` | Base64-encoded GPG private key |
| `PGP_PASSPHRASE` | GPG key passphrase |

The workflow maps these secrets to Mill's expected `MILL_*` environment variables.

## Release Prep

Before tagging a release:

1. Make sure `main` is clean and up to date.
2. Update `CHANGELOG.md` with the notes for the release you are cutting.
3. Sweep checked-in version references from `X.Y.Z-SNAPSHOT` to `X.Y.Z`.

Update these locations before cutting the release tag:

- `build.mill`
- `README.md`
- `example-typescript/build.mill`
- `example-scalajs/build.mill`
- `examples/build.mill`
- `millbun/integration/resources/**/build.mill`

After the release is published, bump `main` to the next snapshot version when new development starts.

## Local Verification

Run the same checks the release workflow relies on:

```bash
./mill --no-server millbun.compile
./mill --no-server millbun.test
./mill --no-server millbun.integration
./mill --no-server millbun.publishLocal
```

If you switch `PUBLISH_VERSION` values in the same checkout, clear the cached publish metadata first:

```bash
./mill --no-server clean millbun.publishVersion millbun.publishArtifacts
```

Verify there are no remaining snapshot references in release-facing files:

```bash
rg -n "SNAPSHOT" README.md build.mill examples example-typescript example-scalajs millbun/integration/resources
```

## Create The Tag

Use an annotated tag so the tag message becomes the GitHub release body.

```bash
# Replace X.Y.Z with the release version.
git tag -a "vX.Y.Z" -m "$(cat <<'EOF'
<paste the release notes here, usually copied from CHANGELOG.md>
EOF
)"

git push origin main
git push origin "vX.Y.Z"
```

If you prefer GitHub-generated notes, you can still push the tag without a detailed body, but annotated tags produce better release notes.

## Workflow Behavior

The release workflow:

1. Validates the tag as semver.
2. Sets `PUBLISH_VERSION` from the tag.
3. Runs compile, unit test, and integration test targets.
4. Verifies the publish metadata and artifacts.
5. Publishes the release to Maven Central.
6. Creates a GitHub release for the tag.

## Verify Publication

After the workflow succeeds:

1. Check the GitHub Actions run for the `Release` workflow.
2. Check the GitHub Releases page for the new tag.
3. Verify the artifact on Maven Central after the usual sync delay.

Expected artifact:

- `com.tjclp:mill-bun_mill1_3:X.Y.Z`

## GPG Setup

Example one-time setup:

```bash
gpg --full-generate-key
gpg --list-secret-keys --keyid-format LONG
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
gpg --export-secret-key -a <KEY_ID> | base64
```

## Troubleshooting

### Publish fails with signing errors

- Re-check `PGP_SECRET` and `PGP_PASSPHRASE`.
- Confirm the public key is visible on keyservers.

### Publish fails with authentication errors

- Re-check `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`.
- Confirm the `com.tjclp` namespace is verified in Sonatype Central.

### The tag builds the wrong version

- Confirm the workflow extracted the version from `refs/tags/vX.Y.Z`.
- Confirm the release-prep commit updated checked-in version references before the tag was created.
