# Dependency and security audits

Run these checks from the repository root.

## Test coverage

Generate the JaCoCo report and enforce core non-UI coverage gates:

```bash
mvn -Pcoverage verify
```

## Software bill of materials

Generate CycloneDX SBOM files at `target/bom.xml` and `target/bom.json`:

```bash
mvn -Psbom verify
```

## Dependency vulnerabilities

Run OWASP Dependency-Check. The build fails for vulnerabilities with CVSS scores of 7 or higher.

```bash
NVD_API_KEY=your-nvd-api-key mvn -Pdependency-audit verify
```

The NVD API key is optional but avoids public API rate limits during vulnerability-data updates.

The scheduled [Security Audit workflow](../.github/workflows/security.yml) runs Dependency-Check weekly and uploads HTML and JSON reports. Configure the repository secret `NVD_API_KEY` to use authenticated NVD requests in CI.

## Available updates

Check Maven dependencies and plugins for newer versions:

```bash
mvn versions:display-dependency-updates versions:display-plugin-updates
```

Dependabot is configured in [`.github/dependabot.yml`](../.github/dependabot.yml) for Maven and GitHub Actions updates.
