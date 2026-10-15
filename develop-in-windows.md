# Developing on Windows (WSL2)

Bitbucket Data Center does not run on Windows. Use WSL2 to build and test.

## Important: Do NOT use /mnt paths

Running Maven or Bitbucket from `/mnt/c/...` or `/mnt/x/...` is extremely slow due to the Windows filesystem bridge (9p/drvfs). Builds will hang or take 10x longer.

**Copy the project to the native Linux filesystem instead:**

```bash
rsync -av --exclude='target' --exclude='.git' \
  /mnt/x/bafl/strive-atlassian-plugins/ ~/bb-build/
```

Then build from `~/bb-build/bb-secret-validator/`.

## Prerequisites in WSL2

- **Java 21** (required for Bitbucket 10)
- **Maven 3.9+**
- **Git 2.42+** (Bitbucket 10 Mesh sidecar requires it; Ubuntu 22.04 ships 2.34 — build from source or use the git-core PPA)

### Building Git from source (if needed)

```bash
sudo apt-get install -y make gcc libz-dev
cd /tmp && curl -L https://github.com/git/git/archive/refs/tags/v2.47.0.tar.gz | tar xz
cd git-2.47.0
make prefix=$HOME/git-local NO_GETTEXT=1 NO_TCLTK=1 NO_CURL=1 NO_EXPAT=1 NO_PYTHON=1 install
export PATH="$HOME/git-local/bin:$PATH"
```

## Maven settings

Create `~/.m2/settings.xml` with the Atlassian repository:

```xml
<settings>
  <profiles>
    <profile>
      <id>atlassian</id>
      <repositories>
        <repository>
          <id>atlassian-external</id>
          <url>https://packages.atlassian.com/mvn/maven-atlassian-external/</url>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>atlassian-external</id>
          <url>https://packages.atlassian.com/mvn/maven-atlassian-external/</url>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>atlassian</activeProfile>
  </activeProfiles>
</settings>
```

## Build

```bash
mvn clean package -DskipTests
```

## Running Bitbucket locally

```bash
mvn bitbucket:debug
```

This starts Bitbucket 10 on `http://localhost:7990/bitbucket`. The AMPS config sets `bitbucket.home` to `target/bitbucket/home` and disables UPM signature checks for local development.

The `debug` goal keeps the process alive (unlike `run` which exits immediately). If the process loses stdin and exits, wrap it:

```bash
setsid mvn bitbucket:debug < /dev/zero &
```
