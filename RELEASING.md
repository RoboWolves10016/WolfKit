# Releasing

WolfKit is published to Maven Central under the group `io.github.robowolves10016`:

| Artifact | Contents |
|---|---|
| `wolfkit-core` | What robot projects depend on |
| `wolfkit-engine` | Pulled in by `wolfkit-core` |
| `wolfkit-pedro` | Optional Pedro Pathing support |

## One-time setup

1. **Central Portal account.** Sign in at <https://central.sonatype.com> with the GitHub
   account that owns the `RoboWolves10016` organisation.
2. **Namespace.** Under Namespaces, add `io.github.robowolves10016` and verify it. The portal
   asks you to create a temporary public repository with a given name in the organisation.
3. **User token.** Under Account, generate a user token. It has a username and a password.
4. **Signing key.** Create a GPG key and publish its public half, so Central can verify
   signatures:

   ```
   gpg --full-generate-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <key id>
   gpg --armor --export-secret-keys <key id>
   ```

   The last command prints the private key used below. Keep it secret.
5. **Repository secrets.** In the GitHub repository's Settings, under Secrets and variables,
   Actions, add:

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | The user token's username |
   | `MAVEN_CENTRAL_PASSWORD` | The user token's password |
   | `SIGNING_KEY` | The armored private key, including the BEGIN and END lines |
   | `SIGNING_KEY_PASSWORD` | The key's passphrase |

## Each release

1. Set `version` in `gradle.properties` and update the version in `README.md` and `docs/`.
2. Commit and push to `main`. Wait for the Build workflow to pass.
3. On GitHub, create a release with the tag `v<version>`, for example `v0.2.0`.

Publishing the release runs the Publish workflow, which builds, signs, uploads and releases
all three artifacts. They appear on Maven Central within about half an hour. A version cannot
be changed or removed once released.

## Testing a build locally

```
./gradlew publishToMavenLocal
```

installs the artifacts in `~/.m2/repository`. A robot project picks them up by adding
`mavenLocal()` to its repositories.
