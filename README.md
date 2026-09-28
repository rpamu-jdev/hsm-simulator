# HSMSimulator

A local stand-in for a SafeNet Luna HSM, for developing any Spring Boot service that loads the
Luna JCE provider on a machine with no network path to the real HSM appliance.

**This is not a real HSM.** It is a plain Java Provider/KeyStore that reads private keys out
of local `.p12`/`.pfx` files. It proves your sign/decrypt code paths run end-to-end; it proves
nothing about how the real Luna hardware behaves (session handling, non-extractable keys, or
whether its OAEP mechanism accepts a custom P-source label). Never point production or UAT
config at this.

It is also not affiliated with or endorsed by SafeNet/Thales. It reimplements, under the same
class name the real client uses (`com.safenetinc.luna.provider.LunaProvider`), only the small
slice of behavior most consumers actually call: `KeyStore.getInstance("Luna")`,
`load(slotLabel, partitionPassword)`, `getKey(label, null)`.

## Why it needs no code changes in the consuming application

Applications that load Luna this way typically do it dynamically, e.g. from a component such
as a `LunaKeyProvider` class:

```java
URLClassLoader loader = new URLClassLoader(new URL[]{ new File(lunaJarPath).toURI().toURL() }, ...);
Class<?> clazz = Class.forName("com.safenetinc.luna.provider.LunaProvider", true, loader);
```

Because this simulator's `LunaProvider` class lives at that exact same fully-qualified name
and registers itself under the same keystore type (`"Luna"`), swapping the real HSM for this
simulator is a single config value: point the app's `hsm.luna.jar.path` (or equivalent
property) at this project's built jar instead of the real `LunaProvider.jar`. Nothing else in
the consuming application needs to change.

## Build

```bash
cd HSMSimulator
mvn package
```

This produces `target/hsm-simulator-1.0.0.jar`.

## Set up local keys

Create a directory to hold the simulated HSM's keys, and drop in one `.p12`/`.pfx` file per
HSM key label your local run needs - named exactly `<label>.p12`. You can reuse existing PFX
files as-is (no need to re-export them), and give each one a sibling `.pass` file holding its
own password. For example, for a test app with two configured labels, `sign-key-label` and
`decrypt-key-label`:

```bash
mkdir -p ~/hsm-simulator-keys

cp /path/to/sample-sign-key.pfx \
   ~/hsm-simulator-keys/sign-key-label.p12
printf '<sign-key-pfx-password>' > ~/hsm-simulator-keys/sign-key-label.pass

cp /path/to/sample-decrypt-key.pfx \
   ~/hsm-simulator-keys/decrypt-key-label.p12
printf '<decrypt-key-pfx-password>' > ~/hsm-simulator-keys/decrypt-key-label.pass
```

Replace the label names and PFX paths/passwords with whatever your application is actually
configured with (e.g. its own `hsm.sign.key.label` / `hsm.decrypt.key.label` properties).

If a `<label>.pass` file is absent, the simulator falls back to trying the partition password
(the one passed to `load(...)`) as the PKCS12 password instead.

## Run a consuming application against the simulator

Override just two JVM system properties on top of the app's normal config - no need to edit
its committed properties file:

```bash
java \
  -Dhsm.luna.jar.path=/absolute/path/to/HSMSimulator/target/hsm-simulator-1.0.0.jar \
  -Dhsm.simulator.keystore.dir=/absolute/path/to/hsm-simulator-keys \
  -jar your-application.jar
```

- The Luna jar path property is whatever your app's own Luna-loading component reads via
  Spring `@Value` (e.g. `hsm.luna.jar.path`); a `-D` system property overrides the value in its
  properties file.
- `hsm.simulator.keystore.dir` is read directly by the simulator (plain `System.getProperty`,
  not Spring-managed), so it only needs to be a JVM system property, not an application
  property. It also accepts the `HSM_SIMULATOR_KEYSTORE_DIR` environment variable, and
  defaults to `./hsm-simulator-keys` (relative to the process's working directory) if neither
  is set.

You should see a startup log line like:

```
[HSM SIMULATOR] NOT A REAL HSM - loaded slot 'tokenlabel:TEST_PARTITION' from /absolute/path/to/hsm-simulator-keys.
```

followed by normal application startup. Signing and decrypting will now use the keys from
your local files, going through the exact same key-lookup-by-label call path used against the
real HSM in UAT/production.

## Limitations

- Read-only: no key generation, import, or deletion.
- No session/HA/PIN semantics - it's a file lookup, not a hardware boundary.
- Keys are ordinary extractable `PrivateKey` objects here; on the real HSM they are
  non-extractable handles. Code that happens to work against this simulator can still behave
  differently against the real Luna provider (see the OAEP custom-P-source caveat above) -
  always confirm sign/decrypt against the real HSM in UAT before trusting this alone.
