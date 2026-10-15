# bb-secret-validator

A Bitbucket Data Center pre-receive hook that validates [git-crypt](https://github.com/AGWA/git-crypt) encrypted files. It rejects pushes containing unencrypted files in paths matching a configurable glob pattern.

## How it works

When a developer pushes to a repository with this hook enabled, the plugin:

1. Scans changed files against the configured glob pattern (e.g. `*secrets*`)
2. Checks matching files for the git-crypt magic header (`\x00GITCRYPT\x00`)
3. **Rejects** the push if any matching file is not properly encrypted
4. **Accepts** the push if all matching files are encrypted (or no files match)

## Requirements

- Bitbucket Data Center 10.x
- Java 21

## Installation

Upload the JAR via Bitbucket UPM (`Administration > Manage apps > Upload app`).

For unsigned plugins, set in `$BITBUCKET_HOME/shared/bitbucket.properties`:

```properties
atlassian.upm.signature.check.disabled=true
```

## Configuration

Enable the hook on a repository or project under `Settings > Hooks > Git-Crypt Secret Validator`.

| Setting | Description | Example |
|---------|-------------|---------|
| Secret Path Glob Pattern | Glob pattern to match secret file paths (case-insensitive) | `*secrets*` |
| Summary notification | Short message shown on rejection | `Unencrypted secret file detected` |
| Detailed notification | Detailed message shown on rejection | `The file must be encrypted with git-crypt before pushing.` |

## Building

```bash
mvn clean package -DskipTests
```

The plugin JAR is produced in `target/`.

## License

Apache License 2.0
