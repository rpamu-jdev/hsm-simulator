package com.safenetinc.luna.provider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;

/**
 * Fakes just the slice of KeyStore that the real Luna client is used for in this codebase:
 * {@code load(slotLabel, partitionPassword)} then {@code getKey(label, null)}.
 * <p>
 * Keys are served from a local directory of PKCS12 files, one file per HSM key label:
 * {@code <keysDir>/<label>.p12} (or {@code .pfx}). If a sibling {@code <label>.pass} file
 * is present, its trimmed content is used as that file's own PKCS12 password - this lets you
 * drop in existing .pfx files unchanged, each keeping its own password, instead of having to
 * re-export them all under one shared password. Without a sibling .pass file, the partition
 * password passed to {@code load(...)} is tried instead.
 * <p>
 * NOT A REAL HSM: read-only, no PIN/session/HA semantics, no hardware-backed non-extractability.
 * Good enough to exercise the sign/decrypt code paths locally; it proves nothing about how the
 * real Luna PKCS#11 mechanisms (e.g. custom-label OAEP) will behave.
 */
public class SimulatedLunaKeyStoreSpi extends KeyStoreSpi {

    private static final String DIR_SYSTEM_PROPERTY = "hsm.simulator.keystore.dir";
    private static final String DIR_ENV_VAR = "HSM_SIMULATOR_KEYSTORE_DIR";
    private static final String DEFAULT_DIR = "./hsm-simulator-keys";

    private File keysDir;
    private char[] partitionPassword;

    @Override
    public void engineLoad(InputStream stream, char[] password) throws IOException, NoSuchAlgorithmException, CertificateException {
        String slotLabel = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        this.partitionPassword = password;
        this.keysDir = resolveKeysDir();

        if (!keysDir.isDirectory()) {
            throw new IOException("[HSM SIMULATOR] Key directory not found: " + keysDir.getAbsolutePath()
                    + ". Create it and add one <label>.p12 (or .pfx) file per HSM key label you need locally. "
                    + "See HSMSimulator/README.md.");
        }

        System.out.println("[HSM SIMULATOR] NOT A REAL HSM - loaded slot '" + slotLabel + "' from "
                + keysDir.getAbsolutePath() + ". Do not point at this outside local development.");
    }

    @Override
    public Key engineGetKey(String alias, char[] password) throws NoSuchAlgorithmException, UnrecoverableKeyException {
        File pfxFile = findKeyFile(alias);
        if (pfxFile == null) {
            return null;
        }

        char[] pfxPassword = readSiblingPassword(pfxFile);
        if (pfxPassword == null) {
            pfxPassword = (password != null && password.length > 0) ? password : partitionPassword;
        }

        try (InputStream is = Files.newInputStream(pfxFile.toPath())) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(is, pfxPassword);

            Enumeration<String> innerAliases = ks.aliases();
            while (innerAliases.hasMoreElements()) {
                String innerAlias = innerAliases.nextElement();
                if (ks.isKeyEntry(innerAlias)) {
                    Key key = ks.getKey(innerAlias, pfxPassword);
                    if (key != null) {
                        return key;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new UnrecoverableKeyException("[HSM SIMULATOR] Could not load key for label '" + alias
                    + "' from " + pfxFile.getName() + ": " + e.getMessage());
        }
    }

    private File findKeyFile(String alias) {
        File p12 = new File(keysDir, alias + ".p12");
        if (p12.isFile()) {
            return p12;
        }
        File pfx = new File(keysDir, alias + ".pfx");
        return pfx.isFile() ? pfx : null;
    }

    private char[] readSiblingPassword(File keyFile) {
        String name = keyFile.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File passFile = new File(keyFile.getParentFile(), base + ".pass");
        if (!passFile.isFile()) {
            return null;
        }
        try {
            String content = Files.readString(passFile.toPath(), StandardCharsets.UTF_8).trim();
            return content.toCharArray();
        } catch (IOException e) {
            return null;
        }
    }

    private File resolveKeysDir() {
        String dir = System.getProperty(DIR_SYSTEM_PROPERTY);
        if (dir == null || dir.isBlank()) {
            dir = System.getenv(DIR_ENV_VAR);
        }
        if (dir == null || dir.isBlank()) {
            dir = DEFAULT_DIR;
        }
        return new File(dir);
    }

    private List<String> listLabels() {
        if (keysDir == null || !keysDir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = keysDir.listFiles((dir, name) -> name.endsWith(".p12") || name.endsWith(".pfx"));
        if (files == null) {
            return Collections.emptyList();
        }
        List<String> labels = new ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            labels.add(dot > 0 ? name.substring(0, dot) : name);
        }
        return labels;
    }

    @Override
    public Enumeration<String> engineAliases() {
        return Collections.enumeration(listLabels());
    }

    @Override
    public boolean engineContainsAlias(String alias) {
        return listLabels().contains(alias);
    }

    @Override
    public int engineSize() {
        return listLabels().size();
    }

    @Override
    public boolean engineIsKeyEntry(String alias) {
        return engineContainsAlias(alias);
    }

    @Override
    public boolean engineIsCertificateEntry(String alias) {
        return false;
    }

    @Override
    public Certificate engineGetCertificate(String alias) {
        return null;
    }

    @Override
    public Certificate[] engineGetCertificateChain(String alias) {
        return null;
    }

    @Override
    public Date engineGetCreationDate(String alias) {
        return engineContainsAlias(alias) ? new Date() : null;
    }

    @Override
    public String engineGetCertificateAlias(Certificate cert) {
        return null;
    }

    @Override
    public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) {
        throw new UnsupportedOperationException("HSM simulator is read-only");
    }

    @Override
    public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) {
        throw new UnsupportedOperationException("HSM simulator is read-only");
    }

    @Override
    public void engineSetCertificateEntry(String alias, Certificate cert) {
        throw new UnsupportedOperationException("HSM simulator is read-only");
    }

    @Override
    public void engineDeleteEntry(String alias) {
        throw new UnsupportedOperationException("HSM simulator is read-only");
    }

    @Override
    public void engineStore(OutputStream stream, char[] password) {
        throw new UnsupportedOperationException("HSM simulator is read-only");
    }
}
